// JNI bridge to the weather-fax receiver (fldigi's wefax, receive only, GPL v3; cpp/wefax_jni.cpp).
package uk.hamdigital.engine

object WefaxNative {
    init { System.loadLibrary("hamdigital") }         // the decoders library

    const val RATE = 11025                            // fldigi's WEFAX sample rate

    /** Audio in: 11025 Hz mono. */
    @JvmStatic external fun process(samples: ShortArray, n: Int)
    /** [state (0 waiting for APT start, 1 APT stop, 2 phasing, 3 picture, 4 idle), rows so far, width, correlation x 1000, APT rate Hz, lines a minute] */
    @JvmStatic external fun state(): IntArray
    /** The picture's grey pixels from row [from] to the last row so far (the last may be part done). */
    @JvmStatic external fun rows(from: Int): ByteArray
    /** A finished picture worth keeping (once): its pixels, [size] = width, height; null if none. */
    @JvmStatic external fun finished(size: IntArray): ByteArray?
    /** 0 carrier Hz, 1 shift Hz, 2 input filter (0 narrow, 1 medium, 2 wide), 3 IOC (576 / 288), 4 start a picture now, 5 end it now. */
    @JvmStatic external fun control(what: Int, value: Double)
}
