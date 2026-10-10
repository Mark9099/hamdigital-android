// The QSO log: every contact, kept in an ADIF file (log.adi in the app's files) - the format every logging program and
// the LoTW / QRZ / Club Log upload tools read. The whole log is held in memory (newest first) for the Logbook page and
// rewritten in full after each change (written to a temporary file, then renamed over the old one, so a crash part way
// through never leaves half a log). ADIF fields the app has no box for (QSL status, LoTW dates, a contest's exchange ...)
// are kept as they were read and written back unchanged, so importing another program's log and exporting it loses
// nothing. Completed FT8 / FT4 contacts are added automatically; the others from a page's Log button or the Logbook.
package uk.hamdigital.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

data class Qso(
    val call: String,                                 // the other station
    val grid: String,                                 // its locator, if known
    val mode: String,                                 // "FT8", "FT4", "JS8", "RTTY", "PSK31", "CW", "SSB" ... (ADIF's submode where it has one)
    val rstSent: String, val rstRcvd: String,         // reports ("-12", "599")
    val startMs: Long, val endMs: Long,               // UTC times (epoch ms)
    val freqHz: Long,                                 // the radio's dial frequency (0: not known)
    val myCall: String, val myGrid: String,           // you (the station that made it)
    val band: String = "",                            // ADIF band ("40m"); worked out from the frequency when empty
    val name: String = "",                            // the operator's name
    val qth: String = "",                             // where they are
    val power: String = "",                           // your transmit power (W)
    val comment: String = "",                         // anything else
    val country: String = "",                         // their country (DXCC entity; filled in from the call by cty.dat when logged)
    val extra: Map<String, String> = emptyMap(),      // other ADIF fields, kept as read (upper-case names)
    val id: Long = 0,                                 // the log's own number for it (not stored in the file)
) {
    val bandName: String get() = band.ifEmpty { Logbook.band(freqHz) } // "40m" ("" if not known)
    /** The country: as logged, else worked out from the call now (older entries, or cty.dat not loaded when logged). */
    val countryName: String get() = country.ifEmpty { Cty.country(call) }
}

object Logbook {
    private const val HEADER = "HF Digital Modes log\n<ADIF_VER:5>3.1.4 <PROGRAMID:16>HF Digital Modes <EOH>\n" // the file's header
    private val _qsos = MutableStateFlow<List<Qso>>(emptyList()) // the log, newest first
    val qsos: StateFlow<List<Qso>> = _qsos
    private var store: File? = null                   // log.adi, once loaded
    private var nextId = 1L                           // for the next contact's id
    val error = MutableStateFlow("")                  // the last load / save problem ("" = none)

    fun file(ctx: Context) = File(ctx.filesDir, "log.adi") // the log

    /** Load the log (once, at start-up). */
    @Synchronized fun init(ctx: Context) {
        if (store != null) return
        val f = file(ctx); store = f
        try { if (f.exists()) _qsos.value = parse(f.readText()).map { it.copy(id = nextId++) }.sortedByDescending { it.startMs } }
        catch (e: Exception) { error.value = "The logbook could not be read: ${e.message}" } // (the file is left alone)
    }

    /** ADIF band names by frequency. */
    fun band(hz: Long): String = when (hz / 1000) {
        in 135..138 -> "2190m"; in 472..479 -> "630m"; in 1800..2000 -> "160m"; in 3500..4000 -> "80m"; in 5060..5450 -> "60m"
        in 7000..7300 -> "40m"; in 10100..10150 -> "30m"; in 14000..14350 -> "20m"; in 18068..18168 -> "17m"; in 21000..21450 -> "15m"
        in 24890..24990 -> "12m"; in 28000..29700 -> "10m"; in 50000..54000 -> "6m"; in 70000..71000 -> "4m"; in 144000..148000 -> "2m"
        in 420000..450000 -> "70cm"; else -> ""
    }

    /** The bands in frequency order (for sorting the Logbook's band choices). */
    val BANDS = listOf("2190m", "630m", "160m", "80m", "60m", "40m", "30m", "20m", "17m", "15m", "12m", "10m", "6m", "4m", "2m", "70cm")

    /** Add a contact (FT8 / FT4 when complete, or typed in). */
    @Synchronized fun add(ctx: Context, q: Qso) { init(ctx); _qsos.value = (listOf(q.copy(id = nextId++, country = q.country.ifEmpty { Cty.country(q.call) })) + _qsos.value).sortedByDescending { it.startMs }; save() } // (with its country)

