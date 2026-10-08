// The app's state for the screens: settings, the startup screen, and which screen is showing (the menu, a mode's
// page, Settings or the Guide). Survives the phone being turned.
package uk.hamdigital

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.hamdigital.core.Mode
import uk.hamdigital.core.Settings

/** What is on the screen under the startup screen. */
sealed interface Screen {
    data object Menu : Screen                         // the mode tiles
    data class ModePage(val mode: Mode) : Screen      // one mode
    data object SettingsPage : Screen                 // Settings
    data object Guide : Screen                        // the User Guide
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = (app as HamDigitalApp).settings // the settings file
    val settings: StateFlow<Settings> = store.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings()) // current settings

    val splash = MutableStateFlow(true)               // startup screen showing?
    val screen = MutableStateFlow<Screen>(Screen.Menu) // the screen under it

    init {
        viewModelScope.launch { settings.collect { uk.hamdigital.rig.Ic705.civAddress = it.civAddr } } // the radio's CI-V address, as set
        viewModelScope.launch {                       // the startup screen: skipped if turned off, else 8 s (a tap skips it sooner)
            if (!store.flow.first().showSplash) splash.value = false else { delay(8000); splash.value = false }
        }
    }

    fun splashDone() { splash.value = false }         // tapped
    fun show(s: Screen) { screen.value = s }          // open a screen
    fun back(): Boolean = if (screen.value != Screen.Menu) { screen.value = Screen.Menu; true } else false // Back: to the menu (false: leave the app)
    fun updateSettings(f: (Settings) -> Settings) { viewModelScope.launch { store.save(f(settings.value)) } } // change and store
}
