// The map (Logbook contacts, WSPR stations): a full-screen window over the page with the flat world map - land, coast
// and borders drawn as in HF Propagation (Natural Earth outlines, the Tab5's colours) - a dot for each station, a
// great-circle line to it from your locator (the white diamond), and its call beside it where there is room. It opens
// zoomed to fit every station (and you); pinch to zoom, drag to move, Fit to see them all again; tap a dot for its
// details. A hollow dot is placed at its country's middle (cty.dat), its locator not being known.
package uk.hamdigital.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import uk.hamdigital.map.FlatMap
import uk.hamdigital.map.WorldData
import kotlin.math.*

/** A station on the map: where, its call, what to say when tapped, its colour; [exact] false = its country's middle. */
data class MapPoint(val lat: Double, val lon: Double, val label: String, val detail: String, val color: Color, val exact: Boolean = true)

private val SEA = Color(0xFF0B1826)                   // HF Propagation's (the Tab5's) map colours
private val GRAT = Color(0xFF13263A)                  // graticule
private val LAND = Color(0xFF2C3D33)                  // land
private val COAST = Color(0xFF6A8A74)                 // coastline
private val BORDER = Color(0xFF50685A)                // country borders

/** A colour for each band, for maps that show several. */
fun bandColor(band: String): Color = when (band) {
    "160m" -> Color(0xFFB07CFF); "80m" -> Color(0xFF5B8CFF); "60m" -> Color(0xFF3FC1FF); "40m" -> Color(0xFF00E0C0); "30m" -> Color(0xFF6BE36B)
    "20m" -> Color(0xFFFFE14D); "17m" -> Color(0xFFFFB432); "15m" -> Color(0xFFFF7A3D); "12m" -> Color(0xFFFF4466); "10m" -> Color(0xFFFF66C4)
    "6m" -> Color(0xFFFFFFFF); else -> Color(0xFFA0B0C0)
}

/** A colour for a signal report (dB), the steps HF Propagation uses for live reports. */
fun snrColor(snr: Int): Color = Pal.Heat[if (snr >= 0) 5 else if (snr >= -10) 4 else if (snr >= -15) 3 else if (snr >= -20) 2 else 1]

/**
 * The map window: [title], [points], your position [home] (null: no locator), [note] under the title, [top] for the
 * page's own controls (e.g. WSPR's heard here / heard me), [legend] under the map. Closes with Back or Close.
 */
@Composable
fun MapDialog(title: String, points: List<MapPoint>, home: Pair<Double, Double>?, onClose: () -> Unit, note: String = "",
              top: @Composable () -> Unit = {}, legend: @Composable () -> Unit = {}) {
    var fit by remember { mutableIntStateOf(0) }      // Fit pressed (count)
    var picked by remember { mutableStateOf<MapPoint?>(null) } // the dot tapped
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Pal.Bg) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(title, fontFamily = OrbitronFamily, fontWeight = FontWeight.Bold, color = Pal.Cyan, fontSize = 18.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton({ fit++; picked = null }) { Text("Fit", color = Pal.Text2) }   // all the stations again
                    TextButton(onClose) { Text("Close", color = Pal.Text2) }
                }
                Column(Modifier.padding(horizontal = 12.dp)) {
                    top()
                    Text(note.ifEmpty { "${points.size} station${if (points.size == 1) "" else "s"}. Pinch to zoom, drag to move, tap a dot." }, color = Pal.Muted, fontSize = 12.sp)
                }
                Box(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp)) {
                    WorldMap(points, home, fit, Modifier.fillMaxSize()) { picked = it }
                    picked?.let { p ->                // the tapped station's details
                        Surface(color = Color(0xEE111820), shape = RoundedCornerShape(10.dp), modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp)) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.label, color = p.color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(p.detail, color = Pal.Text, fontSize = 13.sp, lineHeight = 17.sp)
                                    if (!p.exact) Text("Locator not known: shown at its country's middle", color = Pal.Muted, fontSize = 11.sp)
                                }
                                TextButton({ picked = null }) { Text("OK", color = Pal.Text2) }
                            }
                        }
                    }
                }
                Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { legend() }
            }
        }
    }
}

