// JS8 receive (JS8 Normal, 15 s slots like FT8): SlotAudio hands each slot's audio to JS8Call's decoder; the frames
// heard go into the three things JS8Call shows - Band activity (the text at each audio offset, frames joined into
// messages as they arrive, a diamond at a message's end), the calls heard (with signal, locator when sent, when last
// heard), and the messages addressed to you. Kept while the app runs.
package uk.hamdigital.core

import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.engine.Js8Native
import java.time.Instant
import java.time.ZoneOffset

/** One decoded JS8 frame. */
data class Js8Frame(
    val slotMs: Long, val snr: Int, val dt: Float, val freq: Int, val bits: Int, val lowConfidence: Boolean,
    val frameType: Int, val from: String, val to: String, val message: String, val frame: String,
) {
    val last get() = bits and 2 != 0                  // the last frame of a message (JS8CallLast)
    val utc: String get() = hms(slotMs)               // "123045"
}

/** Band activity: the text heard at one audio offset. */
data class Js8Band(val freq: Int, val lastMs: Long, val snr: Int, val text: String)

/** A call heard. */
data class Js8Call(val call: String, val lastMs: Long, val snr: Int, val grid: String, val freq: Int, val km: Int?)

private fun hms(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).let { "%02d%02d%02d".format(it.hour, it.minute, it.second) }

class Js8Decoder private constructor() {
    val band = MutableStateFlow<List<Js8Band>>(emptyList())   // by offset (newest activity first)
    val calls = MutableStateFlow<List<Js8Call>>(emptyList())  // by when last heard
    val toMe = MutableStateFlow<List<Js8Frame>>(emptyList())  // directed to your call, newest first
    val busy = MutableStateFlow(false)                // decoding now
    val lastCount = MutableStateFlow(-1)              // frames in the last slot (-1: none decoded yet, -2: we transmitted in it)
    @Volatile var nfqso = 1500                        // the receive offset (Hz): tried first
    @Volatile var myCall = ""                         // for "to me"
    @Volatile var myGrid = ""                         // for distances
    private val audio = SlotAudio(15_000L, 14_600L) { slot, out -> decode(slot, out) } // JS8 Normal: 15 s slots, frames over by 13.2 s

    fun feed(b: ShortArray, n: Int) = audio.feed(b, n) // audio in (the capture thread)

    private fun decode(slot: Long, out: ShortArray) { // (the background thread)
        if (uk.hamdigital.audio.Transmitter.sentDuring(slot, slot + 15_000L)) { lastCount.value = -2; return } // our own transmission: not decoded
        busy.value = true
        val lines = try { Js8Native.decode(out, out.size, nfqso) } catch (e: Throwable) { emptyArray() }
        val got = lines.mapNotNull { parse(slot, it) }.sortedBy { it.freq }
        got.forEach { add(it) }
        lastCount.value = got.size; busy.value = false
    }

    private fun parse(slot: Long, line: String): Js8Frame? {
        val p = line.split('\t'); if (p.size < 10) return null
        return Js8Frame(slot, p[0].toIntOrNull() ?: 0, p[1].toFloatOrNull() ?: 0f, p[2].toFloatOrNull()?.toInt() ?: 0, p[3].toIntOrNull() ?: 0,
            p[4] == "1", p[5].toIntOrNull() ?: 255, p[6], p[7], p[8], p[9])
    }

    /** One frame into the three lists. */
    private fun add(f: Js8Frame) {
        // Band activity: frames within 10 Hz of an offset heard in the last 5 minutes join its text (as JS8Call's offsets).
        val now = f.slotMs
        val list = band.value.toMutableList()
        val i = list.indexOfFirst { kotlin.math.abs(it.freq - f.freq) <= 10 && now - it.lastMs < 300_000 }
        val piece = (if (f.lowConfidence) "[${f.message.trim()}] " else f.message) + if (f.last) " ♢ " else "" // [suspect]; a diamond ends a message
        if (i >= 0) { val b = list.removeAt(i); list.add(0, b.copy(lastMs = now, snr = f.snr, text = (b.text + piece).takeLast(2000))) }
        else list.add(0, Js8Band(f.freq, now, f.snr, piece))
        band.value = list.take(100)
        // Calls heard: the sender, with the locator a heartbeat or CQ carries.
        if (f.from.isNotEmpty() && !f.from.startsWith("@") && f.from != "<....>") {
            val grid = f.message.trim().split(' ').lastOrNull()?.takeIf { f.frameType == 0 && Locator.valid(it) } ?: "" // heartbeat: "CALL: @HB HEARTBEAT GRID"
            val cs = calls.value.toMutableList()
            val old = cs.firstOrNull { it.call == f.from }; cs.removeAll { it.call == f.from }
            val g = grid.ifEmpty { old?.grid ?: "" }
            cs.add(0, Js8Call(f.from, now, f.snr, g, f.freq, if (g.isNotEmpty() && myGrid.isNotEmpty()) Locator.km(myGrid, g) else null))
            calls.value = cs.take(200)
        }
        // To you: a directed frame naming your call.
        if (myCall.isNotEmpty() && f.to.trim('<', '>') == myCall) toMe.value = (listOf(f) + toMe.value).take(200)
    }

    fun clear() { band.value = emptyList(); calls.value = emptyList(); toMe.value = emptyList(); lastCount.value = -1 } // empty the lists

    companion object { val one by lazy { Js8Decoder() } } // the app's JS8 decoder
}

fun js8Time(ms: Long) = hms(ms)                       // "123045", for the page
