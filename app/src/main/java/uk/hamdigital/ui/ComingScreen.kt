// A mode's page before its decoder arrives (docs/PLAN.md): the receive audio and its waterfall - so the IC-705's USB
// audio and level can be checked now - the mode's usual dial frequencies (tap one to pick it), and what is coming.
package uk.hamdigital.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.hamdigital.MainViewModel
import uk.hamdigital.audio.Spectrum
import uk.hamdigital.core.Mode

@Composable
fun ComingScreen(vm: MainViewModel, m: Mode) {
    val s by vm.settings.collectAsStateWithLifecycle() // the audio choice
    val spec = remember(m) { Spectrum(m.rate) }       // the waterfall's FFT at the mode's rate
    val rx = rememberRx(m.rate, 512, s.audio) { b, n -> spec.feed(b, n) } // audio in while the page is open
    var band by rememberSaveable(m) { mutableStateOf(m.dialsKHz.firstOrNull { it.first == "20" }?.first ?: m.dialsKHz.first().first) } // band chosen (20 m first)
    ModeFrame(m.title, { vm.back() }) {
        RxStatus(rx)                                  // audio source, level
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { // bands
            m.dialsKHz.forEach { (b, _) -> SmallChip("$b m", b == band) { band = b } }
        }
        val khz = m.dialsKHz.first { it.first == band }.second // its dial frequency
        Text("Dial %.3f MHz, USB-D (data) on the IC-705".format(khz / 1000.0), color = Pal.Text, fontSize = 15.sp, modifier = Modifier.padding(vertical = 2.dp))
        Waterfall(spec, Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp)) // the audio
        Surface(color = Color(0xFF111820), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) { // what is coming
            Column(Modifier.padding(10.dp)) {
                Text("${m.title} decoder: stage ${m.stage}", color = Pal.Amber, fontSize = 14.sp)
                Text("The decoder comes from ${m.source}. Until it is added, this page shows the receive audio so the radio's " +
                    "USB lead and level can be checked: signals show as bright traces. The radio is tuned by hand for now; " +
                    "setting the frequency over the USB lead (CI-V) comes in stage 2.", color = Pal.Text2, fontSize = 13.sp, lineHeight = 17.sp)
            }
        }
    }
}
