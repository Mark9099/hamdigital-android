// The digital modes the app offers: one menu tile and one page each. "stage" is the docs/PLAN.md stage that brings its
// decoder (0 = working now); "rate" is the audio sample rate its decoder wants.
package uk.hamdigital.core

enum class Mode(
    val title: String,                                // tile and page title
    val blurb: String,                                // one line on the menu tile
    val rate: Int,                                    // audio sample rate for its decoder (Hz)
    val source: String,                               // the open-source code its decoder comes from
    val stage: Int,                                   // plan stage that brings the decoder (0 = working)
    val dialsKHz: List<Pair<String, Double>>,         // band -> usual dial frequency (USB, kHz; WSPR's need the 100 Hz: 7038.6)
) {
    FT8("FT8", "Weak-signal QSOs in 15 s slots", 12000, "ft8_lib (Kārlis Goba, MIT)", 0,
        listOf("160" to 1840.0, "80" to 3573.0, "60" to 5357.0, "40" to 7074.0, "30" to 10136.0, "20" to 14074.0, "17" to 18100.0, "15" to 21074.0, "12" to 24915.0, "10" to 28074.0, "6" to 50313.0)),
    FT4("FT4", "Fast contest mode in 7.5 s slots", 12000, "ft8_lib (Kārlis Goba, MIT)", 0,
        listOf("80" to 3575.0, "40" to 7047.0, "30" to 10140.0, "20" to 14080.0, "17" to 18104.0, "15" to 21140.0, "12" to 24919.0, "10" to 28180.0, "6" to 50318.0)),
    WSPR("WSPR", "Beacon reports in 2-minute slots", 12000, "wsprd from WSJT-X (GPL v3)", 0,
        listOf("160" to 1836.6, "80" to 3568.6, "60" to 5287.2, "40" to 7038.6, "30" to 10138.7, "20" to 14095.6, "17" to 18104.6, "15" to 21094.6, "12" to 24924.6, "10" to 28124.6, "6" to 50293.0)), // (WSJT-X's WSPR dials: the 200 Hz WSPR window is then 1400-1600 Hz of audio, where wsprd looks)
    JS8("JS8Call", "Keyboard chat built on FT8", 12000, "JS8Call decoder (GPL v3)", 0,
        listOf("80" to 3578.0, "40" to 7078.0, "30" to 10130.0, "20" to 14078.0, "17" to 18104.0, "15" to 21078.0, "12" to 24922.0, "10" to 28078.0, "6" to 50318.0)),
    RTTY("RTTY", "45.45 baud Baudot teleprinter", 8000, "fldigi (GPL v3)", 0,
        listOf("80" to 3580.0, "40" to 7040.0, "30" to 10140.0, "20" to 14080.0, "17" to 18100.0, "15" to 21080.0, "10" to 28080.0)),
    PSK31("PSK31", "Keyboard chat: PSK31, PSK63, PSK125", 8000, "fldigi (GPL v3)", 0,
        listOf("80" to 3580.0, "40" to 7040.0, "30" to 10142.0, "20" to 14070.0, "17" to 18100.0, "15" to 21070.0, "12" to 24920.0, "10" to 28120.0)),
    OLIVIA("Olivia", "Robust keyboard chat through fading and noise", 8000, "fldigi + Pawel Jalocha's MFSK (GPL v3)", 0, // (the dials put the usual Olivia centres at 1500 Hz)
        listOf("80" to 3582.0, "40" to 7071.0, "30" to 10140.0, "20" to 14071.0, "17" to 18102.0, "15" to 21071.0, "10" to 28121.0)),
    CW("CW", "Morse decoder", 16000, "HamPropCore CW decoder (from Tab5CWDecoder)", 0,
        listOf("80" to 3560.0, "40" to 7030.0, "30" to 10116.0, "20" to 14060.0, "17" to 18086.0, "15" to 21060.0, "12" to 24906.0, "10" to 28060.0)),
    SSTV("SSTV", "Pictures by radio: receive and send", 12000, "Robot36 (0BSD) and SSTV Encoder 2 (Apache 2.0)", 0, // (LSB below 10 MHz, as SSTV is sent there)
        listOf("80" to 3735.0, "40" to 7165.0, "20" to 14230.0, "15" to 21340.0, "10" to 28680.0)),
    FREEDV("FreeDV", "Digital voice: RADE, 700D, 700E, 1600", 8000, "rade_c + Opus FARGAN (BSD), codec2 / FreeDV API (LGPL 2.1)", 0, // (FreeDV's calling frequencies, always USB)
        listOf("80" to 3643.0, "40" to 7177.0, "20" to 14236.0, "17" to 18118.0, "15" to 21313.0, "12" to 24933.0, "10" to 28330.0)),
    WEFAX("Weather fax", "Weather charts by radio (receive)", 11025, "fldigi (GPL v3)", 0, // (stations by name: the dial 1.9 kHz below the station, USB, puts the fax at 1900 Hz)
        listOf("DWD 3855" to 3853.1, "DWD 7880" to 7878.1, "DWD 13882" to 13880.6, // Deutscher Wetterdienst, Hamburg / Pinneberg
            "GYA 2618" to 2616.6, "GYA 4610" to 4608.1, "GYA 8040" to 8038.1, "GYA 11086" to 11084.6)), // Joint Operational Meteorology and Oceanography Centre, Northwood (UK)
    APRS("APRS / Packet", "Positions, messages and packet: 2 m FM or HF", 12000, "Dire Wolf (GPL v2+)", 0, // (2 m / ISS: 1200 baud FM-D; HF: 300 baud USB-D)
        listOf("2 m" to 144800.0, "ISS" to 145825.0, "30 m" to 10147.6)), // Europe's APRS frequency; the ISS digipeater; HF APRS (300 baud, tones 1600 / 1800 Hz)
    FREEDATA("FreeDATA", "Hear and call FreeDATA stations (codec2 data)", 8000, "FreeDATA protocol (GPL v3), codec2 (LGPL 2.1)", 0, // (suggestions in IARU Region 1's data segments, USB-D)
        listOf("80" to 3595.0, "40" to 7048.0, "30" to 10145.0, "20" to 14093.0));

    val working get() = stage == 0                    // decoder in this version?

    /** How the radio is set for this mode on [khz]: CW for CW; SSTV is lower sideband below 10 MHz (as it is sent
     *  there); APRS above 30 MHz is FM (FM-D); everything else upper sideband - all with DATA on, so the audio comes and
     *  goes over the lead / WiFi. */
    fun rigMode(khz: Double): uk.hamdigital.rig.RigMode = when {
        this == CW -> uk.hamdigital.rig.RigMode.CW
        this == SSTV && khz < 10_000 -> uk.hamdigital.rig.RigMode.LSB_D
        this == APRS && khz >= 30_000 -> uk.hamdigital.rig.RigMode.FM_D
        else -> uk.hamdigital.rig.RigMode.USB_D
    }
}
