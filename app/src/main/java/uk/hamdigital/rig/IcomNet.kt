// The IC-705 over WiFi (no lead): Icom's network protocol - the one the RS-BA1 software and the radio's remote
// settings use - through FT8CN's implementation (MIT; package uk.hamdigital.icom). The radio sends its receive audio
// (12 kHz, 16-bit) and CI-V replies; the app sends CI-V commands, PTT and transmit audio. On the IC-705: MENU > SET >
// WLAN Set (connect it to the same WiFi as the phone, or use its own access point), and WLAN Set > Remote Settings >
// Network User1 for the user name and password; the radio's IP address is shown under WLAN Set > Connection Status.
//
// Keeping the link up (found on the air, 0.8.2):
//  - The app's traffic is pinned to the phone's WiFi network (bindProcessToNetwork). Android may route an app's
//    packets over mobile data, where the radio cannot be reached ("sendto failed: EPERM").
//  - The phone's WiFi is watched: when it drops the link waits; when it is back, the link is made again.
//  - A watchdog: the radio serves one remote session at a time and ignores a new one while an old one it was not told
//    about is still open, and FT8CN's code asks "are you ready" only once. So if the login has not succeeded within
//    10 s, or the radio has sent no CI-V for 8 s, the app logs out and tries again (5 s, then 10, 20, 30 s apart).
//  - Logging out properly (disconnect) when the app is closed, so the radio is not left with a stale session.
package uk.hamdigital.rig

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.audio.AudioIn
import uk.hamdigital.icom.IComWifiRig
import uk.hamdigital.icom.WifiRig
import java.util.Timer
import kotlin.concurrent.schedule

object IcomNet {
    private const val TAG = "IcomNet"                 // logcat tag
    @Volatile var rig: IComWifiRig? = null; private set // the connection, while open
    val status = MutableStateFlow("")                 // what is happening
    @Volatile var loggedIn = false; private set       // the radio accepted us

    private data class Target(val ip: String, val port: Int, val user: String, val password: String)
    @Volatile private var target: Target? = null      // what to stay connected to (null: not wanted)
    @Volatile private var wifi: Network? = null       // the phone's WiFi network, while up
    @Volatile private var startedMs = 0L              // when this connection attempt began
    @Volatile private var lastCivMs = 0L              // when the radio last sent CI-V
    @Volatile private var retries = 0                 // failed attempts in a row (for the back-off)
    @Volatile private var nextTryMs = 0L              // when the next attempt may start
    private var watchdog: Timer? = null               // checks the link every 2 s
    private var cm: ConnectivityManager? = null
    private var callbackOn = false                    // WiFi callback registered

    /** Stay connected to the radio (connecting now, and again whenever the link is lost). */
    fun connect(ctx: Context, ip: String, port: Int, user: String, password: String) {
        if (ip.isBlank()) { status.value = "Set the IC-705's IP address in Settings > Connection"; return }
        target = Target(ip.trim(), port, user, password); retries = 0; nextTryMs = 0
        watchWifi(ctx.applicationContext)
        open()
        if (watchdog == null) watchdog = Timer("icom-watchdog", true).apply { schedule(2000, 2000) { check() } }
    }

    /** Stop: log out and do not reconnect. */
    fun disconnect() { target = null; watchdog?.cancel(); watchdog = null; close("WiFi connection closed") }

