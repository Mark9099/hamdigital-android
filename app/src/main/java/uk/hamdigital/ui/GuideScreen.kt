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
        B + "WiFi (no lead): the app can reach the IC-705 over WiFi instead, with Icom's network protocol (as the RS-BA1 " +
        "software does) - receive and transmit audio and CI-V, so every mode works the same. On the radio: MENU > SET > WLAN Set " +
        "(WLAN on, joined to the same network as the phone - or join the phone to the radio's own access point); its IP address is " +
        "under WLAN Set > Connection Status; MENU > SET > WLAN Set > Remote Settings > Network User1 sets a user name and password. Enter them in " +
        "Settings > Connection: WiFi and tap Connect over WiFi; the app then connects by WiFi whenever it starts, until you tap " +
        "\"Use the USB lead\". The page's status line shows \"Audio: IC-705 (WiFi)\".\n" +
        B + "The link keeps running when you switch to another app or the screen goes off - a \"HF Digital Modes\" notification " +
        "shows it is; closing the app (Back from the menu) logs out of the radio. If the phone's WiFi drops, or the radio stops " +
        "answering, the app reconnects by itself. The radio takes a minute or two to let go of a session that ended without " +
        "logging out (the app stopped by Android, say): Settings shows \"trying again\" until it does.",

    "The menu" to
        "A tile for each mode: its name, what it is for, and whether its decoder is in this version (\"Decoder ready\") or still to come " +
        "(\"Waterfall now\" - the page shows the radio's audio as a waterfall so the connection can be checked, and lists the mode's " +
        "frequencies). Above the tiles: your station, and the radio's connection with its frequency and mode. Under the tiles: the " +
        "Logbook (with how many contacts it holds), Settings and this Guide.",

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
        B + "Pause stops decoding; Clear empties the text; Copy and Share (top right) pass on the decoded text.\n" +
        B + "Sending: the IC-705's own keyer sends the Morse (over CI-V), so the radio must be in CW (a band chip sets it) with " +
        "break-in on (the BK-IN button). Type and tap Send, or CQ / 73; choose the speed (15-30 WPM); Stop ends the message.",

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
        B + "Transmitting: double-tap a CQ (or a station calling you) to answer it - a single tap does nothing, so a stray touch never starts a call - or tap Call CQ. The contact then runs itself, as in WSJT-X: " +
        "the six standard messages (Tx1 your call and locator, Tx2 a report, Tx3 R + report, Tx4 RR73, Tx5 73, Tx6 CQ) are chosen " +
        "from what the other station sends, and the completed contact goes into the Logbook. The next message " +
        "is lit; tap another to send it instead.\n" +
        B + "TX: on / off enables transmitting in your slots; 1st / 2nd slot chooses them (answering a station picks the other half " +
        "from theirs). Tap the waterfall to move the TX offset (red lines). Halt stops at once. A message unanswered 6 times turns " +
        "transmitting off.\n" +
        B + "The first time, the app asks you to confirm you hold a licence. It transmits only with the IC-705's CI-V connected (to " +
        "key it) and its USB sound card (or the WiFi link) present - never through the phone's speaker. Set the radio's DATA MOD " +
        "input (MENU > SET > Connectors > MOD Input) to USB when using the lead, or WLAN over WiFi, and the transmit level in " +
        "Settings so the ALC barely moves. To check you are getting out, look up your callsign on pskreporter.info.\n" +
        B + "B4 (amber, before the distance) marks a station already in your log on this band and mode - \"worked before\", as WSJT-X " +
        "calls it. Log (top right) opens the log form filled in with the contact in progress, to finish one by hand (after Halt, say). " +
        "Your own transmit slots are not decoded (the slot bar says so).",

    "WSPR" to
        "Decodes WSPR beacons with wsprd, the decoder inside WSJT-X. WSPR stations transmit for 110.6 seconds starting one second " +
        "after each even UTC minute; the app records each two-minute slot and decodes it 1:54 after the even minute (a few seconds).\n\n" +
        B + "The slot bar shows the time into the two minutes; the waterfall shows the WSPR window, 1400-1600 Hz of audio, where the " +
        "signals are thin lines that drift slowly.\n" +
        B + "Columns: UTC (the slot), dB (signal to noise in 2500 Hz), DT (time offset), MHz (the station's frequency - the IC-705's " +
        "dial plus the audio offset; with the radio not connected, the audio frequency in Hz), Dr (drift, Hz a minute), the station's " +
        "call, locator and power, and km from your locator.\n" +
        B + "Keep the radio on the same frequency for the whole slot. Clear empties the list; it is kept while the app runs.\n" +
        B + "Beacon: transmits \"YOURCALL GRID POWER\" (your callsign, 4-character locator and the power chosen) in the chosen share " +
        "of the two-minute slots - 20 % is usual: about one slot in five, picked at random so beacons on the frequency rarely " +
        "collide - starting one second after the even minute. The other slots are received as usual, so you can see who hears " +
        "you on WSPRnet.org. Choose the power the IC-705 is set to; tap the waterfall to move the beacon's offset (red line, " +
        "1410-1590 Hz). Beacon: off stops it, ending a transmission in progress.",

    "RTTY and PSK31" to
        "Keyboard modes, decoded by fldigi's receivers (the demodulators from the fldigi program).\n\n" +
        B + "Tune the IC-705 to a band's RTTY or PSK31 frequency (a band chip), then tap a signal in the waterfall to tune to it. " +
        "PSK31 is a single narrow trace; tap its centre. RTTY is a pair of traces (170 Hz apart); tap between them. Red lines show where " +
        "the receiver is tuned.\n" +
        B + "AFC (on as standard) follows a signal that drifts and corrects small tuning errors.\n" +
        B + "Squelch: the text is shown only while the signal quality (the green bar) is above it, so noise does not print rubbish. " +
        "Slide it to the left to see weak signals; PSK31 starts at 25, RTTY at 0 (off).\n" +
        B + "s/n is the signal to noise. PSK31 shows DCD while it is locked on to a signal.\n" +
        B + "RTTY: the amateur standard is 45.45 baud with 170 Hz shift. If the text is nonsense, try Reverse (the station's mark and " +
        "space are the other way round). Other shifts are 85, 425 and 850 Hz.\n" +
        B + "The text is kept while the app runs; Copy, Share and Clear are at the top right.\n" +
        B + "Sending: type in the box at the bottom and tap Send, or tap CQ (\"CQ CQ CQ DE call call call PSE K\") or 73. The message " +
        "goes out on the receive frequency (where the red lines are) as one transmission, and appears in the text marked [TX]. " +
        "RTTY sends capitals, figures and common punctuation (Baudot); PSK31 sends any text. The TRANSMITTING bar has Halt.",

    "JS8Call" to
        "Receives JS8 (the JS8Call keyboard-chat mode, Normal speed: 15 s slots like FT8) with JS8Call's own decoder.\n\n" +
        B + "At the end of each slot the frames heard are decoded and turned into text as JS8Call shows it: heartbeats " +
        "(\"G4ABC: @HB HEARTBEAT IO91\"), CQs, directed messages (\"G4ABC: M7JVY SNR -10\") and free text. Longer messages " +
        "arrive over several slots; a diamond marks a message's end. Text in [brackets] was decoded with low confidence.\n" +
        B + "Band activity: the text at each audio offset, newest first. Tap a line (or the waterfall) to set the receive " +
        "offset - the red lines mark it and the 50 Hz a JS8 signal takes; the decoder tries there first.\n" +
        B + "Calls: the stations heard, with signal, locator (from their heartbeats) and distance.\n" +
        B + "To me: messages addressed to your callsign (Settings).\n" +
        B + "Clear empties all three.\n" +
        B + "Sending: HB sends a heartbeat (your call and locator), CQ a CQ. Tap a station in Calls to put it in To (and listen on " +
        "its offset); then SNR?, GRID?, ACK and 73 are sent to it, and Send sends the typed message to it. With To empty, Send " +
        "goes to @ALLCALL (everyone). Messages go out on the receive offset (red lines), one 15 s frame a slot - a longer message " +
        "takes several slots; the line under the buttons shows progress. Halt stops and drops the rest. Text can be letters, " +
        "figures, spaces and . - + ? ! \" /.",

    "Logbook" to
        "Every contact you make, kept in an ADIF file - the format every logging program, and the LoTW, QRZ, Club Log and eQSL upload " +
        "pages, read. Open it from the menu.\n\n" +
        B + "Completed FT8 and FT4 contacts are logged automatically. On the CW, RTTY and PSK31 pages tap the Log chip (beside CQ, 73, " +
        "Callsign); on JS8Call and FT8 / FT4, Log at the top. The form opens over the page, which keeps decoding behind it, filled in " +
        "with the time, the radio's frequency, the mode and the usual report (599 for CW, RTTY and PSK31).\n" +
        B + "The form: call, locator, date and times (UTC - HH:MM or HH:MM:SS), frequency in MHz (the band follows from it; with no " +
        "frequency choose the band), mode (tap one or type another, e.g. SSB, JT65), reports, name, QTH, your power, a comment, and " +
        "your call and locator for that contact (for /P or another station). Save is greyed until it makes sense - the line under the " +
        "title says what is missing. Typing a call shows if you have worked it before, when and on what.\n" +
        B + "The list: newest first, with the time, call, band and mode, then the reports, locator, distance, name, QTH and comment. " +
        "Search finds a call, locator, name, QTH or comment; the chips show one band or one mode. The line above the list counts the " +
        "contacts, different stations, locator squares and bands. Tap a contact to change or delete it.\n" +
        B + "The menu (⋮): Share sends the log file to another app (email, Drive, a logging app). Save to a file puts a copy where you " +
        "choose. Import reads another program's ADIF (.adi) file - contacts already in the log (the same call, band and mode within " +
        "2 minutes) are skipped. Delete all empties the log, after asking.\n" +
        B + "Fields the app has no box for (QSL and LoTW status, contest exchanges ...) are kept from an imported file and written " +
        "back unchanged, so nothing is lost on the way through.",

    "Version history" to
        "This is version ${BuildConfigInfo.VERSION}.\n\n" +
        B + "0.9.0 (October 2026): the Logbook - every contact listed, searched, filtered by band and mode, changed, deleted, " +
        "shared, saved and imported (ADIF); a Log button on every mode's page; B4 marks stations already worked on FT8 / FT4. A " +
        "page opened while the WiFi link was reconnecting no longer stays at \"No audio\": the status line follows the link.\n" +
        B + "0.8.6 (October 2026): a slot you transmitted in is no longer decoded (the radio passes its own transmit audio back, " +
        "so your own message used to appear in the list at +40 dB). FT8, FT4, JS8 and WSPR.\n" +
        B + "0.8.5 (October 2026): closing and reopening the app reconnects to the IC-705 over WiFi straight away (the app now " +
        "finishes logging out of the radio before it stops).\n" +
        B + "0.8.4 (October 2026): the WiFi link connects first time: control commands wait until the radio has opened its " +
        "control stream (sending too early used to break the connection).\n" +
        B + "0.8.3 (October 2026): first FT8 transmissions over WiFi, heard across Europe. Calling a station from the FT8 / FT4 list " +
        "now needs a double-tap (as WSJT-X's double-click), so a stray touch cannot start a call; the licence notice gives the " +
        "DATA MOD setting for WiFi (WLAN).\n" +
        B + "0.8.2 (October 2026): first tests with the IC-705 over WiFi: control, tuning and FT8 receive work. The link now keeps " +
        "running when another app is open (a notification shows it is), reconnects by itself if WiFi drops or the radio is slow " +
        "to let go of an old session, and logs out when the app closes.\n" +
        B + "0.8.1 (October 2026): the WiFi password is hidden; clearer notes on the FT8 and WSPR pages until your callsign and " +
        "locator are set.\n" +
        B + "0.8.0 (October 2026): the IC-705 over WiFi (Icom's network protocol, from the FT8CN app): audio and control without " +
        "a lead, for every mode. Settings > Connection: WiFi.\n" +
        B + "0.7.3 (October 2026): JS8Call sending (heartbeat, CQ, SNR? / GRID? / ACK / 73, and messages). Every mode now " +
        "transmits as well as receives.\n" +
        B + "0.7.2 (October 2026): sending in RTTY and PSK31 (typed text, CQ, 73) and CW (through the IC-705's keyer).\n" +
        B + "0.7.1 (October 2026): the WSPR beacon (transmit percentage, power reported, offset).\n" +
        B + "0.7.0 (October 2026): FT8 and FT4 transmit: answer a CQ or call CQ with WSJT-X-style automatic sequencing, PTT over " +
        "CI-V and audio to the IC-705's USB sound card, the licence notice, the ADIF logbook (Settings > Logbook) and the transmit level.\n" +
        B + "0.6.0 (October 2026): JS8Call receive (JS8 Normal) with JS8Call's decoder: Band activity, Calls and To me. Every " +
        "mode now decodes.\n" +
        B + "0.5.0 (October 2026): RTTY and PSK31 decoding with fldigi's receivers: tap the waterfall to tune, AFC, squelch, RTTY " +
        "shift and Reverse, the decoded text with Copy / Share / Clear.\n" +
        B + "0.4.0 (October 2026): WSPR decoding (wsprd from WSJT-X): the 2-minute slot bar, a waterfall of the WSPR window, and the " +
        "spots with frequency, drift, call, locator, power and distance.\n" +
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
