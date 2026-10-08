// WSPR page: the radio and its bands, the 2-minute slot bar (WSPR transmissions start a second after each even UTC
// minute; the slot is decoded at 1:54), a waterfall of the WSPR window (1400-1600 Hz of audio), and the spots heard -
// newest slot first - with UTC, signal (dB), time offset, frequency, drift, call, locator, power and distance.
// Decoding by wsprd from WSJT-X (GPL v3).
package uk.hamdigital.ui

import androidx.compose.foundation.background
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
import uk.hamdigital.core.Mode
import uk.hamdigital.core.WsprDecoder
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
    val sideways = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp && it.screenHeightDp < 480 } // a phone on its side
    ModeFrame("WSPR", { vm.back() }, actions = { TextButton({ dec.clear() }) { Text("Clear", color = Pal.Text2) } }) {
        RxStatus(rx)                                        // audio, level
        RigBar(Mode.WSPR)                                   // the radio, the bands
        val into = (now % 120_000L) / 1000f                 // seconds into the 2 minutes
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) { // slot bar
            Text("%d:%02d".format(into.toInt() / 60, into.toInt() % 60), color = Pal.Text2, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(44.dp))
            LinearProgressIndicator({ (into / 114f).coerceAtMost(1f) }, Modifier.weight(1f).height(5.dp), color = if (into < 114f) Pal.Cyan else Pal.Amber, trackColor = Pal.Tert)
            Text(when { busy -> "  Decoding..."; msg.isNotEmpty() -> "  $msg"; last < 0 -> "  Decodes at 1:54 of each even minute"; else -> "  $last spots" },
                color = if (busy || msg.isNotEmpty()) Pal.Amber else Pal.Text2, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f, false))
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            if (sideways) Waterfall(spec, Modifier.weight(0.4f).fillMaxHeight().padding(end = 8.dp, top = 4.dp, bottom = 4.dp)) // beside the list
            Column(Modifier.weight(1f)) {
                if (!sideways) Waterfall(spec, Modifier.fillMaxWidth().height(110.dp).padding(vertical = 4.dp)) // above the list
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
        Text("${d.call}  ${d.grid}  ${d.watts}", Modifier.weight(1f), color = Pal.Text, fontSize = 14.sp, fontFamily = mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(d.km?.let { "$it" } ?: "", color = Pal.Muted, fontSize = 12.sp, fontFamily = mono)
    }
}
