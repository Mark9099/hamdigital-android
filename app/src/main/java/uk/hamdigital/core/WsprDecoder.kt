// WSPR receive: WSPR transmissions start one second after each even UTC minute and last 110.6 s. This keeps the last
// 125 s of 12 kHz audio with its UTC time; 114 s after each even minute it saves the slot as a WAV file (as WSJT-X
// does: named yymmdd_hhmm.wav, from the even minute) and runs wsprd on it in the background. The spots go into a list
// (newest slot first) kept while the app runs, and - if turned on - to WSPRnet (WsprNet).
package uk.hamdigital.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.hamdigital.engine.WsprNative
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors

/** One WSPR spot. */
data class WsprSpot(
    val slotMs: Long,                                 // the slot's start (UTC, epoch ms)
    val snr: Int,                                     // dB in 2500 Hz
    val dt: Float,                                    // time offset (s)
    val freqMHz: Double,                              // RF frequency (dial + audio); with no dial known, the audio offset in MHz
    val drift: Int,                                   // Hz a minute
    val call: String,                                 // callsign
    val grid: String,                                 // locator ("" if not sent)
    val dbm: Int?,                                    // power, dBm
    val km: Int?,                                     // distance from your locator
) {
    val utc: String get() = Instant.ofEpochMilli(slotMs).atZone(ZoneOffset.UTC).let { "%02d%02d".format(it.hour, it.minute) } // "0918"
    /** Power in watts, as WSPR's dBm steps are usually read. */
    val watts: String get() = dbm?.let { d -> val w = Math.pow(10.0, (d - 30) / 10.0); if (w >= 1) "%.0f W".format(w) else if (w >= 0.01) "%.0f mW".format(w * 1000) else "%.1f mW".format(w * 1000) } ?: ""
}

class WsprDecoder private constructor(ctx: Context) {
    private val rate = 12000                          // samples a second
    private val slotMs = 120_000L                     // two minutes
    private val recordMs = 114_000L                   // what wsprd reads (114 s)
    private val ring = ShortArray(rate * 125)         // the last 125 s
    private var written = 0L                          // samples written since the start
    private var endMs = 0L                            // UTC time of the newest sample
    private var startedMs = 0L                        // UTC time of the first sample
    private var lastSlot = 0L                         // the last slot decoded
    private val worker = Executors.newSingleThreadExecutor() // wsprd, one slot at a time
    private val wavDir = File(ctx.cacheDir, "wspr").apply { mkdirs() } // the slot's WAV (deleted after)
    private val dataDir = File(ctx.filesDir, "wsprd").apply { mkdirs() } // wsprd's own files (callsign hash table, ALL_WSPR.TXT)

    private val _spots = MutableStateFlow<List<WsprSpot>>(emptyList()) // heard, newest first
    val spots: StateFlow<List<WsprSpot>> = _spots
    val busy = MutableStateFlow(false)                // decoding now
    val lastCount = MutableStateFlow(-1)              // spots in the last slot (-1: none decoded yet, -2: we transmitted in it)
    val message = MutableStateFlow("")                // what went wrong, if anything

    @Volatile var myGrid = ""                         // for distances
    @Volatile var dialMHz = 0.0                       // the radio's dial (0: unknown - the audio offset is shown)

    /** Audio in (the capture thread): 12 kHz mono. */
    fun feed(b: ShortArray, n: Int) {
        val now = System.currentTimeMillis()          // the newest sample's time
        if (written == 0L || now - endMs > 2000) { startedMs = now - n * 1000L / rate; written = 0 } // (re)start after a gap
        for (i in 0 until n) ring[((written + i) % ring.size).toInt()] = b[i] // into the ring
        written += n; endMs = now
        val slot = now - now % slotMs                 // this two minutes' start
        if (now - slot >= recordMs && slot != lastSlot) { lastSlot = slot; take(slot) } // the transmissions are over: decode
    }

