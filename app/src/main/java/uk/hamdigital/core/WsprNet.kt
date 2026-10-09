// WSPRnet spot upload, as WSJT-X does it (Network/wsprnet.cpp, GPL v3): after each receive slot every spot heard is
// POSTed to wsprnet.org/post/ as a form (function=wspr: the spot, plus your call, locator and dial); a slot with no
// spots sends function=wsprstat instead, so WSPRnet knows the station is listening (and what it transmits, if the
// beacon is on). As in WSJT-X, only spots within 10 kHz of the dial are sent, and only the message types WSPRnet takes:
// CALL GRID4 DBM, CALL/x DBM and <CALL> GRID6 DBM - a call wsprd could not work out ("<...>") is left out. Off until
// you turn it on (the WSPR page): it publishes under your callsign.
package uk.hamdigital.core

import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.BuildConfigInfo
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

object WsprNet {
    private const val POST_URL = "https://wsprnet.org/post/" // (WSJT-X uses http://; the https address takes the same form)
    val status = MutableStateFlow("")                 // what the last upload did, for the page
    @Volatile var enabled = false                     // uploading (Settings: off until turned on)
    @Volatile var myCall = ""; @Volatile var myGrid = "" // the receiving station: you
    private val worker = Executors.newSingleThreadExecutor() // one upload at a time, off the decoder's thread

    // A wsprd output line: date time sync snr dt freq message drift cycles jitter (WSJT-X's wspr_re)
    private val LINE = Regex("""^(\d+)\s+(\d+)\s+(\d+)\s+([+-]?\d+)\s+([+-]?\d+\.\d+)\s+(\d+\.\d+)\s+([^ ].*[^ ])\s+([+-]?\d+)\s+([+-]?\d+)\s+([+-]?\d+)""")
    private val TYPE1 = Regex("""^([A-Z0-9]{3,6})\s+([A-R]{2}\d{2})\s+(\d+)""")       // CALL GRID4 DBM
    private val TYPE2 = Regex("""^([A-Z0-9/]+)\s+(\d+)""")                              // CALL/x DBM
    private val TYPE3 = Regex("""^<([A-Z0-9/]+)>\s+([A-R]{2}\d{2}[A-X]{2})\s+(\d+)""") // <CALL> GRID6 DBM

    /** One wsprd line -> the spot's form fields, or null (not a spot WSPRnet takes). (WSJT-X's decodeLine, in its order:
     *  a later message type that matches replaces an earlier one.) */
    private fun spot(line: String): List<Pair<String, String>>? {
        val m = LINE.find(line.trim()) ?: return null
        val msg = m.groupValues[7]
        var call = ""; var grid = ""; var dbm = ""; var type = 0
        TYPE1.find(msg)?.let { type = 1; call = it.groupValues[1]; grid = it.groupValues[2]; dbm = it.groupValues[3] }
        TYPE2.find(msg)?.let { type = 2; call = it.groupValues[1]; grid = ""; dbm = it.groupValues[2] }
        TYPE3.find(msg)?.let { type = 3; call = it.groupValues[1]; grid = it.groupValues[2]; dbm = it.groupValues[3] }
        if (type == 0) return null
        return listOf("function" to "wspr", "date" to m.groupValues[1], "time" to m.groupValues[2], "sig" to m.groupValues[4], "dt" to m.groupValues[5],
            "drift" to m.groupValues[8], "tqrg" to m.groupValues[6], "tcall" to call, "tgrid" to grid, "dbm" to dbm)
    }

    /**
     * A receive slot is decoded: upload what wsprd found ([lines], its output; empty if nothing) heard with the dial on
     * [dialMHz]. [tpct], [txMHz] and [dbm]: your beacon (percent of slots, 0 if off; its RF frequency; reported power).
     */
    fun slot(lines: List<String>, dialMHz: Double, tpct: Int, txMHz: Double, dbm: Int) {
        if (!enabled) return
        if (myCall.isBlank() || myGrid.length < 4) { status.value = "WSPRnet: set your callsign and locator in Settings"; return }
        if (dialMHz <= 0) { status.value = "WSPRnet: not uploaded - the radio's frequency is not known (CI-V)"; return }
        val rqrg = "%.6f".format(Locale.ROOT, dialMHz)
        val version = "HDM-${BuildConfigInfo.VERSION}"  // the program, as WSPRnet lists it
        val spots = lines.mapNotNull { spot(it) }.filter { s -> s.first { it.first == "tqrg" }.second.toDoubleOrNull()?.let { abs(it - dialMHz) < 0.01 } == true } // (this band only)
            .map { it + listOf("version" to version, "rcall" to myCall, "rgrid" to myGrid, "rqrg" to rqrg, "mode" to "2") }
        val forms = spots.ifEmpty { listOf(listOf("function" to "wsprstat", "rcall" to myCall, "rgrid" to myGrid, "rqrg" to rqrg, "tpct" to "$tpct",
            "tqrg" to "%.6f".format(Locale.ROOT, txMHz), "dbm" to "$dbm", "version" to version, "mode" to "2")) } // nothing heard: "listening"
        val t = ZonedDateTime.now(ZoneOffset.UTC).let { "%02d%02d".format(it.hour, it.minute) }
        worker.execute {
            var ok = 0; var err = ""
            for (form in forms) {
                try {
                    val reply = post(form)
                    if (spots.isEmpty() || reply.contains("spot(s) added")) ok++ else err = reply.replace(Regex("<[^>]*>"), " ").trim().take(80) // (WSJT-X checks spots for this)
                } catch (e: Exception) { err = e.message ?: e.javaClass.simpleName }
            }
            status.value = when {
                err.isNotEmpty() -> "WSPRnet $t: upload failed ($err)"
                spots.isEmpty() -> "WSPRnet $t: no spots - reported as listening"
                else -> "WSPRnet $t: $ok spot${if (ok == 1) "" else "s"} uploaded"
            }
        }
    }

    private fun post(form: List<Pair<String, String>>): String { // one form, as application/x-www-form-urlencoded
        val body = form.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }.toByteArray()
        val c = URL(POST_URL).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body) }
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
