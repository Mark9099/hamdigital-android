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

    /** SSTV round trip, every mode: colour bars and a gradient, with "M7JVY" over them, encoded at 12 kHz and fed to a
     *  fresh Robot36 decoder in 50 ms blocks (Robot 36 also with noise added); the mode it names, the size, and the mean
     *  difference per colour channel from the picture sent (0-255). */
    private fun sstv() {
        val bars = android.graphics.Bitmap.createBitmap(640, 480, android.graphics.Bitmap.Config.ARGB_8888)
        val cols = intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF00.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF000000.toInt())
        for (y in 0 until 480) for (x in 0 until 640) bars.setPixel(x, y, if (y < 320) cols[x * 8 / 640] else android.graphics.Color.rgb(x * 255 / 639, x * 255 / 639, x * 255 / 639))
        val rnd = java.util.Random(1)
        for (o in uk.hamdigital.core.SstvTx.modes) for (noise in if (o.name == "Robot 36") listOf(0f, 0.05f, 0.1f, 0.2f, 0.3f, 0.5f) else listOf(0f)) {
            val t0 = System.currentTimeMillis()
            val pic = uk.hamdigital.core.SstvTx.compose(bars, o, "M7JVY", "IO91")
            val audio = uk.hamdigital.core.SstvTx.encode(pic, o, 0.5f)
            val d = xdsopl.robot36.SstvDecoder(uk.hamdigital.core.SstvTx.RATE)
            val blk = FloatArray(600); var done = false; var i = 0
            while (i < audio.size + 2 * 12000 && !done) { // (2 s of silence after, to flush)
                for (k in 0 until 600) blk[k] = (if (i + k < audio.size) audio[i + k] / 32768f else 0f) + if (noise > 0) (rnd.nextGaussian() * noise * 0.5).toFloat() else 0f
                d.process(blk); i += 600
                if (d.image.line >= d.image.height && d.image.height > 0) done = true
            }
            val im = d.image; var diff = 0L; var n = 0
            if (done) for (y in 0 until minOf(im.height, o.h)) for (x in 0 until minOf(im.width, o.w)) {
                val a = im.pixels[y * im.width + x]; val b = pic.getPixel(x, y)
                diff += kotlin.math.abs((a shr 16 and 255) - (b shr 16 and 255)) + kotlin.math.abs((a shr 8 and 255) - (b shr 8 and 255)) + kotlin.math.abs((a and 255) - (b and 255)); n += 3
            }
            Log.i(TAG, "sstv ${o.name}${if (noise > 0) " +noise %.0f dB in 3 kHz".format(10 * Math.log10(0.125 / ((noise * 0.5) * (noise * 0.5) * 0.5))) else ""}: ${audio.size / 12000.0}s audio -> ${if (done) "${d.modeName()} ${im.width}x${im.height}, mean diff ${if (n > 0) diff / n else -1}" else "NO PICTURE (mode ${d.modeName()}, line ${im.line})"} in ${System.currentTimeMillis() - t0} ms")
        }
    }

    /** FreeDV round trip (as tools/test/test_freedv.c, through the JNI): 8 kHz speech [f] sent in each mode (tx), noise
     *  added (5 dB in 3 kHz), received (rx): frames in sync, the SNR estimate, the speech's level against the input's. */
    private fun freedv(f: File) {
        val b = f.readBytes(); val speech = ShortArray(b.size / 2) { ((b[2 * it].toInt() and 0xFF) or (b[2 * it + 1].toInt() shl 8)).toShort() }
        val rnd = java.util.Random(2)
        for (m in uk.hamdigital.core.FreeDv.FdMode.entries) {
            val t0 = System.currentTimeMillis()
            val tx = uk.hamdigital.engine.FreeDvNative.open(m.code); val rx = uk.hamdigital.engine.FreeDvNative.open(m.code)
            uk.hamdigital.engine.FreeDvNative.squelch(rx, false, 0f)
            val nsp = uk.hamdigital.engine.FreeDvNative.sizes(tx)[1]
            val mod = ArrayList<Short>(); var i = 0
            while (i < speech.size + 4 * nsp) { val fr = ShortArray(nsp) { k -> if (i + k < speech.size) speech[i + k] else 0 }; uk.hamdigital.engine.FreeDvNative.tx(tx, fr).forEach { mod.add(it) }; i += nsp }
            var p = 0.0; mod.forEach { p += it * it.toDouble() }; p /= mod.size
            val sd = Math.sqrt(p / Math.pow(10.0, 0.5) * 4000.0 / 3000.0) // noise for 5 dB in 3 kHz
            val noisy = ShortArray(mod.size) { (mod[it] + rnd.nextGaussian() * sd).toInt().coerceIn(-32768, 32767).toShort() }
            var pos = 0; var frames = 0; var synced = 0; var eout = 0.0; var nout = 0; var snr = 0f
            while (true) { val nin = uk.hamdigital.engine.FreeDvNative.sizes(rx)[0]; if (pos + nin > noisy.size) break
                val out = uk.hamdigital.engine.FreeDvNative.rx(rx, noisy.copyOfRange(pos, pos + nin)); pos += nin; frames++
                val st = uk.hamdigital.engine.FreeDvNative.stats(rx); if (st[0] > 0.5f) synced++; snr = st[1]
                out.forEach { eout += it * it.toDouble() }; nout += out.size }
            var ein = 0.0; speech.forEach { ein += it * it.toDouble() }
            Log.i(TAG, "freedv ${m.label} (${f.name}): sync $synced of $frames, SNR est %.1f dB, speech %d samples at %+.1f dB of the input, %d ms".format(
                snr, nout, if (nout > 0) 10 * Math.log10((eout / nout) / (ein / speech.size)) else -99.0, System.currentTimeMillis() - t0))
            uk.hamdigital.engine.FreeDvNative.close(tx); uk.hamdigital.engine.FreeDvNative.close(rx)
        }
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
        if (dir.resolve("sstv").exists()) sstv()       // SSTV: each mode encoded (SSTV Encoder 2) and decoded (Robot36) - the same picture back?
        dir.resolve("freedv").listFiles { f -> f.name.endsWith(".raw") }?.forEach { freedv(it) } // FreeDV: speech through each mode and back
        Log.i(TAG, "dev test done")
    }.start()
}
