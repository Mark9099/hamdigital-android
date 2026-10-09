// FreeDV digital voice, with codec2's FreeDV API (David Rowe and others, LGPL 2.1): modes 700D, 700E and 1600.
// Receive: the radio's audio (8 kHz) goes to the modem a frame at a time; the speech it decodes plays on the phone -
// its speaker, or headphones / Bluetooth if connected, never the radio's USB sound card. "Radio audio" plays the
// radio's audio itself instead (to tune, or hear SSB); the squelch keeps it quiet without a FreeDV signal.
// Transmit: while the talk button is held, the phone's microphone (8 kHz) is coded a frame at a time and the modem
// audio streamed to the radio (Transmitter.startStream); the text channel repeats the text set (your call).
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

object FreeDv {
    const val RATE = 8000                             // FreeDV's modem and speech rate
    enum class FdMode(val label: String, val code: Int) { M700D("700D", FreeDvNative.MODE_700D), M700E("700E", FreeDvNative.MODE_700E), M1600("1600", FreeDvNative.MODE_1600) }
    enum class Listen(val label: String) { SPEECH("FreeDV speech"), RADIO("Radio audio"), OFF("Off") }

    val mode = MutableStateFlow(FdMode.M700D)         // the mode
    val listen = MutableStateFlow(Listen.SPEECH)      // what the phone plays
    val squelch = MutableStateFlow(true)              // quiet without a signal
    val sync = MutableStateFlow(false)                // the modem has a FreeDV signal
    val snr = MutableStateFlow(0f)                    // its SNR estimate (dB)
    val text = MutableStateFlow("")                   // the text channel received (newest at the end)
    val talking = MutableStateFlow(false)             // transmitting
    val txError = MutableStateFlow("")                // why transmitting did not start / stopped

    @Volatile private var rx = 0L                     // the receive session
    private val lock = Any()
    private var buf = ShortArray(RATE); private var have = 0 // audio waiting for the modem
    private var player: AudioTrack? = null            // the phone's speaker / headphones
    @Volatile var txText = ""                         // what the text channel sends
    @Volatile var level = 0.3f                        // transmit level (Settings), 0..1

    /** Open (or re-open, after a mode change) the receive session. */
    fun open(ctx: Context) = synchronized(lock) {
        if (rx != 0L) FreeDvNative.close(rx)
        rx = FreeDvNative.open(mode.value.code); have = 0
        if (rx != 0L) FreeDvNative.squelch(rx, squelch.value, if (mode.value == FdMode.M1600) 2f else -2f) // (FreeDV GUI's own thresholds)
        if (player == null) player = makePlayer(ctx)
    }

    fun setMode(ctx: Context, m: FdMode) { mode.value = m; open(ctx) }
    fun setSquelch(on: Boolean) { squelch.value = on; synchronized(lock) { if (rx != 0L) FreeDvNative.squelch(rx, on, if (mode.value == FdMode.M1600) 2f else -2f) } }

    /** Close everything (the page closes). */
    fun close() = synchronized(lock) {
        stopTalk()
        if (rx != 0L) FreeDvNative.close(rx); rx = 0
        player?.let { try { it.stop() } catch (e: Exception) { }; it.release() }; player = null
        sync.value = false
    }

    /** Audio in (the capture thread): 8 kHz mono from the radio. */
    fun feed(b: ShortArray, n: Int) = synchronized(lock) {
        if (rx == 0L) return
        val now = System.currentTimeMillis()
        if (Transmitter.sentDuring(now - 600, now)) { have = 0; return } // (our own signal while we talk)
        if (listen.value == Listen.RADIO) player?.write(b, 0, n) // the radio itself
        if (have + n > buf.size) buf = buf.copyOf(have + n + RATE)
        System.arraycopy(b, 0, buf, have, n); have += n
        while (true) {                                // as many modem frames as there is audio for
            val nin = FreeDvNative.sizes(rx)[0]
            if (have < nin) break
            val speech = FreeDvNative.rx(rx, buf.copyOf(nin))
            System.arraycopy(buf, nin, buf, 0, have - nin); have -= nin
            if (listen.value == Listen.SPEECH && speech.isNotEmpty()) player?.write(speech, 0, speech.size)
        }
        val st = FreeDvNative.stats(rx); sync.value = st[0] > 0.5f; snr.value = st[1]
        val t = FreeDvNative.text(rx); if (t.isNotEmpty()) text.value = (text.value + t.filter { it >= ' ' || it == '\r' }.replace('\r', '\n')).takeLast(600)
    }

    private fun makePlayer(ctx: Context): AudioTrack {  // 8 kHz to the phone (not the radio's sound card)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), RATE / 2 * 2)) // 0.5 s
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
        val tx = FreeDvNative.open(mode.value.code)
        if (tx == 0L) { txError.value = "FreeDV ${mode.value.label} would not open"; return false }
        FreeDvNative.setText(tx, txText.ifBlank { myCall })
        val sz = FreeDvNative.sizes(tx); val nSpeech = sz[1]
        val rec = try {
            AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), nSpeech * 4)).build()
        } catch (e: Exception) { FreeDvNative.close(tx); txError.value = "The microphone would not open: ${e.message}"; return false }
        ctx.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let { rec.setPreferredDevice(it) } // the phone's own microphone (not the radio's sound card)
        if (!Transmitter.startStream(ctx, myCall, RATE, Mode.FREEDV.name)) { rec.release(); FreeDvNative.close(tx); txError.value = Transmitter.lastError.value; return false }
        txError.value = ""; talking.value = true
        val g = level.coerceIn(0f, 1f) * 2f           // (the modem's audio peaks near half full scale: level 50 % = full)
        talkThread = Thread({
            val speech = ShortArray(nSpeech)
            try {
                rec.startRecording()
                while (talking.value && Transmitter.streaming) {
                    var got = 0
                    while (got < nSpeech && talking.value) { val r = rec.read(speech, got, nSpeech - got); if (r <= 0) break; got += r }
                    if (got < nSpeech) break
                    val mod = FreeDvNative.tx(tx, speech)
                    for (i in mod.indices) mod[i] = (mod[i] * g).toInt().coerceIn(-32768, 32767).toShort()
                    Transmitter.streamWrite(mod)
                }
            } catch (e: Exception) { txError.value = "Transmit stopped: ${e.message}" }
            finally {
                try { rec.stop() } catch (e: Exception) { }; rec.release(); FreeDvNative.close(tx)
                Transmitter.stopStream(); talking.value = false
                if (txError.value.isEmpty() && Transmitter.lastError.value.isNotEmpty()) txError.value = Transmitter.lastError.value
            }
        }, "freedv-tx").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    /** Stop transmitting (the talk button released). */
    fun stopTalk() { talking.value = false }
}
