// JNI bridge to the CW (Morse) decoder engine shared with the Tab5 (HamPropCore hamprop_common/src/cw_decoder.cpp).
package uk.hamdigital.engine

object CwNative {
    init { System.loadLibrary("hamdigital") }         // the decoders library

    @JvmStatic external fun reset()                   // a fresh decoder
    @JvmStatic external fun process(samples: ShortArray, n: Int) // 16 kHz mono 16-bit audio
    /** [locked, toneOn, lockedBin, wpm, toneHz, snrDb, sensitivity, startWpm, follow, noiseFloor, bin 0 .. 13 power]. */
    @JvmStatic external fun state(): FloatArray
    @JvmStatic external fun text(): String           // the transcript
    @JvmStatic external fun symbol(): String         // the letter in progress (dots and dashes)
    /** 0 clear, 1 auto tune, 2 reset speed, 3 lock bin [value], 4 sensitivity [value 0..1], 5 start WPM [value], 6 follow tone [value 0 / 1]. */
    @JvmStatic external fun control(what: Int, value: Float)

    const val BINS = 14                               // 350-1000 Hz in 50 Hz steps
}
