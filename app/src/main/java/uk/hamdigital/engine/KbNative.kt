// JNI bridge to fldigi's RTTY, PSK31 / 63 / 125 and Olivia modems (GPL v3; cpp/fldigi_jni.cpp). "Kb" = the keyboard modes.
package uk.hamdigital.engine

object KbNative {
    init { System.loadLibrary("hamdigital") }         // the decoders library

    const val RTTY = 0                                // mode numbers
    const val PSK31 = 1                               // (PSK31 / 63 / 125)
    const val OLIVIA = 2                              // (Olivia, its tones / bandwidth)

    @JvmStatic external fun process(mode: Int, samples: ShortArray, n: Int) // 8 kHz mono audio
    /** [frequency Hz, metric 0..100, s/n dB (Olivia: sync S/N), dcd 0/1, imd dB (Olivia: offset found, Hz)]. */
    @JvmStatic external fun state(mode: Int): DoubleArray
    @JvmStatic external fun text(mode: Int): String  // decoded since the last call
    /** 0 frequency, 1 AFC on/off, 2 squelch 0..100, 3 reverse (RTTY), 4 reset, 5 RTTY shift (Hz), 7 PSK speed (31, 63, 125), 8 Olivia tones x 10000 + bandwidth. */
    @JvmStatic external fun control(mode: Int, what: Int, value: Double)
    /** Transmit audio for a whole message (8 kHz): RTTY (centre [f0], [shift] Hz, [rate] baud) or PSK (carrier [f0], [rate] = speed 31 / 63 / 125) or Olivia (centre [f0], [shift] = tones, [rate] = bandwidth); peak [amplitude] 0..1. */
    @JvmStatic external fun encode(mode: Int, text: String, f0: Double, shift: Double, rate: Double, amplitude: Double): ShortArray
}
