// APRS / packet page (Dire Wolf): the radio and its frequencies (2 m and the ISS: 1200 baud FM-D; 30 m: 300 baud
// USB-D - the speed follows the radio), a waterfall, how many frames have been heard, and three lists: Heard (every
// frame, newest first: time, station, what it is, its text), Stations (each station's latest, with distance and
// bearing from you when it gave a position) and Messages (APRS messages to you, and those you sent). Map shows the
// stations with a position. Sending: Beacon sends your position (your locator's middle - not your exact address - with
// the symbol and comment chosen); in Messages, a station and text sends a message. One frame each, one transmission.
package uk.hamdigital.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.hamdigital.MainViewModel
import uk.hamdigital.audio.Spectrum
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.core.Aprs
import uk.hamdigital.core.Locator
import uk.hamdigital.core.Mode
import uk.hamdigital.rig.Ic705
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)
private val SYMBOLS = listOf("/-" to "Home", "/>" to "Car", "/[" to "On foot", "/Y" to "Yacht", "/'" to "Small aircraft") // APRS symbols offered (table + code)

@Composable
fun AprsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()  // station, audio choice, level
    remember(s.callsign) { Aprs.myCall = s.callsign; 0 }
    val rig by Ic705.state.collectAsStateWithLifecycle()
    val baud by Aprs.baud.collectAsStateWithLifecycle()
    LaunchedEffect(rig.freqHz) { val b = if (rig.freqHz in 1..29_999_999) 300 else 1200; if (b != baud || !started) { Aprs.open(b); started = true } } // the speed follows the radio (VHF / UHF 1200, HF 300)
    val spec = remember { Spectrum(Aprs.RATE, size = 1024, hop = 512, maxHz = 3000) }
    val rx = rememberRx(Aprs.RATE, 512, s.audio) { b, n -> spec.feed(b, n); Aprs.feed(b, n) } // waterfall + Dire Wolf
    val log by Aprs.log.collectAsStateWithLifecycle(); val stations by Aprs.stations.collectAsStateWithLifecycle()
    val places by Aprs.places.collectAsStateWithLifecycle(); val msgs by Aprs.messages.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }       // 0 heard, 1 stations, 2 messages
    var map by remember { mutableStateOf(false) }
    var symbol by remember { mutableStateOf(SYMBOLS[0].first) }
    var comment by remember { mutableStateOf("HF Digital Modes") }
    var to by remember { mutableStateOf("") }; var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }          // what happened when sending
    val gate = rememberTxGate(vm)                      // the licence notice before transmitting
    val home = Locator.toLatLon(s.locator)
    fun send(tnc2: String?) { if (tnc2 == null) { msg = "Set your callsign and locator in Settings first"; return }
        gate.ask { msg = Aprs.send(ctx, s.callsign, tnc2, s.txLevel / 100.0) ?: "Sent: ${tnc2.substringAfter(':')}" } }
    ModeFrame("APRS / Packet", { vm.back() }, actions = { TextButton({ map = true }) { Text("Map", color = Pal.Text2) } }) {
        RxStatus(rx)                                    // audio, level
        RigBar(Mode.APRS)                               // the radio, the frequencies
        TxBanner { Transmitter.halt() }
        Waterfall(spec, Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp),
            marks = if (baud < 600) listOf(1600f to Pal.Red, 1800f to Pal.Red) else listOf(1200f to Pal.Red, 2200f to Pal.Red)) // the two tones
        Text("$baud baud  •  ${log.count { it.via != "(sent)" }} frames heard, ${stations.size} stations", color = Pal.Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            SmallChip("Heard", tab == 0) { tab = 0 }; SmallChip("Stations", tab == 1) { tab = 1 }
            SmallChip(if (msgs.any { it.via != "(sent)" }) "Messages (${msgs.count { it.via != "(sent)" }})" else "Messages", tab == 2) { tab = 2 }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                0 -> LazyColumn(Modifier.fillMaxSize()) {  // every frame, newest first
                    if (log.isEmpty()) item { Text("Frames heard appear here. On 2 m, 144.800 MHz carries APRS across Europe; the ISS digipeater is on 145.825 MHz when it passes; HF APRS is at 300 baud on 30 m.", color = Pal.Dim, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
                    items(log) { h -> HeardRow(h, home) }
                }
                1 -> LazyColumn(Modifier.fillMaxSize()) {  // each station's latest
                    items(stations.values.sortedByDescending { it.time }) { h -> HeardRow(h, home) }
                }
                else -> Column(Modifier.fillMaxSize()) { // messages, and sending one
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        if (msgs.isEmpty()) item { Text("APRS messages to ${s.callsign.ifBlank { "you" }} appear here, with the ones you send. (They are not acknowledged automatically.)", color = Pal.Dim, fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
                        items(msgs) { h -> Text("${HM.format(Instant.ofEpochMilli(h.time))}  " + if (h.via == "(sent)") "to ${h.toCall}: ${h.message}" else "${h.src}: ${h.message}",
                            color = if (h.via == "(sent)") Pal.Cyan else Pal.Text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 3.dp).clickable { if (h.via != "(sent)") to = h.src }) } // tap: reply
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CompactField(to, { to = it.uppercase().take(9) }, "To", Modifier.width(110.dp))
                        CompactField(text, { text = it.take(67) }, "Message", Modifier.weight(1f))
                        Button({ if (to.isNotBlank() && text.isNotBlank()) { send(if (s.callsign.isBlank()) null else Aprs.message(s.callsign, to, text)); text = "" } }) { Text("Send") }
                    }
                }
            }
        }
        if (msg.isNotEmpty()) Text(msg, color = if (msg.startsWith("Sent")) Pal.Green else Pal.Red, fontSize = 12.sp, maxLines = 2)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { // your beacon
            Button({ send(Aprs.beacon(s.callsign, s.locator, symbol, comment)) }) { Text("Beacon") }
            SYMBOLS.forEach { (code, name) -> SmallChip(name, code == symbol) { symbol = code } }
        }
        CompactField(comment, { comment = it.take(40) }, "Beacon comment", Modifier.fillMaxWidth().padding(top = 4.dp))
    }
    if (map) MapDialog("APRS map", places.values.map { h ->       // the stations with a position
        uk.hamdigital.ui.MapPoint(h.lat!!, h.lon!!, h.name.ifBlank { h.src }, "${HM.format(Instant.ofEpochMilli(h.time))} UTC  ${h.type}  ${h.comment}".trim(),
            if (System.currentTimeMillis() - h.time < 30 * 60_000) Pal.Green else Pal.Cyan) }, home, { map = false }, paths = false,
        note = "Green: heard in the last half hour.")
}

