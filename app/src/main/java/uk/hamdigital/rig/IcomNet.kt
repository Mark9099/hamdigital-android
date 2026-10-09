// The IC-705 over WiFi (no lead): Icom's network protocol - the one the RS-BA1 software and the radio's "Network"
// menu use - through FT8CN's implementation (MIT; package uk.hamdigital.icom). The radio sends its receive audio
// (12 kHz, 16-bit) and CI-V replies; the app sends CI-V commands, PTT and transmit audio. On the IC-705: MENU > SET >
// WLAN Set (connect it to the same WiFi as the phone, or use its own access point), and Network > Network User1 for
// the user name and password; the radio's IP address is shown under WLAN Set > Connection Status.
package uk.hamdigital.rig

import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.audio.AudioIn
import uk.hamdigital.icom.IComWifiRig
import uk.hamdigital.icom.WifiRig

object IcomNet {
    @Volatile var rig: IComWifiRig? = null; private set // the connection, while open
    val status = MutableStateFlow("")                 // what is happening
    @Volatile var loggedIn = false; private set       // the radio accepted us

    /** Connect (closing any earlier connection). */
    fun connect(ip: String, port: Int, user: String, password: String) {
        close()
        if (ip.isBlank()) { status.value = "Set the IC-705's IP address in Settings > Connection"; return }
        status.value = "Connecting to $ip..."
        val r = IComWifiRig(ip.trim(), port, user, password)
        r.onStatus = WifiRig.OnStatus { msg, ok ->   // login and network messages
            status.value = msg; loggedIn = ok
            if (ok) Ic705.netConnected(true) else Ic705.netConnected(false, msg)
        }
        r.setOnDataEvents(object : WifiRig.OnDataEvents {
            override fun onReceivedCivData(data: ByteArray) = Ic705.netFrame(data) // CI-V from the radio
            override fun onReceivedWaveData(data: ByteArray) {                     // receive audio: 12 kHz 16-bit little-endian
                val n = data.size / 2; val s = ShortArray(n) { i -> ((data[2 * i].toInt() and 0xFF) or (data[2 * i + 1].toInt() shl 8)).toShort() }
                AudioIn.netAudio(s, n)
            }
        })
        rig = r
        Thread({ try { r.start() } catch (e: Exception) { status.value = "Could not connect: ${e.message}" } }, "icom-net").start() // (opens sockets: not on the main thread)
    }

    fun close() {
        val r = rig ?: return
        rig = null; loggedIn = false
        Thread({ try { r.close() } catch (e: Exception) { } }, "icom-net-close").start()
        Ic705.netConnected(false, "WiFi connection closed")
    }

    /** CI-V command bytes to the radio. */
    fun civ(frame: ByteArray) { rig?.sendCivData(frame) }

    /** PTT, which also lets the transmit audio through (FT8CN's setPttOn). */
    fun ptt(on: Boolean) { rig?.setPttOn(on) }

    /** Transmit audio: 12 kHz floats, -1..1, sent in real time by the protocol code (20 ms packets). */
    fun sendAudio(samples: FloatArray) { rig?.sendWaveData(samples) }
}
