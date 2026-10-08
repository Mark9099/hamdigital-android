// Guide: the in-app user guide, one topic per screen and mode. Keep it in step with the app whenever a screen, control
// or behaviour changes, and add a Version history entry for each release (newest first). Topics down the left on a
// tablet; chips across the top on a phone.
package uk.hamdigital.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.hamdigital.BuildConfigInfo

private const val B = "•  "                           // bullet

/** The topics: title, text. */
private val GUIDE = listOf(
    "Getting started" to
        "HF Digital Modes brings the common HF digital modes together in one app for the Icom IC-705: FT8, FT4, WSPR, JS8Call, RTTY, " +
        "PSK31 and CW. Each mode has its own page; the menu, after the startup screen, opens them.\n\n" +
        "1. Settings: enter your callsign and locator.\n" +
        "2. Connect the IC-705's USB-C socket to the phone or tablet (a USB-C to USB-C lead, or an OTG adapter). The menu shows " +
        "\"IC-705 connected\" when Android sees the radio's sound card.\n" +
        "3. Tap a mode. The first time, Android asks to allow audio recording - this is how the app hears the radio. Allow it.\n" +
        "4. Tune the radio to the mode's frequency (each page lists them) in USB-D (data) mode, or CW for CW.\n\n" +
        "Android's Back button (or the arrow at the top left) returns to the menu. Every page shows the time in UTC: the slot modes " +
        "(FT8, FT4, WSPR, JS8Call) need the phone's clock to be right to within a second - Android sets it from the network.",

    "The IC-705 connection" to
        "One USB lead carries the radio's receive audio to the app and its CI-V control (frequency and mode; from stage 7 also the transmit " +
        "audio and PTT).\n\n" +
        B + "The first time the radio is plugged in, Android asks whether HF Digital Modes may use it - allow it (tick \"Always\" to skip the " +
        "question next time). The menu then shows \"IC-705 connected: audio + control\" with its frequency and mode.\n" +
        B + "Each mode's page shows the radio's frequency and mode at the top, with the mode's bands as chips: tap one to tune the radio to " +
        "that band's usual frequency for the mode, in USB-D (USB with DATA on) - or CW on the CW page. A note shows if the radio is in the " +
        "wrong mode for the page. Without the radio connected, the chips show the frequency to tune by hand.\n" +
        B + "Settings > Radio control: the connection, the CI-V address (A4, the IC-705's default) and Reconnect. On the radio leave CI-V " +
        "USB Echo Back off and CI-V Transceive on (the defaults).\n" +
        B + "Receive audio level: MENU > SET > Connectors > USB AF/SQL > AF Output Level. Aim for the level bar at the top right of a page " +
        "to sit around the middle on band noise; red means too loud.\n" +
        B + "Settings > Receive audio chooses the IC-705 when it is plugged in (else the microphone), the IC-705 only, or the microphone - " +
        "handy for trying a page with the phone held near a speaker.\n" +
        B + "WiFi (no lead) is planned after the USB link is complete.",

    "The menu" to
        "A tile for each mode: its name, what it is for, and whether its decoder is in this version (\"Decoder ready\") or still to come " +
        "(\"Waterfall now\" - the page shows the radio's audio as a waterfall so the connection can be checked, and lists the mode's " +
        "frequencies). Above the tiles: your station, and the radio's connection with its frequency and mode. Settings and this Guide are under the tiles.",

    "Waterfall" to
        "Each mode's page shows the receive audio as a waterfall: frequency across (0-3000 Hz of audio), time downwards, newest at the top. " +
        "Signals are bright traces on the dark blue of the band noise - blue, cyan, yellow, then red as they get stronger. The picture " +
        "adjusts itself to the noise, so it keeps its contrast whatever the volume.",

    "CW" to
        "Decodes Morse from the radio's audio with the decoder used by HF Propagation and the Tab5 display. Set the IC-705 to CW (or USB) " +
        "with the signal's tone between 350 and 1000 Hz.\n\n" +
        B + "The waterfall shows the 14 tone channels, 350-1000 Hz; tap a column to lock onto that tone, or Auto tune to find the strongest.\n" +
        B + "Speed (WPM), tone and signal strength show beside it, with the letter being received.\n" +
        B + "Sensitivity: higher hears weaker signals but lets more noise through. Follow tone: keeps up with a drifting signal. Start speed: " +
        "the speed it expects before it has measured one; Reset speed measures again.\n" +
        B + "Pause stops decoding; Clear empties the text; Copy and Share (top right) pass on the decoded text.",

    "FT8 and FT4" to
        "Decodes FT8 (15 s slots) and FT4 (7.5 s slots) with ft8_lib, the open-source FT8 / FT4 library also used by the FT8CN app. Slots " +
        "start on the UTC clock (FT8 at :00, :15, :30, :45), so the phone's clock must be right - Android sets it from the network.\n\n" +
        B + "The slot bar shows how far through the slot it is; at the slot's end the audio is decoded (a fraction of a second) and the " +
        "messages appear at the top of the list, newest slot first, with a line between slots.\n" +
        B + "Columns: UTC (the slot's start), dB (signal to noise in 2500 Hz, as WSJT-X reports it), DT (time offset in seconds - if nearly " +
        "every station shows the same large DT, the phone's clock is out), Hz (audio frequency), the message, and km (distance to the " +
        "sender's locator, when sent and yours is set).\n" +
        B + "CQ calls are green; messages to your callsign amber. All / CQ / To me filters the list; Clear (top right) empties it. The " +
        "list is kept while the app runs, so you can look at another mode and come back.\n" +
        B + "The waterfall above the list shows the audio; FT8 signals are short stepped traces 50 Hz wide.\n" +
        B + "Expect about three quarters of what WSJT-X decodes from the same audio - ft8_lib does not yet make WSJT-X's extra passes " +
        "for the weakest signals under stronger ones.\n" +
        B + "Transmitting (answering a CQ, calling CQ, the QSO sequence) comes in stage 7.",

    "WSPR" to
        "Coming in stage 4, using wsprd from WSJT-X: decodes the two-minute WSPR slots and lists the beacons heard (call, locator, power, " +
        "signal, drift and distance). Transmitting your own WSPR beacon follows in stage 7.",

    "RTTY and PSK31" to
        "Coming in stage 5, using fldigi's modems: RTTY at 45.45 baud with 170 Hz shift, and PSK31; tap the signal on the waterfall to tune " +
        "it in, and the text appears below. Typing and sending follow in stage 7.",

    "JS8Call" to
        "Coming in stage 6, using the JS8Call decoder: the JS8 Normal speed (15 s slots) with the stations heard and their messages, " +
        "directed messages to you picked out. Sending follows in stage 7.",

    "Version history" to
        "This is version ${BuildConfigInfo.VERSION}.\n\n" +
        B + "0.3.0 (October 2026): FT8 and FT4 decoding (ft8_lib): slot bar, the list of messages with signal, time offset, frequency " +
        "and distance; CQs and messages to you highlighted; All / CQ / To me. Signal reports and time offsets checked against WSJT-X.\n" +
        B + "0.2.0 (October 2026): IC-705 control over the USB lead (CI-V): its frequency and mode on every mode's page and the menu; " +
        "band chips tune it and set USB-D or CW; Settings > Radio control (CI-V address, Reconnect). Android offers to open the app when " +
        "the radio is plugged in.\n" +
        B + "0.1.0 (October 2026): first version. Startup screen, mode menu, Settings and Guide in HF Propagation's look; receive audio from " +
        "the IC-705's USB sound card (or the microphone) with a waterfall on every mode's page and each mode's frequencies; the CW decoder working.",
)

