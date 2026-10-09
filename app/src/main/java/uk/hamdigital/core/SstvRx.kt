// SSTV receive, with Robot36's decoder (Ahmet Inan, 0BSD; xdsopl.robot36): the page's audio goes in, scan lines come
// out - into the "scope" (every line, a picture or not) and, once a VIS code has named the mode, the picture itself. A
// finished picture is saved as a PNG in the app's files (sstv/) and joins the list of pictures received, newest first.
// Kept while the app runs; our own transmissions are not decoded (the radio passes our audio back).
package uk.hamdigital.core

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.audio.Transmitter
import xdsopl.robot36.SstvDecoder
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime

object SstvRx {
    const val RATE = 12000                            // the decoder's audio rate (the WiFi link's own)
    val dec = SstvDecoder(RATE)                       // Robot36
    val lines = MutableStateFlow(0)                   // scan lines decoded so far (the page redraws when it moves)
    val mode = MutableStateFlow("")                   // the mode heard
    val pictures = MutableStateFlow<List<File>>(emptyList()) // pictures received, newest first
    val saved = MutableStateFlow("")                  // the last picture saved (its name)
    private var dir: File? = null                     // where they go
    private var f = FloatArray(0)                     // (a block, as floats)

    fun attach(ctx: Context) {                        // (once: the pictures already received)
        if (dir != null) return
        dir = File(ctx.filesDir, "sstv").apply { mkdirs() }
        pictures.value = dir!!.listFiles { x -> x.name.endsWith(".png") }?.sortedByDescending { it.name } ?: emptyList()
    }

    /** Audio in (the capture thread): 12 kHz mono. */
    fun feed(b: ShortArray, n: Int) {
        val now = System.currentTimeMillis()
        if (Transmitter.sentDuring(now - 600, now)) return // (our own picture going out)
        if (f.size != n) f = FloatArray(n)
        for (i in 0 until n) f[i] = b[i] / 32768f
        if (!dec.process(f)) return                   // no new lines
        lines.value++; mode.value = dec.modeName()
        val img = dec.image
        if (img.line >= img.height && img.height > 0) { // a picture finished: keep it
            save(Bitmap.createBitmap(img.pixels, img.width, img.height, Bitmap.Config.ARGB_8888), dec.modeName())
            img.line = -1                             // (Robot36 does the same: so it is saved once)
        }
    }

    private fun save(bmp: Bitmap, modeName: String) {
        val d = dir ?: return
        val t = ZonedDateTime.now(ZoneOffset.UTC)
        val file = File(d, "SSTV_%04d%02d%02d_%02d%02d%02d_%s.png".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second, modeName.replace(' ', '_')))
        try {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            pictures.value = (listOf(file) + pictures.value).take(500); saved.value = file.name
        } catch (e: Exception) { saved.value = "Could not save the picture: ${e.message}" }
    }

    /** Forget a picture (and delete its file). */
    fun delete(file: File) { file.delete(); pictures.value = pictures.value - file }
}
