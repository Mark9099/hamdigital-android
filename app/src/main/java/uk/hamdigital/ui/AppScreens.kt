// The screens under the startup screen: the mode menu (a tile per mode, the Logbook, Settings and the Guide), and the page chosen
// from it. Back on any page returns to the menu.
package uk.hamdigital.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import uk.hamdigital.MainViewModel
import uk.hamdigital.Screen
import uk.hamdigital.audio.AudioIn
import uk.hamdigital.core.Mode
import uk.hamdigital.rig.Ic705
import uk.hamdigital.rig.RigState

@Composable
fun AppScreens(vm: MainViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle() // which screen
    Surface(Modifier.fillMaxSize(), color = Pal.Bg) {
        when (val s = screen) {
            Screen.Menu -> MenuScreen(vm)                       // the tiles
            is Screen.ModePage -> ModePage(vm, s.mode)          // one mode
            Screen.SettingsPage -> SettingsScreen(vm)           // Settings
            Screen.Guide -> GuideScreen { vm.back() }           // the User Guide
            Screen.Log -> LogbookScreen(vm)                     // the Logbook
        }
    }
}

/** A mode's page. */
@Composable
private fun ModePage(vm: MainViewModel, m: Mode) = when (m) {
    Mode.CW -> CwScreen(vm)                                    // working: the Morse decoder
    Mode.FT8, Mode.FT4 -> Ft8Screen(vm, m)                     // working: ft8_lib
    Mode.WSPR -> WsprScreen(vm)                                // working: wsprd
    Mode.RTTY, Mode.PSK31 -> KeyboardScreen(vm, m)             // working: fldigi
    Mode.JS8 -> Js8Screen(vm)                                  // working: JS8Call's decoder
    else -> ComingScreen(vm, m)                                // the waterfall until its decoder arrives
}

@Composable
private fun MenuScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()         // your station
    val ctx = LocalContext.current
    var radio by remember { mutableStateOf<String?>(null) }    // the IC-705's sound card, if plugged in
    LaunchedEffect(Unit) { while (true) { radio = AudioIn.usbInput(ctx)?.let { AudioIn.usbName(it) }; delay(2000) } } // checked every 2 s
    LazyVerticalGrid(GridCells.Adaptive(170.dp), Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {           // heading: name, station, radio
            Column {
                Text("HF Digital Modes", fontFamily = OrbitronFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Pal.Cyan)
                Text(if (s.callsign.isNotBlank()) "${s.callsign}  •  ${s.locator.ifBlank { "no locator" }}" else "Set your callsign and locator in Settings",
                    color = Pal.Text2, fontSize = 14.sp)
                val rig by Ic705.state.collectAsStateWithLifecycle() // the radio's CI-V
                val civ = rig.link == RigState.Link.CONNECTED // control working
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Filled.Usb, null, tint = if (radio != null || civ) Pal.Green else Pal.Dim, modifier = Modifier.size(18.dp))
                    Text(when {
                        radio != null && civ -> "  IC-705 connected: audio + control  •  ${rig.freqText} ${rig.modeText}"
                        civ && Ic705.net -> "  IC-705 connected over WiFi: audio + control  •  ${rig.freqText} ${rig.modeText}"
                        civ -> "  IC-705 control connected (no USB audio found)  •  ${rig.freqText} ${rig.modeText}"
                        radio != null -> "  $radio connected (USB audio)  •  ${rig.message.ifEmpty { "control not connected" }}"
                        else -> "  Radio not connected - plug the IC-705 in with a USB lead"
                    }, color = if (radio != null || civ) Pal.Green else Pal.Muted, fontSize = 13.sp)
                }
                Text("Choose a mode", color = Pal.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
        items(Mode.entries) { m -> ModeTile(m) { vm.show(Screen.ModePage(m)) } } // a tile per mode
        item(span = { GridItemSpan(maxLineSpan) }) {           // the Logbook, Settings and the Guide
            val log by uk.hamdigital.core.Logbook.qsos.collectAsStateWithLifecycle() // (for the count)
            FilledTonalButton({ vm.show(Screen.Log) }, Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 52.dp)) {
                Icon(Icons.AutoMirrored.Filled.ListAlt, null); Text("  Logbook  •  ${log.size} contact${if (log.size == 1) "" else "s"}") }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton({ vm.show(Screen.SettingsPage) }, Modifier.weight(1f).heightIn(min = 52.dp)) { Icon(Icons.Filled.Settings, null); Text("  Settings") }
                FilledTonalButton({ vm.show(Screen.Guide) }, Modifier.weight(1f).heightIn(min = 52.dp)) { Icon(Icons.AutoMirrored.Filled.MenuBook, null); Text("  Guide") }
            }
        }
    }
}

/** One mode's tile: its name, what it is for, and whether its decoder is here yet. */
@Composable
private fun ModeTile(m: Mode, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp), color = Pal.Tert, border = BorderStroke(1.dp, if (m.working) Pal.Accent else Pal.Tert),
        modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(m.title, fontFamily = OrbitronFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Pal.Cyan) // name
            Text(m.blurb, color = Pal.Text2, fontSize = 13.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp)) // what for
            Text(if (m.working) "Decoder ready" else "Waterfall now  •  decoder in stage ${m.stage}", color = if (m.working) Pal.Green else Pal.Amber,
                fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) // ready?
        }
    }
}
