// The Logbook page: every contact, newest first, with a search box (call, locator, name, QTH, comment) and band / mode
// choices; a summary line (contacts, stations, locator squares, bands); tap a contact to change or delete it; Add for a
// contact typed in. The menu (⋮) shares the log as an ADIF file, saves it to a file of your choice, imports another
// program's ADIF file (contacts already in the log are skipped), or deletes the whole log (after asking).
// QsoEditor is the form for one contact; the mode pages open it from their Log button, filled in from the radio and the
// page (the call, reports and locator of an FT8 / FT4 contact in progress).
package uk.hamdigital.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.hamdigital.MainViewModel
import uk.hamdigital.core.Locator
import uk.hamdigital.core.Logbook
import uk.hamdigital.core.Mode
import uk.hamdigital.core.Qso
import uk.hamdigital.core.Settings
import uk.hamdigital.rig.Ic705
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC) // a contact's time in the list
private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)        // the form's date
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)         // the form's times
private val LOG_MODES = listOf("FT8", "FT4", "JS8", "RTTY", "PSK31", "CW", "SSB", "FM", "AM")  // quick choices in the form

/** The log's name for a page's mode ("JS8Call" is logged as ADIF's "JS8"). */
fun logMode(m: Mode) = if (m == Mode.JS8) "JS8" else m.title

/** A new contact for [mode]: now, the radio's frequency, your station, the usual report for the mode. */
fun newQso(mode: String, s: Settings): Qso {
    val now = System.currentTimeMillis(); val rst = when (mode) { "CW", "RTTY", "PSK31" -> "599"; "SSB", "FM", "AM" -> "59"; else -> "" }
    return Qso("", "", mode, rst, rst, now, now, Ic705.state.value.freqHz, s.callsign, s.locator)
}

