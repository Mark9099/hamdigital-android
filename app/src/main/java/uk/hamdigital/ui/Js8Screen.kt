// JS8Call page (JS8 Normal): the radio and its bands, the 15 s slot bar, the waterfall with the receive offset marked
// (tap to move it - the decoder tries there first), and JS8Call's views as three tabs: Band activity (the text at each
// offset, frames joined into messages), Calls (the stations heard, with signal, locator and distance) and To me (the
// messages addressed to your callsign). Decoding by JS8Call's decoder (GPL v3).
package uk.hamdigital.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import uk.hamdigital.MainViewModel
import uk.hamdigital.audio.Spectrum
import uk.hamdigital.core.Js8Decoder
import uk.hamdigital.core.Mode
import uk.hamdigital.core.js8Time

@Composable
fun Js8Screen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()  // station, audio choice
    val dec = Js8Decoder.one                            // the app's JS8 decoder
    LaunchedEffect(s.callsign, s.locator) { dec.myCall = s.callsign; dec.myGrid = s.locator } // for "to me" and distances
    val spec = remember { Spectrum(12000) }             // the waterfall
    val rx = rememberRx(12000, 600, s.audio) { b, n -> spec.feed(b, n); dec.feed(b, n) } // waterfall + slots
    val band by dec.band.collectAsStateWithLifecycle()
    val calls by dec.calls.collectAsStateWithLifecycle()
    val toMe by dec.toMe.collectAsStateWithLifecycle()
    val busy by dec.busy.collectAsStateWithLifecycle()
    val last by dec.lastCount.collectAsStateWithLifecycle()
    var offset by rememberSaveable { mutableIntStateOf(dec.nfqso) } // the receive offset
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 band, 1 calls, 2 to me
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) } // for the slot bar
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(100) } }
    val sideways = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp && it.screenHeightDp < 480 } // a phone on its side
    ModeFrame("JS8Call", { vm.back() }, actions = { TextButton({ dec.clear() }) { Text("Clear", color = Pal.Text2) } }) {
        RxStatus(rx)                                      // audio, level
        if (!sideways) RigBar(Mode.JS8)                   // the radio, the bands
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) { // slot bar
            Text("Slot %.1f s".format((now % 15_000) / 1000f), color = Pal.Text2, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(78.dp))
            LinearProgressIndicator({ (now % 15_000) / 15_000f }, Modifier.weight(1f).height(5.dp), color = Pal.Cyan, trackColor = Pal.Tert)
            Text(when { busy -> "  Decoding..."; last < 0 -> "  First decode at the slot's end"; else -> "  $last frames" }, color = if (busy) Pal.Amber else Pal.Text2, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            val wf: @Composable (Modifier) -> Unit = { m -> Waterfall(spec, m, marks = listOf(offset.toFloat() to Pal.Red, offset + 50f to Pal.Red)) { hz -> offset = hz.toInt(); dec.nfqso = offset } } // the 50 Hz JS8 signal width
            if (sideways) wf(Modifier.weight(0.4f).fillMaxHeight().padding(end = 8.dp, top = 4.dp, bottom = 4.dp))
            Column(Modifier.weight(1f)) {
                if (!sideways) wf(Modifier.fillMaxWidth().height(110.dp).padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { // the views
                    SmallChip("Band activity", tab == 0) { tab = 0 }; SmallChip("Calls (${calls.size})", tab == 1) { tab = 1 }
                    SmallChip(if (toMe.isEmpty()) "To me" else "To me (${toMe.size})", tab == 2) { tab = 2 }
                    Text("RX $offset Hz", color = Pal.Muted, fontSize = 12.sp)
                }
                LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
                    when (tab) {
                        0 -> {
                            items(band) { b ->
                                Row(Modifier.fillMaxWidth().clickable { offset = b.freq; dec.nfqso = b.freq }.padding(vertical = 3.dp)) { // tap: listen there first
                                    Text(js8Time(b.lastMs).take(4), Modifier.width(40.dp), color = Pal.Text2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    Text("${b.freq}", Modifier.width(40.dp), color = Pal.Cyan, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    Text("%+d".format(b.snr), Modifier.width(34.dp), color = Pal.Text2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    Text(b.text.trim(), Modifier.weight(1f), color = Pal.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (band.isEmpty()) item { Hint("JS8 traffic appears here at the end of each 15 s slot. Tune the IC-705 to the JS8 frequency (a band chip above) in USB-D.") }
                        }
                        1 -> {
                            items(calls) { c ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Text(c.call, Modifier.weight(1f), color = Pal.Text, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
                                    Text("%+d".format(c.snr), Modifier.width(40.dp), color = Pal.Text2, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                    Text(c.grid, Modifier.width(52.dp), color = Pal.Text2, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                    Text(c.km?.let { "$it km" } ?: "", Modifier.width(70.dp), color = Pal.Muted, fontSize = 12.sp)
                                    Text(js8Time(c.lastMs).take(4), color = Pal.Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                            if (calls.isEmpty()) item { Hint("The stations heard, newest first.") }
                        }
                        else -> {
                            items(toMe) { f ->
                                Row(Modifier.fillMaxWidth().background(Color(0x40FFB432)).padding(vertical = 3.dp)) {
                                    Text(f.utc.take(4), Modifier.width(40.dp), color = Pal.Text2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                    Text(f.message.trim(), Modifier.weight(1f), color = Pal.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                            if (toMe.isEmpty()) item { Hint(if (s.callsign.isBlank()) "Set your callsign in Settings to see messages addressed to you." else "Messages addressed to ${s.callsign} appear here.") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(t: String) = Text(t, color = Pal.Dim, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) // an empty list's note
