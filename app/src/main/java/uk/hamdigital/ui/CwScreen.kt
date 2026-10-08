// CW page: Morse from the IC-705's USB audio (or the microphone) through the decoder shared with HF Propagation and the
// Tab5 (HamPropCore cw_decoder.cpp) - HF Propagation's CW decoder tool, as a full page: Pause / Listen, auto tune,
// reset speed, clear; sensitivity, start speed, follow tone; a waterfall of the 14 tone bins (350-1000 Hz; tap a column
// to lock on it); speed, tone and signal; the decoded text (copy / share).
package uk.hamdigital.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import uk.hamdigital.MainViewModel
import uk.hamdigital.core.Mode
import uk.hamdigital.engine.CwNative
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10

private const val WF_ROWS = 150                       // waterfall history (one row a tick: about 12 s)
private val CW_SPD = listOf(12f, 18f, 25f, 30f)       // start speeds offered

@Composable
fun CwScreen(vm: MainViewModel) {
    val ctx = LocalContext.current; val clip = LocalClipboardManager.current
    val s by vm.settings.collectAsStateWithLifecycle() // the audio choice
    val paused = remember { AtomicBoolean(false) }    // audio ignored while paused
    var listening by remember { mutableStateOf(true) } // (the button)
    val rx = rememberRx(16000, 256, s.audio) { b, n -> if (!paused.get()) CwNative.process(b, n) } // 16 kHz, 16 ms blocks, as the Tab5
    var st by remember { mutableStateOf(CwNative.state()) } // the decoder's state
    var text by remember { mutableStateOf(CwNative.text()) }; var sym by remember { mutableStateOf("") } // transcript, letter in progress
    val wf = remember { Array(WF_ROWS) { FloatArray(CwNative.BINS) } } // the waterfall, newest first
    var tick by remember { mutableIntStateOf(0) }    // redraw it
    LaunchedEffect(Unit) {                            // about 12 times a second
        while (true) {
            st = CwNative.state(); text = CwNative.text(); sym = CwNative.symbol()
            if (rx.running && listening) {            // a waterfall row
                for (r in WF_ROWS - 1 downTo 1) System.arraycopy(wf[r - 1], 0, wf[r], 0, CwNative.BINS) // everything down a row
                val fl = st.copyOfRange(10, 10 + CwNative.BINS).sorted()[CwNative.BINS / 2].coerceAtLeast(1f) // scale: the row's median bin ..
                for (b in 0 until CwNative.BINS) { val p = st[10 + b]; wf[0][b] = if (p > fl) (log10(p / fl) / 2.5f) else 0f } // .. 25 dB above it
                tick++
            }
            delay(80)
        }
    }
    val locked = st[0] > 0.5f; val toneOn = st[1] > 0.5f; val lbin = st[2].toInt(); val follow = st[8] > 0.5f
    val conf = LocalConfiguration.current
    val wide = conf.screenWidthDp >= 600              // controls beside the waterfall
    val sideways = conf.screenWidthDp > conf.screenHeightDp && conf.screenHeightDp < 480 // a phone on its side: the text gets its own column
    ModeFrame("CW", { vm.back() }, actions = {
        TextButton({ clip.setText(AnnotatedString(text)) }) { Text("Copy", color = Pal.Text2) } // the transcript
        TextButton({ ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share the decoded text")) }) { Text("Share", color = Pal.Text2) }
    }) {
        RxStatus(rx)                                  // audio, level
        if (!sideways) RigBar(Mode.CW)                // the radio, the bands (on its side there is no room)
        val controls: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { // the main controls
                Button({ listening = !listening; paused.set(!listening) }, colors = ButtonDefaults.buttonColors(containerColor = if (listening) Pal.Red else Color(0xFF1F8A4C))) { Text(if (listening) "Pause" else "Listen") }
                OutlinedButton({ CwNative.control(1, 0f) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Auto tune", fontSize = 13.sp) }
                OutlinedButton({ CwNative.control(2, 0f) }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Reset speed", fontSize = 13.sp) }
                OutlinedButton({ CwNative.control(0, 0f); text = "" }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Clear", fontSize = 13.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { // sensitivity, follow tone
                Text("Sensitivity", color = Pal.Muted, fontSize = 13.sp)
                Slider(st[6], { CwNative.control(4, it); st = CwNative.state() }, Modifier.weight(1f).padding(horizontal = 8.dp).then(if (sideways) Modifier.height(32.dp) else Modifier))
                SmallChip(if (follow) "Follow tone: on" else "Follow tone: off", follow) { CwNative.control(6, if (follow) 0f else 1f); st = CwNative.state() }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { // start speed
                Text("Start speed", color = Pal.Muted, fontSize = 13.sp)
                CW_SPD.forEach { w -> SmallChip("${w.toInt()} WPM", kotlin.math.abs(st[7] - w) < 0.5f) { CwNative.control(5, w); CwNative.control(2, 0f); st = CwNative.state() } }
            }
        }
        val waterfall: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                Canvas(Modifier.fillMaxWidth().then(if (sideways) Modifier.weight(1f) else Modifier.height(170.dp)).pointerInput(Unit) { detectTapGestures { p -> CwNative.control(3, (p.x / size.width * CwNative.BINS).toInt().toFloat()) } }) { // tap: lock that column
                    tick                              // (redraw on each new row)
                    val cw = size.width / CwNative.BINS; val rh = size.height / WF_ROWS
                    for (r in 0 until WF_ROWS) for (b in 0 until CwNative.BINS) drawRect(wfColour(wf[r][b]), Offset(b * cw, r * rh), Size(cw - 1f, rh + 0.5f))
                    if (locked) drawRect(Color.White, Offset(lbin * cw, 0f), Size(cw, size.height), style = androidx.compose.ui.graphics.drawscope.Stroke(3f)) // the locked bin
                }
                Row(Modifier.fillMaxWidth()) { for (b in 0 until CwNative.BINS step 2) Text(if (b == 0) "350 Hz" else "${350 + 50 * b}", Modifier.weight(1f), color = Pal.Dim, fontSize = 10.sp) }
            }
        }
        val status = when { !rx.running -> "No audio"; !listening -> "Paused - tap Listen"; else -> when {
            !locked -> "Listening for a tone... (or tap its column)"; toneOn -> "Receiving"; else -> "Locked - waiting for the next letter" } } // what it is doing
        val tone = if (!locked) "Tone: searching" else if (st[5] > 0f) "Tone %.0f Hz  •  %.0f dB above the noise".format(st[4], st[5]) else "Tone %.0f Hz".format(st[4]) // (no dB until a mark is decoded on this tone)
        val numbers: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                Text(if (locked) "%.0f WPM".format(st[3]) else "-- WPM", color = Pal.Cyan, fontWeight = FontWeight.Bold, fontSize = 26.sp) // speed
                Text(tone, color = Pal.Text, fontSize = 14.sp)                          // tone, signal
                Text(status, color = Pal.Amber, fontSize = 13.sp)                       // what it is doing
                Text(sym, color = Pal.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp) // the letter in progress
            }
        }
        val numbersSmall: @Composable () -> Unit = {  // on its side: the same on two short lines
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (locked) "%.0f WPM".format(st[3]) else "-- WPM", color = Pal.Cyan, fontWeight = FontWeight.Bold, fontSize = 20.sp) // speed
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(tone, color = Pal.Text, fontSize = 12.sp, maxLines = 1)        // tone, signal
                    Text(status, color = Pal.Amber, fontSize = 12.sp, maxLines = 1)     // what it is doing
                }
                Text(sym, color = Pal.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp) // the letter in progress
            }
        }
        val textBox: @Composable (Modifier) -> Unit = { m -> // the decoded text
            Surface(color = Color(0xFF111820), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), modifier = m) {
                val sc = rememberScrollState(); LaunchedEffect(text.length) { sc.animateScrollTo(sc.maxValue) } // keep the newest in view
                SelectionContainer { Text(text.ifEmpty { "The decoded text appears here. Tune the IC-705 to a CW signal (CW mode, or USB with the tone at 350-1000 Hz)." },
                    Modifier.verticalScroll(sc).padding(10.dp), color = if (text.isEmpty()) Pal.Dim else Pal.Text, fontSize = 20.sp, lineHeight = 26.sp) }
            }
        }
        if (sideways) Row(Modifier.fillMaxSize().padding(bottom = 6.dp)) { // on its side: everything on one screen
            Column(Modifier.weight(1f).fillMaxHeight()) { controls(); waterfall(Modifier.weight(1f).padding(top = 4.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) { numbersSmall(); textBox(Modifier.fillMaxWidth().weight(1f)) }
        } else {
            controls()
            if (wide) Row(Modifier.padding(vertical = 6.dp)) { waterfall(Modifier.weight(1f)); Spacer(Modifier.width(12.dp)); numbers(Modifier.weight(1f)) }
            else { waterfall(Modifier.padding(vertical = 6.dp)); numbers(Modifier) }
            textBox(Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp))
        }
    }
}
