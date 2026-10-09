// FreeDV digital voice: modes 700D, 700E and 1600 with codec2's FreeDV API (David Rowe and others, LGPL 2.1), and RADE V1
// (the FreeDV project's neural "radio autoencoder", rade_c BSD-2 with Opus's FARGAN vocoder, BSD-3). Both are driven the
// same way (Engine). Receive: the radio's audio (8 kHz) goes to the modem a frame at a time; the speech it decodes plays
// on the phone - its speaker, or headphones / Bluetooth if connected, never the radio's USB sound card. "Radio audio"
// plays the radio's audio itself instead (to tune, or hear SSB); the squelch keeps it quiet without a FreeDV signal.
// Transmit: while the talk button is held, the phone's microphone is coded a frame at a time and the modem audio
// streamed to the radio (Transmitter.startStream). codec2's modes repeat the text set (your call) in their text channel;
// RADE sends your callsign once, in the end-of-over frame that closes each over (as freedv-gui does).
package uk.hamdigital.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.engine.FreeDvNative
import uk.hamdigital.engine.RadeNative

object FreeDv {
    const val RATE = 8000                             // the modem's rate (every mode): the radio's audio comes in at this

    /** One mode's engine: codec2's FreeDV API or RADE - the same calls (see FreeDvNative). */
    interface Engine {
        fun open(): Long; fun close(h: Long); fun sizes(h: Long): IntArray
        fun rx(h: Long, modem: ShortArray): ShortArray; fun tx(h: Long, speech: ShortArray): ShortArray
        fun txEnd(h: Long): ShortArray                // the last audio of an over (RADE: the end-of-over frame)
        fun stats(h: Long): FloatArray; fun text(h: Long): String; fun setText(h: Long, t: String)
        fun squelch(h: Long, on: Boolean, db: Float)
    }
    private class Codec2(val code: Int) : Engine {    // 700D / 700E / 1600
        override fun open() = FreeDvNative.open(code); override fun close(h: Long) = FreeDvNative.close(h)
        override fun sizes(h: Long) = FreeDvNative.sizes(h); override fun rx(h: Long, modem: ShortArray) = FreeDvNative.rx(h, modem)
        override fun tx(h: Long, speech: ShortArray) = FreeDvNative.tx(h, speech); override fun txEnd(h: Long) = ShortArray(0)
        override fun stats(h: Long) = FreeDvNative.stats(h); override fun text(h: Long) = FreeDvNative.text(h)
        override fun setText(h: Long, t: String) = FreeDvNative.setText(h, t)
        override fun squelch(h: Long, on: Boolean, db: Float) = FreeDvNative.squelch(h, on, db)
    }
    private object Rade : Engine {                    // RADE V1 (speech at 16 kHz; quiet without a signal anyway)
        override fun open() = RadeNative.open(); override fun close(h: Long) = RadeNative.close(h)
        override fun sizes(h: Long) = RadeNative.sizes(h); override fun rx(h: Long, modem: ShortArray) = RadeNative.rx(h, modem)
        override fun tx(h: Long, speech: ShortArray) = RadeNative.tx(h, speech); override fun txEnd(h: Long) = RadeNative.txEnd(h)
        override fun stats(h: Long) = RadeNative.stats(h); override fun text(h: Long) = RadeNative.text(h)
        override fun setText(h: Long, t: String) = RadeNative.setText(h, t); override fun squelch(h: Long, on: Boolean, db: Float) {}
    }

    enum class FdMode(val label: String, val code: Int) {
        RADE("RADE", -1), M700D("700D", FreeDvNative.MODE_700D), M700E("700E", FreeDvNative.MODE_700E), M1600("1600", FreeDvNative.MODE_1600);
        val engine: Engine get() = if (this == RADE) Rade else Codec2(code) // its engine
        val speechRate get() = if (this == RADE) 16000 else 8000           // its speech's sample rate
    }
    enum class Listen(val label: String) { SPEECH("FreeDV speech"), RADIO("Radio audio"), OFF("Off") }

    val mode = MutableStateFlow(FdMode.RADE)          // the mode (RADE: what most FreeDV stations use now)
    val listen = MutableStateFlow(Listen.SPEECH)      // what the phone plays
    val squelch = MutableStateFlow(true)              // quiet without a signal
    val sync = MutableStateFlow(false)                // the modem has a FreeDV signal
    val snr = MutableStateFlow(0f)                    // its SNR estimate (dB)
    val text = MutableStateFlow("")                   // the text channel received (newest at the end)
    val talking = MutableStateFlow(false)             // transmitting
    val txError = MutableStateFlow("")                // why transmitting did not start / stopped

    @Volatile private var rx = 0L                     // the receive session
    private var rxEngine: Engine = Rade               // ... and its engine
    private val lock = Any()
    private var buf = ShortArray(RATE); private var have = 0 // audio waiting for the modem
    private var player: AudioTrack? = null            // the phone's speaker / headphones
    private var playerRate = 0                        // ... at the mode's speech rate
    private var up = ShortArray(0)                    // (radio audio doubled to 16 kHz for RADE's player)
    @Volatile var txText = ""                         // what codec2's text channel sends
    @Volatile var level = 0.3f                        // transmit level (Settings), 0..1

    /** Open (or re-open, after a mode change) the receive session. */
    fun open(ctx: Context) = synchronized(lock) {
        if (rx != 0L) rxEngine.close(rx)
        rxEngine = mode.value.engine; rx = rxEngine.open(); have = 0
        if (rx != 0L) rxEngine.squelch(rx, squelch.value, if (mode.value == FdMode.M1600) 2f else -2f) // (FreeDV GUI's own thresholds)
        if (player != null && playerRate != mode.value.speechRate) { player?.let { try { it.stop() } catch (e: Exception) { }; it.release() }; player = null }
        if (player == null) { playerRate = mode.value.speechRate; player = makePlayer(ctx, playerRate) }
        sync.value = false
    }

