// SSTV page: pictures by radio. The radio and its bands (LSB below 10 MHz, as SSTV is sent there), a waterfall of
// 1000-2500 Hz (SSTV's tones: sync 1200, black 1500, white 2300), then two tabs.
// Receive (Robot36's decoder): the picture arriving line by line once its VIS code names the mode - or, between
// pictures, the scan lines as they come (Robot36's "scope") - the mode and progress, and the pictures received (saved
// as they finish); tap one to see it full size, share it, save it to Photos or delete it.
// Send (SSTV Encoder 2's modes): choose a picture or take a photo, the mode (its size and how long it takes), your
// callsign and a line of text over it, and Send - one transmission, with the TRANSMITTING bar's Halt.
package uk.hamdigital.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.hamdigital.MainViewModel
import uk.hamdigital.audio.Spectrum
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.core.Mode
import uk.hamdigital.core.SstvRx
import uk.hamdigital.core.SstvTx
import java.io.File

@Composable
fun SstvScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()  // station, audio choice
    remember { SstvRx.attach(ctx); 0 }                  // the pictures already received
    val spec = remember { Spectrum(12000, size = 2048, hop = 1024, minHz = 1000, maxHz = 2500) } // SSTV's tones
    val rx = rememberRx(SstvRx.RATE, 600, s.audio) { b, n -> spec.feed(b, n); SstvRx.feed(b, n) } // waterfall + decoder
    var tab by remember { mutableIntStateOf(0) }       // 0 receive, 1 send
    ModeFrame("SSTV", { vm.back() }) {
        RxStatus(rx)                                      // audio, level
        RigBar(Mode.SSTV)                                 // the radio, the bands
        TxBanner { Transmitter.halt() }
        Waterfall(spec, Modifier.fillMaxWidth().height(70.dp).padding(vertical = 4.dp),
            marks = listOf(1200f to Pal.Red, 1500f to Pal.Dim, 2300f to Pal.Dim)) // sync, black, white
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 2.dp)) {
            SmallChip("Receive", tab == 0) { tab = 0 }; SmallChip("Send", tab == 1) { tab = 1 }
        }
        if (tab == 0) SstvReceive(Modifier.fillMaxWidth().weight(1f)) else SstvSend(vm, Modifier.fillMaxWidth().weight(1f))
    }
}

