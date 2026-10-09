// Development check (debug builds only, docs/DEV_COMMANDS.md): runs the decoders on recordings pushed to the app's
// private files folder (files/test/ft8, ft4, wspr, js8: *.wav, 12 kHz 16-bit mono) and writes what
// they decode to logcat (tag HDTEST), so the native decoders can be checked on the phone's own processor.
//   adb push <wav> /data/local/tmp/ ; adb shell run-as uk.hamdigital cp /data/local/tmp/<wav> files/test/ft8/
//   adb shell am start -n uk.hamdigital/.MainActivity --ez dev_test true
package uk.hamdigital

import android.content.Context
import android.util.Log
import uk.hamdigital.engine.Ft8Native
import uk.hamdigital.engine.Js8Native
import uk.hamdigital.engine.WsprNative
import java.io.File

object DevTest {
    private const val TAG = "HDTEST"                  // logcat tag

    private fun pcm(f: File): ShortArray {            // a WAV's samples (after its data chunk header)
        val b = f.readBytes(); var i = 12             // past RIFF....WAVE
        while (i + 8 <= b.size) {                     // chunks
            val len = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
            if (String(b, i, 4) == "data") { val n = minOf(len, b.size - i - 8) / 2; return ShortArray(n) { k -> ((b[i + 8 + 2 * k].toInt() and 0xFF) or (b[i + 9 + 2 * k].toInt() shl 8)).toShort() } }
            i += 8 + len
        }
        return ShortArray(0)
    }

    fun run(ctx: Context) = Thread {
        val dir = File(ctx.filesDir, "test")       // (the app's own folder: adb copies in with run-as)
        Log.i(TAG, "dev test: ${dir.absolutePath}")
        for (kind in listOf("ft8", "ft4", "js8", "wspr")) {
            dir.resolve(kind).listFiles { f -> f.name.endsWith(".wav", true) }?.sorted()?.forEach { f ->
                val t0 = System.currentTimeMillis()
                val lines: List<String> = when (kind) {
                    "ft8", "ft4" -> { val s = pcm(f); Ft8Native.decode(s, s.size, kind == "ft4").toList() }
                    "js8" -> { val s = pcm(f); Js8Native.decode(s, s.size, 1500).toList() }
                    else -> { val w = File(ctx.cacheDir, f.name); f.copyTo(w, true); val d = File(ctx.filesDir, "wsprd").apply { mkdirs() }
                              (WsprNative.decode(w.absolutePath, d.absolutePath, 14.0956) ?: "FAILED").lines().filter { it.isNotBlank() }.also { w.delete() } }
                }
                Log.i(TAG, "$kind ${f.name}: ${lines.size} in ${System.currentTimeMillis() - t0} ms")
                lines.forEach { Log.i(TAG, "  " + it.replace('\t', ' ')) }
            }
        }
        dir.resolve("adif").listFiles { f -> f.name.endsWith(".adi", true) }?.sorted()?.forEach { f -> // ADIF: read, write, read again - the same?
            val a = uk.hamdigital.core.Logbook.parse(f.readText())
            val b = uk.hamdigital.core.Logbook.parse(uk.hamdigital.core.Logbook.export(a))
            Log.i(TAG, "adif ${f.name}: ${a.size} contacts, round trip ${if (a.sortedBy { it.startMs } == b) "identical" else "DIFFERENT"}")
            a.forEach { q -> Log.i(TAG, "  ${q.call} ${q.bandName} ${q.mode} ${q.startMs} ${q.endMs} ${q.freqHz} s=${q.rstSent} r=${q.rstRcvd} ${q.grid} [${q.name}|${q.qth}|${q.power}|${q.comment}] me=${q.myCall}/${q.myGrid} extra=${q.extra}") }
            if (a.sortedBy { it.startMs } != b) a.sortedBy { it.startMs }.zip(b).filter { (x, y) -> x != y }.forEach { (x, y) -> Log.i(TAG, "  was $x\n  now $y") }
        }
        Log.i(TAG, "dev test done")
    }.start()
}