private var started = false                           // (the receiver set up once since the app started)

/** One frame: time, station (and the digipeater it came through), what it is, and its text; distance / bearing if it gave a position. */
@Composable
private fun HeardRow(h: Aprs.Heard, home: Pair<Double, Double>?) {
    val sent = h.via == "(sent)"
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(HM.format(Instant.ofEpochMilli(h.time)), color = Pal.Muted, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Text("  " + (if (sent) "You" else h.src), color = if (sent) Pal.Cyan else Pal.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (!sent && !h.direct && h.via.isNotEmpty()) Text("  via ${h.via}", color = Pal.Muted, fontSize = 12.sp)
            Text("  " + h.type.ifBlank { if (h.aprs) "APRS" else "Packet" }, color = Pal.Amber, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            if (home != null && h.lat != null && h.lon != null) {
                val km = uk.hamdigital.core.Locator.distanceKm(home.first, home.second, h.lat, h.lon)
                Text("%.0f km".format(km), color = Pal.Green, fontSize = 12.sp)
            }
        }
        val body = listOf(h.name, h.comment.ifBlank { if (h.lat == null && h.weather.isBlank()) h.info else "" }, h.weather, h.device).filter { it.isNotBlank() }.joinToString("  •  ")
        if (body.isNotEmpty()) Text(body, color = Color(0xFFB8C4CC), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 3)
    }
}