/** Receive: the picture (or scope), what is happening, and the pictures received. */
@Composable
private fun SstvReceive(mod: Modifier) {
    val lines by SstvRx.lines.collectAsStateWithLifecycle()  // (redraw as lines arrive)
    val pics by SstvRx.pictures.collectAsStateWithLifecycle()
    val modeName by SstvRx.mode.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<File?>(null) }     // the picture shown full size
    val img = SstvRx.dec.image; val scope = SstvRx.dec.scope
    val receiving = img.line in 0 until img.height
    var lastShown by remember { mutableStateOf<Bitmap?>(null) } // the newest picture received (shown between pictures)
    LaunchedEffect(pics.firstOrNull()) { lastShown = pics.firstOrNull()?.let { f -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path) } } }
    var view by remember { mutableStateOf<Bitmap?>(null) }  // what the box shows now
    LaunchedEffect(Unit) {                            // five times a second: the picture so far, or the scope
        var seen = -1
        while (true) {
            val l = SstvRx.lines.value
            if (l != seen) { seen = l
                view = if (img.line in 0 until img.height) Bitmap.createBitmap(img.pixels, img.width, img.height, Bitmap.Config.ARGB_8888) // the picture so far (rows not yet received are black)
                else if (lastShown == null) { val h = minOf(scope.width * 3 / 4, scope.height / 2); val off = scope.width * (scope.line + scope.height / 2 - h) // the latest 480 scan lines (4:3, as the frame), newest at the bottom
                    if (off >= 0) Bitmap.createBitmap(scope.pixels, off, scope.width, scope.width, h, Bitmap.Config.ARGB_8888) else null } else null
            }
            delay(200)
        }
    }
    Column(mod) {
        Text(when {
            receiving -> "Receiving $modeName: line ${img.line} of ${img.height}"
            lines == 0 -> "Listening for SSTV. Tune to an SSTV frequency (a band chip); a picture starts with its VIS code, which names the mode."
            else -> "Listening for the next picture (its VIS code)" + if (modeName.isNotEmpty()) "  •  last: $modeName" else ""
        }, color = if (receiving) Pal.Green else Pal.Text2, fontSize = 13.sp, lineHeight = 17.sp)
        val shown = if (receiving) view else lastShown ?: view
        val isScope = !receiving && lastShown == null       // (the scan lines, not a picture)
        Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().weight(1f, fill = false).aspectRatio(4f / 3f) // the picture frame: 4:3, SSTV's shape (smaller if the page is short)
            .background(androidx.compose.ui.graphics.Color(0xFF05080C)).border(1.dp, Pal.Tert), contentAlignment = Alignment.Center) {
            if (shown != null) Image(shown.asImageBitmap(), "SSTV picture", Modifier.fillMaxSize().clickable(enabled = !receiving && pics.isNotEmpty()) { open = pics.firstOrNull() },
                contentScale = if (isScope) ContentScale.FillBounds else ContentScale.Fit) // scan lines fill the frame; a picture is shown whole, its own shape
            else Text("Pictures appear here as they arrive.", color = Pal.Dim, fontSize = 13.sp)
            if (isScope && shown != null) Text("Scan lines (no picture yet)", Modifier.align(Alignment.TopStart).background(androidx.compose.ui.graphics.Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp), color = Pal.Text2, fontSize = 11.sp) // (so the noise is not taken for a picture)
        }
        Text(if (pics.isEmpty()) "Received: none yet" else "Received (${pics.size}) - tap one", color = Pal.Muted, fontSize = 12.sp)
        LazyRow(Modifier.fillMaxWidth().height(76.dp).padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(pics, key = { it.name }) { f -> Thumb(f) { open = f } }
        }
    }
    open?.let { f -> PictureDialog(f) { open = null } }
}

/** A received picture, small. */
@Composable
private fun Thumb(f: File, onClick: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, f) { value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 }) } }
    Box(Modifier.size(width = 96.dp, height = 74.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), f.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** A received picture full size: Share, Save to Photos, Delete. */
@Composable
private fun PictureDialog(f: File, onClose: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val bmp by produceState<Bitmap?>(null, f) { value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path) } }
    var note by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf(false) }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Pal.Bg) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
                Text(f.name.removePrefix("SSTV_").removeSuffix(".png").replace('_', ' '), color = Pal.Cyan, fontWeight = FontWeight.Bold, fontSize = 15.sp) // date, time (UTC), mode
                Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    bmp?.let { Image(it.asImageBitmap(), f.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
                if (note.isNotEmpty()) Text(note, color = Pal.Green, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Button({ val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "uk.hamdigital.files", f) // to another app
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share the picture")) }) { Text("Share") }
                    if (Build.VERSION.SDK_INT >= 29) OutlinedButton({ scope.launch { note = withContext(Dispatchers.IO) { savePhoto(ctx, f) } } }) { Text("Save to Photos") }
                    OutlinedButton({ ask = true }) { Text("Delete", color = Pal.Red) }
                    TextButton(onClose) { Text("Close", color = Pal.Text2) }
                }
            }
        }
        if (ask) AlertDialog({ ask = false }, confirmButton = { TextButton({ SstvRx.delete(f); ask = false; onClose() }) { Text("Delete", color = Pal.Red) } },
            dismissButton = { TextButton({ ask = false }) { Text("Keep it") } }, title = { Text("Delete this picture?") })
    }
}

/** Copy a picture into Photos (Pictures/HF Digital Modes). Android 10 and newer. */
private fun savePhoto(ctx: android.content.Context, f: File): String = try {
    val v = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, f.name); put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HF Digital Modes")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: throw Exception("no room")
    ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
    "Saved to Photos (Pictures/HF Digital Modes)"
} catch (e: Exception) { "Could not save: ${e.message}" }

