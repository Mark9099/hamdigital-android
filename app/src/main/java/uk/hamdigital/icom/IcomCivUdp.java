package uk.hamdigital.icom;
/**
 * 处理ICom的CIV流。
 * @author BGY70Z
 * @date 2023-03-20
 */

import android.util.Log;


import java.net.DatagramPacket;
import java.util.Timer;
import java.util.TimerTask;

public class IcomCivUdp extends IcomUdpBase{
    private static final String TAG="IcomCivUdp";
    public byte civAddress=(byte)0xA4;
    public boolean supportTX=true;
    public short civSeq=0;


    private Timer openCivDataTimer;


    public IcomCivUdp() {
        udpStyle=IcomUdpStyle.CivUdp;
    }

    @Override
    public void onDataReceived(DatagramPacket packet,byte[] data) {
        super.onDataReceived(packet,data);

        if (data.length == IComPacketTypes.CONTROL_SIZE) {
            if (IComPacketTypes.ControlPacket.getType(data) == IComPacketTypes.CMD_I_AM_READY) {
                Log.d(TAG, "onDataReceived: civ I am ready!!");
                sendOpenClose(true);//打开连接
                startIdleTimer();//打开发送空包时钟
                startCivDataTimer();//启动civ看门狗

            }
        } else {
            if (IComPacketTypes.ControlPacket.getType(data) != IComPacketTypes.CMD_PING) {
                Log.d(TAG, "onDataReceived: CIV :" + IComPacketTypes.byteToStr(data));
                checkCivData(data);
            }
            if (IComPacketTypes.ControlPacket.getType(data) == IComPacketTypes.CMD_RETRANSMIT) {
                Log.d(TAG, "onDataReceived: type=0x01"+IComPacketTypes.byteToStr(data) );
                //lastReceived=System.currentTimeMillis();//更新一下最后接收数据的时间，让watchDog处理
            }

        }
    }

    public void checkCivData(byte[] data){
       if (IComPacketTypes.CivPacket.checkIsCiv(data)){
           lastReceivedTime=System.currentTimeMillis();
           if (getOnStreamEvents()!=null){
               getOnStreamEvents().OnReceivedCivData(IComPacketTypes.CivPacket.getCivData(data));
           }
       }
    }

    public void sendOpenClose(boolean open){
        if (open) {
            sendTrackedPacket(IComPacketTypes.OpenClosePacket.toBytes((short) 0
                    , localId, remoteId, civSeq,(byte) 0x04));//打开连接
        }else {
            sendTrackedPacket(IComPacketTypes.OpenClosePacket.toBytes((short) 0
                    , localId, remoteId, civSeq,(byte) 0x00));//关闭连接
        }
        civSeq++;
    }
    public void startCivDataTimer(){
        stopTimer(openCivDataTimer);
        Log.d(TAG, String.format("openCivDataTimer: local port:%d,remote port %d", localPort, rigPort));
        openCivDataTimer = new Timer();
        openCivDataTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (System.currentTimeMillis()-lastReceivedTime>2000) {
                    sendOpenClose(true);
                }
            }
        }, 100, IComPacketTypes.OPEN_CLOSE_PERIOD_MS);
    }


    public void sendPttAction(boolean pttOn){
        if (pttOn) {
            sendCivData(new byte[]{(byte) 0xfe, (byte) 0xfe, civAddress, (byte) 0xe0, 0x1c, 0x00, 0x01, (byte) 0xfd});
        }else {
            sendCivData(new byte[]{(byte) 0xfe, (byte) 0xfe, civAddress, (byte) 0xe0, 0x1c, 0x00, 0x00, (byte) 0xfd});
        }
    }

    public void sendCivData(byte[] data){
        sendTrackedPacket(IComPacketTypes.CivPacket.setCivData((short) 0,localId,remoteId,civSeq,data));
        civSeq++;
    }

    @Override
    public void close() {
        sendOpenClose(false);                        // HF Digital Modes: tell the radio the CI-V stream is closing first (FT8CN sent it after closing the socket, so it was lost)
        super.close();
        stopTimer(openCivDataTimer);
        stopTimer(idleTimer);

    }
}
