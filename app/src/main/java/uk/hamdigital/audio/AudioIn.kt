// Receive audio for the open mode's page, on its own thread: from the IC-705's USB sound card when it is plugged in
// (Settings > Receive audio), else the phone's microphone. The radio's USB audio is 48 kHz; Android converts it to the
// rate the mode's decoder wants. Uses the "unprocessed" source where the phone offers it (no noise suppression or
// automatic gain, which would spoil weak digital signals), else voice recognition (lightly processed).
package uk.hamdigital.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import uk.hamdigital.core.AudioChoice
import kotlin.math.abs

object AudioIn {
    @Volatile var running = false; private set        // capturing
    @Volatile var level = 0f; private set             // input peak, 0..1 of full scale (fast up, slow down)
    @Volatile var sourceName = ""; private set        // where the audio is from, for the page's status line
    @Volatile var wifi = false; private set           // the audio comes over the WiFi link (it may be down for a while: RxStatus says so)
    @Volatile var rate = 0; private set               // sample rate now (Hz)
    private var thread: Thread? = null                // the capture thread
    @Volatile var owner: Any? = null; private set     // the page that started it (only it stops it)

    fun allowed(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** The IC-705's (or any) USB sound card's input, if one is plugged in. */
    fun usbInput(ctx: Context): AudioDeviceInfo? =
        ctx.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }

    /** A USB device's name ("IC-705" when it is the radio). */
    fun usbName(d: AudioDeviceInfo) = d.productName?.toString()?.ifBlank { null } ?: "USB audio"

    /**
     * Start capturing [sampleRate] Hz mono 16-bit audio in blocks of [block] samples, each handed to [sink] on the
     * capture thread; [who] is the page asking (see stop). Returns null when running, else what went wrong.
     */
    @Suppress("MissingPermission")                    // checked by allowed()
    fun start(ctx: Context, who: Any, choice: AudioChoice, sampleRate: Int, block: Int, sink: (ShortArray, Int) -> Unit): String? {
        stopNow()                                     // one capture at a time (the previous page's)
        if (choice == AudioChoice.WIFI) {             // the radio's audio over WiFi (IcomNet): no recording here
            netSink = sink; netBlock = ShortArray(block); netFill = 0; netPos = 0.0; netStep = 12000.0 / sampleRate // resample 12 kHz to the page's rate
            sourceName = "IC-705 (WiFi)"; rate = sampleRate; owner = who; running = true; wifi = true
            return null                               // (ready for the audio whenever the link is up: a link that is down, or reconnecting, is shown live by RxStatus - 0.9.0: it used to be a fixed error, so a page opened during a reconnect stayed "No audio")
        }
        wifi = false                                  // (a recording on the phone)
        if (!allowed(ctx)) return "Audio not allowed - Android Settings > Apps > HF Digital Modes > Permissions"
        val usb = if (choice == AudioChoice.MIC) null else usbInput(ctx) // the radio's sound card?
        if (choice == AudioChoice.USB && usb == null) return "IC-705 not found - plug in its USB lead (or Settings > Receive audio)"
        val am = ctx.getSystemService(AudioManager::class.java) // does the phone offer an unprocessed source?
        val src = if (am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") MediaRecorder.AudioSource.UNPROCESSED
                  else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) // smallest buffer
        val rec = try { AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, sampleRate)) } // 0.5 s of buffer
                  catch (e: Exception) { return "The audio input would not open (${e.message})" }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return "The audio input would not open" }
        if (usb != null) rec.setPreferredDevice(usb)  // the radio rather than the microphone
        try { rec.startRecording() } catch (e: Exception) { rec.release(); return "The audio input would not start (${e.message})" }
        sourceName = if (usb != null) "${usbName(usb)} (USB)" else "Phone microphone" // for the status line
        rate = sampleRate; owner = who; running = true // running, for this page
        thread = Thread({
            val buf = ShortArray(block)               // one block
            while (running) {
                val n = rec.read(buf, 0, buf.size)    // waits for a block
                if (n <= 0) continue                  // nothing yet
                var peak = 0; for (i in 0 until n) { val v = abs(buf[i].toInt()); if (v > peak) peak = v } // input level
                val l = peak / 32768f; level = if (l > level) l else level * 0.95f // fast up, slow down
                sink(buf, n)                          // the page's decoder and waterfall
            }
            try { rec.stop() } catch (e: Exception) { }; rec.release(); level = 0f // tidy up
        }, "audio-in").apply { priority = Thread.MAX_PRIORITY; start() }
        return null                                   // running
    }

    /** Stop, if [who] started it (a page closing must not stop the next page's capture). */
    fun stop(who: Any) { if (owner === who) stopNow() }

    private fun stopNow() { running = false; thread?.join(500); thread = null; owner = null; netSink = null } // the thread ends after its next block

    // ---- WiFi audio (IcomNet hands it in on its network thread): 12 kHz, converted to the page's rate ----
    @Volatile private var netSink: ((ShortArray, Int) -> Unit)? = null // the page's decoder and waterfall
    private var netBlock = ShortArray(0); private var netFill = 0      // the block being filled
    private var netPos = 0.0; private var netStep = 1.0                // resampling: position in the input, input samples a output sample
    private var netLast: Short = 0                                    // the previous input sample (for interpolation across packets)

    /** Receive audio from the radio over WiFi. */
    fun netAudio(s: ShortArray, n: Int) {
        val sink = netSink ?: return; if (!running || n == 0) return
        var peak = 0; for (i in 0 until n) { val v = abs(s[i].toInt()); if (v > peak) peak = v } // input level
        val l = peak / 32768f; level = if (l > level) l else level * 0.95f
        while (netPos < n) {                          // linear interpolation between input samples
            val i = netPos.toInt(); val f = netPos - i
            val a = if (i == 0) netLast.toInt() else s[i - 1].toInt(); val b = s[i].toInt() // (sample i-1 .. i)
            netBlock[netFill++] = (a + (b - a) * f).toInt().toShort()
            if (netFill == netBlock.size) { sink(netBlock, netFill); netFill = 0 } // a block to the page
            netPos += netStep
        }
        netPos -= n; netLast = s[n - 1]               // carry over to the next packet
    }
}
