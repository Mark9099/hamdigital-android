// Transmit controls shared by the modes: the licence notice (shown once, before the first transmission; Settings can
// show it again), the TX indicator, and the FT8 / FT4 transmit panel (enable, Call CQ, Halt, 1st / 2nd slot, the six
// messages with the next one lit, the TX offset, and what is happening).
package uk.hamdigital.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.hamdigital.MainViewModel
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.core.FtQso

/** Runs [action] if transmitting is allowed (the licence notice accepted), else shows the notice first. */
class TxGate(val ask: (() -> Unit) -> Unit)

@Composable
fun rememberTxGate(vm: MainViewModel): TxGate {
    val s by vm.settings.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) } // the action waiting for the answer
    pending?.let { act ->
        AlertDialog(onDismissRequest = { pending = null },
            title = { Text("Before you transmit") },
            text = { Text("Transmitting needs an amateur radio licence: you are responsible for what is sent under your callsign, " +
                "on frequencies and at powers your licence allows. The app keys the IC-705 over its USB lead (CI-V) and sends the " +
                "audio to the radio's USB sound card; Halt stops a transmission at once, and nothing is ever sent through the phone's speaker.\n\n" +
                "Set the IC-705 to USB-D with its DATA MOD input on USB (MENU > SET > Connectors > MOD Input), and the power you want. " +
                "Keep the ALC low: turn the transmit level (Settings) down until the radio's ALC barely moves.") },
            confirmButton = { TextButton({ vm.updateSettings { it.copy(txOk = true) }; pending = null; act() }) { Text("I hold a licence") } },
            dismissButton = { TextButton({ pending = null }) { Text("Cancel") } })
    }
    return remember(s.txOk) { TxGate { action -> if (s.txOk) action() else pending = action } }
}

/** A red "TX" banner while transmitting, with Halt. */
@Composable
fun TxBanner(onHalt: () -> Unit) {
    val on by Transmitter.on.collectAsStateWithLifecycle()
    if (on) Row(Modifier.fillMaxWidth().background(Pal.Red, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("TRANSMITTING", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Button(onHalt, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Pal.Red), contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Halt") }
    }
}

/** FT8 / FT4 transmit panel. */
@Composable
fun FtTxPanel(qso: FtQso, gate: TxGate) {
    val q by qso.state.collectAsStateWithLifecycle()  // the contact
    TxBanner { qso.halt() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        SmallChip(if (q.enabled) "TX: on" else "TX: off", q.enabled) { if (q.enabled) qso.enable(false) else gate.ask { qso.enable(true) } } // enable
        SmallChip("Call CQ", q.enabled && q.dx.isEmpty() && q.next == 6) { gate.ask { qso.callCq() } }
        SmallChip("Halt", false) { qso.halt() }
        SmallChip(if (q.secondSlot) "2nd slot" else "1st slot", false) { qso.setSlot(!q.secondSlot) } // which slots we use
        Text("TX ${q.txHz} Hz", color = Pal.Red, fontSize = 12.sp)
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { // the six messages
        for (n in listOf(1, 2, 3, 4, 5, 6)) {
            val t = qso.text(n, q); val ok = n == 6 || q.dx.isNotEmpty() // (Tx1-5 need a DX call)
            SmallChip("Tx$n ${if (ok) t else "-"}", n == q.next) { if (ok) qso.setNext(n) }
        }
    }
    Text(listOfNotNull(q.dx.takeIf { it.isNotEmpty() }?.let { "DX $it ${q.dxGrid}" }, q.rcvd.takeIf { it.isNotEmpty() }?.let { "rcvd $it" }, q.status.ifEmpty { null },
        if (q.logged) "logged" else null).joinToString("  •  ").ifEmpty { if (qso.myCall.isBlank()) "Set your callsign and locator in Settings to transmit." else "Tap a CQ (or a station calling you) to answer it, or Call CQ. Tap the waterfall to set the TX offset." },
        color = if (q.enabled) Pal.Amber else Pal.Muted, fontSize = 12.sp, fontFamily = FontFamily.Default, maxLines = 2)
}

/** Typing and sending for the keyboard modes: a text box, Send, and quick messages (CQ, 73) with your callsign. */
@Composable
fun SendBox(myCall: String, mode: String, onSend: (String) -> Unit, extra: @Composable RowScope.() -> Unit = {}) {
    var text by remember { mutableStateOf("") }       // being typed
    val call = myCall.ifBlank { "MYCALL" }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        CompactField(text, { text = it.uppercase() }, "Type to send ($mode)", Modifier.weight(1f))
        Button({ if (text.isNotBlank()) { onSend(text.trim()); text = "" } }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Send") }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        SmallChip("CQ", false) { onSend("CQ CQ CQ DE $call $call $call PSE K") }      // calling CQ
        SmallChip("73", false) { onSend("TNX FER QSO 73 DE $call SK") }               // ending a contact
        SmallChip("Callsign", false) { text = (text + " $call").trim() }              // your call into the box
        extra()
    }
}
