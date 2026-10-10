// Weather fax page (fldigi's WEFAX receiver): the radio and the fax stations (chips: the dial for each, USB, so the fax
// sits at 1900 Hz of audio), a waterfall of 1300-2500 Hz (black 1500, white 2300 Hz marked), what the receiver is doing
// (waiting for a start signal, phasing, receiving - with the rows so far), Start now / Stop, the chart arriving line by
// line (scrolled to the newest rows), and the charts received (saved as they finish); tap one to see it full size,
// zoom, share, save to Photos or delete it. Settings: IOC 576 (nearly all stations) or 288, shift 800 or 850 Hz (DWD),
// the input filter.
package uk.hamdigital.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.hamdigital.MainViewModel
import uk.hamdigital.audio.Spectrum
import uk.hamdigital.core.Mode
import uk.hamdigital.core.Wefax
import uk.hamdigital.engine.WefaxNative
import uk.hamdigital.rig.Ic705
import java.io.File

private val STATES = arrayOf("Waiting for a start signal", "Stop signal", "Phasing (lining up)", "Receiving a chart", "Idle")

@Composable
fun WefaxScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()  // the audio choice
    remember { Wefax.attach(ctx); 0 }                   // the charts already received
    val spec = remember { Spectrum(WefaxNative.RATE, size = 2048, hop = 1024, minHz = 1300, maxHz = 2500) } // the fax's tones
    val rx = rememberRx(WefaxNative.RATE, 512, s.audio) { b, n -> spec.feed(b, n); Wefax.feed(b, n) } // waterfall + receiver
    val pics by Wefax.pictures.collectAsStateWithLifecycle()
    var st by remember { mutableStateOf(WefaxNative.state()) } // [state, rows, width, corr x1000, APT Hz, lpm]
    var ioc by remember { mutableIntStateOf(576) }; var shift by remember { mutableIntStateOf(800) }; var filt by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<Bitmap?>(null) } // the chart arriving
    var ver by remember { mutableIntStateOf(0) }        // (redraw when it grows)
    var shownRows by remember { mutableIntStateOf(0) }  // whole rows in the view
    var open by remember { mutableStateOf<File?>(null) } // a chart shown full size
    val rig by Ic705.state.collectAsStateWithLifecycle()
    LaunchedEffect(rig.freqHz) { Wefax.station = Mode.WEFAX.dialsKHz.firstOrNull { Math.abs(Math.round(it.second * 1000) - rig.freqHz) <= 3000 }?.first ?: "%.1f kHz".format(rig.freqHz / 1000.0 + 1.9) } // the station (for the file name)
    LaunchedEffect(Unit) {                              // twice a second: the state, and the chart's new rows
        var have = 0                                    // rows already in the view
        while (true) {
            val now = WefaxNative.state(); st = now
            val w = now[2]; val rows = now[1]
            if (now[0] != 3 || rows < have) { if (now[0] != 3) { view = null; have = 0 } else have = 0 } // a new chart (or none)
            if (now[0] == 3 && rows > 0) {
                val from = maxOf(0, have - 1)           // (the last row was part done)
                val px = withContext(Dispatchers.Default) { WefaxNative.rows(from) }
                val n = px.size / w                     // whole rows fetched
                if (n > 0) {
                    var b = view
                    if (b == null || b.height < from + n) { val nb = Bitmap.createBitmap(w, maxOf(from + n + 256, 512), Bitmap.Config.ARGB_8888); nb.eraseColor(android.graphics.Color.WHITE); b?.let { android.graphics.Canvas(nb).drawBitmap(it, 0f, 0f, null) }; b = nb } // grow in 256-row steps
                    val argb = IntArray(n * w) { i -> val g = px[i].toInt() and 255; (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
                    b.setPixels(argb, 0, w, 0, from, w, n)
                    view = b; have = from + n; shownRows = have; ver++
                }
            }
            delay(500)
        }
    }
    ModeFrame("Weather fax", { vm.back() }) {
        RxStatus(rx)                                    // audio, level
        RigBar(Mode.WEFAX)                              // the radio, the stations
        Waterfall(spec, Modifier.fillMaxWidth().height(60.dp).padding(vertical = 4.dp), marks = listOf(1500f to Pal.Dim, 1900f to Pal.Red, 2300f to Pal.Dim)) // black, centre, white
        val receiving = st[0] == 3
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { // what it is doing
            Text(STATES.getOrElse(st[0]) { "" } + if (receiving) ": line ${st[1]}" else "", color = if (receiving) Pal.Green else Pal.Text2, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("corr %.2f".format(st[3] / 1000.0), color = Pal.Muted, fontSize = 12.sp) // line-to-line correlation: a chart is about 0.1 and over
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp)) {
            SmallChip("Start now", false) { WefaxNative.control(4, 0.0) } // (tuned in part way through a chart)
            if (receiving) SmallChip("Stop", false) { WefaxNative.control(5, 0.0) } // end it (kept if worth keeping)
            SmallChip("IOC $ioc", false) { ioc = if (ioc == 576) 288 else 576; WefaxNative.control(3, ioc.toDouble()) }
            SmallChip("Shift $shift", false) { shift = if (shift == 800) 850 else 800; WefaxNative.control(1, shift.toDouble()) }
            SmallChip(arrayOf("Filter: narrow", "Filter: medium", "Filter: wide")[filt], false) { filt = (filt + 1) % 3; WefaxNative.control(2, filt.toDouble()) }
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 4.dp).background(Color(0xFF05080C)).border(1.dp, Pal.Tert)) { // the chart arriving
            val b = view
            if (b != null && receiving) {
                val sc = rememberScrollState()
                LaunchedEffect(ver) { sc.scrollTo(sc.maxValue) } // keep the newest rows in view
                Column(Modifier.verticalScroll(sc)) {
                    val img = remember(ver) { b.asImageBitmap() }   // (the same bitmap, drawn again as it grows)
                    val rows = minOf(b.height, maxOf(1, shownRows)) // just the rows received (no copy)
                    Image(androidx.compose.ui.graphics.painter.BitmapPainter(img, androidx.compose.ui.unit.IntOffset.Zero, androidx.compose.ui.unit.IntSize(b.width, rows)),
                        "Chart arriving", Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / rows), contentScale = ContentScale.FillWidth)
                }
            } else Text("A chart appears here line by line as it arrives. Tune to a fax station (a chip above); a chart starts with a " +
                "start signal and some lining-up lines. Joined part way through? Tap Start now.", Modifier.padding(10.dp), color = Pal.Dim, fontSize = 13.sp)
        }
        Text(if (pics.isEmpty()) "Received: none yet" else "Received (${pics.size}) - tap one", color = Pal.Muted, fontSize = 12.sp)
        LazyRow(Modifier.fillMaxWidth().height(76.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(pics, key = { it.name }) { f -> Thumb(f) { open = f } }
        }
    }
    open?.let { f -> PictureDialog(f, prefix = "WEFAX_", onDelete = { Wefax.delete(it) }) { open = null } }
}
