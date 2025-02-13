package com.minhui.vpn.service;

import static com.minhui.vpn.VPNConstants.VPN_SP_NAME;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.Keep;

import com.minhui.vpn.Packet;
import com.minhui.vpn.ProxyConfig;
import com.minhui.vpn.R;
import com.minhui.vpn.UDPServer;
import com.minhui.vpn.http.HttpRequestHeaderParser;
import com.minhui.vpn.nat.NatSession;
import com.minhui.vpn.nat.NatSessionManager;
import com.minhui.vpn.processparse.PortHostService;
import com.minhui.vpn.proxy.TcpProxyServer;
import com.minhui.vpn.tcpip.IPHeader;
import com.minhui.vpn.tcpip.TCPHeader;
import com.minhui.vpn.tcpip.UDPHeader;
import com.minhui.vpn.utils.AppDebug;
import com.minhui.vpn.utils.CommonMethods;
import com.minhui.vpn.utils.DebugLog;
import com.minhui.vpn.utils.ThreadProxy;
import com.minhui.vpn.utils.TimeFormatUtil;
import com.minhui.vpn.utils.VpnServiceHelper;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;


/**
 * Created by zengzheying on 15/12/28.
 */
@Keep public class FirewallVpnService extends VpnService implements Runnable
{
    private static final String VPN_ROUTE = "0.0.0.0"; // Intercept everything
    private static final String GOOGLE_DNS_FIRST = "8.8.8.8";
    private static final String GOOGLE_DNS_SECOND = "8.8.4.4";

    private static int ID;
    private static int LOCAL_IP;
    private boolean IsRunning = false;
    private Thread mVPNThread;
    private ParcelFileDescriptor mVPNInterface;
    private TcpProxyServer mTcpProxyServer;
    private FileOutputStream mVPNOutputStream;

    private byte[] mPacket;
    private IPHeader mIPHeader;
    private TCPHeader mTCPHeader;
    private Handler mHandler;
    private ConcurrentLinkedQueue<Packet> udpQueue;
    private FileInputStream in;
    private UDPServer udpServer;
    private final String selectPackage =  "com.albiononline";
    private final UDPHeader mUDPHeader;
    private final ByteBuffer mDNSBuffer;
    public static final int MUTE_SIZE = 2048;

    public static long vpnStartTime;
    public static String lastVpnStartTimeFormat = null;
    private SharedPreferences sp;

    public FirewallVpnService()
    {
        ID++;
        mHandler = new Handler();
        mPacket = new byte[MUTE_SIZE];
        mIPHeader = new IPHeader(mPacket, 0);

        //Offset = ip报文头部长度
        mTCPHeader = new TCPHeader(mPacket, 20);
        mUDPHeader = new UDPHeader(mPacket, 20);

        //Offset = ip报文头部长度 + udp报文头部长度 = 28
        mDNSBuffer = ((ByteBuffer) ByteBuffer.wrap(mPacket).position(28)).slice();

        DebugLog.i("New VPNService(%d)\n", ID);
    }

    //启动Vpn工作线程
    @Override
    public void onCreate()
    {
        DebugLog.i("VPNService(%s) created.\n", ID);
        sp = getSharedPreferences(VPN_SP_NAME, Context.MODE_PRIVATE);
        VpnServiceHelper.onVpnServiceCreated(this);
        mVPNThread = new Thread(this, "VPNServiceThread");
        mVPNThread.start();
        setVpnRunningStatus(true);

        //   notifyStatus(new VPNEvent(VPNEvent.Status.STARTING));
        super.onCreate();
    }

    //只设置IsRunning = true;
    @Override public int onStartCommand(Intent intent, int flags, int startId)
    {
        return super.onStartCommand(intent, flags, startId);
    }

    //停止Vpn工作线程
    @Override public void onDestroy()
    {
        Log.d("VPNService","stopped");
        DebugLog.i("VPNService(%s) destroyed.\n", ID);

        if (mVPNThread != null)
        {
            mVPNThread.interrupt();
        }

        VpnServiceHelper.onVpnServiceDestroy();
        super.onDestroy();
    }

    //建立VPN，同时监听出口流量
    private void runVPN() throws Exception
    {
        this.mVPNInterface = establishVPN();
        startStream();
    }

    private void startStream() throws Exception
    {
        int size = 0;

        mVPNOutputStream = new FileOutputStream(mVPNInterface.getFileDescriptor());
        in = new FileInputStream(mVPNInterface.getFileDescriptor());

        while (size != -1 && IsRunning)
        {
            boolean hasWrite = false;

            size = in.read(mPacket);

            if (size > 0)
            {
                if (mTcpProxyServer.Stopped)
                {
                    in.close();
                    throw new Exception("LocalServer stopped.");
                }

                hasWrite = onIPPacketReceived(mIPHeader, size);
            }

            if (!hasWrite)
            {
                Packet packet = udpQueue.poll();

                if (packet != null)
                {
                    ByteBuffer bufferFromNetwork = packet.backingBuffer;
                    bufferFromNetwork.flip();

                    mVPNOutputStream.write(bufferFromNetwork.array());
                }
            }

            Thread.sleep(10);
        }

        in.close();
        disconnectVPN();
    }

