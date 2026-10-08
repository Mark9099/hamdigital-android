// Startup screen, laid out as HF Propagation's: a picture (here a moving waterfall with each mode's signal in it - FT8's
// stepped tones, a WSPR line, RTTY's two tones, a PSK31 trace and CW's dashes), the app's name, "Created by M7JVY",
// the version, what the app does, your station, the modes, and the open-source code it is built on. Shown for 8 s
// when the app starts (unless "Show this screen at start-up" is unticked; Settings shows it again); a tap skips it.
// Picture above the text on a phone held upright, beside it otherwise.
package uk.hamdigital.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.hamdigital.BuildConfigInfo
import uk.hamdigital.MainViewModel
import uk.hamdigital.core.Mode
import kotlin.random.Random

@Composable
fun SplashScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle() // your station
    val cfg = LocalConfiguration.current; val beside = cfg.screenWidthDp > cfg.screenHeightDp // landscape / tablet: side by side
    val short = beside && cfg.screenHeightDp < 480     // a phone on its side: tighter gaps so it all fits
    val align = if (beside) Alignment.Start else Alignment.CenterHorizontally // text alignment
    val textAlign = if (beside) TextAlign.Start else TextAlign.Center
    Box(Modifier.fillMaxSize().background(Pal.Bg)
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.splashDone() } // tap: skip
        .systemBarsPadding().padding(20.dp), contentAlignment = Alignment.Center) {
        val art: @Composable (Modifier) -> Unit = { m -> WaterfallArt(m.aspectRatio(4f / 3f)) } // the picture
        val info: @Composable (Modifier) -> Unit = { m ->
            Column(m.then(if (beside) Modifier.verticalScroll(rememberScrollState()) else Modifier), horizontalAlignment = align) { // (scrolls rather than cut off)
                Text("HF Digital Modes", fontFamily = OrbitronFamily, fontWeight = FontWeight.Bold, fontSize = if (short) 26.sp else 30.sp, color = Pal.Cyan, textAlign = textAlign) // name
                Text("Created by M7JVY", color = Pal.Text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp)) // author
                Text("M7JVYM7JVY@gmail.com", color = Pal.Text2, fontSize = 14.sp)                                // the author's e-mail (as HF Propagation)
                Text("Built with Claude Code (Anthropic's AI coding assistant)", color = Pal.Muted, fontSize = 13.sp, textAlign = textAlign) // how it was made
                Text("Version ${BuildConfigInfo.VERSION}", color = Pal.Muted, fontSize = 14.sp)                     // version
                Text("Digital modes for the Icom IC-705 in one app - plug the radio into this phone or tablet with a USB lead.",
                    color = Pal.Text2, fontSize = 14.sp, textAlign = textAlign, lineHeight = 19.sp,
                    modifier = Modifier.padding(top = if (short) 6.dp else 12.dp))                                    // what it does
                Text(Mode.entries.joinToString("  •  ") { it.title }, color = Pal.Amber, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    textAlign = textAlign, modifier = Modifier.padding(top = 6.dp))                                    // the modes
                Text(when { s.callsign.isNotBlank() && s.locator.isNotBlank() -> "Your station: ${s.callsign}  •  ${s.locator}"
                            s.callsign.isNotBlank() -> "Your station: ${s.callsign}"
                            else -> "Set your callsign and locator in Settings" },
                    color = Pal.Text2, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))                     // you
                Text("Built on open-source code: ft8_lib  •  WSJT-X (wsprd)  •  JS8Call  •  fldigi  •  HamPropCore CW decoder. " +
                    "Licensed GPL v3.", color = Pal.Dim, fontSize = 11.sp, lineHeight = 14.sp, textAlign = textAlign,
                    modifier = Modifier.padding(top = if (short) 8.dp else 18.dp))                                    // credits (tight lines)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (short) 0.dp else 6.dp)) { // show it next time?
                    Checkbox(s.showSplash, { b -> vm.updateSettings { it.copy(showSplash = b) } })
                    Text("Show this screen at start-up", color = Pal.Text2, fontSize = 14.sp)
                }
                Text("Tap to continue  •  Settings > Show the startup screen shows it again", color = Pal.Dim, fontSize = 12.sp, textAlign = textAlign)
            }
        }
        if (beside) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            art(Modifier.weight(1.15f)); info(Modifier.weight(1f))
        } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            art(Modifier.fillMaxWidth()); Spacer(Modifier.height(20.dp)); info(Modifier.fillMaxWidth())
        }
    }
}

