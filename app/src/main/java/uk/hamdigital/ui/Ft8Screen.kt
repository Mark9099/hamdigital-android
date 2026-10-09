// FT8 and FT4 page (one screen, two modes): the radio and its bands, a slot bar (time left in the slot, what the last
// decode found), the waterfall, and the messages heard - newest slot at the top - with UTC, signal (dB), time offset,
// audio frequency, the message, and the distance to the sender's locator. CQ calls are green, messages to you amber.
// Show: everything, CQs only, or to you only. Decoding by ft8_lib (MIT). Transmit (FtTxPanel): double-tap a line to answer
// that station, or Call CQ; the contact then runs itself (FtQso) and is logged; tap the waterfall for the TX offset.
package uk.hamdigital.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import uk.hamdigital.core.FtDecode
import uk.hamdigital.core.FtQso
import uk.hamdigital.core.Mode
import uk.hamdigital.core.SlotDecoder

@Composable
fun Ft8Screen(vm: MainViewModel, m: Mode) {
    val s by vm.settings.collectAsStateWithLifecycle()  // station, audio choice
    val dec = if (m == Mode.FT4) SlotDecoder.FT4 else SlotDecoder.FT8 // the mode's decoder
    LaunchedEffect(s.callsign, s.locator) { dec.myCall = s.callsign; dec.myGrid = s.locator } // for "to me" and distances
    val spec = remember(m) { Spectrum(12000) }          // the waterfall
    val rx = rememberRx(12000, 600, s.audio) { b, n -> spec.feed(b, n); dec.feed(b, n) } // 50 ms blocks: waterfall + slots
    val list by dec.decodes.collectAsStateWithLifecycle() // heard
    val busy by dec.busy.collectAsStateWithLifecycle()
    val last by dec.lastCount.collectAsStateWithLifecycle()
    var show by rememberSaveable { mutableIntStateOf(0) } // 0 all, 1 CQ, 2 to me
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) } // for the slot bar
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(100) } }
    val sideways = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp && it.screenHeightDp < 480 } // a phone on its side
    val ctx = LocalContext.current
    val qso = if (m == Mode.FT4) FtQso.FT4 else FtQso.FT8 // contacts (transmit)
    remember(s) { qso.attach(ctx); qso.myCall = s.callsign; qso.myGrid = s.locator; qso.level = s.txLevel / 100f } // you, the level
    val q by qso.state.collectAsStateWithLifecycle()    // (the TX offset marker)
    val gate = rememberTxGate(vm)                       // the licence notice before transmitting
    ModeFrame(m.title, { vm.back() }, actions = { TextButton({ dec.clear() }) { Text("Clear", color = Pal.Text2) } }) {
        RxStatus(rx)                                      // audio, level
        RigBar(m)                                         // the radio, the bands
        val into = (now % dec.periodMs) / dec.periodMs.toFloat() // how far through the slot
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) { // slot bar
            Text("Slot %.1f s".format((now % dec.periodMs) / 1000f), color = Pal.Text2, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(78.dp))
            LinearProgressIndicator({ into }, Modifier.weight(1f).height(5.dp), color = Pal.Cyan, trackColor = Pal.Tert)
            Text(when { busy -> "  Decoding..."; last < 0 -> "  First decode at the slot's end"; else -> "  $last decoded" },
                color = if (busy) Pal.Amber else Pal.Text2, fontSize = 12.sp)
        }
        FtTxPanel(qso, gate)                              // transmit
        val marks = listOf(q.txHz.toFloat() to Pal.Red, q.txHz + (if (m == Mode.FT4) 83f else 50f) to Pal.Red) // the TX signal's width
        Row(Modifier.fillMaxWidth().weight(1f)) {
            if (sideways) Waterfall(spec, Modifier.weight(0.4f).fillMaxHeight().padding(end = 8.dp, top = 4.dp, bottom = 4.dp), marks = marks) { qso.setTxHz(it.toInt()) } // beside the list
            Column(Modifier.weight(1f)) {
                if (!sideways) Waterfall(spec, Modifier.fillMaxWidth().height(110.dp).padding(vertical = 4.dp), marks = marks) { qso.setTxHz(it.toInt()) } // above the list; tap: TX offset
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { // what to show
                    SmallChip("All", show == 0) { show = 0 }; SmallChip("CQ", show == 1) { show = 1 }
                    SmallChip(if (s.callsign.isBlank()) "To me (set callsign)" else "To ${s.callsign}", show == 2) { show = 2 }
                    Text("${list.size} heard", color = Pal.Dim, fontSize = 12.sp)
                }
                val shown = when (show) { 1 -> list.filter { it.cq }; 2 -> list.filter { it.toMe }; else -> list } // filtered
                DecodeHeader()
                val state = rememberLazyListState()
                LaunchedEffect(list.firstOrNull()?.slotMs) { state.scrollToItem(0) } // a new slot: back to the top
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    items(shown) { d -> DecodeRow(d, shown.firstOrNull { it.slotMs == d.slotMs } === d) { gate.ask { qso.pick(d) } } } // a line each (double-tap: call that station) (a gap above each slot)
                    if (shown.isEmpty()) item { Text(if (list.isEmpty()) "Messages heard appear here at the end of each ${if (m == Mode.FT4) "7.5" else "15"} s slot. " +
                        "Tune the IC-705 to the ${m.title} frequency (a band chip above) in USB-D." else "Nothing to show with this filter.",
                        color = Pal.Dim, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
                }
            }
        }
    }
}

private val COLS = listOf(56.dp, 34.dp, 38.dp, 42.dp)  // UTC, dB, DT, Hz

@Composable
private fun DecodeHeader() = Row(Modifier.fillMaxWidth().padding(top = 4.dp)) { // column titles
    listOf("UTC", "dB", "DT", "Hz").forEachIndexed { i, t -> Text(t, Modifier.width(COLS[i]), color = Pal.Muted, fontSize = 11.sp) }
    Text("Message", Modifier.weight(1f), color = Pal.Muted, fontSize = 11.sp); Text("km", color = Pal.Muted, fontSize = 11.sp)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DecodeRow(d: FtDecode, firstOfSlot: Boolean, onPick: () -> Unit) {
    val bg = when { d.toMe -> Color(0x40FFB432); d.cq -> Color(0x3000FF88); else -> Color.Transparent } // amber: to you; green: CQ
    if (firstOfSlot) Spacer(Modifier.fillMaxWidth().padding(top = 3.dp).height(1.dp).background(Pal.Tert)) // between slots
    Row(Modifier.fillMaxWidth().background(bg).combinedClickable(onClick = {}, onDoubleClick = onPick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) { // double-tap: answer / call this station (as WSJT-X's double-click: a stray tap never starts a call)
        val mono = FontFamily.Monospace
        Text(d.utc, Modifier.width(COLS[0]), color = Pal.Text2, fontSize = 13.sp, fontFamily = mono)
        Text("%+d".format(d.snr), Modifier.width(COLS[1]), color = if (d.snr >= -10) Pal.Green else Pal.Text, fontSize = 13.sp, fontFamily = mono)
        Text("%.1f".format(d.dt), Modifier.width(COLS[2]), color = Pal.Text2, fontSize = 13.sp, fontFamily = mono)
        Text("${d.freq}", Modifier.width(COLS[3]), color = Pal.Text2, fontSize = 13.sp, fontFamily = mono)
        Text(d.text, Modifier.weight(1f), color = Pal.Text, fontSize = 14.sp, fontFamily = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(d.km?.let { "$it" } ?: "", color = Pal.Muted, fontSize = 12.sp, fontFamily = mono)
    }
}
