// Transmitting: keys the IC-705 over CI-V (PTT), plays the audio to the radio's USB sound card, and unkeys it. One
// transmission at a time, on its own thread. Safety: nothing is sent unless the radio's CI-V is connected (so PTT can
// be released), the USB sound card is there (never the phone's speaker), and a callsign is set; a watchdog ends any
// transmission after 2 minutes 10 s (WSPR's 110.6 s is the longest); Halt stops at once.
package uk.hamdigital.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.hamdigital.rig.Ic705
import uk.hamdigital.rig.RigState

object Transmitter {
    private val _on = MutableStateFlow(false)         // transmitting now
    val on: StateFlow<Boolean> = _on
    val lastError = MutableStateFlow("")              // why the last transmission did not happen
    @Volatile private var stop = false                // Halt
    @Volatile private var thread: Thread? = null      // the transmission
    private const val WATCHDOG_MS = 130_000L          // longest allowed transmission

    /** The IC-705's (or any) USB sound card's output, if plugged in. */
    fun usbOutput(ctx: Context): AudioDeviceInfo? =
        ctx.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }

    /** Why sending is not possible now, or null if it is. */
    fun blocked(ctx: Context, callsign: String): String? = when {
        callsign.isBlank() -> "Set your callsign in Settings before transmitting"
        Ic705.state.value.link != RigState.Link.CONNECTED -> "The IC-705's CI-V is not connected, so the app cannot key it"
        usbOutput(ctx) == null -> "The IC-705's USB sound card is not connected"
        else -> null
    }

    /**
     * Send [audio] ([rate] Hz mono) starting at [startAtMs] (UTC epoch ms; 0 = now): PTT on, the audio, PTT off.
     * [onDone] runs afterwards (true if it was all sent). Returns false (with lastError set) if it cannot start.
     */
    fun send(ctx: Context, callsign: String, audio: ShortArray, rate: Int, startAtMs: Long = 0, onDone: (Boolean) -> Unit = {}): Boolean {
        blocked(ctx, callsign)?.let { lastError.value = it; return false } // safety first
        if (thread != null) { lastError.value = "Already transmitting"; return false }
        val dev = usbOutput(ctx)!!                    // the radio's sound card
        stop = false; lastError.value = ""
        thread = Thread({
            var ok = false
            var track: AudioTrack? = null
            try {
                val wait = startAtMs - System.currentTimeMillis() - 60 // key 60 ms before the audio starts
                if (wait > 0) Thread.sleep(wait)
                if (stop) return@Thread
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 5 * 2)) // 0.2 s
                    .build()
                track.setPreferredDevice(dev)         // the radio, never the speaker
                Ic705.ptt(true); _on.value = true     // key the transmitter
                Thread.sleep(maxOf(0L, minOf(60L, startAtMs - System.currentTimeMillis()))) // (the rest of the keying lead)
                track.play()
                val t0 = System.currentTimeMillis()
                var i = 0
                while (i < audio.size && !stop && System.currentTimeMillis() - t0 < WATCHDOG_MS) { // in 50 ms pieces, so Halt is quick
                    val n = minOf(rate / 20, audio.size - i)
                    val w = track.write(audio, i, n)   // blocks while the buffer is full
                    if (w <= 0) break
                    i += w
                }
                if (!stop) { val tail = 250L; Thread.sleep(tail) } // let the buffered audio play out
                ok = i >= audio.size && !stop
            } catch (e: Exception) {
                lastError.value = "Transmit failed: ${e.message}"
            } finally {
                Ic705.ptt(false); Ic705.ptt(false)    // unkey (twice: CI-V has no acknowledgement here)
                try { track?.stop() } catch (e: Exception) { }; track?.release()
                _on.value = false; thread = null
                onDone(ok)
            }
        }, "tx").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    /** Stop transmitting now (and drop a transmission waiting to start). */
    fun halt() { stop = true; if (thread == null) Ic705.ptt(false) }
}
