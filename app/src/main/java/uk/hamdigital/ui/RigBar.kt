// The radio bar on every mode's page: the IC-705's frequency and mode (over CI-V), and the mode's bands as chips - a tap
// tunes the radio to that band's usual frequency for the mode and sets USB-D (or CW). Without the radio connected the
// chips just show the frequency to tune by hand.
package uk.hamdigital.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.hamdigital.core.Mode
import uk.hamdigital.rig.Ic705
import uk.hamdigital.rig.RigState
import kotlin.math.abs

/** The radio's frequency and mode, then the mode's band chips. Returns nothing; the radio is the state. */
@Composable
fun RigBar(m: Mode) {
    val ctx = LocalContext.current
    val rig by Ic705.state.collectAsStateWithLifecycle() // the radio
    val on = rig.link == RigState.Link.CONNECTED      // talking to it
    var picked by rememberSaveable(m) { mutableStateOf(m.dialsKHz.firstOrNull { it.first == "20" }?.first ?: m.dialsKHz.first().first) } // band chosen by hand (no radio)
    val onBand = m.dialsKHz.firstOrNull { abs(it.second * 1000L - rig.freqHz) <= 3000 }?.first // the band the radio is on, for this mode (within 3 kHz)
    val shown = if (on) onBand else picked             // chip lit
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (on) {
            Text(rig.freqText, color = Pal.Text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp) // 14.074.000
            Text("  ${rig.modeText}", color = Pal.Cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)                 // USB-D
            val want = if (m == Mode.CW) rig.mode.startsWith("CW") else rig.mode == "USB" && rig.data // right mode for this page?
            if (rig.mode.isNotEmpty() && !want) Text("  (${if (m == Mode.CW) "CW" else "USB-D"} needed)", color = Pal.Amber, fontSize = 13.sp)
            if (rig.tx) Text("  TX", color = Pal.Red, fontWeight = FontWeight.Bold, fontSize = 15.sp)                       // transmitting
        } else {
            val khz = m.dialsKHz.first { it.first == picked }.second // tune by hand
            Text("Tune %.3f MHz %s".format(khz / 1000.0, if (m == Mode.CW) "CW" else "USB-D"), color = Pal.Text, fontSize = 15.sp, modifier = Modifier.weight(1f, false))
            Text("  ${rig.message.ifEmpty { "IC-705 not connected" }}", color = Pal.Muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            if (rig.link == RigState.Link.NONE && Ic705.findDevice(ctx) != null) TextButton({ Ic705.connect(ctx) }) { Text("Connect") } // plugged in but not open
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { // bands
        m.dialsKHz.forEach { (b, khz) -> SmallChip("$b m", b == shown) { picked = b; if (on) Ic705.tune(khz, m == Mode.CW) } } // tap: tune the radio
    }
}