@Composable
fun LogbookScreen(vm: MainViewModel) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()  // your station (for new contacts and distances)
    val all by Logbook.qsos.collectAsStateWithLifecycle() // the log
    val err by Logbook.error.collectAsStateWithLifecycle() // a load / save problem
    var query by rememberSaveable { mutableStateOf("") }  // search
    var bandSel by rememberSaveable { mutableStateOf("") } // "" = all bands
    var modeSel by rememberSaveable { mutableStateOf("") } // "" = all modes
    var editing by remember { mutableStateOf<Qso?>(null) } // the contact in the form
    var isNew by remember { mutableStateOf(false) }       // the form adds (rather than changes)
    var menu by remember { mutableStateOf(false) }        // the ⋮ menu open
    var askAll by remember { mutableStateOf(false) }      // "delete everything?" showing
    var note by remember { mutableStateOf("") }           // the result of an import / save
    var mapOpen by remember { mutableStateOf(false) }     // the map showing
    val ctyReady by uk.hamdigital.core.Cty.loaded.collectAsStateWithLifecycle() // (countries arrive after the download)
    val q = query.trim()
    val shown = remember(all, q, bandSel, modeSel, ctyReady) { // the contacts that match
        all.filter { c -> (bandSel.isEmpty() || c.bandName == bandSel) && (modeSel.isEmpty() || c.mode == modeSel) &&
            (q.isEmpty() || listOf(c.call, c.grid, c.name, c.qth, c.comment, c.countryName).any { it.contains(q, ignoreCase = true) }) }
    }
    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> // save a copy where you choose
        if (uri != null) scope.launch {
            note = withContext(Dispatchers.IO) { try { ctx.contentResolver.openOutputStream(uri)?.use { it.write(Logbook.export().toByteArray()) }; "Saved ${plural(all.size, "contact")} (ADIF)" } catch (e: Exception) { "Could not save: ${e.message}" } }
        }
    }
    val importFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> // read another program's ADIF file
        if (uri != null) scope.launch {
            note = withContext(Dispatchers.IO) {
                try {
                    val text = ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                    val (added, dupes) = Logbook.import(text)
                    if (added + dupes == 0) "No contacts found in that file (is it an ADIF .adi file?)" else "Imported ${plural(added, "contact")}" + if (dupes > 0) " ($dupes already in the log, skipped)" else ""
                } catch (e: Exception) { "Could not import: ${e.message}" }
            }
        }
    }
    ModeFrame("Logbook", { vm.back() }, actions = {
        TextButton({ editing = newQso(logMode(Mode.FT8), s).copy(mode = ""); isNew = true }) { Text("Add", color = Pal.Text2) }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "Logbook menu", tint = Pal.Text2) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Share the log (ADIF)") }, enabled = all.isNotEmpty(), onClick = { menu = false // to another app: email, Drive, a logging app
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "uk.hamdigital.files", Logbook.file(ctx))
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share the logbook")) })
                DropdownMenuItem({ Text("Save to a file (ADIF)") }, enabled = all.isNotEmpty(), onClick = { menu = false
                    saveTo.launch("${s.callsign.ifBlank { "log" }.replace('/', '_')}_${DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(Instant.now())}.adi") })
                DropdownMenuItem({ Text("Import an ADIF file") }, onClick = { menu = false; importFrom.launch(arrayOf("*/*")) })
                DropdownMenuItem({ Text("Delete all contacts", color = Pal.Red) }, enabled = all.isNotEmpty(), onClick = { menu = false; askAll = true })
            }
        }
    }) {
        if (err.isNotEmpty()) Text(err, color = Pal.Red, fontSize = 13.sp)
        if (note.isNotEmpty()) Text(note, color = Pal.Green, fontSize = 13.sp, modifier = Modifier.clickable { note = "" })
        CompactField(query, { query = it }, "Search: call, locator, name, QTH, comment", Modifier.fillMaxWidth().padding(top = 4.dp),
            trailing = if (query.isNotEmpty()) ({ TextButton({ query = "" }) { Text("Clear", color = Pal.Text2) } }) else null)
        val bands = remember(all) { all.map { it.bandName }.filter { it.isNotEmpty() }.distinct().sortedBy { Logbook.BANDS.indexOf(it) } } // bands in the log
        val modes = remember(all) { all.map { it.mode }.filter { it.isNotEmpty() }.distinct().sorted() } // modes in the log
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallChip("All bands", bandSel.isEmpty()) { bandSel = "" }
            bands.forEach { b -> SmallChip(b, bandSel == b) { bandSel = if (bandSel == b) "" else b } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallChip("All modes", modeSel.isEmpty()) { modeSel = "" }
            modes.forEach { m -> SmallChip(m, modeSel == m) { modeSel = if (modeSel == m) "" else m } }
        }
        val grids = shown.mapNotNull { c -> c.grid.take(4).uppercase().takeIf { it.length == 4 } }.distinct().size // locator squares
        val countries = shown.map { it.countryName }.filter { it.isNotEmpty() }.distinct().size // countries (DXCC entities)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text("${shown.size} contact${if (shown.size == 1) "" else "s"}" + (if (shown.size != all.size) " of ${all.size}" else "") +
                "  •  ${plural(shown.map { it.call }.distinct().size, "station")}  •  ${plural(countries, "country", "countries")}  •  ${plural(grids, "square")}  •  " +
                plural(shown.map { it.bandName }.filter { it.isNotEmpty() }.distinct().size, "band"), color = Pal.Text2, fontSize = 13.sp, modifier = Modifier.weight(1f))
            FilledTonalButton({ mapOpen = true }, enabled = shown.isNotEmpty(), contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Map") } // the contacts shown, on a map
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { c -> QsoRow(c, s.locator) { editing = c; isNew = false } } // tap: change it
            if (shown.isEmpty()) item {
                Text(if (all.isEmpty()) "No contacts yet. Completed FT8 and FT4 contacts are logged automatically; for the other modes use the Log " +
                    "button on the mode's page, or Add here. The ⋮ menu imports a log from another program (ADIF)." else "No contacts match.",
                    color = Pal.Dim, fontSize = 13.sp, lineHeight = 17.sp, modifier = Modifier.padding(8.dp))
            }
        }
    }
    editing?.let { e -> QsoEditor(e, isNew) { editing = null } } // the form, over the list
    if (mapOpen) LogMap(shown, s.locator) { mapOpen = false } // the map of the contacts shown
    if (askAll) AlertDialog({ askAll = false }, confirmButton = { TextButton({ Logbook.deleteAll(); askAll = false; note = "The log was emptied" }) { Text("Delete all", color = Pal.Red) } },
        dismissButton = { TextButton({ askAll = false }) { Text("Keep them") } }, title = { Text("Delete all ${all.size} contacts?") },
        text = { Text("This cannot be undone. Save a copy first (⋮ > Save to a file) if you may want them again.") })
}

/** The map of [list] (the contacts the Logbook shows): one dot per station - at its locator, else its country's middle -
 *  coloured by the band of the latest contact, with what was logged when tapped. */
