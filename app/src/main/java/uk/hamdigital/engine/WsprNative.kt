// JNI bridge to wsprd from WSJT-X (GPL v3) for WSPR (cpp/wspr_run.c, wspr_jni.c).
package uk.hamdigital.engine

object WsprNative {
    init { System.loadLibrary("hamdigital") }         // the decoders library

    /** Decode a 2-minute slot's WAV (12 kHz, yymmdd_hhmm.wav); wsprd keeps its files in dataDir. Returns wsprd's spots
     *  ("date time sync snr dt freq message drift cycles jitter" a line), or null if it failed. */
    @JvmStatic external fun decode(wavPath: String, dataDir: String, dialMHz: Double): String?
}
