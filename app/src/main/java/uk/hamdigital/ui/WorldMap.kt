// The map (Logbook contacts, WSPR stations): a full-screen window over the page with HF Propagation's map centred on
// your station (azimuthal equidistant: land keeps its shape close in, a straight line from you is the great-circle path
// and distance from you is true distance; the rim is the far side of the world) - land, coast and borders as HF
// Propagation draws them (Natural Earth outlines, the Tab5's colours) - a dot for each station with the path to it from
// you (the white diamond), and its call beside it where there is room. It opens zoomed to fit every station (and you);
// pinch to zoom, drag to move, Fit to see them all again; tap a dot for its details. A hollow dot is at its country's
// middle (cty.dat), its locator not being known. The outlines are projected once for your position (in rim = 1 units)
// and drawn moved and scaled, so zooming and dragging stay smooth.
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
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
import uk.hamdigital.map.CentredMap
import uk.hamdigital.map.WorldData
import kotlin.math.*

/** A station on the map: where, its call, what to say when tapped, its colour; [exact] false = its country's middle. */
data class MapPoint(val lat: Double, val lon: Double, val label: String, val detail: String, val color: Color, val exact: Boolean = true)

private val SEA = Color(0xFF0B1826)                   // HF Propagation's (the Tab5's) map colours
private val GRAT = Color(0xFF13263A)                  // graticule
private val LAND = Color(0xFF2C3D33)                  // land
private val COAST = Color(0xFF6A8A74)                 // coastline
private val BORDER = Color(0xFF50685A)                // country borders
private val OUTSIDE = Color(0xFF05080C)               // beyond the rim (the far side of the world)

/** A colour for each band, for maps that show several. */
fun bandColor(band: String): Color = when (band) {
    "160m" -> Color(0xFFB07CFF); "80m" -> Color(0xFF5B8CFF); "60m" -> Color(0xFF3FC1FF); "40m" -> Color(0xFF00E0C0); "30m" -> Color(0xFF6BE36B)
    "20m" -> Color(0xFFFFE14D); "17m" -> Color(0xFFFFB432); "15m" -> Color(0xFFFF7A3D); "12m" -> Color(0xFFFF4466); "10m" -> Color(0xFFFF66C4)
    "6m" -> Color(0xFFFFFFFF); else -> Color(0xFFA0B0C0)
}

/** A colour for a signal report (dB), the steps HF Propagation uses for live reports. */
fun snrColor(snr: Int): Color = Pal.Heat[if (snr >= 0) 5 else if (snr >= -10) 4 else if (snr >= -15) 3 else if (snr >= -20) 2 else 1]

/**
 * The map window: [title], [points], your position [home] (null: no locator - the map is then centred on the stations),
 * [note] under the title, [top] for the page's own controls (e.g. WSPR's heard here / heard me), [legend] under the map.
 * Closes with Back or Close.
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

/** The outlines projected for one centre, in unit coordinates (rim = 1): land rings, borders, the graticule. */
private class Projected(val land: Path, val borders: Path, val grat: Path)