@Composable
private fun LogMap(list: List<Qso>, myGrid: String, onClose: () -> Unit) {
    val home = Locator.toLatLon(myGrid)               // you
    val byCall = list.groupBy { it.call }             // (newest first within each)
    var unplaced = 0                                  // no locator, country unknown
    val points = byCall.mapNotNull { (call, qs) ->
        val c = qs.first()                            // the latest contact
        val ll = Locator.toLatLon(c.grid) ?: qs.firstNotNullOfOrNull { Locator.toLatLon(it.grid) } // its locator (from any contact)
        val ent = if (ll == null) uk.hamdigital.core.Cty.lookup(call) else null // else its country's middle
        val at = ll ?: ent?.let { it.lat to it.lon } ?: run { unplaced++; return@mapNotNull null }
        val km = if (myGrid.isNotEmpty() && ll != null) Locator.toLatLon(myGrid)?.let { h -> greatKm(h.first, h.second, at.first, at.second) } else null
        val detail = listOfNotNull(SHOWN.format(Instant.ofEpochMilli(c.startMs)) + " UTC", "${c.bandName} ${c.mode}".trim(),
            if (c.rstSent.isNotEmpty() || c.rstRcvd.isNotEmpty()) "sent ${c.rstSent.ifEmpty { "-" }}, rcvd ${c.rstRcvd.ifEmpty { "-" }}" else null,
            c.grid.ifEmpty { null }, km?.let { "$it km" }, c.countryName.ifEmpty { null }, c.name.ifEmpty { null },
            if (qs.size > 1) "${qs.size} contacts" else null).joinToString("  •  ")
        MapPoint(at.first, at.second, call, detail, bandColor(c.bandName), exact = ll != null)
    }
    val bands = list.map { it.bandName }.filter { it.isNotEmpty() }.distinct().sortedBy { Logbook.BANDS.indexOf(it) }
    MapDialog("Contacts map", points, home, onClose,
        note = "${points.size} station${if (points.size == 1) "" else "s"}" + (if (unplaced > 0) " ($unplaced not placed: no locator or known country)" else "") +
            (if (home == null) ". Set your locator in Settings to show yourself." else ". Pinch to zoom, drag to move, tap a dot."),
        legend = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) { // the bands' colours
                bands.forEach { b -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(10.dp).background(bandColor(b), androidx.compose.foundation.shape.CircleShape)); Text(" $b", color = Pal.Text2, fontSize = 12.sp) } }
            }
        })
}

/** Great-circle distance in km. */
private fun greatKm(la1: Double, lo1: Double, la2: Double, lo2: Double): Int {
    val r = Math.PI / 180
    return (6371 * Math.acos((Math.sin(la1 * r) * Math.sin(la2 * r) + Math.cos(la1 * r) * Math.cos(la2 * r) * Math.cos((lo2 - lo1) * r)).coerceIn(-1.0, 1.0))).toInt()
}

