// JNI bridge to codec2's FreeDV API (LGPL 2.1) for FreeDV digital voice (cpp/freedv_jni.c, cpp/codec2/).
package uk.hamdigital.engine

object FreeDvNative {
    init { System.loadLibrary("hamdigital") }         // the decoders library

    const val MODE_1600 = 0; const val MODE_700D = 7; const val MODE_700E = 13 // FreeDV's mode numbers

    /** A session (modem + codec) for [mode]; 0 if it could not be opened. */
    @JvmStatic external fun open(mode: Int): Long
    @JvmStatic external fun close(h: Long)
    /** [nin (modem samples rx wants next), speech samples a frame, most speech rx gives, modem samples tx gives, modem rate, speech rate] */
    @JvmStatic external fun sizes(h: Long): IntArray
    /** Receive exactly nin modem samples; the speech decoded (may be empty). */
    @JvmStatic external fun rx(h: Long, modem: ShortArray): ShortArray
    /** Transmit one frame of speech (a frame's samples); its modem audio. */
    @JvmStatic external fun tx(h: Long, speech: ShortArray): ShortArray
    /** [sync 0/1, SNR estimate dB, rx status flags] */
    @JvmStatic external fun stats(h: Long): FloatArray
    /** Text received since the last call. */
    @JvmStatic external fun text(h: Long): String
    /** Text to send, repeated while transmitting. */
    @JvmStatic external fun setText(h: Long, text: String)
    /** Squelch on / off and its SNR threshold (dB). */
    @JvmStatic external fun squelch(h: Long, on: Boolean, db: Float)
}
