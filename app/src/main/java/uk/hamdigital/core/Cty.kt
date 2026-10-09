// Callsign -> country (DXCC entity) from cty.dat (country-files.com, AD1C - the file logging and contest programs use),
// as in HF Propagation (uk.hamprop.net.Cty) and the Tab5: downloaded once a month and kept in the app's storage.
// Lookup: an exact callsign first (special stations), then the longest matching prefix; portable forms are handled
// ("W1AW/P" -> W1AW, "EA8/DL1ABC" -> EA8, "DL1ABC/EA8" -> EA8, "/4" keeps the home country). Until the first download
// (it needs the internet once) no countries are shown.
package uk.hamdigital.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A DXCC entity: name, continent, position (cty.dat's: roughly the middle of the country), primary prefix. */
class Entity(val name: String, val cont: String, val lat: Double, val lon: Double, val pri: String)

object Cty {
    @Volatile private var exact: Map<String, Entity> = emptyMap() // complete callsigns
    @Volatile private var prefixes: Map<String, Entity> = emptyMap() // prefixes
    private var maxLen = 0                            // longest prefix
    val ready get() = prefixes.isNotEmpty()           // loaded?
    val loaded = MutableStateFlow(false)              // for the pages: redraw when the countries arrive

    /** Load the stored copy, downloading a new one when it is missing or over 30 days old (the app's start, off the main thread). */
    fun prepare(ctx: Context) {
        val f = File(ctx.filesDir, "cty.dat")         // stored copy
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < 30L * 86_400_000 // under a month old
        if (!fresh) download()?.takeIf { it.length > 50_000 }?.let { f.writeText(it) } // (~106 KB; a failed download keeps the old copy)
        if (!ready && f.exists()) try { parse(f.readText()); loaded.value = true } catch (e: Exception) { }
    }

    private fun download(): String? = try {
        val c = URL("https://www.country-files.com/cty/cty.dat").openConnection() as HttpURLConnection
        try { c.connectTimeout = 15_000; c.readTimeout = 30_000; if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
    } catch (e: Exception) { null }

    private fun parse(t: String) {
        val ex = HashMap<String, Entity>(); val px = HashMap<String, Entity>(); var ml = 0 // tables
        var i = 0                                     // read position
        while (i < t.length) {
            val nl = t.indexOf('\n', i); if (nl < 0) break // entity line
            val f = t.substring(i, nl).split(':')     // Name: CQ: ITU: Cont: Lat: Lon: TZ: Prefix:
            i = nl + 1                                // prefixes follow, to ';'
            if (f.size < 8) continue                  // not an entity line
            val e = Entity(f[0].trim(), f[3].trim().take(2), f[4].trim().toDoubleOrNull() ?: 0.0, -(f[5].trim().toDoubleOrNull() ?: 0.0), f[7].trim().removePrefix("*")) // longitude is WEST positive in cty.dat
            val semi = t.indexOf(';', i); if (semi < 0) break // end of the prefix list
            for (tok in t.substring(i, semi).split(',')) { // each prefix or "=CALL"
                val s = tok.trim(); if (s.isEmpty()) continue
                val isExact = s.startsWith('=')       // complete callsign
                val key = s.removePrefix("=").takeWhile { it !in "([<{~" }.uppercase() // without modifiers
                if (key.isEmpty()) continue
                if (isExact) ex[key] = e else { px[key] = e; if (key.length > ml) ml = key.length } // keep
            }
            i = semi + 1                              // next entity
        }
        exact = ex; prefixes = px; maxLen = ml        // publish
    }

    private val SUFFIX = setOf("P", "M", "MM", "AM", "QRP", "LGT", "A", "LH", "R", "B", "J", "T") // portable modifiers

    /** The entity of a callsign, or null (unknown, or the list not loaded yet). */
    fun lookup(call: String): Entity? {
        val c = call.trim().trim('<', '>').uppercase(); if (c.isEmpty() || c.startsWith("...") || !ready) return null // nothing / hashed / not loaded
        exact[c]?.let { return it }                   // a special station
        var key = c                                   // the part that says where
        val sl = c.indexOf('/')                       // portable forms
        if (sl >= 0) { val a = c.substring(0, sl); val b = c.substring(sl + 1)
            key = when { b in SUFFIX || (b.length == 1 && b[0].isDigit()) -> a; a.length <= b.length -> a; else -> b } }
        for (n in minOf(key.length, maxLen) downTo 1) prefixes[key.substring(0, n)]?.let { return it } // longest prefix
        return null                                   // unknown
    }

    /** The country of a callsign ("" if not known). */
    fun country(call: String): String = lookup(call)?.name ?: ""
}
