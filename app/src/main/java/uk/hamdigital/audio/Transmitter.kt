// Transmitting: keys the IC-705 over CI-V (PTT), plays the audio to the radio's USB sound card, and unkeys it. One
// transmission at a time, on its own thread. Safety: nothing is sent unless the radio's CI-V is connected (so PTT can
// be released), the USB sound card is there (never the phone's speaker), and a callsign is set; a watchdog ends any
// transmission after 2 minutes 10 s (WSPR's 110.6 s is the longest); Halt stops at once. The link is checked again when
// keying (a transmission is often arranged seconds ahead) and all through: if the radio's CI-V or the WiFi link goes,
// the transmission stops there (0.10.4: a WSPR beacon "sent" 110 s with the link closed, then carried on when it was
// back). [owner] says which mode is sending, so opening another mode can stop it (TxControl).
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
    @Volatile var owner = ""; private set             // the mode sending (Mode.name: "FT8", "WSPR" ...)
    private const val WATCHDOG_MS = 130_000L          // longest allowed transmission

    /** The IC-705's (or any) USB sound card's output, if plugged in. */
    fun usbOutput(ctx: Context): AudioDeviceInfo? =
        ctx.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }

    /** Why sending is not possible now, or null if it is. */
    fun blocked(ctx: Context, callsign: String): String? = when {
        callsign.isBlank() -> "Set your callsign in Settings before transmitting"
        Ic705.state.value.link != RigState.Link.CONNECTED -> "The IC-705's CI-V is not connected, so the app cannot key it"
        Ic705.net -> if (uk.hamdigital.rig.IcomNet.loggedIn) null else "The WiFi link to the IC-705 is not logged in"
        usbOutput(ctx) == null -> "The IC-705's USB sound card is not connected"
        else -> null
    }

    /**
     * Send [audio] ([rate] Hz mono) starting at [startAtMs] (UTC epoch ms; 0 = now): PTT on, the audio, PTT off.
     * [tag] is the mode sending (Mode.name). [onDone] runs afterwards (true if it was all sent). Returns false (with
     * lastError set) if it cannot start.
     */
    fun send(ctx: Context, callsign: String, audio: ShortArray, rate: Int, startAtMs: Long = 0, tag: String = "", onDone: (Boolean) -> Unit = {}): Boolean {
        blocked(ctx, callsign)?.let { lastError.value = it; return false } // safety first
        if (thread != null) { lastError.value = "Already transmitting"; return false }
        owner = tag
        if (Ic705.net) return sendNet(audio, rate, startAtMs, onDone) // over WiFi
        val dev = usbOutput(ctx)!!                    // the radio's sound card
        stop = false; lastError.value = ""
        thread = Thread({
            var ok = false
            var track: AudioTrack? = null
            try {
                val wait = startAtMs - System.currentTimeMillis() - 60 // key 60 ms before the audio starts
                if (wait > 0) Thread.sleep(wait)
                if (stop) return@Thread
                if (Ic705.state.value.link != RigState.Link.CONNECTED) { lastError.value = "The IC-705's CI-V went before the transmission: not sent"; return@Thread } // (checked again: arranged seconds ago)
                track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 5 * 2)) // 0.2 s
                    .build()
                track.setPreferredDevice(dev)         // the radio, never the speaker
                Ic705.ptt(true); keyed(true)          // key the transmitter
                Thread.sleep(maxOf(0L, minOf(60L, startAtMs - System.currentTimeMillis()))) // (the rest of the keying lead)
                track.play()
                val t0 = System.currentTimeMillis()
                var i = 0
                while (i < audio.size && !stop && System.currentTimeMillis() - t0 < WATCHDOG_MS) { // in 50 ms pieces, so Halt is quick
                    if (Ic705.state.value.link != RigState.Link.CONNECTED) { lastError.value = "The IC-705's CI-V went during the transmission: stopped"; break }
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
                keyed(false); thread = null
                onDone(ok)
            }
        }, "tx").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    /** Over WiFi: PTT (which opens the transmit stream), the audio as 12 kHz floats to the protocol code, which sends it
     *  in real time, then PTT off when it has had time to go. */
    private fun sendNet(audio: ShortArray, rate: Int, startAtMs: Long, onDone: (Boolean) -> Unit): Boolean {
        stop = false; lastError.value = ""
        val n = (audio.size.toLong() * 12000 / rate).toInt() // samples at 12 kHz
        val f = FloatArray(n) { i -> val p = i.toDouble() * rate / 12000; val k = p.toInt(); val a = audio[minOf(k, audio.size - 1)]; val b = audio[minOf(k + 1, audio.size - 1)]; ((a + (b - a) * (p - k)) / 32768.0).toFloat() } // resampled
        thread = Thread({
            var ok = false
            try {
                val wait = startAtMs - System.currentTimeMillis() - 60; if (wait > 0) Thread.sleep(wait) // key 60 ms before
                if (stop) return@Thread
                val link = uk.hamdigital.rig.IcomNet.rig // the link it goes out on
                if (link == null || !uk.hamdigital.rig.IcomNet.loggedIn) { lastError.value = "The WiFi link to the IC-705 is down: not sent"; return@Thread } // (checked again: arranged seconds ago)
                Ic705.ptt(true); keyed(true)          // PTT over the network
                Thread.sleep(60)
                uk.hamdigital.rig.IcomNet.sendAudio(f) // streamed by the protocol code in 20 ms packets
                val end = System.currentTimeMillis() + n * 1000L / 12000 + 200 // its length, plus the protocol's lead-in
                var dropped = false
                while (!stop && System.currentTimeMillis() < end) { // (at most 110.6 s + 0.2: within the watchdog)
                    if (uk.hamdigital.rig.IcomNet.rig !== link || !uk.hamdigital.rig.IcomNet.loggedIn) { dropped = true; lastError.value = "The WiFi link to the IC-705 dropped during the transmission: stopped"; break }
                    Thread.sleep(20)
                }
                ok = !stop && !dropped
            } catch (e: Exception) { lastError.value = "Transmit failed: ${e.message}" }
            finally { Ic705.ptt(false); keyed(false); thread = null; onDone(ok) }
        }, "tx-net").apply { priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    private val sent = ArrayDeque<LongArray>()        // the last few transmissions: [keyed, unkeyed] UTC ms (unkeyed MAX while on)

    private fun keyed(on: Boolean) {                  // PTT on / off: noted for sentDuring
        synchronized(sent) {
            if (on) { sent.addLast(longArrayOf(System.currentTimeMillis(), Long.MAX_VALUE)); while (sent.size > 8) sent.removeFirst() }
            else sent.lastOrNull()?.let { if (it[1] == Long.MAX_VALUE) it[1] = System.currentTimeMillis() }
        }
        _on.value = on
    }

    /** Whether the app transmitted at any time from [fromMs] to [toMs] (UTC ms). The radio passes its own transmit audio
     *  back while keyed, so a slot we sent in decodes as our own message at +40 dB (found on the air, 0.8.6): the
     *  decoders skip such slots, as WSJT-X does - the radio hears nobody else while it transmits. */
    fun sentDuring(fromMs: Long, toMs: Long): Boolean = synchronized(sent) { sent.any { it[0] < toMs && it[1] > fromMs } }

    /** Stop transmitting now (and drop a transmission waiting to start). */
    fun halt() { stop = true; if (thread == null) Ic705.ptt(false) }
}
