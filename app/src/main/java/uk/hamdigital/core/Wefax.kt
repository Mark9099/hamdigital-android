// Weather fax (WEFAX / HF fax) receive, with fldigi's receiver (WefaxNative): the page's audio goes in (11025 Hz);
// each picture the receiver thinks worth keeping (fldigi's rules: big enough, a real picture, not blank or noise) is
// saved as a PNG in the app's files (wefax/) and joins the list of charts received, newest first. Saving happens on a
// thread of its own, not the audio thread. Kept while the app runs.
package uk.hamdigital.core

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.engine.WefaxNative
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.Executors

object Wefax {
    val pictures = MutableStateFlow<List<File>>(emptyList()) // charts received, newest first
    val saved = MutableStateFlow("")                  // the last one saved (its name), or why it could not be
    var station = ""                                  // the station tuned (a band chip), for the file name
    private var dir: File? = null                     // where they go
    private val saver = Executors.newSingleThreadExecutor() // (PNG writing off the audio thread)
    private val size = IntArray(2)

    fun attach(ctx: Context) {                        // (once: the charts already received)
        if (dir != null) return
        dir = File(ctx.filesDir, "wefax").apply { mkdirs() }
        pictures.value = dir!!.listFiles { x -> x.name.endsWith(".png") }?.sortedByDescending { it.name } ?: emptyList()
    }

    /** Audio in (the capture thread): 11025 Hz mono. */
    fun feed(b: ShortArray, n: Int) {
        WefaxNative.process(b, n)
        WefaxNative.finished(size)?.let { px -> val w = size[0]; val h = size[1]; saver.execute { save(px, w, h) } } // a finished picture
    }

    private fun save(px: ByteArray, w: Int, h: Int) {
        val d = dir ?: return
        if (w <= 0 || h <= 0) return
        val argb = IntArray(w * h) { i -> val g = px[i].toInt() and 255; (0xFF shl 24) or (g shl 16) or (g shl 8) or g } // grey
        val t = ZonedDateTime.now(ZoneOffset.UTC)
        val name = "WEFAX_%04d%02d%02d_%02d%02d%s.png".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, if (station.isEmpty()) "" else "_" + station.replace(Regex("[^A-Za-z0-9.]+"), "_"))
        val file = File(d, name)
        try {
            file.outputStream().use { Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
            pictures.value = (listOf(file) + pictures.value.filter { it != file }).take(300); saved.value = file.name
        } catch (e: Exception) { saved.value = "Could not save the chart: ${e.message}" }
    }

    /** Forget a chart (and delete its file). */
    fun delete(file: File) { file.delete(); pictures.value = pictures.value - file }
}