    /** The slot's 114 s from the ring (silence for any part before the audio started) to a WAV file, then wsprd. */
    private fun take(slot: Long) {
        if (startedMs > slot + 30_000) { message.value = "Waiting for the next 2-minute slot (WSPR decodes need most of one)"; return } // too little heard
        if (uk.hamdigital.audio.Transmitter.sentDuring(slot, slot + recordMs)) { message.value = ""; lastCount.value = -2; return } // our beacon: not decoded
        val n = (recordMs * rate / 1000).toInt()      // 114 s of samples
        val first = written - ((endMs - slot) * rate / 1000) // the slot start's sample number
        val pcm = ShortArray(n)
        for (i in 0 until n) { val k = first + i; if (k >= 0 && k >= written - ring.size && k < written) pcm[i] = ring[(k % ring.size).toInt()] }
        val dial = dialMHz                            // (as it was in this slot)
        busy.value = true; message.value = ""
        worker.execute {
            val t = Instant.ofEpochMilli(slot).atZone(ZoneOffset.UTC)
            val wav = File(wavDir, "%02d%02d%02d_%02d%02d.wav".format(t.year % 100, t.monthValue, t.dayOfMonth, t.hour, t.minute)) // WSJT-X's name: wsprd reads the date and time from it
            try {
                writeWav(wav, pcm)                    // 12 kHz 16-bit mono
                val out = WsprNative.decode(wav.absolutePath, dataDir.absolutePath, dial) // wsprd
                if (out == null) message.value = "wsprd could not decode this slot"
                val got = out?.lines()?.mapNotNull { parse(slot, it, dial) } ?: emptyList()
                _spots.value = (got + _spots.value).take(500) // newest slot first
                lastCount.value = got.size
                if (out != null) { val b = WsprBeacon.state.value // to WSPRnet, if turned on (with what the beacon is doing)
                    WsprNet.slot(out.lines().filter { it.isNotBlank() }, dial, if (b.enabled) b.percent else 0, dial + b.txHz / 1e6, b.dbm) }
            } catch (e: Throwable) { message.value = "WSPR decode failed: ${e.message}" }
            wav.delete(); busy.value = false          // tidy up
        }
    }

    fun clear() { _spots.value = emptyList(); lastCount.value = -1 } // empty the list

    /** "date time sync snr dt freq message... drift cycles jitter" -> WsprSpot. */
    private fun parse(slot: Long, line: String, dial: Double): WsprSpot? {
        val w = line.trim().split(Regex("\\s+")); if (w.size < 10) return null // fixed fields + at least one message word
        val msg = w.subList(6, w.size - 3)            // the message: CALL GRID DBM, <CALL> DBM or CALL/x DBM
        val call = msg.getOrNull(0)?.trim('<', '>') ?: return null
        val grid = msg.firstOrNull { Locator.valid(it) && it != call } ?: ""
        val dbm = msg.lastOrNull()?.toIntOrNull()
        val f = w[5].toDoubleOrNull() ?: return null   // RF MHz (dial + 1500 Hz + offset)
        return WsprSpot(slot, w[3].toFloatOrNull()?.toInt() ?: 0, w[4].toFloatOrNull() ?: 0f, f, w[w.size - 3].toIntOrNull() ?: 0, call, grid, dbm,
            if (grid.isNotEmpty() && myGrid.isNotEmpty()) Locator.km(myGrid, grid) else null)
    }

    private fun writeWav(f: File, pcm: ShortArray) {  // a plain 44-byte-header WAV (wsprd skips the header unread)
        RandomAccessFile(f, "rw").use { r ->
            r.setLength(0)
            val data = pcm.size * 2                   // bytes of samples
            val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(data) // PCM, mono, 12 kHz, 16-bit
            r.write(h.array())
            val bb = java.nio.ByteBuffer.allocate(data).order(java.nio.ByteOrder.LITTLE_ENDIAN); bb.asShortBuffer().put(pcm)
            r.write(bb.array())
        }
    }

    companion object {
        @Volatile private var one: WsprDecoder? = null
        fun get(ctx: Context) = one ?: synchronized(this) { one ?: WsprDecoder(ctx.applicationContext).also { one = it } } // the app's WSPR decoder
    }
}
