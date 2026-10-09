// JNI bridge to ft8_lib (MIT) for FT8 and FT4 (cpp/ft8_jni.c).
package uk.hamdigital.engine

object Ft8Native {
    init { System.loadLibrary("hamdigital") }         // the decoders library

    /** Decode one slot of 12 kHz audio from its start: "snr\tdt\tfreq\ttext" per message. */
    @JvmStatic external fun decode(samples: ShortArray, n: Int, ft4: Boolean): Array<String>

    /** Transmit audio for a message: 12 kHz, base tone [f0] Hz, peak [amplitude] 0..1; null if it cannot be sent. */
    @JvmStatic external fun encode(text: String, ft4: Boolean, f0: Float, amplitude: Float): ShortArray?
}