    /** Replace contact [q] (same id) with its edited version. */
    @Synchronized fun update(q: Qso) { _qsos.value = _qsos.value.map { if (it.id == q.id) q else it }.sortedByDescending { it.startMs }; save() }

    /** Remove one contact. */
    @Synchronized fun delete(id: Long) { _qsos.value = _qsos.value.filterNot { it.id == id }; save() }

    /** Remove every contact (the Logbook asks first). */
    @Synchronized fun deleteAll() { _qsos.value = emptyList(); save() }

    /** Add the contacts in an ADIF file's [text], skipping ones already in the log. Returns (added, already there). */
    @Synchronized fun import(text: String): Pair<Int, Int> {
        val got = parse(text); var dupes = 0
        val now = _qsos.value.toMutableList()
        for (q in got) { if (now.any { same(it, q) }) dupes++ else now.add(q.copy(id = nextId++)) }
        _qsos.value = now.sortedByDescending { it.startMs }; save()
        return (got.size - dupes) to dupes
    }

    /** The same contact: the same station, band and mode within 2 minutes (as logging programs check on import). */
    private fun same(a: Qso, b: Qso) = a.call.equals(b.call, true) && a.bandName == b.bandName && a.mode.equals(b.mode, true) && abs(a.startMs - b.startMs) < 120_000

    /** Contacts with [call] (newest first), for "worked before". */
    fun withCall(call: String): List<Qso> = if (call.isBlank()) emptyList() else _qsos.value.filter { it.call.equals(call.trim(), true) }

    /** The whole log as ADIF text (oldest first, as logging programs write it). */
    fun export(list: List<Qso> = _qsos.value): String = HEADER + list.sortedBy { it.startMs }.joinToString("") { record(it) } // ([list]: DevTest)

