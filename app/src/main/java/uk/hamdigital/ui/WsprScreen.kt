// WSPR page: the radio and its bands, the 2-minute slot bar (WSPR transmissions start a second after each even UTC
// minute; the slot is decoded at 1:54), a waterfall of the WSPR window (1400-1600 Hz of audio), and the spots heard -
// newest slot first - with UTC, signal (dB), time offset, frequency, drift, call, locator, power and distance.
// Decoding by wsprd from WSJT-X (GPL v3). The beacon (WsprBeacon): on / off, % of slots, power; tap the waterfall for its offset.
// WSPRnet upload (WsprNet): on / off, and what the last upload did.
package uk.hamdigital.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import kotlin.math.max
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import uk.hamdigital.MainViewModel
import uk.hamdigital.audio.Spectrum
import uk.hamdigital.core.Cty
import uk.hamdigital.core.Locator
import uk.hamdigital.core.Mode
import uk.hamdigital.core.WsprBeacon
import uk.hamdigital.core.WsprDecoder
import uk.hamdigital.core.WsprNet
import uk.hamdigital.core.WsprSpot
import uk.hamdigital.rig.Ic705
import uk.hamdigital.rig.RigState

@Composable
fun WsprScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()  // station, audio choice
    val dec = remember { WsprDecoder.get(ctx) }         // the app's WSPR decoder
    val rig by Ic705.state.collectAsStateWithLifecycle() // the radio (its dial gives the spots' frequencies)
    LaunchedEffect(s.locator) { dec.myGrid = s.locator } // for distances
    LaunchedEffect(rig.freqHz, rig.link) { dec.dialMHz = if (rig.link == RigState.Link.CONNECTED) rig.freqHz / 1e6 else 0.0 } // dial, if known
    val spec = remember { Spectrum(12000, size = 8192, hop = 4096, minHz = 1400, maxHz = 1600) } // 1.5 Hz bins over the WSPR window
    val rx = rememberRx(12000, 600, s.audio) { b, n -> spec.feed(b, n); dec.feed(b, n) } // waterfall + slots
    val list by dec.spots.collectAsStateWithLifecycle()   // heard
    val busy by dec.busy.collectAsStateWithLifecycle()
    val last by dec.lastCount.collectAsStateWithLifecycle()
    val msg by dec.message.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) } // for the slot bar
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    val gate = rememberTxGate(vm)                       // the licence notice before transmitting
    val b by WsprBeacon.state.collectAsStateWithLifecycle() // the beacon (its offset marker)
    remember(s) { WsprBeacon.attach(ctx); WsprBeacon.myCall = s.callsign; WsprBeacon.myGrid = s.locator; WsprBeacon.level = s.txLevel / 100f; WsprBeacon.setDbm(s.wsprDbm)
        WsprNet.enabled = s.wsprUpload; WsprNet.myCall = s.callsign; WsprNet.myGrid = s.locator } // you, the level, the power reported; spot upload
    val marks = listOf(b.txHz.toFloat() to Pal.Red)     // where the beacon transmits
    val sideways = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp && it.screenHeightDp < 480 } // a phone on its side
    var mapOpen by remember { mutableStateOf(false) }   // the map showing
    ModeFrame("WSPR", { vm.back() }, actions = {
        TextButton({ mapOpen = true }) { Text("Map", color = Pal.Text2) } // where the stations are
        TextButton({ dec.clear() }) { Text("Clear", color = Pal.Text2) }
    }) {
        RxStatus(rx)                                        // audio, level
        RigBar(Mode.WSPR)                                   // the radio, the bands
        WsprTxPanel(gate) { d -> vm.updateSettings { it.copy(wsprDbm = d) } } // the beacon (the power chosen is kept)
        val net by WsprNet.status.collectAsStateWithLifecycle() // the last upload
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
            SmallChip(if (s.wsprUpload) "WSPRnet upload: on" else "WSPRnet upload: off", s.wsprUpload) { vm.updateSettings { it.copy(wsprUpload = !s.wsprUpload) } }
            Text(if (s.wsprUpload) net.ifEmpty { "Spots go to wsprnet.org after each slot" } else "Off: spots stay on this phone", color = if (net.contains("failed") || net.contains("not uploaded")) Pal.Amber else Pal.Muted,
                fontSize = 12.sp, maxLines = 2, modifier = Modifier.weight(1f))
        }
        val into = (now % 120_000L) / 1000f                 // seconds into the 2 minutes
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) { // slot bar
            Text("%d:%02d".format(into.toInt() / 60, into.toInt() % 60), color = Pal.Text2, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(44.dp))
            LinearProgressIndicator({ (into / 114f).coerceAtMost(1f) }, Modifier.weight(1f).height(5.dp), color = if (into < 114f) Pal.Cyan else Pal.Amber, trackColor = Pal.Tert)
            Text(when { busy -> "  Decoding..."; msg.isNotEmpty() -> "  $msg"; last == -2 -> "  Your beacon slot (not decoded)"; last < 0 -> "  Decodes at 1:54"; else -> "  $last spots" },
                color = if (busy || msg.isNotEmpty()) Pal.Amber else Pal.Text2, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f, false))
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            if (sideways) Waterfall(spec, Modifier.weight(0.4f).fillMaxHeight().padding(end = 8.dp, top = 4.dp, bottom = 4.dp), marks = marks) { WsprBeacon.setTxHz(it.toInt()) } // beside the list
            Column(Modifier.weight(1f)) {
                if (!sideways) Waterfall(spec, Modifier.fillMaxWidth().height(110.dp).padding(vertical = 4.dp), marks = marks) { WsprBeacon.setTxHz(it.toInt()) } // above the list; tap: beacon offset
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {  // column titles
                    listOf("UTC" to 42.dp, "dB" to 34.dp, "DT" to 36.dp, "MHz" to 84.dp, "Dr" to 24.dp).forEach { (t, w) -> Text(t, Modifier.width(w), color = Pal.Muted, fontSize = 11.sp) }
                    Text("Call  Locator  Power", Modifier.weight(1f), color = Pal.Muted, fontSize = 11.sp); Text("km", color = Pal.Muted, fontSize = 11.sp)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list) { d -> SpotRow(d, list.firstOrNull { it.slotMs == d.slotMs } === d) } // a line each (a gap above each slot)
                    if (list.isEmpty()) item { Text("Spots appear here 1:54 after each even UTC minute. Tune the IC-705 to the WSPR " +
                        "frequency (a band chip above) in USB-D; the signals are in the 200 Hz around 1500 Hz of audio, shown in the waterfall.",
                        color = Pal.Dim, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
                }
            }
        }
    }
    if (mapOpen) WsprMap(list, s.callsign, s.locator) { mapOpen = false } // the stations on a map (decoding carries on behind it)
}