/** Send: the picture, mode, text, preview and Send. */
@Composable
private fun SstvSend(vm: MainViewModel, mod: Modifier) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsStateWithLifecycle()
    val gate = rememberTxGate(vm)                       // the licence notice before transmitting
    var src by remember { mutableStateOf<Bitmap?>(null) } // the picture chosen
    var opt by remember { mutableStateOf(SstvTx.modes.first()) } // the mode (Robot 36 to start)
    var top by remember(s.callsign) { mutableStateOf(s.callsign) } // over the picture: your call ..
    var bottom by remember(s.locator) { mutableStateOf(s.locator) } // .. and a line of text
    var msg by remember { mutableStateOf("") }          // what happened
    var busy by remember { mutableStateOf(false) }      // encoding
    val on by Transmitter.on.collectAsStateWithLifecycle()
    var started by remember { mutableLongStateOf(0L) }  // when the transmission began (for the progress bar)
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(on) { while (on) { now = System.currentTimeMillis(); delay(500) } }
    val pic = remember(src, opt, top, bottom) { src?.let { SstvTx.compose(it, opt, top, bottom) } } // what will be sent
    fun load(uri: Uri) { scope.launch { src = withContext(Dispatchers.IO) { loadBitmap(ctx, uri) }; if (src == null) msg = "That picture could not be read" } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) load(uri) }
    val photoFile = remember { File(ctx.cacheDir, "sstv_photo.jpg") }
    val photoUri = remember { androidx.core.content.FileProvider.getUriForFile(ctx, "uk.hamdigital.files", photoFile) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) load(photoUri) }
    Column(mod.verticalScroll(rememberScrollState())) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose a picture") }
            OutlinedButton({ camera.launch(photoUri) }) { Text("Take a photo") }
        }
        Text("Mode (picture size, time on the air)", color = Pal.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SstvTx.modes.forEach { o -> SmallChip("${o.name}  ${o.w}x${o.h}  ${o.seconds} s", o == opt) { opt = o } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            CompactField(top, { top = it.take(24) }, "Top text", Modifier.weight(1f))
            CompactField(bottom, { bottom = it.take(40) }, "Bottom text", Modifier.weight(1f))
        }
        Box(Modifier.fillMaxWidth().aspectRatio(opt.w.toFloat() / opt.h).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            if (pic != null) Image(pic.asImageBitmap(), "Picture to send", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Text("Choose a picture or take a photo. It is cropped to the mode's shape, with the text written over it.", color = Pal.Dim, fontSize = 13.sp)
        }
        if (on && started > 0) {                      // how far through the transmission
            LinearProgressIndicator({ ((now - started) / (opt.seconds * 1000f)).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(6.dp), color = Pal.Red, trackColor = Pal.Tert)
            Text("Sending ${opt.name}: ${((now - started) / 1000).coerceAtLeast(0)} of ${opt.seconds} s", color = Pal.Amber, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            Button({ val p = pic ?: return@Button; gate.ask {
                busy = true; msg = "Encoding..."
                scope.launch {
                    val ok = withContext(Dispatchers.Default) { SstvTx.send(ctx, s.callsign, p, opt, s.txLevel / 100f) }
                    busy = false; started = System.currentTimeMillis()
                    msg = if (ok) "Sending ${opt.name} (${opt.seconds} s)" else Transmitter.lastError.value
                } } }, enabled = pic != null && !busy && !on) { Text("Send") }
            Text(msg, color = if (msg.startsWith("Sending") || msg.startsWith("Encoding")) Pal.Amber else Pal.Red, fontSize = 13.sp)
        }
        Text("Send on the SSTV frequency (a band chip) - LSB on 80 and 40 m, USB above, as SSTV is sent. Listen first: a picture " +
            "takes the frequency for its whole length. Halt (the TRANSMITTING bar) stops it.", color = Pal.Muted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

/** A picture from the gallery or camera, at most 1600 pixels a side. */
private fun loadBitmap(ctx: android.content.Context, uri: Uri): Bitmap? = try {
    val b = if (Build.VERSION.SDK_INT >= 28) android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
        d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE                  // (drawable on a Canvas)
        val big = maxOf(info.size.width, info.size.height); if (big > 1600) d.setTargetSampleSize(big / 1600) }
    else ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    b?.let { SstvTx.shrink(it) }
} catch (e: Exception) { null }