    private fun save() {                              // write the log: a temporary file, then renamed over the old one
        val f = store ?: return
        try {
            val tmp = File(f.parentFile, "log.adi.tmp")
            tmp.writeText(export())
            if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw Exception("could not replace log.adi") } // (rename replaces it on Android; this is the fallback)
            error.value = ""
        } catch (e: Exception) { error.value = "The logbook could not be saved: ${e.message}" }
    }

    // ---- ADIF ----

    private fun f(tag: String, v: String) = if (v.isEmpty()) "" else "<$tag:${v.length}>$v " // one field
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd"); private val TIME = DateTimeFormatter.ofPattern("HHmmss")
    private val SUBMODES = mapOf("FT4" to "MFSK", "JS8" to "MFSK", "FST4" to "MFSK", "Q65" to "MFSK", "MFSK16" to "MFSK", // ADIF's submodes and their modes
        "PSK31" to "PSK", "PSK63" to "PSK", "PSK125" to "PSK", "QPSK31" to "PSK", "USB" to "SSB", "LSB" to "SSB",
        "OLIVIA 4/125" to "OLIVIA", "OLIVIA 4/250" to "OLIVIA", "OLIVIA 8/250" to "OLIVIA", "OLIVIA 8/500" to "OLIVIA", "OLIVIA 16/500" to "OLIVIA", "OLIVIA 16/1000" to "OLIVIA", "OLIVIA 32/1000" to "OLIVIA")
    /** The fields the app has boxes for (everything else goes to extra; OPERATOR is kept there too, as well as standing in
     *  for STATION_CALLSIGN when that is missing). */
    private val KNOWN = setOf("CALL", "GRIDSQUARE", "MODE", "SUBMODE", "RST_SENT", "RST_RCVD", "QSO_DATE", "TIME_ON", "QSO_DATE_OFF", "TIME_OFF",
        "BAND", "FREQ", "STATION_CALLSIGN", "MY_GRIDSQUARE", "NAME", "QTH", "TX_PWR", "COMMENT", "COUNTRY")

    private fun record(q: Qso): String {              // one contact as an ADIF record
        val s = java.time.Instant.ofEpochMilli(q.startMs).atZone(ZoneOffset.UTC); val e = java.time.Instant.ofEpochMilli(q.endMs).atZone(ZoneOffset.UTC)
        val m = q.mode.uppercase(); val parent = SUBMODES[m] // FT4 -> MFSK + FT4
        return f("CALL", q.call) + f("GRIDSQUARE", q.grid) + f("MODE", parent ?: m) + f("SUBMODE", if (parent != null) m else "") +
            f("RST_SENT", q.rstSent) + f("RST_RCVD", q.rstRcvd) +
            f("QSO_DATE", DATE.format(s)) + f("TIME_ON", TIME.format(s)) + f("QSO_DATE_OFF", DATE.format(e)) + f("TIME_OFF", TIME.format(e)) +
            f("BAND", q.bandName) + f("FREQ", if (q.freqHz > 0) "%.6f".format(java.util.Locale.ROOT, q.freqHz / 1e6) else "") +
            f("STATION_CALLSIGN", q.myCall) + f("MY_GRIDSQUARE", q.myGrid) + f("NAME", q.name) + f("QTH", q.qth) + f("TX_PWR", q.power) +
            f("COMMENT", q.comment) + f("COUNTRY", q.country) + q.extra.entries.joinToString("") { (k, v) -> f(k, v) } + "<EOR>\n"
    }

    /** ADIF (.adi) text -> contacts. Fields are <NAME:LENGTH> or <NAME:LENGTH:TYPE> then LENGTH characters of data; any
     *  text before <EOH> is the header; <EOR> ends each record. Names are not case-sensitive. Records without a call or a
     *  date are skipped. */
    fun parse(text: String): List<Qso> {
        val out = mutableListOf<Qso>()
        var i = text.indexOf("<EOH>", ignoreCase = true).let { if (it < 0) 0 else it + 5 } // after the header (if there is one)
        var rec = LinkedHashMap<String, String>()     // the record being read
        while (true) {
            val lt = text.indexOf('<', i); if (lt < 0) break
            val gt = text.indexOf('>', lt); if (gt < 0) break
            val p = text.substring(lt + 1, gt).split(':') // NAME[:LENGTH[:TYPE]]
            val name = p[0].trim().uppercase()
            if (name == "EOR") { toQso(rec)?.let { out.add(it) }; rec = LinkedHashMap(); i = gt + 1; continue } // end of a record
            val len = p.getOrNull(1)?.trim()?.toIntOrNull()
            if (len == null) { i = gt + 1; continue } // (not a field: skipped)
            val end = minOf(text.length, gt + 1 + len)
            rec[name] = text.substring(gt + 1, end); i = end
        }
        return out
    }

    private fun toQso(r: Map<String, String>): Qso? {  // one record's fields -> a contact
        val call = r["CALL"]?.trim()?.uppercase().orEmpty(); val date = r["QSO_DATE"]?.trim().orEmpty()
        if (call.isEmpty() || date.length != 8) return null
        fun ms(d: String, t: String): Long? = try {   // date YYYYMMDD + time HHMM[SS] -> epoch ms
            LocalDate.parse(d.trim(), DATE).atTime(LocalTime.parse(t.trim().padEnd(6, '0').take(6), TIME)).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (e: Exception) { null }
        val start = ms(date, r["TIME_ON"] ?: "000000") ?: return null
        var end = if (r["TIME_OFF"].isNullOrBlank()) start else ms(r["QSO_DATE_OFF"] ?: date, r["TIME_OFF"]!!) ?: start
        if (end < start && r["QSO_DATE_OFF"] == null) end += 86_400_000 // (ended after midnight, no end date given)
        val sideband = r["MODE"]?.trim().equals("SSB", true) && r["SUBMODE"]?.trim()?.uppercase() in setOf("USB", "LSB") // SSB's sideband: logged as SSB
        val mode = if (sideband) "SSB" else (r["SUBMODE"]?.takeIf { it.isNotBlank() } ?: r["MODE"] ?: "").trim().uppercase()
        val freq = r["FREQ"]?.trim()?.toDoubleOrNull()?.let { (it * 1e6 + 0.5).toLong() } ?: 0L // MHz -> Hz
        val bandField = r["BAND"]?.trim()?.lowercase().orEmpty()
        return Qso(call, r["GRIDSQUARE"]?.trim().orEmpty(), mode, r["RST_SENT"]?.trim().orEmpty(), r["RST_RCVD"]?.trim().orEmpty(), start, end, freq,
            (r["STATION_CALLSIGN"] ?: r["OPERATOR"] ?: "").trim().uppercase(), r["MY_GRIDSQUARE"]?.trim().orEmpty(),
            band = if (bandField == band(freq)) "" else bandField, // (kept only when the frequency does not give it)
            name = r["NAME"]?.trim().orEmpty(), qth = r["QTH"]?.trim().orEmpty(), power = r["TX_PWR"]?.trim().orEmpty(), comment = r["COMMENT"]?.trim().orEmpty(), country = r["COUNTRY"]?.trim().orEmpty(),
            extra = r.filterKeys { it !in KNOWN } + if (sideband) mapOf("SUBMODE" to r["SUBMODE"]!!.trim()) else emptyMap()) // (the sideband is kept and written back)
    }
}
