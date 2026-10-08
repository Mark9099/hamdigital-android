// The digital modes the app offers: one menu tile and one page each. "stage" is the docs/PLAN.md stage that brings its
// decoder (0 = working now); "rate" is the audio sample rate its decoder wants.
package uk.hamdigital.core

enum class Mode(
    val title: String,                                // tile and page title
    val blurb: String,                                // one line on the menu tile
    val rate: Int,                                    // audio sample rate for its decoder (Hz)
    val source: String,                               // the open-source code its decoder comes from
    val stage: Int,                                   // plan stage that brings the decoder (0 = working)
    val dialsKHz: List<Pair<String, Int>>,            // band -> usual dial frequency (USB, kHz)
) {
    FT8("FT8", "Weak-signal QSOs in 15 s slots", 12000, "ft8_lib (Kārlis Goba, MIT)", 0,
        listOf("160" to 1840, "80" to 3573, "60" to 5357, "40" to 7074, "30" to 10136, "20" to 14074, "17" to 18100, "15" to 21074, "12" to 24915, "10" to 28074, "6" to 50313)),
    FT4("FT4", "Fast contest mode in 7.5 s slots", 12000, "ft8_lib (Kārlis Goba, MIT)", 0,
        listOf("80" to 3575, "40" to 7047, "30" to 10140, "20" to 14080, "17" to 18104, "15" to 21140, "12" to 24919, "10" to 28180, "6" to 50318)),
    WSPR("WSPR", "Beacon reports in 2-minute slots", 12000, "wsprd from WSJT-X (GPL v3)", 0,
        listOf("160" to 1836, "80" to 3568, "60" to 5287, "40" to 7038, "30" to 10138, "20" to 14095, "17" to 18104, "15" to 21094, "12" to 24924, "10" to 28124, "6" to 50293)),
    JS8("JS8Call", "Keyboard chat built on FT8", 12000, "JS8Call decoder (GPL v3)", 6,
        listOf("80" to 3578, "40" to 7078, "30" to 10130, "20" to 14078, "17" to 18104, "15" to 21078, "12" to 24922, "10" to 28078, "6" to 50318)),
    RTTY("RTTY", "45.45 baud Baudot teleprinter", 8000, "fldigi (GPL v3)", 0,
        listOf("80" to 3580, "40" to 7040, "30" to 10140, "20" to 14080, "17" to 18100, "15" to 21080, "10" to 28080)),
    PSK31("PSK31", "Keyboard chat in a narrow signal", 8000, "fldigi (GPL v3)", 0,
        listOf("80" to 3580, "40" to 7040, "30" to 10142, "20" to 14070, "17" to 18100, "15" to 21070, "12" to 24920, "10" to 28120)),
    CW("CW", "Morse decoder", 16000, "HamPropCore CW decoder (from Tab5CWDecoder)", 0,
        listOf("80" to 3560, "40" to 7030, "30" to 10116, "20" to 14060, "17" to 18086, "15" to 21060, "12" to 24906, "10" to 28060));

    val working get() = stage == 0                    // decoder in this version?
}
