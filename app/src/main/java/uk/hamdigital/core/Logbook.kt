// The QSO log: each completed contact is appended to an ADIF file (log.adi in the app's files), the format every
// logging program and LoTW / QRZ / Club Log upload tools read. Settings > Logbook shares it.
package uk.hamdigital.core

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

data class Qso(
    val call: String,                                 // the other station
    val grid: String,                                 // its locator, if sent
    val mode: String,                                 // "FT8", "FT4", "JS8", "RTTY", "PSK31", "CW"
    val rstSent: String, val rstRcvd: String,         // reports ("-12", "599")
    val startMs: Long, val endMs: Long,               // UTC times
    val freqHz: Long,                                 // the radio's dial frequency (0: not known)
    val myCall: String, val myGrid: String,           // you
)

object Logbook {
    fun file(ctx: Context) = File(ctx.filesDir, "log.adi") // the log

    private fun f(tag: String, v: String) = if (v.isEmpty()) "" else "<$tag:${v.length}>$v " // one ADIF field

    private fun band(hz: Long): String = when (hz / 1000) {   // ADIF band names
        in 1800..2000 -> "160m"; in 3500..4000 -> "80m"; in 5250..5450 -> "60m"; in 7000..7300 -> "40m"; in 10100..10150 -> "30m"
        in 14000..14350 -> "20m"; in 18068..18168 -> "17m"; in 21000..21450 -> "15m"; in 24890..24990 -> "12m"; in 28000..29700 -> "10m"
        in 50000..54000 -> "6m"; in 70000..70500 -> "4m"; in 144000..148000 -> "2m"; in 430000..440000 -> "70cm"; else -> ""
    }

    /** Append a contact. */
    @Synchronized fun add(ctx: Context, q: Qso) {
        val file = file(ctx)
        if (!file.exists()) file.writeText("HF Digital Modes log\n<ADIF_VER:5>3.1.4 <PROGRAMID:16>HF Digital Modes <EOH>\n") // header
        val s = Instant.ofEpochMilli(q.startMs).atZone(ZoneOffset.UTC); val e = Instant.ofEpochMilli(q.endMs).atZone(ZoneOffset.UTC)
        val (mode, sub) = when (q.mode) { "FT4" -> "MFSK" to "FT4"; "JS8" -> "MFSK" to "JS8"; "PSK31" -> "PSK" to "PSK31"; else -> q.mode to "" } // ADIF's names
        val rec = f("CALL", q.call) + f("GRIDSQUARE", q.grid) + f("MODE", mode) + f("SUBMODE", sub) + f("RST_SENT", q.rstSent) + f("RST_RCVD", q.rstRcvd) +
            f("QSO_DATE", "%04d%02d%02d".format(s.year, s.monthValue, s.dayOfMonth)) + f("TIME_ON", "%02d%02d%02d".format(s.hour, s.minute, s.second)) +
            f("QSO_DATE_OFF", "%04d%02d%02d".format(e.year, e.monthValue, e.dayOfMonth)) + f("TIME_OFF", "%02d%02d%02d".format(e.hour, e.minute, e.second)) +
            f("BAND", band(q.freqHz)) + f("FREQ", if (q.freqHz > 0) "%.6f".format(q.freqHz / 1e6) else "") +
            f("STATION_CALLSIGN", q.myCall) + f("MY_GRIDSQUARE", q.myGrid) + "<EOR>\n"
        file.appendText(rec)
    }

    /** How many contacts are logged. */
    fun count(ctx: Context): Int = file(ctx).takeIf { it.exists() }?.readText()?.split("<EOR>")?.size?.minus(1) ?: 0
}
