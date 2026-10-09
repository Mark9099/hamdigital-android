// RTTY and PSK31 page (fldigi's receivers): the radio and its bands, a waterfall - tap a signal to tune to it (for RTTY
// tap between its two tones) - with the receive frequency marked, the signal (s/n, quality), AFC and squelch (and for
// RTTY the shift and Reverse), and the decoded text, which is kept while the app runs (Copy / Share / Clear).
package uk.hamdigital.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import uk.hamdigital.MainViewModel
import uk.hamdigital.audio.Spectrum
import uk.hamdigital.core.Mode
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.engine.KbNative

/** The decoded text of each keyboard mode, kept while the app runs. */
object KbText {
    val text = arrayOf(StringBuilder(), StringBuilder()) // RTTY, PSK31
    fun add(mode: Int, s: String) { val b = text[mode]; b.append(s); if (b.length > 20000) b.delete(0, b.length - 15000) } // (the newest 15-20 thousand characters)
}

private val SHIFTS = listOf(170, 85, 425, 850)        // RTTY shifts offered (170 Hz: the amateur standard)

@Composable
fun KeyboardScreen(vm: MainViewModel, m: Mode) {
    val ctx = LocalContext.current; val clip = LocalClipboardManager.current
    val k = if (m == Mode.RTTY) KbNative.RTTY else KbNative.PSK31 // which receiver
    val s by vm.settings.collectAsStateWithLifecycle()  // the audio choice
    val spec = remember(m) { Spectrum(8000, size = 2048, hop = 512) } // waterfall: 3.9 Hz bins, 16 rows a second
    val rx = rememberRx(8000, 256, s.audio) { b, n -> spec.feed(b, n) // fldigi's 8 kHz: the waterfall always ..
        val now = System.currentTimeMillis()
        if (!Transmitter.sentDuring(now - 600, now)) KbNative.process(k, b, n) } // .. the decoder not while we send (the radio passes our own audio back: the text came out twice, 0.10.5)
    var st by remember { mutableStateOf(KbNative.state(k)) } // [freq, metric, s/n, dcd, imd]
    var text by remember { mutableStateOf(KbText.text[k].toString()) } // what has been decoded
    var afc by rememberSaveable(m) { mutableStateOf(true) } // follow the signal
    var sql by rememberSaveable(m) { mutableFloatStateOf(if (m == Mode.RTTY) 0f else 25f) } // squelch (fldigi-style 0..100)
    var rev by rememberSaveable { mutableStateOf(false) } // RTTY reversed
    var shift by rememberSaveable { mutableIntStateOf(170) } // RTTY shift
    LaunchedEffect(k) { KbNative.control(k, 1, if (afc) 1.0 else 0.0); KbNative.control(k, 2, sql.toDouble()); if (k == KbNative.RTTY) KbNative.control(k, 3, if (rev) 1.0 else 0.0) } // settings into the receiver
    LaunchedEffect(k) { while (true) { val t = KbNative.text(k); if (t.isNotEmpty()) { KbText.add(k, t); text = KbText.text[k].toString() }; st = KbNative.state(k); delay(100) } } // ten times a second
    val gate = rememberTxGate(vm)                       // the licence notice before transmitting
    var txMsg by remember { mutableStateOf("") }       // why a transmission did not start
    var logging by remember { mutableStateOf<uk.hamdigital.core.Qso?>(null) } // the log form, open
    val log = { logging = newQso(m.title, s) }         // Log: a new contact, filled in from the radio
    // Send [t] on the receive frequency: the whole message as audio to the IC-705, the text copied into the window.
    fun send(t: String) = gate.ask {
        val a = KbNative.encode(k, if (k == KbNative.RTTY) "\n$t\n" else " $t ", st[0], shift.toDouble(), s.txLevel / 100.0) // (RTTY: new lines around it)
        if (Transmitter.send(ctx, s.callsign, a, 8000, 0, m.name)) { KbText.add(k, "\n[TX] $t\n"); text = KbText.text[k].toString(); txMsg = "" } else txMsg = Transmitter.lastError.value
    }
    val sideways = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp && it.screenHeightDp < 480 } // a phone on its side
    ModeFrame(m.title, { vm.back() }, actions = {
        TextButton({ clip.setText(AnnotatedString(text)) }) { Text("Copy", color = Pal.Text2) }
        TextButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share the decoded text")) }) { Text("Share", color = Pal.Text2) }
        TextButton({ KbText.text[k].clear(); text = "" }) { Text("Clear", color = Pal.Text2) }
        if (sideways) TextButton(log) { Text("Log", color = Pal.Text2) } // (on its side the send box, with its Log chip, is not shown)
    }) {
        RxStatus(rx)                                      // audio, level
        if (txMsg.isNotEmpty()) Text(txMsg, color = Pal.Red, fontSize = 12.sp) // why it did not transmit
        if (!sideways) RigBar(m) else AutoTune(m)         // the radio, the bands (sideways: just the tuning)
        val f = st[0].toFloat()                           // the receive frequency
        val marks = if (k == KbNative.RTTY) listOf(f - shift / 2f to Pal.Red, f + shift / 2f to Pal.Red) else listOf(f to Pal.Red) // tones / carrier
        val wf: @Composable (Modifier) -> Unit = { mod -> Waterfall(spec, mod, marks = marks) { hz -> KbNative.control(k, 0, hz.toDouble()) } } // tap: tune there
        val controls: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) { // frequency, signal
                Text("RX %.0f Hz".format(st[0]), color = Pal.Cyan, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                Text("   s/n %.0f dB".format(st[2]), color = Pal.Text, fontSize = 14.sp)
                if (k == KbNative.PSK31) Text(if (st[3] > 0.5) "   DCD" else "   --", color = if (st[3] > 0.5) Pal.Green else Pal.Dim, fontSize = 14.sp) // decoding?
                LinearProgressIndicator({ (st[1] / 100.0).toFloat().coerceIn(0f, 1f) }, Modifier.weight(1f).padding(start = 10.dp).height(6.dp), color = Pal.Green, trackColor = Pal.Tert) // quality
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { // AFC, squelch, (RTTY) reverse
                SmallChip(if (afc) "AFC: on" else "AFC: off", afc) { afc = !afc; KbNative.control(k, 1, if (afc) 1.0 else 0.0) }
                if (k == KbNative.RTTY) SmallChip(if (rev) "Reverse: on" else "Reverse", rev) { rev = !rev; KbNative.control(k, 3, if (rev) 1.0 else 0.0) }
                Text("Squelch", color = Pal.Muted, fontSize = 13.sp)
                Slider(sql, { sql = it; KbNative.control(k, 2, it.toDouble()) }, Modifier.weight(1f).height(32.dp), valueRange = 0f..100f)
            }
            if (k == KbNative.RTTY) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { // shift (45.45 baud)
                Text("Shift", color = Pal.Muted, fontSize = 13.sp)
                SHIFTS.forEach { h -> SmallChip("$h Hz", h == shift) { shift = h; KbNative.control(k, 5, h.toDouble()) } }
                Text("45.45 baud", color = Pal.Dim, fontSize = 12.sp)
            }
        }
        val textBox: @Composable (Modifier) -> Unit = { mod ->
            Surface(color = Color(0xFF111820), shape = RoundedCornerShape(10.dp), modifier = mod) {
                val sc = rememberScrollState(); LaunchedEffect(text.length) { sc.animateScrollTo(sc.maxValue) } // keep the newest in view
                SelectionContainer { Text(text.ifEmpty { "The decoded text appears here. Tap a ${m.title} signal in the waterfall to tune to it" +
                    (if (k == KbNative.RTTY) " (between its two tones; Reverse if the text is nonsense)." else ".") },
                    Modifier.verticalScroll(sc).padding(10.dp), color = if (text.isEmpty()) Pal.Dim else Pal.Text, fontSize = 17.sp, lineHeight = 23.sp, fontFamily = FontFamily.Monospace) }
            }
        }
        if (sideways) Row(Modifier.fillMaxSize().padding(bottom = 6.dp)) { // on its side: waterfall and controls | the text
            Column(Modifier.weight(1f).fillMaxHeight()) { controls(); wf(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) }
            Spacer(Modifier.width(10.dp)); textBox(Modifier.weight(1f).fillMaxHeight())
        } else {
            wf(Modifier.fillMaxWidth().height(140.dp).padding(vertical = 4.dp))
            controls()
            textBox(Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp))
            TxBanner { Transmitter.halt() }
            SendBox(s.callsign, m.title, ::send, onLog = log) // typing and sending; Log
        }
    }
    logging?.let { QsoEditor(it, true) { logging = null } } // the log form, over the page (decoding carries on)
}
