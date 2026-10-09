// The radio bar on every mode's page: the IC-705's frequency and mode (over CI-V), and the mode's bands as chips - a tap
// tunes the radio to that band's usual frequency for the mode and sets USB-D (or CW). Without the radio connected the
// chips just show the frequency to tune by hand. Opening a mode's page tunes the radio to that mode's frequency on the
// band it is already on (FT8 on 7.074 -> WSPR 7.0386), with USB-D or CW, once; never while something is being sent.
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

/** Opening mode [m]'s page: the radio to that mode's frequency on the band it is on (and USB-D / CW), once the radio's
 *  frequency is known; once each time the page is opened; never while something is being sent. (Part of RigBar; pages
 *  that hide RigBar when sideways call it themselves.) */
@Composable
fun AutoTune(m: Mode) {
    val rig by Ic705.state.collectAsStateWithLifecycle() // the radio
    val on = rig.link == RigState.Link.CONNECTED
    var tuned by rememberSaveable(m) { mutableStateOf(false) } // done for this opening
    LaunchedEffect(m, on, rig.freqHz > 0) {
        if (tuned || !on || rig.freqHz <= 0) return@LaunchedEffect // (not until the radio's frequency is known)
        tuned = true
        if (busyTransmitting()) return@LaunchedEffect // never while sending, or with a contact, beacon or message under way
        val dial = dialFor(m, rig.freqHz)             // this mode's frequency nearest the radio's
        val rm = m.rigMode(dial)                      // USB-D, LSB-D or CW
        if (abs(Math.round(dial * 1000) - rig.freqHz) > 50 || !Ic705.isIn(rm, rig)) Ic705.tune(dial, rm) // (unless already right)
    }
}

/** Mode [m]'s usual frequency (kHz) for the band the radio is on ([hz]); if the mode has none there, the nearest one it has. */
private fun dialFor(m: Mode, hz: Long): Double {
    val band = uk.hamdigital.core.Logbook.band(hz)    // "40m"
    m.dialsKHz.firstOrNull { "${it.first}m" == band }?.let { return it.second } // the same band
    return m.dialsKHz.minBy { kotlin.math.abs(kotlin.math.ln(it.second * 1000 / hz)) }.second // else the nearest (by ratio)
}

/** Something is being sent or is due to be: a transmission, an FT8 / FT4 contact with transmit on, the WSPR beacon, or
 *  a JS8 message still queued - then a page must not move the radio. */
private fun busyTransmitting(): Boolean = uk.hamdigital.audio.Transmitter.on.value || uk.hamdigital.core.FtQso.FT8.state.value.enabled ||
    uk.hamdigital.core.FtQso.FT4.state.value.enabled || uk.hamdigital.core.WsprBeacon.state.value.enabled || uk.hamdigital.core.Js8Tx.queued.value > 0

/** The radio's frequency and mode, then the mode's band chips. Returns nothing; the radio is the state. Opening the page
 *  tunes the radio to this mode's frequency on the band it is on (unless something is being sent). */
@Composable
fun RigBar(m: Mode) {
    val ctx = LocalContext.current
    val rig by Ic705.state.collectAsStateWithLifecycle() // the radio
    val on = rig.link == RigState.Link.CONNECTED      // talking to it
    var picked by rememberSaveable(m) { mutableStateOf(m.dialsKHz.firstOrNull { it.first == "20" }?.first ?: m.dialsKHz.first().first) } // band chosen by hand (no radio)
    val onBand = m.dialsKHz.firstOrNull { abs(Math.round(it.second * 1000) - rig.freqHz) <= 3000 }?.first // the band the radio is on, for this mode (within 3 kHz)
    val shown = if (on) onBand else picked             // chip lit
    AutoTune(m)                                       // opening the page: the radio to this mode
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (on) {
            Text(rig.freqText, color = Pal.Text, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 20.sp) // 14.074.000
            Text("  ${rig.modeText}", color = Pal.Cyan, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)                 // USB-D
            val rm = m.rigMode(rig.freqHz / 1000.0)    // right mode for this page (and band)?
            if (rig.mode.isNotEmpty() && !Ic705.isIn(rm, rig)) Text("  (${rm.label} needed)", color = Pal.Amber, fontSize = 13.sp)
            if (rig.tx) Text("  TX", color = Pal.Red, fontWeight = FontWeight.Bold, fontSize = 15.sp)                       // transmitting
        } else {
            val khz = m.dialsKHz.first { it.first == picked }.second // tune by hand
            Text("Tune %s MHz %s".format(if (khz % 1.0 != 0.0) "%.4f".format(khz / 1000.0) else "%.3f".format(khz / 1000.0), m.rigMode(khz).label), color = Pal.Text, fontSize = 15.sp, modifier = Modifier.weight(1f, false))
            Text("  ${rig.message.ifEmpty { "IC-705 not connected" }}", color = Pal.Muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
            if (rig.link == RigState.Link.NONE && Ic705.findDevice(ctx) != null) TextButton({ Ic705.connect(ctx) }) { Text("Connect") } // plugged in but not open
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { // bands
        m.dialsKHz.forEach { (b, khz) -> SmallChip("$b m", b == shown) { picked = b; if (on) Ic705.tune(khz, m.rigMode(khz)) } } // tap: tune the radio
    }
}
