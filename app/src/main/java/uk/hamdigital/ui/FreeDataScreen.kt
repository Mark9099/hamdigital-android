// FreeDATA page: the radio and its bands, a waterfall (FreeDATA's signalling is a narrow DATAC13 burst around 1500 Hz),
// whether the modem has a signal and its SNR, and what has been heard from FreeDATA stations (CQ, QRV, beacons, pings,
// session openings) - newest first, or by station with their locator and distance. Sending: CQ, Beacon, Ping a station;
// Answer replies to CQs and to pings for you as FreeDATA does (off as standard: it transmits by itself).
package uk.hamdigital.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.hamdigital.MainViewModel
import uk.hamdigital.audio.Spectrum
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.core.FreeData
import uk.hamdigital.core.Locator
import uk.hamdigital.core.Mode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)

@Composable
fun FreeDataScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()  // station, audio choice, level
    remember(s) { FreeData.attach(ctx); FreeData.myCall = s.callsign; FreeData.myGrid = s.locator; FreeData.level = s.txLevel / 100.0; 0 }
    val spec = remember { Spectrum(FreeData.RATE, size = 1024, hop = 512, maxHz = 3000) }
    val rx = rememberRx(FreeData.RATE, 320, s.audio) { b, n -> spec.feed(b, n); FreeData.feed(b, n) } // waterfall + modems
    val log by FreeData.log.collectAsStateWithLifecycle(); val stations by FreeData.stations.collectAsStateWithLifecycle()
    val sync by FreeData.sync.collectAsStateWithLifecycle(); val snr by FreeData.snr.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }       // 0 heard, 1 stations
    var answer by remember { mutableStateOf(FreeData.answer) }
    var pingTo by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val gate = rememberTxGate(vm)                      // the licence notice before transmitting
    fun send(f: () -> ByteArray, what: String) {
        if (s.callsign.isBlank() || !Locator.valid(s.locator)) { msg = "Set your callsign and locator in Settings first"; return }
        gate.ask { msg = FreeData.send(ctx, f(), what) ?: "Sent: $what" }
    }
    ModeFrame("FreeDATA", { vm.back() }, actions = { TextButton({ FreeData.log.value = emptyList() }) { Text("Clear", color = Pal.Text2) } }) {
        RxStatus(rx)                                    // audio, level
        RigBar(Mode.FREEDATA)                           // the radio, the bands
        TxBanner { Transmitter.halt() }
        Waterfall(spec, Modifier.fillMaxWidth().height(70.dp).padding(vertical = 4.dp), marks = listOf(1500f to Pal.Dim))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(12.dp).background(if (sync) Pal.Green else Pal.Tert, CircleShape))
            Text(if (sync) "  FreeDATA signal, SNR %.0f dB".format(snr) else "  Listening (DATAC13)", color = if (sync) Pal.Green else Pal.Text2, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("you: ${FreeData.full()}", color = Pal.Muted, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            SmallChip("Heard", tab == 0) { tab = 0 }; SmallChip("Stations (${stations.size})", tab == 1) { tab = 1 }
            SmallChip(if (answer) "Answer: on" else "Answer: off", answer) { if (answer) { answer = false; FreeData.answer = false } else gate.ask { answer = true; FreeData.answer = true } }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (tab == 0) LazyColumn(Modifier.fillMaxSize()) {
                if (log.isEmpty()) item { Text("FreeDATA stations' CQs, beacons and pings appear here. FreeDATA is a messaging and file program using codec2's data modems; tune to where its users are (the band chips are suggestions in each band's data segment).", color = Pal.Dim, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
                items(log) { h -> Row(Modifier.padding(vertical = 2.dp)) {
                    Text(HMS.format(Instant.ofEpochMilli(h.time)), color = Pal.Muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    if (h.from.isNotEmpty()) Text("  ${h.from}", color = if (h.forMe) Pal.Amber else Pal.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (h.grid.isNotEmpty()) Text("  ${h.grid}", color = Pal.Cyan, fontSize = 13.sp)
                    Text("  ${h.text}" + if (h.type >= 0) "  (%.0f dB)".format(h.snr) else "", color = if (h.type == -2) Pal.Cyan else Pal.Text2, fontSize = 13.sp)
                } }
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(stations.values.sortedByDescending { it.time }) { h -> Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(h.from, color = Pal.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(110.dp))
                    Text(h.grid, color = Pal.Cyan, fontSize = 13.sp, modifier = Modifier.width(70.dp))
                    Text(Locator.km(s.locator, h.grid)?.let { "$it km" } ?: "", color = Pal.Green, fontSize = 13.sp, modifier = Modifier.width(80.dp))
                    Text("${HMS.format(Instant.ofEpochMilli(h.time))}  %.0f dB".format(h.snr), color = Pal.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton({ pingTo = h.from }) { Text("Ping", fontSize = 12.sp) }
                } }
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = if (msg.startsWith("Sent")) Pal.Green else Pal.Red, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Button({ send({ FreeData.buildCq() }, "CQ") }) { Text("CQ") }
            OutlinedButton({ send({ FreeData.buildBeacon() }, "beacon") }) { Text("Beacon") }
            CompactField(pingTo, { pingTo = it.uppercase().take(10) }, "Ping (CALL-SSID)", Modifier.weight(1f))
            OutlinedButton({ if (pingTo.isNotBlank()) send({ FreeData.buildPing(pingTo) }, "ping to $pingTo") }) { Text("Ping") }
        }
    }
}