/** The map itself: [points] and [home]; zooms to fit them when it opens and whenever [fitKey] changes; [onPick] gets a tapped dot. */
@Composable
fun WorldMap(points: List<MapPoint>, home: Pair<Double, Double>?, fitKey: Int, modifier: Modifier, onPick: (MapPoint?) -> Unit) {
    val ctx = LocalContext.current
    val world = remember { WorldData.load(ctx) }      // the outlines (cached)
    val land = remember(world) { worldPath(world.land, true) }; val brd = remember(world) { worldPath(world.borders, false) } // in degrees
    var w by remember { mutableFloatStateOf(0f) }; var h by remember { mutableFloatStateOf(0f) } // the view's size
    var zoom by remember { mutableFloatStateOf(1f) }; var clon by remember { mutableFloatStateOf(0f) }; var clat by remember { mutableFloatStateOf(0f) } // the view
    LaunchedEffect(w, h, fitKey, points) {            // fit: open, Fit, new stations (e.g. the other WSPR list)
        if (w <= 0f || h <= 0f) return@LaunchedEffect
        val ref = home?.second ?: points.firstOrNull()?.lon ?: 0.0 // longitudes are taken relative to you (so a spread across 180 degrees stays together)
        val lats = points.map { it.lat } + listOfNotNull(home?.first)
        val rels = points.map { wrap(it.lon - ref) } + listOfNotNull(home?.let { 0.0 })
        if (lats.isEmpty()) { zoom = 1f; clon = ref.toFloat(); clat = 0f; return@LaunchedEffect }
        val lonSpan = max(rels.max() - rels.min(), 3.0); val latSpan = max(lats.max() - lats.min(), 2.0) // (at least a few degrees: one station alone is not a dot filling the screen)
        val base = max(w / 360.0, h / 180.0)          // pixels per degree at 1x
        zoom = min(w * 0.82 / (lonSpan * base), h * 0.80 / (latSpan * base)).coerceIn(1.0, 120.0).toFloat() // fill most of the view
        clon = wrap(ref + (rels.max() + rels.min()) / 2).toFloat(); clat = ((lats.max() + lats.min()) / 2).toFloat()
    }
    val proj = FlatMap(max(w, 1f), max(h, 1f), clon.toDouble(), clat.toDouble(), zoom.toDouble()) // the view now
    val tm = rememberTextMeasurer()                   // labels
    Canvas(modifier.clipToBounds().onSizeChanged { w = it.width.toFloat(); h = it.height.toFloat() }
        .pointerInput(Unit) {                         // pinch and drag
            detectTransformGestures { _, pan, z, _ ->
                zoom = (zoom * z).coerceIn(1f, 120f)
                val ppd = max(w / 360.0, h / 180.0) * zoom
                clon = wrap(clon - pan.x / ppd).toFloat(); clat = (clat + pan.y / ppd).toFloat().coerceIn(-85f, 85f)
            }
        }
        .pointerInput(points) {                       // tap: the nearest dot (within a finger's width)
            detectTapGestures { pos ->
                val pr = FlatMap(w, h, clon.toDouble(), clat.toDouble(), zoom.toDouble())
                onPick(points.minByOrNull { hypot(pr.xOf(it.lon).toFloat() - pos.x, pr.yOf(it.lat).toFloat() - pos.y) }
                    ?.takeIf { hypot(pr.xOf(it.lon).toFloat() - pos.x, pr.yOf(it.lat).toFloat() - pos.y) < 28.dp.toPx() })
            }
        }) {
        drawRect(SEA)                                 // sea
        val ppd = proj.ppd.toFloat()
        for (k in -1..1) withTransform({ translate(left = (proj.xOf(0.0) + k * 360 * proj.ppd).toFloat(), top = proj.yOf(0.0).toFloat()); scale(ppd, ppd, Offset.Zero) }) { // up to three copies (east-west wrap)
            drawPath(land, LAND); drawPath(land, COAST, style = Stroke(1.2f / ppd)); drawPath(brd, BORDER, style = Stroke(0.8f / ppd))
        }
        graticule(proj)                               // every 10 degrees (30 when zoomed out)
        home?.let { (hla, hlo) -> points.forEach { p -> greatCircle(proj, hla, hlo, p.lat, p.lon, p.color.copy(alpha = 0.35f)) } } // the paths
        val r = 4.5f.dp.toPx(); val placed = ArrayList<Rect>() // dot size; space taken by labels and dots
        for (p in points) { val x = proj.xOf(p.lon).toFloat(); val y = proj.yOf(p.lat).toFloat(); placed += Rect(x - r, y - r, x + r, y + r) }
        for (p in points) {                           // the dots
            val o = Offset(proj.xOf(p.lon).toFloat(), proj.yOf(p.lat).toFloat())
            if (p.exact) { drawCircle(p.color, r, o); drawCircle(Color.Black, r, o, style = Stroke(1f)) } else drawCircle(p.color, r, o, style = Stroke(2f)) // hollow: country's middle
        }
        home?.let { (hla, hlo) ->                     // you: a white diamond
            val x = proj.xOf(hlo).toFloat(); val y = proj.yOf(hla).toFloat(); val s = 7.dp.toPx()
            val d = Path().apply { moveTo(x, y - s); lineTo(x + s, y); lineTo(x, y + s); lineTo(x - s, y); close() }
            drawPath(d, Color.White); drawPath(d, Color.Black, style = Stroke(1.5f)); placed += Rect(x - s, y - s, x + s, y + s)
        }
        val style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        for (p in points) {                           // calls beside the dots: right, else left, else none (no overlaps)
            val x = proj.xOf(p.lon).toFloat(); val y = proj.yOf(p.lat).toFloat()
            if (x < -50 || x > size.width + 50 || y < -20 || y > size.height + 20) continue // off screen
            val t = tm.measure(p.label, style); val gap = 7.dp.toPx()
            for (side in 0..1) {
                val left = if (side == 0) x + gap else x - gap - t.size.width
                val rc = Rect(left, y - t.size.height / 2f, left + t.size.width, y + t.size.height / 2f)
                if (rc.left < 0f || rc.right > size.width || rc.top < 0f || rc.bottom > size.height || placed.any { it.overlaps(rc) }) continue
                placed += rc; drawRect(Color(0x99000000), rc.topLeft, rc.size); drawText(t, topLeft = rc.topLeft); break
            }
        }
    }
}

