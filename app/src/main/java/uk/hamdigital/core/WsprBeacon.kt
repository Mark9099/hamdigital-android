// The WSPR beacon: in each two-minute slot it transmits "CALL GRID DBM" with the chance set (WSJT-X's "Tx Pct":
// 20 % means about one slot in five, picked at random so beacons on the same frequency rarely collide), starting one
// second after the even minute, for 110.6 s. The other slots are received as usual.
package uk.hamdigital.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.engine.WsprNative
import java.time.Instant
import java.time.ZoneOffset
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.random.Random

object WsprBeacon {
    data class State(
        val enabled: Boolean = false,                 // beaconing
        val percent: Int = 20,                        // chance of transmitting in a slot
        val dbm: Int = 37,                            // power reported (dBm: 37 = 5 W)
        val txHz: Int = 1500,                         // audio offset (1400-1600)
        val status: String = "",                      // what is happening
        val sent: Int = 0,                            // transmissions so far
    )

    val state = MutableStateFlow(State())             // for the page
    private var timer: Timer? = null                  // the next slot's decision
    private lateinit var app: Context
    @Volatile var myCall = ""; @Volatile var myGrid = ""; @Volatile var level = 0.3f // you, the level

    fun attach(ctx: Context) { app = ctx.applicationContext }
    private fun set(f: (State) -> State) { state.value = f(state.value) }

    /** The message: WSPR sends a 4-character locator. */
    fun message(s: State = state.value) = "$myCall ${myGrid.take(4).let { it.take(2).uppercase() + it.drop(2) }} ${s.dbm}"

    fun setPercent(p: Int) = set { it.copy(percent = p) }
    fun setDbm(d: Int) = set { it.copy(dbm = d) }
    fun setTxHz(hz: Int) = set { it.copy(txHz = hz.coerceIn(1410, 1590)) } // (inside the 200 Hz WSPR window)
    fun enable(on: Boolean) { set { it.copy(enabled = on, status = if (on) "Beacon on: deciding at each even minute" else "Beacon off") }; if (on) schedule() else { timer?.cancel(); Transmitter.halt() } }

    private fun schedule() {                          // decide just before the next even minute
        timer?.cancel(); if (!state.value.enabled) return
        val now = System.currentTimeMillis()
        val slot = now - now % 120_000 + 120_000      // the next two-minute slot
        timer = Timer("wspr-tx", true).apply { schedule(maxOf(0L, slot - 2_000 - now)) { decide(slot) } } // 2 s before it
    }

    private fun decide(slot: Long) {
        val s = state.value; if (!s.enabled) return
        val t = Instant.ofEpochMilli(slot).atZone(ZoneOffset.UTC).let { "%02d%02d".format(it.hour, it.minute) }
        if (Random.nextInt(100) >= s.percent) { set { it.copy(status = "Receiving in the $t slot (transmits ${s.percent} % of slots)") }; schedule(); return }
        if (myGrid.length < 4) { set { it.copy(enabled = false, status = "Set your locator in Settings first") }; return }
        val audio = WsprNative.encode(message(s), s.txHz.toFloat(), level)
        if (audio == null) { set { it.copy(enabled = false, status = "Cannot send \"${message(s)}\" (WSPR needs a standard callsign)") }; return }
        val ok = Transmitter.send(app, myCall, audio, 12000, slot + 1000, Mode.WSPR.name) { schedule() } // 1 s after the even minute; then the next slot
        if (ok) set { it.copy(sent = it.sent + 1, status = "Transmitting in the $t slot: ${message(s)}") }
        else set { it.copy(enabled = false, status = Transmitter.lastError.value) }
    }
}
