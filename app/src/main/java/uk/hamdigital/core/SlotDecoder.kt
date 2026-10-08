// FT8 / FT4 receive: keeps the last 30 s of 12 kHz audio with its UTC time, and when each slot (15 s FT8, 7.5 s FT4,
// starting on the UTC clock) is nearly over hands that slot's audio to ft8_lib on a background thread. The results go
// into a list (newest first) that the page shows; it is kept while the app runs, so leaving the page and coming back
// keeps what was heard.
package uk.hamdigital.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.hamdigital.engine.Ft8Native
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors

/** One decoded message. */
data class FtDecode(
    val slotMs: Long,                                 // the slot's start (UTC, epoch ms)
    val snr: Int,                                     // dB in 2500 Hz
    val dt: Float,                                    // time offset (s)
    val freq: Int,                                    // audio frequency (Hz)
    val text: String,                                 // the message
    val to: String,                                   // first call ("CQ" for a CQ)
    val from: String,                                 // the sender
    val grid: String,                                 // the sender's locator, if sent
    val km: Int?,                                     // distance to it from your locator
    val cq: Boolean,                                  // a CQ call
    val toMe: Boolean,                                // addressed to your callsign
) {
    val utc: String get() = Instant.ofEpochMilli(slotMs).atZone(ZoneOffset.UTC).let { "%02d%02d%02d".format(it.hour, it.minute, it.second) } // "123045"
}

class SlotDecoder(val ft4: Boolean) {
    val periodMs = if (ft4) 7500L else 15000L         // slot length
    private val decodeAtMs = if (ft4) 7300L else 14700L // when in the slot to decode (the transmissions are over by then)
    private val rate = 12000                          // samples a second
    private val ring = ShortArray(rate * 30)          // the last 30 s
    private var written = 0L                          // samples written since the start
    private var endMs = 0L                            // UTC time of the newest sample
    private var startedMs = 0L                        // UTC time of the first sample (audio started)
    private var lastSlot = 0L                         // the last slot handed to the decoder
    private val worker = Executors.newSingleThreadExecutor() // decodes, one slot at a time

    private val _decodes = MutableStateFlow<List<FtDecode>>(emptyList()) // what was heard, newest first
    val decodes: StateFlow<List<FtDecode>> = _decodes
    val busy = MutableStateFlow(false)                // decoding now
    val lastCount = MutableStateFlow(-1)              // decodes in the last slot (-1: none decoded yet)

    @Volatile var myCall = ""                         // for "to me"
    @Volatile var myGrid = ""                         // for distances

    /** Audio in (the capture thread): 12 kHz mono. */
    fun feed(b: ShortArray, n: Int) {
        val now = System.currentTimeMillis()          // the newest sample's time (capture delay is a few ms)
        if (written == 0L || now - endMs > 2000) { startedMs = now - n * 1000L / rate; written = 0 } // (re)start: after a gap the old audio does not join on
        for (i in 0 until n) ring[((written + i) % ring.size).toInt()] = b[i] // into the ring
        written += n; endMs = now
        val slot = now - now % periodMs               // this slot's start
        if (now - slot >= decodeAtMs && slot != lastSlot) { lastSlot = slot; take(slot) } // nearly over: decode it
    }

    /** Copy the slot's audio from the ring (zeros for any part before the audio started) and decode it in the background. */
    private fun take(slot: Long) {
        if (startedMs > slot + 3000) return           // too little of the slot was heard
        val n = ((endMs - slot) * rate / 1000).toInt().coerceAtMost(rate * 15) // samples from the slot start to now
        val first = written - n                        // the slot start's sample number
        val out = ShortArray(n)
        for (i in 0 until n) { val k = first + i; if (k >= 0 && k >= written - ring.size) out[i] = ring[(k % ring.size).toInt()] } // (before the audio: silence)
        busy.value = true
        worker.execute {
            val lines = try { Ft8Native.decode(out, n, ft4) } catch (e: Throwable) { emptyArray() } // ft8_lib
            val got = lines.mapNotNull { parse(slot, it) }.sortedBy { it.freq } // by frequency within the slot
            _decodes.value = (got + _decodes.value).take(600) // newest slot first; the most recent 600 kept
            lastCount.value = got.size; busy.value = false
        }
    }

    fun clear() { _decodes.value = emptyList(); lastCount.value = -1 } // empty the list

    /** "snr\tdt\tfreq\ttext" -> FtDecode, with the calls and locator picked out. */
    private fun parse(slot: Long, line: String): FtDecode? {
        val p = line.split('\t'); if (p.size < 4) return null
        val text = p[3].trim(); val w = text.split(' ').filter { it.isNotEmpty() } // the message, its words
        val cq = w.firstOrNull() == "CQ"              // a CQ
        val from: String; val to: String
        if (cq) {                                     // CQ [modifier] CALL [GRID]
            val mod = w.size >= 3 && w[1].length <= 4 && w[1].all { it.isLetter() } && !w[1].any { it.isDigit() } // "DX", "POTA", "NA" ...
            from = w.getOrNull(if (mod) 2 else 1) ?: ""; to = "CQ"
        } else { to = w.getOrNull(0) ?: ""; from = w.getOrNull(1) ?: "" } // TO FROM [extra]
        val last = w.lastOrNull() ?: ""
        val grid = if (w.size >= 3 && Locator.valid(last) && last != "RR73") last else "" // a locator at the end ("RR73" looks like one)
        val me = myCall.isNotEmpty() && to.trim('<', '>') == myCall // to you
        return FtDecode(slot, p[0].toFloatOrNull()?.toInt() ?: 0, p[1].toFloatOrNull() ?: 0f, p[2].toFloatOrNull()?.toInt() ?: 0, text,
            to.trim('<', '>'), from.trim('<', '>'), grid, if (grid.isNotEmpty() && myGrid.isNotEmpty()) Locator.km(myGrid, grid) else null, cq, me)
    }

    companion object {
        val FT8 by lazy { SlotDecoder(false) }        // the app's FT8 decoder
        val FT4 by lazy { SlotDecoder(true) }         // and FT4
    }
}
