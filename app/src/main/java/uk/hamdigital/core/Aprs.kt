// APRS and AX.25 packet, with Dire Wolf (AprsNative): 1200 baud on VHF / UHF FM (the IC-705 in FM-D), 300 baud on HF
// (USB-D). The page's audio goes in (12 kHz); every frame decoded joins the list heard (newest first), the station's
// latest report (and its last position, for the map), and - if it is an APRS message to you - your messages. Sending:
// your position (from your locator: the middle of the square, so not your exact address) as a beacon, or a message to
// a station; one frame each, through the radio as one transmission.
package uk.hamdigital.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.engine.AprsNative

object Aprs {
    const val RATE = 12000                            // Dire Wolf's audio rate here (the WiFi link's own)
    const val TOCALL = "APZHDM"                       // the destination that names the app ("APZ": experimental software)

    /** A frame heard. [text] is the TNC2 monitor text (SRC>DEST,PATH:info). */
    data class Heard(val time: Long, val text: String, val via: String, val level: String, val aprs: Boolean, val lat: Double?, val lon: Double?,
                     val symbol: String, val type: String, val name: String, val comment: String, val speedMph: Double?, val course: Double?,
                     val altFt: Double?, val weather: String, val device: String) {
        val src get() = text.substringBefore('>')     // who sent it
        val info get() = text.substringAfter(':', "") // what it says
        val toCall get() = if (info.length >= 11 && info[0] == ':' && info[10] == ':') info.substring(1, 10).trim() else "" // an APRS message's addressee
        val message get() = if (toCall.isNotEmpty()) info.substring(11).substringBefore('{') else "" // its text (without the message number)
        val direct get() = via == src                 // heard from the station itself (not through a digipeater)
    }

    val log = MutableStateFlow<List<Heard>>(emptyList())        // everything heard, newest first
    val stations = MutableStateFlow<Map<String, Heard>>(emptyMap()) // each station's latest frame
    val places = MutableStateFlow<Map<String, Heard>>(emptyMap())   // each station's latest frame with a position (for the map)
    val messages = MutableStateFlow<List<Heard>>(emptyList())   // APRS messages to you, and the ones you sent, newest first
    val baud = MutableStateFlow(1200)                 // 1200 VHF / UHF FM, 300 HF
    @Volatile var myCall = ""                         // (for the messages to you)
    private var msgNo = 1                             // the next message number

    /** (Re)start the receiver at [b] baud. */
    fun open(b: Int) { baud.value = b; AprsNative.init(b, RATE) }

    /** Audio in (the capture thread): 12 kHz mono. */
    fun feed(b: ShortArray, n: Int) {
        val now = System.currentTimeMillis()
        if (Transmitter.sentDuring(now - 600, now)) return // (our own frame going out)
        AprsNative.process(b, n)
        val t = AprsNative.take(); if (t.isEmpty()) return
        t.lineSequence().filter { it.isNotBlank() }.forEach { add(parse(it, now)) }
    }

    private fun num(s: String) = s.toDoubleOrNull()?.takeIf { it > -999990 } // (Dire Wolf's "unknown" is -999999)
    private fun parse(line: String, now: Long): Heard {
        val f = line.split('\t') + List(16) { "" }
        return Heard(now, f[0], f[1], f[2], f[3] == "1", num(f[4]), num(f[5]), f[6].trim(), f[7], f[8], f[9], num(f[10]), num(f[11]), num(f[12]), f[13],
            f[14].takeUnless { it.startsWith("Internal error") } ?: "")
    }

    private fun add(h: Heard) {
        log.value = (listOf(h) + log.value).take(500)
        stations.value = stations.value + (h.src to h)
        if (h.lat != null && h.lon != null) places.value = places.value + ((h.name.ifBlank { h.src }) to h) // (an object or item goes on the map by its name)
        if (h.toCall.isNotEmpty() && myCall.isNotEmpty() && h.toCall.equals(myCall, true) && !h.message.startsWith("ack") && !h.message.startsWith("rej"))
            messages.value = (listOf(h) + messages.value).take(200)
    }

    private fun path() = if (baud.value < 600) "WIDE2-1" else "WIDE1-1,WIDE2-1" // (HF: one hop)

    private fun ddmm(v: Double, deg: Int, pos: Char, neg: Char): String { // APRS's degrees and decimal minutes: 5130.75N / 00012.25W
        val a = Math.abs(v); val d = a.toInt(); val m = (a - d) * 60
        return "%0${deg}d%05.2f%c".format(d, m, if (v >= 0) pos else neg)
    }

    /** Your position report (TNC2 text): from your [locator]'s middle, with [symbol] (table + code) and [comment];
     *  "=" says you can take messages. Null without a callsign or locator. */
    fun beacon(call: String, locator: String, symbol: String, comment: String): String? {
        val ll = Locator.toLatLon(locator) ?: return null
        if (call.isBlank()) return null
        val sym = symbol.padEnd(2, '-')
        return "${call.uppercase()}>$TOCALL,${path()}:=${ddmm(ll.first, 2, 'N', 'S')}${sym[0]}${ddmm(ll.second, 3, 'E', 'W')}${sym[1]}${comment.take(40)}"
    }

    /** A message to [to] (TNC2 text), numbered so the other station can acknowledge it. */
    fun message(call: String, to: String, text: String): String = "${call.uppercase()}>$TOCALL,${path()}::${to.uppercase().padEnd(9).take(9)}:${text.take(67)}{${msgNo++}"

    /** Send one frame (TNC2 text) through the radio. Null if it went (and it is added to the list heard as sent), else why not. */
    fun send(ctx: Context, call: String, tnc2: String, level: Double): String? {
        val a = AprsNative.encode(tnc2, baud.value, RATE, level.coerceIn(0.05, 1.0)) ?: return "That is not a valid frame"
        if (!Transmitter.send(ctx, call, a, RATE, 0, Mode.APRS.name)) return Transmitter.lastError.value
        val h = Heard(System.currentTimeMillis(), tnc2, "(sent)", "", true, null, null, "", "Sent", "", "", null, null, null, "", "")
        log.value = (listOf(h) + log.value).take(500)
        if (h.toCall.isNotEmpty()) messages.value = (listOf(h) + messages.value).take(200)
        return null
    }
}
