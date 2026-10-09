// FreeDV page: digital voice - RADE V1 (rade_c + Opus FARGAN, BSD) and codec2's 700D / 700E / 1600 (LGPL 2.1). The
// radio and its bands (FreeDV's calling frequencies, USB), a waterfall (a FreeDV signal is a block 1.1-1.5 kHz wide
// around 1.5 kHz of audio), whether the modem is in sync and the SNR, the mode (both ends must use the same), what the
// phone plays (the decoded speech, the radio's own audio, or nothing) and the squelch (codec2's modes), what stations
// send of themselves (codec2: the text channel, usually their call; RADE: the callsign at the end of each over), and
// talking: hold the button (or tap to start and stop) and speak into the phone; your call goes with it.
package uk.hamdigital.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.hamdigital.MainViewModel
import uk.hamdigital.audio.Spectrum
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.core.FreeDv
import uk.hamdigital.core.Mode

@Composable
fun FreeDvScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()  // station, audio choice, level
    DisposableEffect(Unit) { FreeDv.open(ctx); onDispose { FreeDv.close() } } // the modem while the page is open
    remember(s) { FreeDv.level = s.txLevel / 100f; 0 }
    val spec = remember { Spectrum(FreeDv.RATE, size = 1024, hop = 256) } // 7.8 Hz bins, 31 rows a second
    val rx = rememberRx(FreeDv.RATE, 320, s.audio) { b, n -> spec.feed(b, n); FreeDv.feed(b, n) } // waterfall + modem
    val sync by FreeDv.sync.collectAsStateWithLifecycle(); val snr by FreeDv.snr.collectAsStateWithLifecycle()
    val mode by FreeDv.mode.collectAsStateWithLifecycle(); val listen by FreeDv.listen.collectAsStateWithLifecycle()
    val squelch by FreeDv.squelch.collectAsStateWithLifecycle(); val text by FreeDv.text.collectAsStateWithLifecycle()
    val talking by FreeDv.talking.collectAsStateWithLifecycle(); val err by FreeDv.txError.collectAsStateWithLifecycle()
    var txText by remember(s.callsign) { mutableStateOf(FreeDv.txText.ifBlank { s.callsign }) }
    var latch by remember { mutableStateOf(false) }     // tap to start / stop, rather than hold
    val gate = rememberTxGate(vm)                       // the licence notice before transmitting
    ModeFrame("FreeDV", { vm.back() }, actions = { TextButton({ FreeDv.text.value = "" }) { Text("Clear", color = Pal.Text2) } }) {
        RxStatus(rx)                                      // audio, level
        RigBar(Mode.FREEDV)                               // the radio, the bands
        TxBanner { FreeDv.stopTalk(); Transmitter.halt() }
        val rade = mode == FreeDv.FdMode.RADE
        Waterfall(spec, Modifier.fillMaxWidth().height(90.dp).padding(vertical = 4.dp), marks = if (rade) listOf(750f to Pal.Dim, 2250f to Pal.Dim) else listOf(900f to Pal.Dim, 2100f to Pal.Dim)) // (the signal's edges: RADE 30 carriers 50 Hz apart; 700D / 700E)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {   // sync light, SNR
                Spacer(Modifier.size(12.dp).background(if (sync) Pal.Green else Pal.Tert, CircleShape))
                Text(if (sync) "  FreeDV ${mode.label}: in sync, SNR %.0f dB".format(snr) else "  No FreeDV ${mode.label} signal", color = if (sync) Pal.Green else Pal.Text2, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                Text("Mode", color = Pal.Muted, fontSize = 13.sp)
                FreeDv.FdMode.entries.forEach { m -> SmallChip(m.label, m == mode) { if (!talking) FreeDv.setMode(ctx, m) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                Text("Play", color = Pal.Muted, fontSize = 13.sp)
                FreeDv.Listen.entries.forEach { l -> SmallChip(l.label, l == listen) { FreeDv.listen.value = l } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (!rade) SmallChip(if (squelch) "Squelch: on" else "Squelch: off", squelch) { FreeDv.setSquelch(!squelch) } // (RADE is quiet without a signal anyway)
                SmallChip(if (latch) "Talk: tap on / off" else "Talk: hold", latch) { latch = !latch }
            }
            Text(if (rade) "Callsigns heard" else "Text received", color = Pal.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            Surface(color = Color(0xFF111820), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(text.ifEmpty { if (rade) "Each station's callsign appears here at the end of its over." else "Stations' text (usually their call) appears here." }, Modifier.padding(8.dp), color = if (text.isEmpty()) Pal.Dim else Pal.Text, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
            }
            if (rade) Text("Your callsign (${s.callsign.ifBlank { "set it in Settings" }}) is sent at the end of each over.", color = Pal.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            else CompactField(txText, { txText = it.take(60); FreeDv.txText = txText }, "Text to send (your call)", Modifier.fillMaxWidth().padding(top = 8.dp))
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { // the talk button
                Surface(color = if (talking) Pal.Red else Pal.Accent, shape = CircleShape, modifier = Modifier.size(132.dp)
                    .pointerInput(latch) {
                        detectTapGestures(onPress = {
                            if (latch) return@detectTapGestures            // (tap mode: onTap below)
                            var started = false
                            gate.ask { FreeDv.txText = txText; started = FreeDv.startTalk(ctx, s.callsign) } // hold: talk ..
                            tryAwaitRelease(); if (started) FreeDv.stopTalk()                              // .. until let go
                        }, onTap = { if (latch) { if (talking) FreeDv.stopTalk() else gate.ask { FreeDv.txText = txText; FreeDv.startTalk(ctx, s.callsign) } } })
                    }) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(if (talking) "TALKING" else if (latch) "TAP TO\nTALK" else "HOLD TO\nTALK", color = Pal.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 20.sp)
                    }
                }
            }
            if (err.isNotEmpty()) Text(err, color = Pal.Red, fontSize = 13.sp)
            Text("Speak into the phone's microphone, a hand's width away. Both stations must use the same mode (RADE is FreeDV's " +
                "newest; 700D the most used of the older ones). The decoded speech plays on the phone - headphones or Bluetooth if " +
                "connected. Transmitting stops after 5 minutes, or if the radio link goes.", color = Pal.Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}