private fun wrap(lon: Double): Double = lon - 360.0 * floor((lon + 180.0) / 360.0) // -180..180

/** The world outlines as one path in degrees (x = lon, y = -lat), for scaled drawing. */
private fun worldPath(lines: List<FloatArray>, closed: Boolean): Path = Path().apply {
    for (r in lines) {
        if (r.size < 4) continue                       // nothing to draw
        moveTo(r[0], -r[1]); var i = 2; while (i < r.size) { lineTo(r[i], -r[i + 1]); i += 2 } // the points
        if (closed) close()                            // ring
    }
}

private fun DrawScope.graticule(p: FlatMap) {          // parallels and meridians
    val step = if (p.zoom >= 4) 10 else 30            // degrees
    for (lat in -80..80 step step) { val y = p.yOf(lat.toDouble()).toFloat(); if (y in 0f..size.height) drawLine(GRAT, Offset(0f, y), Offset(size.width, y), 1f) }
    for (lon in -180 until 180 step step) { val x = p.xOf(lon.toDouble()).toFloat(); if (x in 0f..size.width) drawLine(GRAT, Offset(x, 0f), Offset(x, size.height), 1f) }
}

/** The great-circle path between two points, broken where it crosses the map's east-west edge (as HF Propagation draws it). */
private fun DrawScope.greatCircle(p: FlatMap, la1: Double, lo1: Double, la2: Double, lo2: Double, col: Color) {
    val r = PI / 180.0
    val d = acos((sin(la1 * r) * sin(la2 * r) + cos(la1 * r) * cos(la2 * r) * cos((lo2 - lo1) * r)).coerceIn(-1.0, 1.0)) // angular distance
    if (d < 1e-6) return                              // same place
    val path = Path(); var px = 0f; val n = 48
    for (k in 0..n) {                                 // intermediate points
        val f = k.toDouble() / n; val a = sin((1 - f) * d) / sin(d); val b = sin(f * d) / sin(d)
        val x = a * cos(la1 * r) * cos(lo1 * r) + b * cos(la2 * r) * cos(lo2 * r)
        val y = a * cos(la1 * r) * sin(lo1 * r) + b * cos(la2 * r) * sin(lo2 * r)
        val z = a * sin(la1 * r) + b * sin(la2 * r)
        val sx = p.xOf(atan2(y, x) / r).toFloat(); val sy = p.yOf(atan2(z, sqrt(x * x + y * y)) / r).toFloat()
        if (k == 0 || abs(sx - px) > 180 * p.ppd) path.moveTo(sx, sy) else path.lineTo(sx, sy); px = sx // (a jump of half a world: across the edge)
    }
    drawPath(path, col, style = Stroke(1.5f.dp.toPx()))
}
