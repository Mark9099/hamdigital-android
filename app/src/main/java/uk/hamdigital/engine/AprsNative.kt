// JNI bridge to Dire Wolf (GPL v2 or later; cpp/aprs_jni.c, cpp/direwolf): APRS and AX.25 packet, 1200 baud (VHF / UHF
// FM) or 300 baud (HF).
package uk.hamdigital.engine

object AprsNative {
    init { System.loadLibrary("hamdigital") }         // the decoders library

    /** Set the receiver up for [baud] (300 / 1200) at [rate] samples a second. */
    @JvmStatic external fun init(baud: Int, rate: Int)
    /** Audio in. */
    @JvmStatic external fun process(samples: ShortArray, n: Int)
    /** Frames decoded since the last call: one a line, tab-separated - monitor text (SRC>DEST,PATH:info), heard from,
     *  audio level, APRS 0/1, latitude, longitude (-999999 unknown), symbol (table + code), APRS type, object name,
     *  comment, speed mph, course, altitude ft, weather, device. */
    @JvmStatic external fun take(): String
    /** The audio for one frame in TNC2 monitor format ("SRC>DEST,PATH:info"); null if it is not a valid frame. */
    @JvmStatic external fun encode(tnc2: String, baud: Int, rate: Int, amplitude: Double): ShortArray?
}