    private fun watchWifi(app: Context) {             // follow the phone's WiFi network
        if (callbackOn) return; callbackOn = true
        val c = app.getSystemService(ConnectivityManager::class.java) ?: return; cm = c
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        c.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "phone WiFi up: $network"); wifi = network
                c.bindProcessToNetwork(network)       // the app's traffic over WiFi, whatever Android's default
                if (target != null && rig == null) { retries = 0; nextTryMs = 0 } // back: the watchdog reconnects now
            }
            override fun onLost(network: Network) {
                Log.d(TAG, "phone WiFi lost: $network")
                if (wifi == network) { wifi = null; c.bindProcessToNetwork(null) }
                if (target != null) close("The phone's WiFi dropped - reconnecting when it is back")
            }
        })
    }

    private fun open() {                              // one connection attempt
        val t = target ?: return
        close(null)                                   // (any earlier attempt)
        val w = wifi ?: cm?.activeNetwork?.takeIf { cm?.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        if (w == null) { status.value = "Waiting for the phone's WiFi"; return }
        if (wifi == null) { wifi = w; cm?.bindProcessToNetwork(w) } // (the callback had not reported it yet)
        status.value = if (retries == 0) "Connecting to ${t.ip}..." else "Connecting to ${t.ip} (try ${retries + 1})..."
        Log.d(TAG, "open ${t.ip}:${t.port} try ${retries + 1}")
        val r = IComWifiRig(t.ip, t.port, t.user, t.password)
        r.onStatus = WifiRig.OnStatus { msg, ok ->   // login and network messages
            if (r !== rig) return@OnStatus           // (an old connection's last words)
            Log.d(TAG, "status: $msg ($ok)")
            status.value = msg; loggedIn = ok
            if (ok) { retries = 0; lastCivMs = System.currentTimeMillis(); Ic705.netConnected(true) }
            else { Ic705.netConnected(false, msg); rig = null; scheduleRetry() } // (FT8CN closes it itself)
        }
        r.setOnDataEvents(object : WifiRig.OnDataEvents {
            override fun onReceivedCivData(data: ByteArray) { if (r === rig) { lastCivMs = System.currentTimeMillis(); Ic705.netFrame(data) } } // CI-V from the radio
            override fun onReceivedWaveData(data: ByteArray) {                     // receive audio: 12 kHz 16-bit little-endian
                if (r !== rig) return
                val n = data.size / 2; val s = ShortArray(n) { i -> ((data[2 * i].toInt() and 0xFF) or (data[2 * i + 1].toInt() shl 8)).toShort() }
                AudioIn.netAudio(s, n)
            }
        })
        rig = r; startedMs = System.currentTimeMillis(); loggedIn = false
        Thread({ try { r.start() } catch (e: Exception) { status.value = "Could not connect: ${e.message}" } }, "icom-net").start() // (opens sockets: not on the main thread)
    }

    private fun close(why: String?) {                 // log out of the current connection, if any
        val r = rig
        rig = null; loggedIn = false
        if (r != null) Thread({ try { r.close() } catch (e: Exception) { } }, "icom-net-close").start() // (token delete, CI-V close)
        if (why != null) status.value = why
        Ic705.netConnected(false, why ?: "")
    }

    private fun scheduleRetry() {                     // back off: 5, 10, 20, 30 s
        retries++
        nextTryMs = System.currentTimeMillis() + listOf(5_000L, 10_000L, 20_000L, 30_000L)[minOf(retries - 1, 3)]
    }

    private fun check() {                             // the watchdog (every 2 s)
        if (target == null) return
        val now = System.currentTimeMillis()
        when {
            rig == null -> if (now >= nextTryMs && wifi != null) open() // waiting to try again
            !loggedIn && now - startedMs > 10_000 -> { Log.d(TAG, "watchdog: no login"); close("The radio did not answer - trying again (an old session may still be open on it)"); scheduleRetry() }
            loggedIn && now - lastCivMs > 8_000 -> { Log.d(TAG, "watchdog: radio quiet"); close("The radio stopped answering - reconnecting"); scheduleRetry() }
        }
    }

    /** CI-V command bytes to the radio. */
    fun civ(frame: ByteArray) { rig?.sendCivData(frame) }

    /** PTT, which also lets the transmit audio through (FT8CN's setPttOn). */
    fun ptt(on: Boolean) { rig?.setPttOn(on) }

    /** Transmit audio: 12 kHz floats, -1..1, sent in real time by the protocol code (20 ms packets). */
    fun sendAudio(samples: FloatArray) { rig?.sendWaveData(samples) }
}
