// Colours and theme: HF Propagation's dark palette (the Tab5's colours), as a Material 3 colour scheme - the same look.
package uk.hamdigital.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Pal {                                          // the Tab5 colours (ui.cpp COL_*)
    val Bg = Color(0xFF0A0E14)                        // page background
    val Tert = Color(0xFF1A2332)                      // cells, strips, idle buttons
    val Accent = Color(0xFF0090B0)                    // theme primary (selected)
    val Cyan = Color(0xFF00DDFF)                      // titles
    val Text = Color(0xFFFFFFFF)                      // main text
    val Text2 = Color(0xFFA0B0C0)                     // secondary text
    val Muted = Color(0xFF8090A0)                     // captions
    val Dim = Color(0xFF5A6672)                       // hints
    val Amber = Color(0xFFFFB432)                     // FAIR / accents
    val Green = Color(0xFF00FF88)                     // GOOD / open
    val Red = Color(0xFFFF4466)                       // POOR / warnings
    /** Reliability colours: below 10, 10-19, 20-39, 40-59, 60-79, 80+ % (the Chart's heat_color steps). */
    val Heat = listOf(Color(0xFF441111), Color(0xFFCC2200), Color(0xFFFF6600), Color(0xFFFFCC00), Color(0xFF55BB00), Color(0xFF00CC00))
    fun heat(pct: Int): Color = Heat[when { pct >= 80 -> 5; pct >= 60 -> 4; pct >= 40 -> 3; pct >= 20 -> 2; pct >= 10 -> 1; else -> 0 }] // step
}

/** Orbitron Bold: the mode tiles and the startup screen's title (as HF Propagation; a variable font, weight 700). */
val OrbitronFamily = androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(uk.hamdigital.R.font.orbitron,
    androidx.compose.ui.text.font.FontWeight.Bold, variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(700))))

/** A compact on / off chip (about 28 dp high): for rows of many choices where Material's chips take too much room.
 *  Material pads anything tappable to a 48 dp touch area, which put big gaps between rows of these; it is turned off
 *  here (28 dp is still easy to tap). */
@Composable
fun SmallChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) { // no 48 dp padding
        Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(8.dp), color = if (selected) Color(0xFF3A4458) else Color.Transparent,
            border = BorderStroke(1.dp, if (selected) Color(0xFF3A4458) else Color(0xFF3A4452))) { // filled when chosen
            Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), color = if (selected) Pal.Text else Pal.Text2, fontSize = 13.sp, maxLines = 1)
        }
    }
}

/** A compact outlined text field (44 dp high instead of Material's 56), label on the border as usual. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, readOnly: Boolean = false, isError: Boolean = false,
                 keyboardOptions: KeyboardOptions = KeyboardOptions.Default, trailing: (@Composable () -> Unit)? = null, enabled: Boolean = true, // enabled = false: greyed, not editable
                 password: Boolean = false) { // password: the characters are hidden
    val interaction = remember { MutableInteractionSource() } // focus state, shared with the decoration
    val colors = OutlinedTextFieldDefaults.colors()   // the theme's outlined colours
    BasicTextField(value, onValueChange, modifier.heightIn(min = 44.dp), enabled = enabled, readOnly = readOnly, singleLine = true, textStyle = TextStyle(color = if (enabled) Pal.Text else Pal.Dim, fontSize = 15.sp),
        cursorBrush = SolidColor(Pal.Cyan), keyboardOptions = keyboardOptions, interactionSource = interaction,
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else VisualTransformation.None) { inner ->
        OutlinedTextFieldDefaults.DecorationBox(value = value, innerTextField = inner, enabled = enabled, singleLine = true, visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else VisualTransformation.None,
            interactionSource = interaction, isError = isError, label = { Text(label) }, trailingIcon = trailing, colors = colors,
            contentPadding = OutlinedTextFieldDefaults.contentPadding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp), // the compact part
            container = { OutlinedTextFieldDefaults.Container(enabled = enabled, isError = isError, interactionSource = interaction, colors = colors) })
    }
}

@Composable
fun HamDigitalTheme(content: @Composable () -> Unit) {
    // The phone's text size is followed up to 1.3 x (Android's "large"): above that the dense pages (the chart's grid,
    // the beacons' table) cannot stay readable, so they stop growing there rather than spill.
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale.coerceAtMost(1.3f))) { ThemeInner(content) }
}

@Composable
private fun ThemeInner(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(                // always dark (shack-friendly, as the Tab5)
            primary = Pal.Accent, onPrimary = Pal.Text, secondary = Pal.Cyan,
            background = Pal.Bg, onBackground = Pal.Text, surface = Pal.Bg, onSurface = Pal.Text,
            surfaceVariant = Pal.Tert, onSurfaceVariant = Pal.Text2, surfaceContainer = Pal.Tert,
            error = Pal.Red,
        ),
        content = content,                            // the app
    )
}