    boolean onIPPacketReceived(IPHeader ipHeader, int size) throws IOException
    {
        boolean hasWrite = false;

        switch (ipHeader.getProtocol())
        {
            case IPHeader.TCP:
                hasWrite = onTcpPacketReceived(ipHeader, size);

                break;
            case IPHeader.UDP:
                onUdpPacketReceived(ipHeader, size);
                break;

            default:
                break;
        }
        return hasWrite;
    }

    private void onUdpPacketReceived(IPHeader ipHeader, int size) throws UnknownHostException
    {
        TCPHeader tcpHeader = mTCPHeader;
        short portKey = tcpHeader.getSourcePort();

        NatSession session = NatSessionManager.getSession(portKey);

        if (session == null || session.remoteIP != ipHeader.getDestinationIP() || session.remotePort != tcpHeader.getDestinationPort())
        {
            session = NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), tcpHeader.getDestinationPort(), NatSession.UDP);
            session.vpnStartTime = vpnStartTime;

            ThreadProxy.getInstance().execute(() ->
            {
                if(PortHostService.getInstance()!=null)
                {
                    PortHostService.getInstance().refreshSessionInfo();
                }
            });
        }

        // session.lastRefreshTime = System.currentTimeMillis();
        // session.packetSent++; //注意顺序

        byte[] bytes = Arrays.copyOf(mPacket, mPacket.length);

        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes, 0, size);
        byteBuffer.limit(size);

        //Log.d("payload",""+bytes);

        Packet packet = new Packet(byteBuffer);
        udpServer.processUDPPacket(packet, portKey);
    }

    private boolean onTcpPacketReceived(IPHeader ipHeader, int size) throws IOException
    {
        boolean hasWrite = false;
        TCPHeader tcpHeader = mTCPHeader;
        tcpHeader.mOffset = ipHeader.getHeaderLength();

        if (tcpHeader.getSourcePort() == mTcpProxyServer.port)
        {
            //VPNLog.d(TAG, "process  tcp packet from net ");
            NatSession session = NatSessionManager.getSession(tcpHeader.getDestinationPort());

            if (session != null)
            {
                ipHeader.setSourceIP(ipHeader.getDestinationIP());
                tcpHeader.setSourcePort(session.remotePort);
                ipHeader.setDestinationIP(LOCAL_IP);

                CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                mVPNOutputStream.write(ipHeader.mData, ipHeader.mOffset, size);
                //mReceivedBytes += size;
            }
            else
            {
                DebugLog.i("NoSession: %s %s\n", ipHeader.toString(), tcpHeader.toString());
            }
        }
        else
        {
            //VPNLog.d(TAG, "process  tcp packet to net ");

            short portKey = tcpHeader.getSourcePort();
            NatSession session = NatSessionManager.getSession(portKey);

            if (session == null || session.remoteIP != ipHeader.getDestinationIP() || session.remotePort != tcpHeader.getDestinationPort())
            {
                session = NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), tcpHeader.getDestinationPort(), NatSession.TCP);
                session.vpnStartTime = vpnStartTime;

                ThreadProxy.getInstance().execute(() ->
                {
                    PortHostService instance = PortHostService.getInstance();

                    if (instance != null)
                    {
                        instance.refreshSessionInfo();
                    }
                });
            }

            session.lastRefreshTime = System.currentTimeMillis();
            session.packetSent++;

            int tcpDataSize = ipHeader.getDataLength() - tcpHeader.getHeaderLength();

            if (session.packetSent == 2 && tcpDataSize == 0)
            {
                return false;
            }

            if (session.bytesSent == 0 && tcpDataSize > 10)
            {
                int dataOffset = tcpHeader.mOffset + tcpHeader.getHeaderLength();
                HttpRequestHeaderParser.parseHttpRequestHeader(session, tcpHeader.mData, dataOffset, tcpDataSize);

                DebugLog.i("Host: %s\n", session.remoteHost);
                DebugLog.i("Request: %s %s\n", session.method, session.requestUrl);
            }
            else if (session.bytesSent > 0 && !session.isHttpsSession && session.isHttp && session.remoteHost == null && session.requestUrl == null)
            {
                int dataOffset = tcpHeader.mOffset + tcpHeader.getHeaderLength();

                session.remoteHost = HttpRequestHeaderParser.getRemoteHost(tcpHeader.mData, dataOffset, tcpDataSize);
                session.requestUrl = "http://" + session.remoteHost + "/" + session.pathUrl;
            }

            ipHeader.setSourceIP(ipHeader.getDestinationIP());
            ipHeader.setDestinationIP(LOCAL_IP);
            tcpHeader.setDestinationPort(mTcpProxyServer.port);

            CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
            mVPNOutputStream.write(ipHeader.mData, ipHeader.mOffset, size);

            session.bytesSent += tcpDataSize;
            //mSentBytes += size;
        }

        hasWrite = true;
        return hasWrite;
    }

    private void waitUntilPrepared()
    {
        while (prepare(this) != null)
        {
            try
            {
                Thread.sleep(10);
            }
            catch (InterruptedException e)
            {
                if (AppDebug.IS_DEBUG)
                {
                    e.printStackTrace();
                }

                DebugLog.e("waitUntilPrepared catch an exception %s\n", e);
            }
        }
    }
    private ParcelFileDescriptor establishVPN() throws Exception
    {
        Builder builder = new Builder();
        builder.setMtu(MUTE_SIZE);

        DebugLog.i("setMtu: %d\n", ProxyConfig.Instance.getMTU());

        ProxyConfig.IPAddress ipAddress = ProxyConfig.Instance.getDefaultLocalIP();
        LOCAL_IP = CommonMethods.ipStringToInt(ipAddress.Address);
        builder.addAddress(ipAddress.Address, ipAddress.PrefixLength);
        DebugLog.i("addAddress: %s/%d\n", ipAddress.Address, ipAddress.PrefixLength);

        builder.addRoute(VPN_ROUTE, 0);

        builder.addDnsServer(GOOGLE_DNS_FIRST);
        builder.addDnsServer(GOOGLE_DNS_SECOND);

        vpnStartTime = System.currentTimeMillis();
        lastVpnStartTimeFormat = TimeFormatUtil.formatYYMMDDHHMMSS(vpnStartTime);

        try
        {
            builder.addAllowedApplication(selectPackage);
            builder.addAllowedApplication(getPackageName());
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        builder.setSession(getString(R.string.app_name));
        ParcelFileDescriptor pfdDescriptor = builder.establish();

        //notifyStatus(new VPNEvent(VPNEvent.Status.ESTABLISHED));

        return pfdDescriptor;
    }

    @Override
    public void run()
    {
        try
        {
            DebugLog.i("VPNService(%s) work thread is Running...\n", ID);

            waitUntilPrepared();
            udpQueue = new ConcurrentLinkedQueue<>();

            //启动TCP代理服务
            mTcpProxyServer = new TcpProxyServer(0);
            mTcpProxyServer.start();
            udpServer = new UDPServer(this, udpQueue);
            udpServer.start();

            NatSessionManager.clearAllSession();

            if(PortHostService.getInstance()!=null)
            {
                PortHostService.startParse(getApplicationContext());
            }

            DebugLog.i("DnsProxy started.\n");

            ProxyConfig.Instance.onVpnStart(this);

            while (IsRunning)
            {
                runVPN();
            }
        }
        catch (InterruptedException e)
        {
            if (AppDebug.IS_DEBUG)
            {
                e.printStackTrace();
            }

            DebugLog.e("VpnService run catch an exception %s.\n", e);
        }
        catch (Exception e)
        {
            if (AppDebug.IS_DEBUG)
            {
                e.printStackTrace();
            }

            DebugLog.e("VpnService run catch an exception %s.\n", e);
        }
        finally
        {
            DebugLog.i("VpnService terminated");
            ProxyConfig.Instance.onVpnEnd(this);
            dispose();
        }
    }

    public void disconnectVPN()
    {
        try
        {
            if (mVPNInterface != null)
            {
                mVPNInterface.close();
                mVPNInterface = null;
            }
        }
        catch (Exception e)
        {
            //ignore
        }

        // notifyStatus(new VPNEvent(VPNEvent.Status.UNESTABLISHED));
        this.mVPNOutputStream = null;
    }

    private synchronized void dispose()
    {
        try
        {
            disconnectVPN();

            if (mTcpProxyServer != null)
            {
                mTcpProxyServer.stop();
                mTcpProxyServer = null;
                DebugLog.i("TcpProxyServer stopped.\n");
            }

            if(udpServer!=null)
            {
                udpServer.closeAllUDPConn();
            }

            ThreadProxy.getInstance().execute(() ->
            {
                if(PortHostService.getInstance() != null)
                {
                    PortHostService.getInstance().refreshSessionInfo();
                }

                PortHostService.stopParse(getApplicationContext());
            });

            stopSelf();
            setVpnRunningStatus(false);
        }
        catch (Exception e)
        {

        }
    }

    public boolean vpnRunningStatus() {
        return IsRunning;
    }

    public void setVpnRunningStatus(boolean isRunning) {
        IsRunning = isRunning;
    }
}
