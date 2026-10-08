// JNI bridge to ft8_lib (MIT) for FT8 and FT4 (cpp/ft8_jni.c).
package uk.hamdigital.engine

object Ft8Native {
    init { System.loadLibrary("hamdigital") }         // the decoders library

    /** Decode one slot of 12 kHz audio from its start: "snr\tdt\tfreq\ttext" per message. */
    @JvmStatic external fun decode(samples: ShortArray, n: Int, ft4: Boolean): Array<String>
}
