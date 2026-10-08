// The one activity: draws edge to edge and shows the app - the startup screen fading to the mode menu.
package uk.hamdigital

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import uk.hamdigital.ui.AppScreens
import uk.hamdigital.ui.HamDigitalTheme
import uk.hamdigital.ui.SplashScreen

/** The app's version, for the startup screen and Settings (kept here so it is not tied to the generated BuildConfig). */
object BuildConfigInfo { const val VERSION = "0.6.0" }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()     // state (survives rotation)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)            // Android's part
        enableEdgeToEdge()                            // draw under the system bars (the screens pad)
        setContent { HamDigitalTheme {                // the app, with the startup screen over it while it starts
            val screen by vm.screen.collectAsState()  // the screen under it
            BackHandler(enabled = screen != Screen.Menu) { vm.back() } // Back on a page: to the menu
            Box {
                AppScreens(vm)                        // menu / mode page / Settings / Guide
                val splash by vm.splash.collectAsState() // startup screen shown?
                AnimatedVisibility(splash, enter = EnterTransition.None, exit = fadeOut(tween(600))) { SplashScreen(vm) } // fades away
            }
        } }
    }

    override fun onResume() {
        super.onResume()                              // Android's part
        uk.hamdigital.rig.Ic705.connect(this)         // the radio's CI-V, if plugged in (asks for USB access the first time)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)                     // Android's part
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) uk.hamdigital.rig.Ic705.connect(this) // plugged in while open
    }
}
