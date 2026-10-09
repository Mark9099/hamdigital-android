// JNI bridge to FreeDV RADE V1 (cpp/rade_jni.c, cpp/rade/: rade_c BSD-2, Opus BSD-3), in its own library (libhamrade).
// The same calls as FreeDvNative, so the FreeDV page drives both alike; speech is 16 kHz here, the modem 8 kHz.
package uk.hamdigital.engine

object RadeNative {
    init { System.loadLibrary("hamrade") }           // RADE + the Opus parts it uses (with their weights)

    /** A RADE V1 session; 0 if it could not be opened. */
    @JvmStatic external fun open(): Long
    @JvmStatic external fun close(h: Long)
    /** [nin (modem samples rx wants next), speech samples a transmit frame, most speech rx gives, modem samples tx gives, modem rate, speech rate] */
    @JvmStatic external fun sizes(h: Long): IntArray
    /** Receive exactly nin modem samples; the speech decoded (16 kHz; empty without a signal). */
    @JvmStatic external fun rx(h: Long, modem: ShortArray): ShortArray
    /** Transmit one frame of speech (sizes[1] samples, 16 kHz); its modem audio (8 kHz). */
    @JvmStatic external fun tx(h: Long, speech: ShortArray): ShortArray
    /** The end-of-over frame (carrying the callsign): sent last, when the talk button is let go. */
    @JvmStatic external fun txEnd(h: Long): ShortArray
    /** [sync 0/1, SNR estimate dB, frequency offset Hz] */
    @JvmStatic external fun stats(h: Long): FloatArray
    /** Callsigns received since the last call, each ending '\r'. */
    @JvmStatic external fun text(h: Long): String
    /** The callsign sent in the end-of-over frame (up to 8 characters). */
    @JvmStatic external fun setText(h: Long, text: String)
}