/** The map itself: [points] and [home]; zooms to fit them when it opens and whenever [fitKey] changes; [onPick] gets a tapped dot. */
@Composable
fun WorldMap(points: List<MapPoint>, home: Pair<Double, Double>?, fitKey: Int, modifier: Modifier, onPick: (MapPoint?) -> Unit) {
    val ctx = LocalContext.current
    val world = remember { WorldData.load(ctx) }      // the outlines (cached)
    val centre = home ?: points.takeIf { it.isNotEmpty() }?.let { ps -> ps.map { it.lat }.average() to ps.map { it.lon }.average() } ?: (0.0 to 0.0) // you (else the stations' middle)
    val proj = remember(centre) { CentredMap(centre.first, centre.second) }
    val shapes = remember(proj, world) { project(world, proj) } // projected once for this centre
    val pts = remember(proj, points) { points.map { p -> FloatArray(2).also { proj.unit(p.lat, p.lon, it) } } } // the stations, unit coordinates
    var w by remember { mutableFloatStateOf(0f) }; var h by remember { mutableFloatStateOf(0f) } // the view's size
    var zoom by remember { mutableFloatStateOf(1f) }  // 1 = the whole world (the rim) fits the shorter side
    var ox by remember { mutableFloatStateOf(0f) }; var oy by remember { mutableFloatStateOf(0f) } // the view's centre, in unit coordinates
    LaunchedEffect(w, h, fitKey, pts) {               // fit: open, Fit, new stations (e.g. the other WSPR list)
        if (w <= 0f || h <= 0f) return@LaunchedEffect
        val xs = pts.map { it[0] } + listOfNotNull(home?.let { 0f }); val ys = pts.map { it[1] } + listOfNotNull(home?.let { 0f }) // (you are at 0, 0)
        if (xs.isEmpty()) { zoom = 1f; ox = 0f; oy = 0f; return@LaunchedEffect }
        val bw = max(xs.max() - xs.min(), 0.012f); val bh = max(ys.max() - ys.min(), 0.012f) // (at least ~250 km across: one station alone is not a dot filling the screen)
        val r0 = min(w, h) / 2                        // the rim's radius at zoom 1
        zoom = min(w * 0.70f / (bw * r0), h * 0.75f / (bh * r0)).coerceIn(1f, 400f) // fill most of the view, room for the calls beside the dots
        ox = (xs.max() + xs.min()) / 2; oy = (ys.max() + ys.min()) / 2
    }
    val tm = rememberTextMeasurer()                   // labels
    Canvas(modifier.clipToBounds().onSizeChanged { w = it.width.toFloat(); h = it.height.toFloat() }
        .pointerInput(Unit) {                         // pinch and drag
            detectTransformGestures { _, pan, z, _ ->
                zoom = (zoom * z).coerceIn(1f, 400f)
                val r = min(w, h) / 2 * zoom          // pixels per unit
                ox = (ox - pan.x / r).coerceIn(-1f, 1f); oy = (oy - pan.y / r).coerceIn(-1f, 1f) // (the view stays over the disc)
            }
        }
        .pointerInput(pts) {                          // tap: the nearest dot (within a finger's width)
            detectTapGestures { pos ->
                val r = min(w, h) / 2 * zoom
                val best = pts.indices.minByOrNull { i -> hypot(w / 2 + (pts[i][0] - ox) * r - pos.x, h / 2 + (pts[i][1] - oy) * r - pos.y) }
                onPick(best?.takeIf { i -> hypot(w / 2 + (pts[i][0] - ox) * r - pos.x, h / 2 + (pts[i][1] - oy) * r - pos.y) < 28.dp.toPx() }?.let { points[it] })
            }
        }) {
        val r = min(size.width, size.height) / 2 * zoom // pixels per unit
        fun sx(u: Float) = size.width / 2 + (u - ox) * r // unit -> screen
        fun sy(v: Float) = size.height / 2 + (v - oy) * r
        drawRect(OUTSIDE)                             // beyond the rim
        drawCircle(SEA, r, Offset(sx(0f), sy(0f)))    // the world: sea ..
        withTransform({ translate(sx(0f), sy(0f)); scale(r, r, Offset.Zero) }) { // .. land, coast, borders, graticule (unit coordinates, scaled)
            drawPath(shapes.grat, GRAT, style = Stroke(1f / r))
            drawPath(shapes.land, LAND); drawPath(shapes.land, COAST, style = Stroke(1.2f / r)); drawPath(shapes.borders, BORDER, style = Stroke(0.8f / r))
        }
        drawCircle(Pal.Dim, r, Offset(sx(0f), sy(0f)), style = Stroke(1.5f)) // the rim (the far side of the world)
        if (home != null) for (i in pts.indices) drawLine(points[i].color.copy(alpha = 0.4f), Offset(sx(0f), sy(0f)), Offset(sx(pts[i][0]), sy(pts[i][1])), 1.5f.dp.toPx()) // paths: straight from the centre
        val d = 4.5f.dp.toPx(); val placed = ArrayList<Rect>() // dot size; space taken by dots and labels
        for (q in pts) { val x = sx(q[0]); val y = sy(q[1]); placed += Rect(x - d, y - d, x + d, y + d) }
        for (i in pts.indices) {                      // the dots
            val o = Offset(sx(pts[i][0]), sy(pts[i][1])); val p = points[i]
            if (p.exact) { drawCircle(p.color, d, o); drawCircle(Color.Black, d, o, style = Stroke(1f)) } else drawCircle(p.color, d, o, style = Stroke(2f)) // hollow: country's middle
        }
        if (home != null) {                           // you: a white diamond at the centre
            val x = sx(0f); val y = sy(0f); val s = 7.dp.toPx()
            val dm = Path().apply { moveTo(x, y - s); lineTo(x + s, y); lineTo(x, y + s); lineTo(x - s, y); close() }
            drawPath(dm, Color.White); drawPath(dm, Color.Black, style = Stroke(1.5f)); placed += Rect(x - s, y - s, x + s, y + s)
        }
        val style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        for (i in pts.indices) {                      // calls beside the dots: right, else left, else none (no overlaps)
            val x = sx(pts[i][0]); val y = sy(pts[i][1])
            if (x < -50 || x > size.width + 50 || y < -20 || y > size.height + 20) continue // off screen
            val t = tm.measure(points[i].label, style); val gap = 7.dp.toPx()
            for (side in 0..1) {
                val left = if (side == 0) x + gap else x - gap - t.size.width
                val rc = Rect(left, y - t.size.height / 2f, left + t.size.width, y + t.size.height / 2f)
                if (rc.left < 0f || rc.right > size.width || rc.top < 0f || rc.bottom > size.height || placed.any { it.overlaps(rc) }) continue
                placed += rc; drawRect(Color(0x99000000), rc.topLeft, rc.size); drawText(t, topLeft = rc.topLeft); break
            }
        }
    }
}