/** One contact in the list: time, call, country, band and mode; reports, locator, distance, name and comment under it. */
@Composable
private fun QsoRow(c: Qso, myGrid: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(SHOWN.format(Instant.ofEpochMilli(c.startMs)), color = Pal.Text2, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(128.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { // call, country
                Text(c.call, color = Pal.Cyan, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                Text("  ${c.countryName}", color = Pal.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${c.bandName}  ${c.mode}".trim(), color = Pal.Text, fontSize = 13.sp)
        }
        val km = if (c.grid.isNotEmpty() && myGrid.isNotEmpty()) Locator.km(myGrid, c.grid) else null // distance
        val parts = listOfNotNull(
            if (c.rstSent.isNotEmpty() || c.rstRcvd.isNotEmpty()) "Sent ${c.rstSent.ifEmpty { "-" }}  Rcvd ${c.rstRcvd.ifEmpty { "-" }}" else null,
            c.grid.ifEmpty { null }, km?.let { "$it km" }, c.name.ifEmpty { null }, c.qth.ifEmpty { null }, c.comment.ifEmpty { null })
        if (parts.isNotEmpty()) Text(parts.joinToString("  •  "), color = Pal.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 128.dp))
        Spacer(Modifier.fillMaxWidth().padding(top = 6.dp).height(1.dp).background(Pal.Tert))
    }
}

/** The form for one contact (a full-screen window over the page, so a mode page keeps decoding behind it). [isNew]: Save
 *  adds it; otherwise Save changes it and Delete removes it (after asking). */
@Composable
fun QsoEditor(initial: Qso, isNew: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var call by remember { mutableStateOf(initial.call) }; var grid by remember { mutableStateOf(initial.grid) }
    var date by remember { mutableStateOf(DAY.format(Instant.ofEpochMilli(initial.startMs))) }
    var on by remember { mutableStateOf(CLOCK.format(Instant.ofEpochMilli(initial.startMs))) }
    var off by remember { mutableStateOf(CLOCK.format(Instant.ofEpochMilli(initial.endMs))) }
    var freq by remember { mutableStateOf(if (initial.freqHz > 0) "%.6f".format(java.util.Locale.ROOT, initial.freqHz / 1e6) else "") }
    var band by remember { mutableStateOf(initial.band) }  // only used when there is no frequency
    var mode by remember { mutableStateOf(initial.mode) }
    var sent by remember { mutableStateOf(initial.rstSent) }; var rcvd by remember { mutableStateOf(initial.rstRcvd) }
    var name by remember { mutableStateOf(initial.name) }; var qth by remember { mutableStateOf(initial.qth) }
    var power by remember { mutableStateOf(initial.power) }; var comment by remember { mutableStateOf(initial.comment) }
    var myCall by remember { mutableStateOf(initial.myCall) }; var myGrid by remember { mutableStateOf(initial.myGrid) }
    var askDelete by remember { mutableStateOf(false) }
    var country by remember { mutableStateOf(initial.country.ifEmpty { uk.hamdigital.core.Cty.country(initial.call) }) } // from the call (cty.dat) ..
    var countryAuto by remember { mutableStateOf(initial.country.isEmpty()) } // .. until typed over

    val day = parseDate(date); val tOn = parseTime(on); val tOff = parseTime(off) // what was typed, understood (null: not valid)
    val hz = freq.trim().toDoubleOrNull()?.let { (it * 1e6 + 0.5).toLong() } // MHz -> Hz
    val freqBand = hz?.let { Logbook.band(it) } ?: ""  // the band the frequency is in
    val gridOk = grid.isEmpty() || Locator.valid(grid); val myGridOk = myGrid.isEmpty() || Locator.valid(myGrid)
    val problem = when {                               // what stops Save
        call.isBlank() -> "Enter the other station's call"
        day == null -> "Date: YYYY-MM-DD"
        tOn == null -> "Time on: HH:MM or HH:MM:SS (UTC)"
        tOff == null -> "Time off: HH:MM or HH:MM:SS (UTC)"
        freq.isNotBlank() && (hz == null || hz <= 0) -> "Frequency: in MHz, e.g. 7.074"
        mode.isBlank() -> "Choose or type the mode"
        !gridOk -> "Locator: e.g. IO91 or IO91wm"
        !myGridOk -> "Your locator: e.g. IO91 or IO91wm"
        else -> null
    }
    fun build(): Qso {                                 // the form -> a contact
        val start = day!!.atTime(tOn!!).toInstant(ZoneOffset.UTC).toEpochMilli()
        var end = day.atTime(tOff!!).toInstant(ZoneOffset.UTC).toEpochMilli(); if (end < start) end += 86_400_000 // (ended after midnight)
        return initial.copy(call = call.trim().uppercase(), grid = grid.trim(), mode = mode.trim().uppercase(), rstSent = sent.trim(), rstRcvd = rcvd.trim(),
            startMs = start, endMs = end, freqHz = hz ?: 0, band = if (hz != null && hz > 0) "" else band, name = name.trim(), qth = qth.trim(),
            power = power.trim(), comment = comment.trim(), country = country.trim(), myCall = myCall.trim().uppercase(), myGrid = myGrid.trim())
    }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize(), color = Pal.Bg) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) { // title, Cancel, Save
                    Text(if (isNew) "New contact" else "Change contact", fontFamily = OrbitronFamily, fontWeight = FontWeight.Bold, color = Pal.Cyan, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    TextButton(onClose) { Text("Cancel", color = Pal.Text2) }
                    Button({ val q = build(); if (isNew) Logbook.add(ctx, q) else Logbook.update(q); onClose() }, enabled = problem == null) { Text("Save") }
                }
                Text(problem ?: "Times are UTC.", color = if (problem != null) Pal.Amber else Pal.Muted, fontSize = 13.sp)
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val caps = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactField(call, { call = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '/' }.take(20); if (countryAuto) country = uk.hamdigital.core.Cty.country(call) }, "Call", Modifier.weight(1f), keyboardOptions = caps)
                        CompactField(grid, { grid = it.filter { c -> c.isLetterOrDigit() }.take(8) }, "Locator", Modifier.weight(1f), isError = !gridOk, keyboardOptions = caps)
                    }
                    val before = Logbook.withCall(call).filter { it.id != initial.id } // worked before (not counting this one)
                    if (before.isNotEmpty()) Text("Worked before: ${before.size} contact${if (before.size == 1) "" else "s"}, last ${SHOWN.format(Instant.ofEpochMilli(before.first().startMs))} " +
                        "${before.first().bandName} ${before.first().mode}" + (before.firstOrNull { it.name.isNotEmpty() }?.let { "  •  ${it.name}" } ?: ""), color = Pal.Amber, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactField(date, { date = it.take(10) }, "Date (UTC)", Modifier.weight(1.2f), isError = day == null)
                        CompactField(on, { on = it.take(8) }, "Time on", Modifier.weight(1f), isError = tOn == null)
                        CompactField(off, { off = it.take(8) }, "Time off", Modifier.weight(1f), isError = tOff == null)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactField(freq, { freq = it.filter { c -> c.isDigit() || c == '.' }.take(12) }, "Frequency (MHz)", Modifier.weight(1f),
                            isError = freq.isNotBlank() && (hz == null || hz <= 0), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        Text(if (freq.isNotBlank()) freqBand.ifEmpty { "not an amateur band" } else band.ifEmpty { "band: choose below" }, color = Pal.Text2, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    }
                    if (freq.isBlank()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { // no frequency: the band by hand
                        Logbook.BANDS.forEach { b -> SmallChip(b, band == b) { band = b } }
                    }
                    CompactField(mode, { mode = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(12) }, "Mode", Modifier.fillMaxWidth(), keyboardOptions = caps)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { LOG_MODES.forEach { m -> SmallChip(m, mode == m) { mode = m } } }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactField(sent, { sent = it.take(8) }, "Report sent", Modifier.weight(1f))
                        CompactField(rcvd, { rcvd = it.take(8) }, "Report received", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactField(name, { name = it.take(40) }, "Name", Modifier.weight(1f))
                        CompactField(qth, { qth = it.take(60) }, "QTH", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactField(power, { power = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, "Your power (W)", Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        Spacer(Modifier.weight(1f))
                    }
                    CompactField(country, { country = it.take(40); countryAuto = false }, "Country", Modifier.fillMaxWidth()) // (filled in from the call)
                    CompactField(comment, { comment = it.take(200) }, "Comment", Modifier.fillMaxWidth())
                    Text("Your station", color = Pal.Cyan, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactField(myCall, { myCall = it.uppercase().filter { c -> c.isLetterOrDigit() || c == '/' }.take(20) }, "Your call", Modifier.weight(1f), keyboardOptions = caps)
                        CompactField(myGrid, { myGrid = it.filter { c -> c.isLetterOrDigit() }.take(8) }, "Your locator", Modifier.weight(1f), isError = !myGridOk, keyboardOptions = caps)
                    }
                    if (initial.extra.isNotEmpty()) Text("Also kept from the imported record: ${initial.extra.keys.joinToString(", ")}", color = Pal.Dim, fontSize = 12.sp)
                    if (!isNew) OutlinedButton({ askDelete = true }, Modifier.padding(top = 8.dp)) { Text("Delete this contact", color = Pal.Red) }
                }
            }
        }
        if (askDelete) AlertDialog({ askDelete = false }, confirmButton = { TextButton({ Logbook.delete(initial.id); askDelete = false; onClose() }) { Text("Delete", color = Pal.Red) } },
            dismissButton = { TextButton({ askDelete = false }) { Text("Keep it") } }, title = { Text("Delete the contact with ${initial.call}?") })
    }
}

private fun plural(n: Int, one: String, many: String = one + "s") = "$n " + if (n == 1) one else many // "1 band", "3 bands"

private fun parseDate(s: String): LocalDate? = try {   // "2026-10-09" or "20261009"
    val d = s.trim(); if (d.length == 8 && d.all { it.isDigit() }) LocalDate.parse(d, DateTimeFormatter.BASIC_ISO_DATE) else LocalDate.parse(d)
} catch (e: Exception) { null }

private fun parseTime(s: String): LocalTime? {         // "11:23", "11:23:41", "1123" or "112341"
    val d = s.filter { it.isDigit() }; if (d.length != 4 && d.length != 6) return null
    val h = d.substring(0, 2).toInt(); val m = d.substring(2, 4).toInt(); val sec = if (d.length == 6) d.substring(4, 6).toInt() else 0
    return if (h < 24 && m < 60 && sec < 60) LocalTime.of(h, m, sec) else null
}
