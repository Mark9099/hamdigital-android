// What every mode page shares: the frame (back arrow, title, UTC clock, where the audio comes from), the waterfall
// (a Spectrum's rows drawn into a bitmap, newest at the top), the input level bar, and the receive-audio controller
// that starts the capture when the page opens and stops it when the page closes.
package uk.hamdigital.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import uk.hamdigital.audio.AudioIn
import uk.hamdigital.audio.Spectrum
import uk.hamdigital.core.AudioChoice
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.log10

/** 0..1 -> dark blue, blue, cyan, yellow, red (HF Propagation's and the Tab5's waterfall colours). */
fun wfColour(v: Float): Color {
    val k = arrayOf(floatArrayOf(8f, 12f, 30f), floatArrayOf(20f, 60f, 200f), floatArrayOf(0f, 220f, 255f), floatArrayOf(255f, 220f, 0f), floatArrayOf(255f, 50f, 30f))
    val x = v.coerceIn(0f, 1f) * 4; val i = x.toInt().coerceAtMost(3); val f = x - i // which pair, how far between
    return Color((k[i][0] + (k[i + 1][0] - k[i][0]) * f) / 255f, (k[i][1] + (k[i + 1][1] - k[i][1]) * f) / 255f, (k[i][2] + (k[i + 1][2] - k[i][2]) * f) / 255f)
}

private val WF_ARGB = IntArray(256) { val c = wfColour(it / 255f); android.graphics.Color.rgb((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt()) } // the colours, as pixels

/** A page's frame: a top bar with the back arrow, the title, the UTC time and [actions]; [content] below. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeFrame(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    var utc by remember { mutableStateOf("") }        // the clock (digital modes run on UTC slots)
    LaunchedEffect(Unit) { while (true) { utc = ZonedDateTime.now(ZoneOffset.UTC).let { "%02d:%02d:%02d".format(it.hour, it.minute, it.second) }; delay(250) } }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TopAppBar(
            title = { Text(title, fontFamily = OrbitronFamily, fontWeight = FontWeight.Bold, color = Pal.Cyan, fontSize = 20.sp) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to the menu") } },
            actions = { actions(); Text("$utc UTC", Modifier.padding(horizontal = 10.dp), color = Pal.Text2, fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Pal.Bg),
            windowInsets = WindowInsets(0),           // (the column already pads for the status bar)
        )
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), content = content) // the page
    }
}

/** Receive audio while the page is open: asks for the permission the first time; returns the status for the page. */
class RxState { var error by mutableStateOf<String?>(null); var running by mutableStateOf(false) } // what went wrong / capturing

@Composable
fun rememberRx(rate: Int, block: Int, choice: AudioChoice, sink: (ShortArray, Int) -> Unit): RxState {
    val ctx = LocalContext.current; val st = remember { RxState() } // this page's capture
    fun go() { st.error = AudioIn.start(ctx, st, choice, rate, block, sink); st.running = st.error == null } // start (or say why not)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) go() else st.error = "Audio not allowed - Android Settings > Apps > HF Digital Modes > Permissions" }
    DisposableEffect(choice) {                        // page opens (or the audio choice changes): start; page closes: stop
        if (AudioIn.allowed(ctx)) go() else ask.launch(android.Manifest.permission.RECORD_AUDIO)
        onDispose { AudioIn.stop(st); st.running = false }
    }
    return st
}

/** The status line under the top bar: where the audio is from, or what is wrong. */
@Composable
fun RxStatus(rx: RxState, extra: String = "") {
    var lvl by remember { mutableFloatStateOf(0f) }   // input level, refreshed
    LaunchedEffect(Unit) { while (true) { lvl = AudioIn.level; delay(100) } }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(rx.error ?: if (rx.running) "Audio: ${AudioIn.sourceName}" else "Starting audio...", color = if (rx.error != null) Pal.Red else Pal.Text2,
            fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 2)
        if (extra.isNotEmpty()) Text(extra, color = Pal.Amber, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp)) // the page's own note
        val db = if (lvl > 0.001f) 20 * log10(lvl) else -60f // level in dB
        LinearProgressIndicator({ ((db + 60) / 60).coerceIn(0f, 1f) }, Modifier.width(80.dp).height(6.dp),
            color = if (lvl > 0.9f) Pal.Red else Pal.Green, trackColor = Pal.Tert) // red: too loud
    }
}

/**
 * The waterfall: [spec]'s rows, newest at the top, [rows] high, with a frequency scale under it. [marks] are frequencies
 * (Hz) to draw a line at (e.g. the RX and TX offsets); [onTap] gets the frequency tapped.
 */
@Composable
fun Waterfall(spec: Spectrum, modifier: Modifier, rows: Int = 200, marks: List<Pair<Float, Color>> = emptyList(), onTap: ((Float) -> Unit)? = null) {
    val w = spec.bins                                 // one pixel a bin
    val px = remember(spec) { IntArray(w * rows) { WF_ARGB[0] } } // the picture's pixels
    val bmp = remember(spec) { Bitmap.createBitmap(w, rows, Bitmap.Config.ARGB_8888) } // and its bitmap
    var tick by remember { mutableIntStateOf(0) }     // redraw
    LaunchedEffect(spec) {                            // take the new rows about 20 times a second
        while (true) {
            var got = false
            while (true) { val r = spec.rows.poll() ?: break; System.arraycopy(px, 0, px, w, w * (rows - 1)); for (i in 0 until w) px[i] = WF_ARGB[(r[i] * 255).toInt()]; got = true } // shift down, new row on top
            if (got) { bmp.setPixels(px, 0, w, 0, 0, w, rows); tick++ } // into the bitmap
            delay(50)
        }
    }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f).then(if (onTap != null) Modifier.pointerTapHz(spec, onTap) else Modifier)) {
            tick                                      // (redraw on each new row)
            drawImage(bmp.asImageBitmap(), IntOffset.Zero, IntSize(w, rows), dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low) // stretched to fit
            marks.forEach { (hz, c) -> val x = hz / spec.maxHz * size.width; drawLine(c, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 2f) } // markers
        }
        Row(Modifier.fillMaxWidth()) { for (k in 0 until 6) Text(if (k == 0) "0 Hz" else "${k * spec.maxHz / 6}", Modifier.weight(1f), color = Pal.Dim, fontSize = 10.sp) } // scale
    }
}

private fun Modifier.pointerTapHz(spec: Spectrum, onTap: (Float) -> Unit) =
    this.pointerInput(spec) { detectTapGestures { p -> onTap(p.x / size.width * spec.maxHz) } } // tap: that frequency
