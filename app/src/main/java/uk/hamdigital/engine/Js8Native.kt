// JNI bridge to JS8Call's decoder and message unpacking (GPL v3; cpp/js8_jni.cpp, cpp/js8/).
package uk.hamdigital.engine

object Js8Native {
    init { System.loadLibrary("hamdigital") }         // the decoders library

    /** Decode one 15 s JS8 Normal slot of 12 kHz audio from its start, trying [nfqso] Hz first. Lines:
     *  "snr \t dt \t freq \t bits \t lowConfidence \t frameType \t from \t to \t message \t frame". */
    @JvmStatic external fun decode(samples: ShortArray, n: Int, nfqso: Int): Array<String>
}
