// The application: creates the long-lived parts once (settings) for every screen to share.
package uk.hamdigital

import android.app.Application
import uk.hamdigital.core.SettingsStore

class HamDigitalApp : Application() {
    lateinit var settings: SettingsStore              // user settings

    override fun onCreate() {
        super.onCreate()                              // Android's part
        settings = SettingsStore(this)                // DataStore
    }
}
