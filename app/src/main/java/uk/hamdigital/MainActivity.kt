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
object BuildConfigInfo { const val VERSION = "0.12.1" }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()     // state (survives rotation)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)            // Android's part
        enableEdgeToEdge()                            // draw under the system bars (the screens pad)
        setContent { HamDigitalTheme {                // the app, with the startup screen over it while it starts
            val screen by vm.screen.collectAsState()  // the screen under it
            BackHandler { if (screen != Screen.Menu) vm.back() else finish() } // Back on a page: to the menu; on the menu: close the app - finished, so onDestroy logs out of the radio (Android 12+ only sends the app to the background on Back, leaving the link up)
            Box {
                AppScreens(vm)                        // menu / mode page / Settings / Guide
                val splash by vm.splash.collectAsState() // startup screen shown?
                AnimatedVisibility(splash, enter = EnterTransition.None, exit = fadeOut(tween(600))) { SplashScreen(vm) } // fades away
            }
        } }
        devCommand(intent)                            // (debug builds) a development check asked for by adb
    }

    /** Debug builds only: "--ez dev_test true" runs the decoders on test recordings (DevTest, docs/DEV_COMMANDS.md). */
    private fun devCommand(i: android.content.Intent?) {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && i?.getBooleanExtra("dev_test", false) == true) DevTest.run(this)
    }

    override fun onDestroy() {
        if (isFinishing) { val app = applicationContext; uk.hamdigital.core.TxControl.stopAll(); uk.hamdigital.rig.IcomNet.disconnect { RadioService.stop(app) } } // (and nothing goes on sending: 0.10.4) // the app is closing (not just turning): log out of the radio (or it keeps a stale session), then stop the background service - not before, or the logout is blocked
        super.onDestroy()                             // Android's part
    }

    override fun onResume() {
        super.onResume()                              // Android's part
        RadioService.start(this)                      // keep the radio link and audio running when another app is opened
        uk.hamdigital.rig.Ic705.connect(this)         // the radio's CI-V, if plugged in (asks for USB access the first time)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)                     // Android's part
        devCommand(intent)                            // (debug builds) adb commands while running
        if (intent.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) uk.hamdigital.rig.Ic705.connect(this) // plugged in while open
    }
}
