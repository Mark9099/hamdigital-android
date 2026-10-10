// JNI bridge to FreeDATA's modems (codec2's FreeDV data API, LGPL 2.1; cpp/fdata_jni.c): DATAC13 / DATAC14 receive,
// one frame's transmit audio.
package uk.hamdigital.engine

object FreeDataNative {
    init { System.loadLibrary("hamdigital") }         // the decoders library

    const val DATAC13 = 19                            // FreeDATA's "signalling" mode
    const val DATAC14 = 20                            // its "signalling ack" mode

    /** Audio in: 8 kHz mono. */
    @JvmStatic external fun process(samples: ShortArray, n: Int)
    /** Frames received since the last call (CRC good): [mode][snr x10, signed][length][bytes] one after another. */
    @JvmStatic external fun take(): ByteArray
    /** [DATAC13 receiver in sync 0/1, its SNR dB] */
    @JvmStatic external fun stats(): FloatArray
    /** The audio (8 kHz) for one frame in [mode]: preamble, the frame (padded) with its CRC16, postamble; null if it cannot. */
    @JvmStatic external fun encode(mode: Int, frame: ByteArray): ShortArray?
}
