package uk.hamdigital.icom;
/**
 * 处理ICom的音频流，继承至AudioUdp。
 * @author BGY70Z
 * @date 2023-08-26
 */

import android.util.Log;


import java.net.DatagramPacket;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class IcomAudioUdp extends AudioUdp {
    private static final String TAG = "IcomAudioUdp";


    private final ExecutorService doTXThreadPool =Executors.newCachedThreadPool();
    private final DoTXAudioRunnable doTXAudioRunnable=new DoTXAudioRunnable(this);


    @Override
    public void sendTxAudioData(float[] audioData) {
        if (audioData==null) return;

        short[] temp=new short[audioData.length];
        //传递过来的音频是LPCM,32 float，12000Hz
        //iCOM的音频格式是LPCM 16 Int，12000Hz
        //要做一下浮点到16位int的转换
        for (int i = 0; i < audioData.length; i++) {
            float x = audioData[i];
            if (x > 1.0)
                x = 1.0f;
            else if (x < -1.0)
                x = -1.0f;
            temp[i] = (short)  (x * 32767.0);
        }
        doTXAudioRunnable.audioData=temp;
        doTXThreadPool.execute(doTXAudioRunnable);
    }
    private static class DoTXAudioRunnable implements Runnable{
        IcomAudioUdp icomAudioUdp;
        short[] audioData;//传递过来的音频是LPCM 16bit Int,12000hz

        public DoTXAudioRunnable(IcomAudioUdp icomAudioUdp) {
            this.icomAudioUdp = icomAudioUdp;
        }

        @Override
        public void run() {
            if (audioData==null) return;

            final int partialLen = IComPacketTypes.TX_BUFFER_SIZE * 2;//数据包的长度
            //要转换一下到BYTE,小端模式

            //byte[] data = new byte[audioData.length * 2 + partialLen * 4];//多出一点空声音放在前后各20ms*2共80ms
            //先播放，是给出空的声音，for i 循环，做了一个判断，是给前面的空声音，for j循环，做得判断，是让后面发送空声音
            byte[] audioPacket = new byte[partialLen];
            for (int i = 0; i < (audioData.length / IComPacketTypes.TX_BUFFER_SIZE) + 8; i++) {//多出6个周期，前面3个，后面3个多
                if (!icomAudioUdp.isPttOn) break;
                long now = System.currentTimeMillis() - 1;//获取当前时间

                icomAudioUdp.sendTrackedPacket(IComPacketTypes.AudioPacket.getTxAudioPacket(audioPacket
                        , (short) 0, icomAudioUdp.localId, icomAudioUdp.remoteId, icomAudioUdp.innerSeq));
                icomAudioUdp.innerSeq++;

                Arrays.fill(audioPacket,(byte)0x00);
                if (i>=3) {//让前两个空数据发送出去
                    for (int j = 0; j < IComPacketTypes.TX_BUFFER_SIZE; j++) {
                        if ((i-3) * IComPacketTypes.TX_BUFFER_SIZE + j < audioData.length) {
                            System.arraycopy(IComPacketTypes.shortToBigEndian((short)
                                            (audioData[(i-3) * IComPacketTypes.TX_BUFFER_SIZE + j]
                                                    * 1.0f))//乘以信号量的比率
                                    , 0, audioPacket, j * 2, 2);
                        }
                    }
                }
                while (icomAudioUdp.isPttOn) {
                    if (System.currentTimeMillis() - now >= 21) {//20毫秒一个周期
                        break;
                    }
                }
            }
            Log.d(TAG, "run: 音频发送完毕！！" );
            Thread.currentThread().interrupt();
        }

    }


    // ---- HF Digital Modes: streamed transmit audio (FreeDV voice), added to FT8CN's code (ANDROID_CHANGES.txt) ----
    // sendTxAudioData above sends one finished recording (padded with silence, on its own thread); live speech needs a
    // stream instead: pushTxAudio queues 12 kHz samples as they are made, and the stream thread sends a packet of
    // TX_BUFFER_SIZE samples (20 ms) every 20 ms while PTT is on - silence if the queue has run dry.
    private final short[] txFifo = new short[12000 * 2];   // 2 s of queue
    private int txHead = 0, txCount = 0;                   // where the oldest sample is, how many are queued
    private volatile boolean streaming = false;            // the stream thread runs

    /** Queue 12 kHz transmit samples (the oldest are dropped if the queue is full). */
    public synchronized void pushTxAudio(short[] s, int n) {
        for (int i = 0; i < n; i++) {
            if (txCount == txFifo.length) { txHead = (txHead + 1) % txFifo.length; txCount--; } // (full: drop the oldest)
            txFifo[(txHead + txCount) % txFifo.length] = s[i]; txCount++;
        }
    }

    private synchronized void pullTxAudio(short[] out) {   // the next packet's samples (zeros where the queue is short)
        for (int i = 0; i < out.length; i++) {
            if (txCount > 0) { out[i] = txFifo[txHead]; txHead = (txHead + 1) % txFifo.length; txCount--; } else out[i] = 0;
        }
    }

    /** Start sending the queue (PTT must be on): a packet every 20 ms until stopTxStream or PTT off. */
    public void startTxStream() {
        if (streaming) return; streaming = true;
        synchronized (this) { txHead = 0; txCount = 0; }
        new Thread(() -> {
            short[] samples = new short[IComPacketTypes.TX_BUFFER_SIZE];
            byte[] audioPacket = new byte[IComPacketTypes.TX_BUFFER_SIZE * 2];
            long next = System.currentTimeMillis();
            while (streaming && isPttOn) {
                pullTxAudio(samples);
                for (int j = 0; j < samples.length; j++) System.arraycopy(IComPacketTypes.shortToBigEndian(samples[j]), 0, audioPacket, j * 2, 2); // (as sendTxAudioData packs them)
                sendTrackedPacket(IComPacketTypes.AudioPacket.getTxAudioPacket(audioPacket, (short) 0, localId, remoteId, innerSeq));
                innerSeq++;
                next += 20;                                // 20 ms a packet, paced to the clock (no drift)
                long wait = next - System.currentTimeMillis();
                if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException e) { break; }
            }
            streaming = false;
        }, "icom-tx-stream").start();
    }

    /** Samples queued, not yet sent (the app waits for none before it stops: the end of an over is not cut off). */
    public synchronized int txQueued() { return txCount; }

    /** Stop sending the queue (and forget what is left in it). */
    public void stopTxStream() { streaming = false; synchronized (this) { txCount = 0; } }

    @Override
    public void onDataReceived(DatagramPacket packet, byte[] data) {
        super.onDataReceived(packet, data);
        //接收到的是12000采样率的数据
        if (!IComPacketTypes.AudioPacket.isAudioPacket(data)) return;
        byte[] audioData = IComPacketTypes.AudioPacket.getAudioData(data);
        rxInOrder(IComPacketTypes.ControlPacket.getSeq(data) & 0xFFFF, audioData); // (HF Digital Modes: in order, gaps asked for again or filled)
    }

    // ---- HF Digital Modes: receive audio in sequence (ANDROID_CHANGES.txt) ----
    // FT8CN passed audio packets on as they arrived. Over WiFi some are lost or come late, and every one lost shifted the
    // timing of all that followed - SSTV pictures broke into bands, each shifted sideways. Now, as wfview does it: the
    // packets are held briefly in sequence order; a missing one is asked for again (a retransmit request, type 0x01 with
    // its sequence number); if it has still not come when its turn is up (RX_HOLD packets later), its 20 ms is filled
    // with silence, so everything after it keeps its time.
    private static final int RX_HOLD = 5;                        // packets held for a missing one (about 100 ms)
    private final java.util.TreeMap<Integer, byte[]> rxHeld = new java.util.TreeMap<>(); // waiting, by distance from rxNext
    private final java.util.HashSet<Integer> rxAsked = new java.util.HashSet<>(); // sequence numbers asked for again
    private int rxNext = -1;                                     // the sequence number due next (-1: not yet known)
    private int rxLastLen = 0;                                   // a packet's audio bytes (for a silent one)
    public volatile long rxPackets, rxLost, rxRecovered, rxLate;  // counts: received, filled with silence, recovered by asking, too late (dropped)

    private synchronized void rxInOrder(int seq, byte[] audio) {
        rxPackets++; rxLastLen = audio.length;
        if (rxNext < 0) rxNext = seq;
        int d = (seq - rxNext) & 0xFFFF;                         // how far ahead of the one due
        if (d >= 0x8000) { rxLate++; return; }                   // behind: already played (or filled) - dropped
        if (d > 200) { rxFlush(); rxNext = seq; d = 0; }         // a big jump (the stream restarted): start again from here
        if (rxAsked.remove(seq)) rxRecovered++;
        rxHeld.put(d, audio);
        rxRelease();
    }

    private void rxRelease() {                                   // (under the lock) play what is in order; ask for / fill gaps
        while (!rxHeld.isEmpty()) {
            byte[] next = rxHeld.remove(0);
            if (next == null) {                                  // the one due is missing
                int newest = rxHeld.lastKey();
                for (int k = 0; k < newest; k++) {               // ask for each missing one, once
                    int s = (rxNext + k) & 0xFFFF;
                    if (!rxHeld.containsKey(k) && rxAsked.add(s))
                        sendUntrackedPacket(IComPacketTypes.ControlPacket.toBytes(IComPacketTypes.CMD_RETRANSMIT, (short) s, localId, remoteId));
                }
                if (newest < RX_HOLD) return;                    // wait for it a little
                next = new byte[rxLastLen]; rxLost++;            // too long: its time in silence
                rxAsked.remove(rxNext);
            }
            if (onStreamEvents != null) onStreamEvents.OnReceivedAudioData(next);
            rxNext = (rxNext + 1) & 0xFFFF;                      // the next one is due: shift the waiting ones down
            java.util.TreeMap<Integer, byte[]> moved = new java.util.TreeMap<>();
            for (java.util.Map.Entry<Integer, byte[]> e : rxHeld.entrySet()) moved.put(e.getKey() - 1, e.getValue());
            rxHeld.clear(); rxHeld.putAll(moved);
        }
    }

    private void rxFlush() {                                     // (under the lock) play all that is held, in order
        for (byte[] a : rxHeld.values()) if (onStreamEvents != null) onStreamEvents.OnReceivedAudioData(a);
        rxHeld.clear(); rxAsked.clear();
    }
}
