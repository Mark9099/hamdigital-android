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

    /** FreeDV round trip (as tools/test/test_freedv.c and test_rade.c, through the JNI): 8 kHz speech [f] sent in each
     *  mode (tx; RADE: doubled to 16 kHz, callsign M7JVY in the end-of-over frame), noise added (5 dB in 3 kHz), received
     *  (rx): frames in sync, the SNR estimate, the speech's level against the input's, the text / callsign received. */
    private fun freedv(f: File) {
        val b = f.readBytes(); val speech8 = ShortArray(b.size / 2) { ((b[2 * it].toInt() and 0xFF) or (b[2 * it + 1].toInt() shl 8)).toShort() }
        val speech16 = ShortArray(speech8.size * 2) { k -> val a = speech8[k / 2]; if (k % 2 == 0) a else ((a + speech8[minOf(k / 2 + 1, speech8.size - 1)]) / 2).toShort() } // (linear, 8 -> 16 kHz)
        val rnd = java.util.Random(2)
        for (m in uk.hamdigital.core.FreeDv.FdMode.entries) {
            val t0 = System.currentTimeMillis(); val e = m.engine
            val speech = if (m.speechRate == 16000) speech16 else speech8
            val tx = e.open(); val rx = e.open()
            e.squelch(rx, false, 0f); e.setText(tx, "M7JVY")
            val nsp = e.sizes(tx)[1]
            val mod = ArrayList<Short>(); var i = 0
            while (i < speech.size + 4 * nsp) { val fr = ShortArray(nsp) { k -> if (i + k < speech.size) speech[i + k] else 0 }; e.tx(tx, fr).forEach { mod.add(it) }; i += nsp }
            e.txEnd(tx).forEach { mod.add(it) }; repeat(8000) { mod.add(0) } // the end of the over, and a second after
            val tEnc = System.currentTimeMillis() - t0
            var p = 0.0; mod.forEach { p += it * it.toDouble() }; p /= mod.size
            val sd = Math.sqrt(p / Math.pow(10.0, 0.5) * 4000.0 / 3000.0) // noise for 5 dB in 3 kHz
            val noisy = ShortArray(mod.size) { (mod[it] + rnd.nextGaussian() * sd).toInt().coerceIn(-32768, 32767).toShort() }
            var pos = 0; var frames = 0; var synced = 0; var eout = 0.0; var nout = 0; var snr = 0f; var text = ""
            while (true) { val nin = e.sizes(rx)[0]; if (pos + nin > noisy.size) break
                val out = e.rx(rx, noisy.copyOfRange(pos, pos + nin)); pos += nin; frames++
                val st = e.stats(rx); if (st[0] > 0.5f) synced++; snr = st[1]; text += e.text(rx)
                out.forEach { eout += it * it.toDouble() }; nout += out.size }
            var ein = 0.0; speech.forEach { ein += it * it.toDouble() }
            Log.i(TAG, "freedv ${m.label} (${f.name}): sync $synced of $frames, SNR est %.1f dB, speech %d samples at %+.1f dB of the input, text [%s], tx %d ms, all %d ms for %.1f s".format(
                snr, nout, if (nout > 0) 10 * Math.log10((eout / nout) / (ein / speech.size)) else -99.0, text.trim().replace('\r', '|'), tEnc, System.currentTimeMillis() - t0, mod.size / 8000.0))
            e.close(tx); e.close(rx)
        }
    }

    /** A RADE recording (8 kHz WAV, e.g. rade_c's FDV_offair.wav resampled) decoded as the FreeDV page does: callsigns,
     *  frames in sync, speech made, and how long it took against the recording's length (real time?). */
    private fun rade(f: File) {
        val s = pcm(f); val e = uk.hamdigital.core.FreeDv.FdMode.RADE.engine; val h = e.open()
        val t0 = System.currentTimeMillis(); var pos = 0; var frames = 0; var synced = 0; var nout = 0L; var text = ""
        while (true) { val nin = e.sizes(h)[0]; if (pos + nin > s.size) break
            nout += e.rx(h, s.copyOfRange(pos, pos + nin)).size; pos += nin; frames++
            if (e.stats(h)[0] > 0.5f) synced++; text += e.text(h) }
        val ms = System.currentTimeMillis() - t0
        Log.i(TAG, "rade ${f.name}: %.1f s, sync %d of %d frames, %.1f s of speech, callsigns [%s], %d ms (%.0f%% of real time)".format(
            s.size / 8000.0, synced, frames, nout / 16000.0, text.trim().replace('\r', '|'), ms, 100.0 * ms / (s.size / 8.0)))
        e.close(h)
    }

    /** Keyboard modes through the JNI, as the page does: each mode's transmit audio (RTTY, PSK31 / 63 / 125, Olivia's
     *  tones / bandwidths) with a little noise and 5 s of noise after, fed back to its receiver 256 samples at a time. */
    private fun kb() {
        val t = "CQ CQ DE M7JVY M7JVY IO91 K"; val rnd = java.util.Random(5); val K = uk.hamdigital.engine.KbNative
        val runs = listOf(Triple(K.RTTY, 170.0, 45.45), Triple(K.PSK31, 0.0, 31.0), Triple(K.PSK31, 0.0, 63.0), Triple(K.PSK31, 0.0, 125.0)) +
            listOf(4 to 125, 8 to 250, 8 to 500, 16 to 500, 16 to 1000, 32 to 1000).map { Triple(K.OLIVIA, it.first.toDouble(), it.second.toDouble()) }
        for ((k, a1, a2) in runs) {
            val t0 = System.currentTimeMillis()
            when (k) { K.PSK31 -> K.control(k, 7, a2); K.OLIVIA -> { K.control(k, 8, a1 * 10000 + a2); K.control(k, 2, 5.0) }; else -> K.control(k, 5, a1) }
            K.control(k, 0, 1500.0); K.control(k, 4, 0.0); K.text(k)
            val a = K.encode(k, if (k == K.RTTY) "\n$t\n" else " $t ", 1500.0, a1, a2, 0.3)
            val s = ShortArray(a.size + 5 * 8000) { i -> ((if (i < a.size) a[i].toInt() else 0) + rnd.nextGaussian() * 300).toInt().coerceIn(-32768, 32767).toShort() }
            var got = ""; var i = 0
            while (i < s.size) { val n = minOf(256, s.size - i); K.process(k, s.copyOfRange(i, i + n), n); got += K.text(k); i += n }
            Log.i(TAG, "kb ${when (k) { K.RTTY -> "RTTY"; K.PSK31 -> "PSK${a2.toInt()}"; else -> "Olivia ${a1.toInt()}/${a2.toInt()}" }}: %.1f s -> [%s] in %d ms".format(a.size / 8000.0, got.trim().replace('\n', '|'), System.currentTimeMillis() - t0))
        }
        K.control(K.PSK31, 7, 31.0); K.control(K.OLIVIA, 8, 80250.0); K.control(K.OLIVIA, 0, 1500.0) // (back to the pages' starting settings)
    }

    /** A weather-fax recording (11025 Hz WAV, e.g. tools/test's wefax_broadcast.wav) through the JNI as the page feeds it:
     *  the receiver's states, the chart kept (size), and the time taken against the recording's length. */
    private fun wefax(f: File) {
        val s = pcm(f); val W = uk.hamdigital.engine.WefaxNative; val size = IntArray(2)
        W.control(3, 576.0); W.control(0, 1900.0); W.control(5, 0.0) // (a fresh start)
        val t0 = System.currentTimeMillis(); var last = -1; val states = StringBuilder(); var kept = ""
        var i = 0
        while (i < s.size) { val n = minOf(512, s.size - i); W.process(s.copyOfRange(i, i + n), n); i += n
            val st = W.state()[0]; if (st != last) { last = st; states.append("${arrayOf("APTstart", "APTstop", "phasing", "image", "idle")[st]}@${i / 11025}s ") }
            W.finished(size)?.let { kept += "${size[0]}x${size[1]} at ${i / 11025}s " } }
        Log.i(TAG, "wefax ${f.name}: %.0f s -> %s| kept: %s| %d ms".format(s.size / 11025.0, states, kept.ifEmpty { "none" }, System.currentTimeMillis() - t0))
    }

    /** APRS / packet through Dire Wolf (the JNI): three frames sent at 1200 and 300 baud at 12 kHz, noise added at several
     *  SNRs, received again: how many come back, and (at the best SNR) each decode line. */
    private fun aprs() {
        val frames = listOf("M7JVY-7>APDR16,WIDE1-1,WIDE2-1:=5130.75N/00012.25W>Mobile, HF Digital Modes test",
            "M7JVY>APRS,WIDE2-1::G4ABC    :Hello from the app{01",
            "M7JVY-10>APRS,TCPIP*:@101215z5130.00N/00010.00W_090/005g010t055r000p000P000h80b10132")
        val A = uk.hamdigital.engine.AprsNative; val rnd = java.util.Random(6); val rate = 12000
        for (baud in listOf(1200, 300)) for (snr in listOf(20.0, 10.0, 5.0, 0.0)) {
            A.init(baud, rate); var got = 0; val t0 = System.currentTimeMillis(); var secs = 0.0
            for (f in frames) {
                val a = A.encode(f, baud, rate, 0.5) ?: run { Log.i(TAG, "aprs: not a frame: $f"); null } ?: continue
                var p = 0.0; a.forEach { p += it * it.toDouble() }; p /= a.size
                val sd = Math.sqrt(p / Math.pow(10.0, snr / 10) * (rate / 2.0) / 2500.0)
                val s = ShortArray(a.size) { (a[it] + rnd.nextGaussian() * sd).toInt().coerceIn(-32768, 32767).toShort() }
                A.process(s, s.size); secs += s.size / rate.toDouble()
                val out = A.take(); if (out.isNotEmpty()) { got++; if (snr == 20.0) out.trim().lines().forEach { Log.i(TAG, "  " + it.replace('\t', '|')) } }
            }
            Log.i(TAG, "aprs $baud baud, SNR %+.0f dB: %d of %d frames (%.1f s of audio, %d ms)".format(snr, got, frames.size, secs, System.currentTimeMillis() - t0))
        }
    }

    /** FreeDATA: the frame fields against FreeDATA's own helpers.py results (run on a PC: callsign M7JVY-0 = 0007476a6a40,
     *  IO91CC = 13c5b042, CRC-24 of M7JVY-0 = 0f7cbf), then a CQ and a ping sent in DATAC13 with noise and received. */
    private fun freedata() {
        val F = uk.hamdigital.core.FreeData; val N = uk.hamdigital.engine.FreeDataNative
        fun hx(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        Log.i(TAG, "freedata call ${hx(F.encodeCall("M7JVY-0"))} (0007476a6a40), decode ${F.decodeCall(F.encodeCall("DJ2LS-3"), 0)} (DJ2LS-3), grid ${hx(F.encodeGrid("IO91CC"))} (13c5b042), ${F.decodeGrid(F.encodeGrid("JN48LL"), 0)} (JN48LL), crc24 ${hx(F.crc24("M7JVY-0"))} (0f7cbf)")
        F.myCall = "M7JVY"; F.myGrid = "IO91CC"; F.ssid = 0
        val rnd = java.util.Random(8)
        for (snr in listOf(10.0, 0.0, -5.0)) {
            var got = ""
            for ((what, fr) in listOf("CQ" to F.buildCq(), "ping" to F.buildPing("M7JVY-0"))) {
                val a = N.encode(N.DATAC13, fr) ?: continue
                var p = 0.0; a.forEach { p += it * it.toDouble() }; p /= a.size
                val sd = Math.sqrt(p / Math.pow(10.0, snr / 10) * 4000.0 / 2500.0)
                val s = ShortArray(a.size + 16000) { i -> ((if (i in 4000 until 4000 + a.size) a[i - 4000].toInt() else 0) + rnd.nextGaussian() * sd).toInt().coerceIn(-32768, 32767).toShort() }
                var i = 0; while (i < s.size) { val n = minOf(320, s.size - i); N.process(s.copyOfRange(i, i + n), n); i += n }
                val r = N.take(); var k = 0
                while (k + 3 <= r.size) { val len = r[k + 2].toInt() and 0xFF; F.parse(r[k].toInt() and 0xFF, r[k + 1] / 10.0, r.copyOfRange(k + 3, k + 3 + len), 0)?.let { got += "[$what -> ${it.from} ${it.grid} ${it.text} %.0f dB] ".format(it.snr) }; k += 3 + len }
            }
            Log.i(TAG, "freedata DATAC13 SNR %+.0f dB: %s".format(snr, got.ifEmpty { "nothing" }))
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
        dir.resolve("rade").listFiles { f -> f.name.endsWith(".wav", true) }?.forEach { rade(it) } // RADE: a recording off air
        if (dir.resolve("kb").exists()) kb()           // RTTY / PSK / Olivia: send and receive through the JNI
        dir.resolve("wefax").listFiles { f -> f.name.endsWith(".wav", true) }?.forEach { wefax(it) } // weather fax: a broadcast
        if (dir.resolve("aprs").exists()) aprs()       // APRS / packet: send and receive through Dire Wolf
        if (dir.resolve("freedata").exists()) freedata() // FreeDATA: frame fields, and DATAC13 through the modem
        Log.i(TAG, "dev test done")
    }.start()
}
