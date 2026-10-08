// The user's settings, kept with DataStore: station (callsign, locator), where the receive audio comes from, and
// whether the startup screen shows.
package uk.hamdigital.core

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings") // the settings file

/** Where receive audio comes from. */
enum class AudioChoice(val label: String) {
    AUTO("IC-705 (USB) when plugged in, else the microphone"), // the radio first
    USB("IC-705 (USB) only"),                         // never the microphone
    MIC("Phone microphone"),                          // e.g. held near a speaker
}

data class Settings(
    val callsign: String = "",                        // your callsign
    val locator: String = "",                         // your Maidenhead locator (4 or 6 characters)
    val audio: AudioChoice = AudioChoice.AUTO,        // receive audio source
    val showSplash: Boolean = true,                   // startup screen at start
    val civAddr: Int = 0xA4,                          // the IC-705's CI-V address (its default)
)

class SettingsStore(private val ctx: Context) {
    private val kCall = stringPreferencesKey("callsign")   // keys
    private val kLoc = stringPreferencesKey("locator")
    private val kAudio = stringPreferencesKey("audio")
    private val kSplash = booleanPreferencesKey("show_splash")
    private val kCiv = intPreferencesKey("civ_address")

    val flow: Flow<Settings> = ctx.store.data.map { p -> // the settings, as they change
        Settings(
            callsign = p[kCall] ?: "",
            locator = p[kLoc] ?: "",
            audio = AudioChoice.entries.firstOrNull { it.name == p[kAudio] } ?: AudioChoice.AUTO,
            showSplash = p[kSplash] ?: true,
            civAddr = p[kCiv] ?: 0xA4,
        )
    }

    suspend fun save(s: Settings) {                   // store them all
        ctx.store.edit { p ->
            p[kCall] = s.callsign.trim().uppercase()  // callsigns in capitals
            p[kLoc] = s.locator.trim().let { if (it.length >= 4) it.take(2).uppercase() + it.drop(2).take(2) + it.drop(4).lowercase() else it.uppercase() } // AB12cd
            p[kAudio] = s.audio.name
            p[kSplash] = s.showSplash
            p[kCiv] = s.civAddr
        }
    }
}