/** The outlines projected for [p] (HF Propagation's projectedPaths, in unit coordinates): a land ring that reaches
 *  round the far side tears - its points jump across the disc - and is left out rather than filled wrongly; border and
 *  graticule lines are broken where they jump. */
private fun project(world: WorldData, p: CentredMap): Projected {
    val land = Path(); val brd = Path(); val grat = Path(); val q = FloatArray(2)
    for (r in world.land) {                           // land rings
        val ring = Path(); var torn = false; var px = 0f; var py = 0f; var i = 0
        while (i < r.size) {
            p.unit(r[i + 1].toDouble(), r[i].toDouble(), q)
            if (i == 0) ring.moveTo(q[0], q[1]) else { if (hypot(q[0] - px, q[1] - py) > 0.5f) { torn = true; break }; ring.lineTo(q[0], q[1]) } // a jump = torn
            px = q[0]; py = q[1]; i += 2
        }
        if (torn) continue                            // would fill wrongly across the rim
        ring.close(); land.addPath(ring)
    }
    fun line(path: Path, n: Int, pt: (Int) -> Pair<Double, Double>) { // a polyline, broken where it jumps
        var px = 0f; var py = 0f
        for (k in 0 until n) { val (la, lo) = pt(k); p.unit(la, lo, q)
            if (k == 0 || hypot(q[0] - px, q[1] - py) > 1f / 6) path.moveTo(q[0], q[1]) else path.lineTo(q[0], q[1]); px = q[0]; py = q[1] }
    }
    for (r in world.borders) line(brd, r.size / 2) { k -> r[2 * k + 1].toDouble() to r[2 * k].toDouble() } // borders
    for (lat in -80..80 step 10) line(grat, 181) { k -> lat.toDouble() to (-180.0 + 2 * k) } // parallels every 10 degrees
    for (lon in -180 until 180 step 10) line(grat, 89) { k -> (-88.0 + 2 * k) to lon.toDouble() } // meridians
    return Projected(land, brd, grat)
}
