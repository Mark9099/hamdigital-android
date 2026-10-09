// The application: creates the long-lived parts once (settings, the logbook, the callsign -> country list) for every screen to share.
package uk.hamdigital

import android.app.Application
import uk.hamdigital.core.SettingsStore

class HamDigitalApp : Application() {
    lateinit var settings: SettingsStore              // user settings

    override fun onCreate() {
        super.onCreate()                              // Android's part
        settings = SettingsStore(this)                // DataStore
        uk.hamdigital.core.Logbook.init(this)         // the log (log.adi), read once
        Thread({ uk.hamdigital.core.Cty.prepare(this) }, "cty").start() // callsign -> country (cty.dat; downloaded monthly) - off the main thread
    }
}