    fun setMode(ctx: Context, m: FdMode) { mode.value = m; open(ctx) }
    fun setSquelch(on: Boolean) { squelch.value = on; synchronized(lock) { if (rx != 0L) rxEngine.squelch(rx, on, if (mode.value == FdMode.M1600) 2f else -2f) } }

    /** Close everything (the page closes). */
    fun close() = synchronized(lock) {
        stopTalk()
        if (rx != 0L) rxEngine.close(rx); rx = 0
        player?.let { try { it.stop() } catch (e: Exception) { }; it.release() }; player = null
        sync.value = false
    }

    /** Audio in (the capture thread): 8 kHz mono from the radio. */
    fun feed(b: ShortArray, n: Int) = synchronized(lock) {
        if (rx == 0L) return
        val now = System.currentTimeMillis()
        if (Transmitter.sentDuring(now - 600, now)) { have = 0; return } // (our own signal while we talk)
        if (listen.value == Listen.RADIO) player?.let { p -> if (playerRate == RATE) p.write(b, 0, n) else { // the radio itself ..
            if (up.size < 2 * n) up = ShortArray(2 * n)
            for (i in 0 until n) { up[2 * i] = b[i]; up[2 * i + 1] = ((b[i] + (if (i + 1 < n) b[i + 1] else b[i])) / 2).toShort() } // (.. doubled: 8 -> 16 kHz)
            p.write(up, 0, 2 * n) } }
        if (have + n > buf.size) buf = buf.copyOf(have + n + RATE)
        System.arraycopy(b, 0, buf, have, n); have += n
        while (true) {                                // as many modem frames as there is audio for
            val nin = rxEngine.sizes(rx)[0]
            if (have < nin) break
            val speech = rxEngine.rx(rx, buf.copyOf(nin))
            System.arraycopy(buf, nin, buf, 0, have - nin); have -= nin
            if (listen.value == Listen.SPEECH && speech.isNotEmpty()) player?.write(speech, 0, speech.size)
        }
        val st = rxEngine.stats(rx); sync.value = st[0] > 0.5f; snr.value = st[1]
        val t = rxEngine.text(rx); if (t.isNotEmpty()) text.value = (text.value + t.filter { it >= ' ' || it == '\r' }.replace('\r', '\n')).takeLast(600)
    }

    private fun makePlayer(ctx: Context, rate: Int): AudioTrack { // speech to the phone (not the radio's sound card)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 2 * 2)) // 0.5 s
            .build()
        val outs = ctx.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()
        val pref = listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        pref.firstNotNullOfOrNull { ty -> outs.firstOrNull { it.type == ty } }?.let { t.setPreferredDevice(it) } // headphones first, else the speaker
        t.play(); return t
    }

    // ---- Talking ----
    @Volatile private var talkThread: Thread? = null

    /** Start transmitting the phone's microphone (the talk button pressed). False (txError) if it cannot. */
    @SuppressLint("MissingPermission")
    fun startTalk(ctx: Context, myCall: String): Boolean {
        if (talking.value) return true
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { txError.value = "Microphone not allowed"; return false }
        val m = mode.value; val eng = m.engine
        val tx = eng.open()
        if (tx == 0L) { txError.value = "FreeDV ${m.label} would not open"; return false }
        eng.setText(tx, if (m == FdMode.RADE) myCall.trim().uppercase() else txText.ifBlank { myCall }) // (RADE: the callsign alone)
        val sz = eng.sizes(tx); val nSpeech = sz[1]; val rate = m.speechRate
        val rec = try {
            AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), nSpeech * 4)).build()
        } catch (e: Exception) { eng.close(tx); txError.value = "The microphone would not open: ${e.message}"; return false }
        ctx.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let { rec.setPreferredDevice(it) } // the phone's own microphone (not the radio's sound card)
        if (!Transmitter.startStream(ctx, myCall, RATE, Mode.FREEDV.name)) { rec.release(); eng.close(tx); txError.value = Transmitter.lastError.value; return false }
        txError.value = ""; talking.value = true
        val g = level.coerceIn(0f, 1f) * 2f           // (the modem's audio peaks near half full scale: level 50 % = full)
        fun send(mod: ShortArray) { for (i in mod.indices) mod[i] = (mod[i] * g).toInt().coerceIn(-32768, 32767).toShort(); Transmitter.streamWrite(mod) }
        talkThread = Thread({
            val speech = ShortArray(nSpeech)
            try {
                rec.startRecording()
                while (talking.value && Transmitter.streaming) {
                    var got = 0
                    while (got < nSpeech && talking.value) { val r = rec.read(speech, got, nSpeech - got); if (r <= 0) break; got += r }
                    if (got < nSpeech) break
                    send(eng.tx(tx, speech))
                }
                if (Transmitter.streaming) { send(eng.txEnd(tx)); Transmitter.drainStream() } // the over's last audio (RADE: the callsign), sent before PTT goes off
            } catch (e: Exception) { txError.value = "Transmit stopped: ${e.message}" }
            finally {
                try { rec.stop() } catch (e: Exception) { }; rec.release(); eng.close(tx)
                Transmitter.stopStream(); talking.value = false
                if (txError.value.isEmpty() && Transmitter.lastError.value.isNotEmpty()) txError.value = Transmitter.lastError.value
            }
        }, "freedv-tx").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    /** Stop transmitting (the talk button released). */
    fun stopTalk() { talking.value = false }
}
