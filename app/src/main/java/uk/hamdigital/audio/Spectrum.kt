// The waterfall's spectrum: a Hann-windowed FFT of the receive audio, one row of 0..1 levels every [hop] samples, from
// 0 Hz to [maxHz]. Levels are in dB above the row's noise floor (its 20th-percentile bin), 0 to [rangeDb] dB, so the
// picture keeps its contrast whatever the volume.
package uk.hamdigital.audio

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

class Spectrum(val rate: Int, val size: Int = 2048, val hop: Int = 1024, val maxHz: Int = 3000, private val rangeDb: Float = 30f) {
    val bins = maxHz * size / rate                   // bins shown (0 .. maxHz)
    val rows = ConcurrentLinkedQueue<FloatArray>()    // new rows, for the screen to take
    private val win = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / size)).toFloat() } // Hann window
    private val ring = FloatArray(size)               // the last [size] samples
    private var fill = 0                              // samples since the last row
    private var pos = 0                               // write position in the ring
    private val re = FloatArray(size); private val im = FloatArray(size) // FFT work
    private val cosT = FloatArray(size / 2) { cos(2 * PI * it / size).toFloat() } // twiddles
    private val sinT = FloatArray(size / 2) { -sin(2 * PI * it / size).toFloat() }

    /** Audio in (the capture thread). */
    fun feed(s: ShortArray, n: Int) {
        for (i in 0 until n) {
            ring[pos] = s[i] / 32768f; pos = (pos + 1) % size // into the ring
            if (++fill >= hop) { fill = 0; row() }    // a row every hop
        }
    }

    private fun row() {
        for (i in 0 until size) { re[i] = ring[(pos + i) % size] * win[i]; im[i] = 0f } // oldest first, windowed
        fft()                                         // in place
        val p = FloatArray(bins) { val r = re[it]; val q = im[it]; 10 * log10(r * r + q * q + 1e-12f) } // power, dB
        val floor = p.copyOf().apply { sort() }[bins / 5] // noise floor
        rows.add(FloatArray(bins) { ((p[it] - floor) / rangeDb).coerceIn(0f, 1f) }) // 0..1
        while (rows.size > 64) rows.poll()           // the screen is not keeping up: drop the oldest
    }

    private fun fft() {                               // radix-2, in place (size is a power of two)
        var j = 0
        for (i in 1 until size) {                     // bit-reversed order
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val t = re[i]; re[i] = re[j]; re[j] = t; val u = im[i]; im[i] = im[j]; im[j] = u }
        }
        var len = 2
        while (len <= size) {                         // butterflies
            val step = size / len
            for (i in 0 until size step len) for (k in 0 until len / 2) {
                val wr = cosT[k * step]; val wi = sinT[k * step] // twiddle
                val a = i + k; val b = a + len / 2
                val tr = re[b] * wr - im[b] * wi; val ti = re[b] * wi + im[b] * wr
                re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
            }
            len = len shl 1
        }
    }
}