/** One report for the WSPR map: when (UTC ms), the station, its locator, the report (dB), what else to say, distance. */
private class Rpt(val ms: Long, val call: String, val grid: String, val snr: Int, val extra: String, val km: Int?)

private fun hhmm(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).let { "%02d:%02d".format(it.hour, it.minute) } // "14:02"

/** One dot per station: its best report among [rs] (at its locator, else its country's middle), with what was heard;
 *  strongest first (stations sharing a locator square share a dot, which takes the first one's colour). */
private fun dots(rs: List<Rpt>): List<MapPoint> = rs.groupBy { it.call }.mapNotNull { (call, xs) ->
    val r = xs.maxBy { it.snr }; val ll = Locator.toLatLon(r.grid.take(6)); val ent = if (ll == null) Cty.lookup(call) else null
    val at = ll ?: ent?.let { it.lat to it.lon } ?: return@mapNotNull null
    r.snr to MapPoint(at.first, at.second, call, listOfNotNull("${r.snr} dB at ${hhmm(r.ms)} UTC", r.extra.ifEmpty { null }, r.grid.ifEmpty { null },
        r.km?.let { "$it km" }, Cty.country(call).ifEmpty { null }, if (xs.size > 1) "${xs.size} reports" else null).joinToString("  •  "), snrColor(r.snr), exact = ll != null)
}.sortedByDescending { it.first }.map { it.second }

/**
 * The WSPR map: "Heard here" - the stations this page decoded - or "Heard me" - the stations that reported your beacon
 * to WSPRnet in the last 24 hours (fetched from wspr.live). Dots coloured by the report (dB), no path lines. Under it a
 * timeline, as wspr.rocks's hours slider: the reports in each hour (or each 2-minute slot, when they cover under 3
 * hours) as bars; tap or drag along it to show just that hour's stations, Play to step through, All for everything. The
 * view stays put while the time changes (Fit frames all the stations of the list).
 */