/** The startup picture: a waterfall scrolling down, with each mode's signal in it, and its name under its column. */
@Composable
private fun WaterfallArt(m: Modifier) {
    val t by rememberInfiniteTransition(label = "wf").animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "scroll") // 0..1 every 6 s
    val noise = remember { val r = Random(7); List(900) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) } } // speckles: x, y, brightness
    Box(m) {                                          // the picture with the names over it
    Canvas(Modifier.fillMaxSize().background(Color(0xFF07101C))) {
        val w = size.width; val h = size.height       // the picture
        val top = h * 0.08f; val bottom = h * 0.86f   // the waterfall's part (names under it)
        clipRect(0f, top, w, bottom) {
            val wh = bottom - top                     // its height
            noise.forEach { (x, y, b) ->              // the noise, scrolling down
                val yy = top + ((y + t) % 1f) * wh
                drawRect(wfColour(0.08f + b * 0.22f), Offset(x * w, yy), Size(w / 160f, wh / 90f))
            }
            fun band(xc: Float, f: DrawScope.(Float, Float) -> Unit) { // a signal drawn twice so it scrolls round seamlessly
                for (k in 0..1) f(xc * w, top + (t + k - 1f) * wh)
            }
            band(0.14f) { x, y0 ->                    // FT8: 8 tones, stepping, in 13 s bursts (two slots shown)
                val r = Random(3)
                for (slot in 0..1) for (i in 0 until 24) {
                    val tone = r.nextInt(8)           // the symbol's tone
                    drawRect(wfColour(0.85f), Offset(x - w * 0.04f + tone * w * 0.01f, y0 + (slot * 0.5f + i * 0.018f) * wh), Size(w * 0.01f, wh * 0.018f))
                }
            }
            band(0.33f) { x, y0 -> drawRect(wfColour(0.6f), Offset(x, y0), Size(w * 0.004f, wh)) } // WSPR: one thin, slow line
            band(0.50f) { x, y0 ->                    // RTTY: two tones 170 Hz apart, keying back and forth
                val r = Random(5)
                for (i in 0 until 40) { val mark = r.nextBoolean(); drawRect(wfColour(if (mark) 0.95f else 0.7f), Offset(x + (if (mark) 0f else w * 0.05f), y0 + i * wh / 40f), Size(w * 0.012f, wh / 40f)) }
            }
            band(0.69f) { x, y0 -> drawRect(wfColour(0.75f), Offset(x, y0), Size(w * 0.012f, wh)) } // PSK31: a narrow trace
            band(0.86f) { x, y0 ->                    // CW: dots and dashes
                val r = Random(9); var y = y0
                while (y < y0 + wh) { val len = if (r.nextBoolean()) 0.012f else 0.036f; drawRect(wfColour(1f), Offset(x, y), Size(w * 0.008f, wh * len)); y += wh * (len + 0.014f) }
            }
        }
        drawRect(Pal.Accent, Offset(0f, top), Size(w, bottom - top), style = androidx.compose.ui.graphics.drawscope.Stroke(2f)) // frame
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {      // the names under the columns
        listOf("FT8" to 0.14f, "WSPR" to 0.33f, "RTTY" to 0.52f, "PSK31" to 0.70f, "CW" to 0.86f).forEach { (n, x) ->
            Text(n, Modifier.align(Alignment.BottomStart).offset(x = maxWidth * x - 14.dp), color = Pal.Cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
    }
}
