// JNI bridge to JS8Call's decoder and message unpacking (GPL v3; cpp/js8_jni.cpp, cpp/js8/).
package uk.hamdigital.engine

object Js8Native {
    init { System.loadLibrary("hamdigital") }         // the decoders library

    /** Decode one 15 s JS8 Normal slot of 12 kHz audio from its start, trying [nfqso] Hz first. Lines:
     *  "snr \t dt \t freq \t bits \t lowConfidence \t frameType \t from \t to \t message \t frame". */
    @JvmStatic external fun decode(samples: ShortArray, n: Int, nfqso: Int): Array<String>

    /** Frames for a message: kind 0 heartbeat, 1 CQ ([cmd] = which CQ), 2 directed to [to] (command [cmd], [num], [text]),
     *  3 [text] to everyone. Each "frame TAB bits"; sent in consecutive slots. */
    @JvmStatic external fun build(kind: Int, myCall: String, grid: String, to: String, cmd: Int, num: String, text: String): Array<String>
    /** JS8 Normal audio for one frame from [f0] Hz (12 kHz). */
    @JvmStatic external fun audio(frame: String, bits: Int, f0: Double, amplitude: Double): ShortArray
}