@Composable
private fun WsprMap(spots: List<WsprSpot>, myCall: String, myGrid: String, onClose: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }      // 0 heard here, 1 heard me
    var heard by remember { mutableStateOf<List<WsprNet.HeardBy>?>(null) } // heard me (null: not fetched yet)
    var err by remember { mutableStateOf("") }        // why it could not be fetched
    LaunchedEffect(tab) {                             // fetch "heard me" when first shown
        if (tab == 1 && heard == null) { err = ""
            try { heard = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { WsprNet.heardMe(myCall) } } catch (e: Exception) { err = "Could not reach WSPRnet's database (wspr.live): ${e.message}" } }
    }
    val all = remember(tab, spots, heard) {           // the list's reports
        if (tab == 0) spots.filter { it.call != "..." }.map { Rpt(it.slotMs, it.call, it.grid, it.snr, it.watts, it.km) }
        else (heard ?: emptyList()).map { Rpt(it.ms, it.call, it.grid, it.snr, uk.hamdigital.core.Logbook.band(it.freqHz), it.km) }
    }
    // The timeline's span and steps: Heard me - the last 24 hours, by the hour; Heard here - what the page has heard, by
    // the 2-minute slot if that is under 3 hours, else by the hour.
    val hour = 3_600_000L; val slot = 120_000L
    val (start, step, bins) = remember(tab, all) {
        if (tab == 1) { val end = (System.currentTimeMillis() / hour + 1) * hour; Triple(end - 24 * hour, hour, 24) }
        else if (all.isEmpty()) Triple(0L, slot, 0)
        else { val first = all.minOf { it.ms }; val last = all.maxOf { it.ms } + slot
            if (last - first <= 3 * hour) Triple(first / slot * slot, slot, ((last - first / slot * slot + slot - 1) / slot).toInt())
            else { val s0 = first / hour * hour; Triple(s0, hour, ((last - s0 + hour - 1) / hour).toInt()) } }
    }
    var sel by remember(tab) { mutableStateOf<Int?>(null) } // the time chosen (null: all)
    var playing by remember(tab) { mutableStateOf(false) } // stepping through
    LaunchedEffect(playing) {                         // Play: one step every 0.8 s, from the start (or the time chosen), to the end
        if (!playing) return@LaunchedEffect
        var i = sel?.let { if (it >= bins - 1) 0 else it } ?: 0
        while (playing && i < bins) { sel = i; kotlinx.coroutines.delay(800); i++ }
        playing = false
    }
    val shown = sel?.let { b -> all.filter { it.ms >= start + b * step && it.ms < start + (b + 1) * step } } ?: all // the reports for the time chosen
    val points = remember(shown) { dots(shown) }; val every = remember(all) { dots(all) }
    val period = sel?.let { b -> "${hhmm(start + b * step)}-${hhmm(start + (b + 1) * step)} UTC: " } ?: ""
    MapDialog("WSPR map", points, Locator.toLatLon(myGrid), onClose,
        note = when {
            tab == 0 -> if (spots.isEmpty()) "Nothing heard yet on this page." else "$period${points.size} stations heard here (${shown.size} reports). Tap a dot."
            err.isNotEmpty() -> err
            heard == null -> "Asking WSPRnet who heard $myCall in the last 24 hours..."
            else -> "$period${points.size} stations reported hearing $myCall (${shown.size} reports) - WSPRnet, last 24 hours. Tap a dot."
        },
        top = { Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 2.dp)) {
            SmallChip("Heard here", tab == 0) { tab = 0 }; SmallChip(if (myCall.isBlank()) "Heard me (set callsign)" else "Heard me", tab == 1) { if (myCall.isNotBlank()) tab = 1 } } },
        legend = { Column {
            if (bins > 0) Timeline(all.map { it.ms }, start, step, bins, sel, playing, { sel = it; playing = false }, { playing = !playing })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { // the report colours
                listOf(0 to "0 dB +", -10 to "-10", -15 to "-15", -20 to "-20", -25 to "below").forEach { (v, t) -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(10.dp).background(snrColor(v), androidx.compose.foundation.shape.CircleShape)); Text(" $t", color = Pal.Text2, fontSize = 12.sp) } } }
        } },
        paths = false, fitTo = every)                     // (no lines from you; the view frames every station of the list)
}

/**
 * The timeline: a bar per [step] from [start] ([bins] of them) - the reports in it, of [times] - with the time chosen
 * ([sel], null = all) lit; tap or drag along the bars to choose ([onSel]); Play steps through ([onPlay]); All clears.
 */