@Composable
fun GuideScreen(onBack: () -> Unit) {
    var topic by rememberSaveable { mutableIntStateOf(0) } // topic shown
    val wide = LocalConfiguration.current.screenWidthDp >= 600 // tablet: list down the left
    ModeFrame("Guide", onBack) {
        val body: @Composable (Modifier) -> Unit = { m ->
            Column(m.verticalScroll(rememberScrollState(), reverseScrolling = false).padding(bottom = 16.dp)) {
                Text(GUIDE[topic].first, color = Pal.Cyan, fontWeight = FontWeight.Bold, fontSize = 18.sp) // its title
                Text(GUIDE[topic].second, color = Pal.Text, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp)) // its text
            }
        }
        if (wide) Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(200.dp).verticalScroll(rememberScrollState())) { // topics
                GUIDE.forEachIndexed { i, (t, _) ->
                    Text(t, Modifier.fillMaxWidth().clickable { topic = i }.padding(vertical = 8.dp), color = if (i == topic) Pal.Cyan else Pal.Text2,
                        fontWeight = if (i == topic) FontWeight.Bold else FontWeight.Normal, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.width(16.dp)); body(Modifier.weight(1f))
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { // topics as chips
                GUIDE.forEachIndexed { i, (t, _) -> SmallChip(t, i == topic) { topic = i } }
            }
            body(Modifier.fillMaxSize())
        }
    }
}
