// Settings: your station (callsign, locator - used by the modes' messages and reports), where receive audio comes from
// (the IC-705's USB sound card or the microphone), the startup screen, and About (version, licence, open-source code).
package uk.hamdigital.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import uk.hamdigital.BuildConfigInfo
import uk.hamdigital.MainViewModel
import uk.hamdigital.audio.AudioIn
import uk.hamdigital.core.AudioChoice
import uk.hamdigital.rig.Ic705
import uk.hamdigital.rig.RigState

private val LOCATOR = Regex("^[A-Ra-r]{2}[0-9]{2}([A-Xa-x]{2})?$") // a 4- or 6-character Maidenhead locator

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle() // stored settings
    val ctx = LocalContext.current
    var call by remember { mutableStateOf(s.callsign) } // being typed
    var loc by remember { mutableStateOf(s.locator) }
    LaunchedEffect(s.callsign, s.locator) { if (call.isBlank()) call = s.callsign; if (loc.isBlank()) loc = s.locator } // the stored ones once loaded
    var radio by remember { mutableStateOf<String?>(null) } // the USB sound card, if any
    LaunchedEffect(Unit) { while (true) { radio = AudioIn.usbInput(ctx)?.let { AudioIn.usbName(it) }; delay(2000) } } // checked every 2 s
    ModeFrame("Settings", { vm.back() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Heading("Your station")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CompactField(call, { v -> call = v.uppercase().filter { it.isLetterOrDigit() || it == '/' }.take(12); vm.updateSettings { it.copy(callsign = call) } }, "Callsign",
                    Modifier.weight(1f), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)) // saved as typed
                val bad = loc.isNotBlank() && !LOCATOR.matches(loc) // not a locator
                CompactField(loc, { v -> loc = v.filter { it.isLetterOrDigit() }.take(6); if (loc.isBlank() || LOCATOR.matches(loc)) vm.updateSettings { it.copy(locator = loc) } }, "Locator (e.g. IO91wm)",
                    Modifier.weight(1f), isError = bad, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)) // saved when valid
            }
            Text("Your callsign and locator go into the messages you send (FT8, FT4, WSPR, JS8Call) and are shown on the startup screen.", color = Pal.Muted, fontSize = 13.sp)

            Heading("Receive audio")
            AudioChoice.entries.forEach { c ->        // where audio comes from
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { vm.updateSettings { it.copy(audio = c) } }) {
                    RadioButton(s.audio == c, { vm.updateSettings { it.copy(audio = c) } })
                    Text(c.label, color = Pal.Text, fontSize = 15.sp)
                }
            }
            Text(if (radio != null) "Found now: $radio (USB audio)" else "No USB audio found now. Connect the IC-705's USB-C socket to this phone or tablet " +
                "(a USB-C to USB-C lead, or an OTG adapter). The IC-705 needs no settings for receive audio; its USB audio level is MENU > SET > " +
                "Connectors > USB AF/SQL > AF Output Level.", color = if (radio != null) Pal.Green else Pal.Muted, fontSize = 13.sp, lineHeight = 17.sp)

            Heading("Radio control (CI-V)")
            val rig by Ic705.state.collectAsStateWithLifecycle() // the radio's CI-V
            Text(if (rig.link == RigState.Link.CONNECTED) "Connected: ${rig.freqText} ${rig.modeText}" else rig.message.ifEmpty { "Not connected" },
                color = if (rig.link == RigState.Link.CONNECTED) Pal.Green else Pal.Muted, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var addr by remember(s.civAddr) { mutableStateOf("%02X".format(s.civAddr)) } // being typed (hex)
                CompactField(addr, { v -> addr = v.uppercase().filter { it in "0123456789ABCDEF" }.take(2); if (addr.length == 2) addr.toIntOrNull(16)?.takeIf { it in 1..0xDF }?.let { a -> vm.updateSettings { it.copy(civAddr = a) } } },
                    "CI-V address (hex)", Modifier.width(170.dp))
                OutlinedButton({ Ic705.disconnect(); Ic705.connect(ctx) }) { Text("Reconnect") } // after changing it, or a lost link
            }
            Text("The app controls the radio over the same USB lead as the audio: it reads the frequency and mode, and a band chip on a mode's " +
                "page tunes it. The IC-705's address is A4 unless changed (MENU > SET > Connectors > CI-V > CI-V Address). Leave \"CI-V USB " +
                "Echo Back\" off and \"CI-V Transceive\" on (the defaults).", color = Pal.Muted, fontSize = 13.sp, lineHeight = 17.sp)

            Heading("Startup screen")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(s.showSplash, { b -> vm.updateSettings { it.copy(showSplash = b) } })
                Text("Show this screen at start-up", color = Pal.Text, fontSize = 15.sp)
            }
            OutlinedButton({ vm.splash.value = true }) { Text("Show the startup screen") } // now (a tap closes it)

            Heading("About")
            Text("HF Digital Modes ${BuildConfigInfo.VERSION}, by M7JVY (M7JVYM7JVY@gmail.com). Built with Claude Code.", color = Pal.Text, fontSize = 14.sp)
            Text("Free software under the GNU General Public License v3: you may share and change it, and anyone given the app may have its " +
                "source code. Built on open-source code: ft8_lib by Kārlis Goba (MIT) for FT8 and FT4; wsprd from WSJT-X by Joe Taylor K1JT " +
                "and others (GPL v3) for WSPR; the JS8Call decoder by Jordan Sherer KN4CRD and others (GPL v3); fldigi by Dave Freese W1HKJ " +
                "and others (GPL v3) for RTTY and PSK31; the CW decoder from HamPropCore (as HF Propagation); the Orbitron font (SIL OFL).",
                color = Pal.Text2, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun Heading(t: String) = Text(t, color = Pal.Cyan, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp)) // a section title