@Composable
private fun Timeline(times: List<Long>, start: Long, step: Long, bins: Int, sel: Int?, playing: Boolean, onSel: (Int?) -> Unit, onPlay: () -> Unit) {
    val counts = remember(times, start, step, bins) { IntArray(bins).also { c -> times.forEach { t -> val i = ((t - start) / step).toInt(); if (i in 0 until bins) c[i]++ } } }
    val top = max(1, counts.maxOrNull() ?: 1)         // the tallest bar
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallChip(if (playing) "Stop" else "Play", playing) { onPlay() } // step through the hours (slots)
            SmallChip("All", sel == null) { onSel(null) }
            Text(if (step >= 3_600_000L) "Reports per hour (UTC) - tap or drag to show one hour" else "Reports per 2-minute slot (UTC) - tap or drag to show one",
                color = Pal.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val pick: (Float, Float) -> Unit = { x, w -> onSel(((x / w) * bins).toInt().coerceIn(0, bins - 1)) } // x -> the bar under it
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(62.dp).padding(top = 4.dp)
            .pointerInput(bins) { detectTapGestures { p -> pick(p.x, size.width.toFloat()) } }
            .pointerInput(bins) { detectHorizontalDragGestures { ch, _ -> pick(ch.position.x, size.width.toFloat()) } }) {
            val labH = 14.sp.toPx(); val barArea = size.height - labH // bars above, times below
            val bw = size.width / bins                    // a bar's width
            for (i in 0 until bins) {
                val hgt = if (counts[i] == 0) 0f else max(2f, barArea * counts[i] / top) // (a report or two still shows)
                val col = when { sel == null -> Pal.Accent; sel == i -> Pal.Cyan; else -> Pal.Tert } // the chosen one lit
                drawRect(col, androidx.compose.ui.geometry.Offset(i * bw + 1f, barArea - hgt), androidx.compose.ui.geometry.Size(max(1f, bw - 2f), hgt))
            }
            drawLine(Pal.Dim, androidx.compose.ui.geometry.Offset(0f, barArea), androidx.compose.ui.geometry.Offset(size.width, barArea), 1f) // the axis
            val every = if (step >= 3_600_000L) 6 else max(1, bins / 6) // a time under every few bars
            val style = androidx.compose.ui.text.TextStyle(color = Pal.Muted, fontSize = 10.sp)
            for (i in 0 until bins step every) {
                val t = tm.measure(hhmm(start + i * step), style)
                val x = (i * bw).coerceAtMost(size.width - t.size.width)
                drawText(t, topLeft = androidx.compose.ui.geometry.Offset(x, barArea + 1f))
            }
        }
    }
}

@Composable
private fun SpotRow(d: WsprSpot, firstOfSlot: Boolean) {
    if (firstOfSlot) Spacer(Modifier.fillMaxWidth().padding(top = 3.dp).height(1.dp).background(Pal.Tert)) // between slots
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val mono = FontFamily.Monospace
        Text(d.utc, Modifier.width(42.dp), color = Pal.Text2, fontSize = 13.sp, fontFamily = mono)
        Text("%+d".format(d.snr), Modifier.width(34.dp), color = if (d.snr >= -15) Pal.Green else Pal.Text, fontSize = 13.sp, fontFamily = mono)
        Text("%.1f".format(d.dt), Modifier.width(36.dp), color = Pal.Text2, fontSize = 13.sp, fontFamily = mono)
        Text(if (d.freqMHz >= 0.1) "%.6f".format(d.freqMHz) else "%.0f Hz".format(d.freqMHz * 1e6), Modifier.width(84.dp), color = Pal.Text2, fontSize = 13.sp, fontFamily = mono) // RF, or audio when the dial is not known
        Text("${d.drift}", Modifier.width(24.dp), color = Pal.Text2, fontSize = 13.sp, fontFamily = mono)
        Column(Modifier.weight(1f)) {                 // call, locator, power; the country under them
            Text("${if (d.call == "...") "<...>" else d.call}  ${d.grid}  ${d.watts}", color = Pal.Text, fontSize = 14.sp, fontFamily = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Cty.country(d.call).takeIf { it.isNotEmpty() }?.let { Text(it, color = Pal.Text2, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text(d.km?.let { "$it" } ?: "", color = Pal.Muted, fontSize = 12.sp, fontFamily = mono)
    }
}

private val PERCENTS = listOf(10, 20, 33, 50)          // WSJT-X-style transmit percentages
private val POWERS = listOf(23 to "200 mW", 30 to "1 W", 33 to "2 W", 37 to "5 W", 40 to "10 W") // dBm reported

/** The WSPR beacon's controls: on / off, how often, the power reported, and what it is doing. */
@Composable
private fun WsprTxPanel(gate: TxGate, onDbm: (Int) -> Unit) {
    val b by WsprBeacon.state.collectAsStateWithLifecycle()
    TxBanner { WsprBeacon.enable(false) }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallChip(if (b.enabled) "Beacon: on" else "Beacon: off", b.enabled) { if (b.enabled) WsprBeacon.enable(false) else gate.ask { WsprBeacon.enable(true) } }
        PERCENTS.forEach { p -> SmallChip("$p %", p == b.percent) { WsprBeacon.setPercent(p) } }
        POWERS.forEach { (d, t) -> SmallChip(t, d == b.dbm) { WsprBeacon.setDbm(d); onDbm(d) } }
    }
    Text(b.status.ifEmpty { if (WsprBeacon.myCall.isBlank() || WsprBeacon.myGrid.length < 4) "Set your callsign and locator in Settings to use the beacon." else "Beacon: \"${WsprBeacon.message(b)}\" at ${b.txHz} Hz (tap the waterfall to move it). Set the power to what the IC-705 sends." },
        color = if (b.enabled) Pal.Amber else Pal.Muted, fontSize = 12.sp, maxLines = 2)
}
