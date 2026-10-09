// The IC-705 over its USB lead (or, with IcomNet, over WiFi): CI-V control on the radio's first USB serial port (the second carries GPS data), using
// usb-serial-for-android (MIT) for the CDC-ACM serial link. Reads the frequency and mode (asked every second, and from
// the radio's own transceive broadcasts), tunes it, sets the mode (with DATA for the digital modes), and - from stage 7 -
// keys the transmitter. CI-V frames: FE FE <to> <from> <command> [sub] [data] FD; the IC-705's address is A4 by default
// (MENU > SET > Connectors > CI-V > CI-V Address), the app's E0.
package uk.hamdigital.rig

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream

/** The radio as last heard. */
data class RigState(
    val link: Link = Link.NONE,                       // connection
    val message: String = "",                         // what is happening / went wrong
    val freqHz: Long = 0,                             // VFO frequency
    val mode: String = "",                            // USB, LSB, CW, ...
    val data: Boolean = false,                        // DATA mode on (USB-D)
    val tx: Boolean = false,                          // transmitting
) {
    enum class Link { NONE, ASKING, CONNECTED }       // not connected / waiting for USB permission / talking to it
    /** "14.074.000" */
    val freqText get() = if (freqHz <= 0) "--.---.---" else "%d.%03d.%03d".format(freqHz / 1_000_000, freqHz / 1000 % 1000, freqHz % 1000)
    val modeText get() = if (mode.isEmpty()) "" else mode + if (data) "-D" else "" // "USB-D"
}

object Ic705 {
    const val VENDOR_ICOM = 0x0C26                    // Icom's USB vendor id
    const val PRODUCT_IC705 = 0x0036                  // the IC-705
    private const val ACTION_PERMISSION = "uk.hamdigital.USB_PERMISSION" // our permission answer
    private const val CTRL = 0xE0                     // the app's CI-V address (a controller)
    @Volatile var civAddress = 0xA4                   // the radio's CI-V address (IC-705 default)

    private val _state = MutableStateFlow(RigState()) // the radio, for the screens
    val state: StateFlow<RigState> = _state

    private var port: UsbSerialPort? = null           // the open CI-V port
    private var reader: Thread? = null                // reads its replies; asks for the frequency and mode every second
    @Volatile private var running = false             // reader running
    private var receiverOn = false                    // permission receiver registered
    private val lock = Any()                          // writes from several threads

    /** Mode names by CI-V mode number (command 04 / 01). */
    private val MODES = mapOf(0x00 to "LSB", 0x01 to "USB", 0x02 to "AM", 0x03 to "CW", 0x04 to "RTTY", 0x05 to "FM", 0x06 to "WFM", 0x07 to "CW-R", 0x08 to "RTTY-R", 0x17 to "DV")

    /** The IC-705 (or another Icom) on the USB bus, if plugged in. */
    fun findDevice(ctx: Context): UsbDevice? {
        val um = ctx.getSystemService(UsbManager::class.java) ?: return null // no USB host
        val icom = um.deviceList.values.filter { it.vendorId == VENDOR_ICOM } // Icom radios
        return icom.firstOrNull { it.productId == PRODUCT_IC705 } ?: icom.firstOrNull() // the IC-705 first
    }

    /** Connect if the radio is plugged in and not yet connected (asks for USB permission the first time). Safe to call often. */
    fun connect(ctx: Context) {
        val app = ctx.applicationContext               // (outlives any screen)
        if (port != null || net) return                // already connected (USB or WiFi)
        val dev = findDevice(app) ?: run { set { it.copy(link = RigState.Link.NONE, message = "IC-705 not plugged in") }; return }
        val um = app.getSystemService(UsbManager::class.java)
        if (!um.hasPermission(dev)) {                  // Android asks the user once
            registerReceiver(app)                      // to hear the answer
            val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), PendingIntent.FLAG_MUTABLE) // (mutable: Android adds the answer)
            um.requestPermission(dev, pi)              // the "Allow HF Digital Modes to access IC-705?" box
            set { it.copy(link = RigState.Link.ASKING, message = "Allow access to the IC-705 (Android is asking)") }
            return
        }
        open(app, um, dev)                             // allowed: open it
    }

    private fun registerReceiver(app: Context) {
        if (receiverOn) return; receiverOn = true      // once
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    ACTION_PERMISSION -> if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect(c) // allowed: connect
                                         else set { it.copy(link = RigState.Link.NONE, message = "USB access to the IC-705 was not allowed") }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> if (findDevice(c) == null) disconnect("IC-705 unplugged") // the lead came out
                }
            }
        }
        val f = IntentFilter(ACTION_PERMISSION).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) } // permission answers, unplugging
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f) // (system broadcasts and our own permission answer still arrive)
    }

    private fun open(app: Context, um: UsbManager, dev: UsbDevice) {
        registerReceiver(app)                          // to hear it unplugged
        try {
            val drv = CdcAcmSerialDriver(dev)          // the radio's USB serial ports (composite device: audio + 2 serial)
            val p = drv.ports.firstOrNull() ?: throw Exception("no serial port") // port A: CI-V
            val conn = um.openDevice(dev) ?: throw Exception("would not open")
            p.open(conn); p.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) // (the speed is nominal over USB)
            p.dtr = true; p.rts = false                // RTS low: the IC-705 can key on RTS (USB SEND), so never raise it
            port = p; running = true
            set { RigState(link = RigState.Link.CONNECTED, message = "IC-705 connected (CI-V)") }
            reader = Thread({ readLoop(p) }, "civ").apply { isDaemon = true; start() } // replies + polling
        } catch (e: Exception) { set { it.copy(link = RigState.Link.NONE, message = "IC-705 CI-V would not open: ${e.message}") } }
    }

    fun disconnect(why: String = "Disconnected") {
        running = false; try { port?.close() } catch (e: Exception) { }; port = null // close the port (ends the reader)
        set { RigState(link = RigState.Link.NONE, message = why) }
    }

    private fun readLoop(p: UsbSerialPort) {
        val buf = ByteArray(256); val frame = ByteArrayOutputStream() // read buffer, the frame being collected
        var lastPoll = 0L                              // when the radio was last asked
        while (running) {
            val now = System.currentTimeMillis()
            if (now - lastPoll > 1000) { lastPoll = now; send(0x03); send(0x04); send(0x1A, 0x06) } // frequency, mode, data mode (each second)
            val n = try { p.read(buf, 200) } catch (e: Exception) { if (running) disconnect("IC-705 link lost: ${e.message}"); return } // waits up to 0.2 s
            for (i in 0 until n) {                     // collect frames
                val b = buf[i].toInt() and 0xFF
                if (b == 0xFE && frame.size() >= 2 && frame.toByteArray().all { (it.toInt() and 0xFF) == 0xFE }) continue // (extra preamble bytes)
                frame.write(b)
                if (b == 0xFD) { handle(frame.toByteArray()); frame.reset() } // end of frame
                if (frame.size() > 64) frame.reset()   // rubbish: start again
            }
        }
    }

    /** One frame from the radio: FE FE to from cmd [data] FD. */
    private fun handle(f: ByteArray) {
        val u = f.map { it.toInt() and 0xFF }          // unsigned
        if (u.size < 6 || u[0] != 0xFE || u[1] != 0xFE) return // not a frame
        if (u[3] == CTRL) return                       // our own command echoed back (CI-V USB Echo Back on)
        if (u[2] != CTRL && u[2] != 0x00) return       // for another controller
        val cmd = u[4]; val d = u.subList(5, u.size - 1) // command, data
        when (cmd) {
            0x00, 0x03 -> if (d.size >= 5) set { it.copy(freqHz = bcdToHz(d)) } // frequency (broadcast / reply)
            0x01, 0x04 -> if (d.isNotEmpty()) set { it.copy(mode = MODES[d[0]] ?: "?") } // mode (broadcast / reply)
            0x1A -> if (d.size >= 2 && d[0] == 0x06) set { it.copy(data = d[1] != 0) } // data mode
            0x1C -> if (d.size >= 2 && d[0] == 0x00) set { it.copy(tx = d[1] != 0) } // transmit state
            0xFA -> set { it.copy(message = "The IC-705 refused a command") } // NG
        }
    }

    private fun bcdToHz(d: List<Int>): Long { var hz = 0L; var mul = 1L; for (i in 0 until 5) { hz += ((d[i] and 0x0F) + 10 * (d[i] shr 4)) * mul; mul *= 100 }; return hz } // 5 bytes, least significant first

    private fun hzToBcd(hz: Long): IntArray { var v = hz; return IntArray(5) { val lo = (v % 10).toInt(); v /= 10; val hi = (v % 10).toInt(); v /= 10; (hi shl 4) or lo } } // the reverse

    /** Send a command (bytes after the addresses, before FD). */
    private fun send(vararg body: Int) {
        val f = ByteArray(body.size + 5)               // FE FE to from .. FD
        f[0] = 0xFE.toByte(); f[1] = 0xFE.toByte(); f[2] = civAddress.toByte(); f[3] = CTRL.toByte()
        body.forEachIndexed { i, b -> f[4 + i] = b.toByte() }; f[f.size - 1] = 0xFD.toByte()
        if (net) { IcomNet.civ(f); return }            // over WiFi
        val p = port ?: return
        synchronized(lock) { try { p.write(f, 500) } catch (e: Exception) { } } // (a lost write is retried by the next poll)
    }

    // ---- WiFi (IcomNet): the same commands and replies over Icom's network protocol ----
    @Volatile var net = false; private set             // using the WiFi link
    private var netPoll: java.util.Timer? = null       // asks for the frequency and mode every second

    /** IcomNet: logged in (or not) to the radio over WiFi. */
    fun netConnected(on: Boolean, why: String = "") {
        android.util.Log.d("IcomNet", "netConnected($on, $why)")
        net = on; netPoll?.cancel(); netPoll = null
        if (on) {
            if (port != null) disconnect("Using WiFi")     // (one link at a time)
            net = true
            set { RigState(link = RigState.Link.CONNECTED, message = "IC-705 connected over WiFi") }
            netPoll = java.util.Timer("civ-net", true).apply { scheduleAtFixedRate(object : java.util.TimerTask() { override fun run() { send(0x03); send(0x04); send(0x1A, 0x06) } }, 500, 1000) }
        } else if (port == null) set { RigState(link = RigState.Link.NONE, message = why) }
    }

    /** IcomNet: CI-V bytes from the radio (one or more frames). */
    fun netFrame(data: ByteArray) {
        var start = -1
        for (i in data.indices) {                      // split at FD
            if (start < 0 && (data[i].toInt() and 0xFF) == 0xFE) start = i
            if (start >= 0 && (data[i].toInt() and 0xFF) == 0xFD) { handle(data.copyOfRange(start, i + 1)); start = -1 }
        }
    }

    /** Tune to [hz]. */
    fun setFrequency(hz: Long) { send(0x05, *hzToBcd(hz)); set { it.copy(freqHz = hz) } }

    /** Set the mode: USB with DATA on for the digital modes ("USB-D"), or CW. */
    fun setMode(cw: Boolean) {
        if (cw) { send(0x06, 0x03, 0x01); send(0x1A, 0x06, 0x00, 0x00) } // CW, filter 1; DATA off
        else { send(0x06, 0x01, 0x01); send(0x1A, 0x06, 0x01, 0x01) }    // USB, filter 1; DATA on (D1), filter 1
    }

    /** Tune to a mode's dial frequency and set its mode. */
    fun tune(khz: Int, cw: Boolean) { setFrequency(khz * 1000L); setMode(cw) }

    /** Key / unkey the transmitter (over WiFi this also opens the transmit audio stream). */
    fun ptt(on: Boolean) { if (net) IcomNet.ptt(on) else send(0x1C, 0x00, if (on) 0x01 else 0x00) }

    /** Send Morse with the radio's own keyer (CI-V 17): up to 30 characters a command, so longer text goes in pieces
     *  (the radio queues them). Needs the radio in CW with break-in on. Only characters the keyer knows are sent. */
    fun sendCw(text: String) {
        val ok = text.uppercase().filter { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789/?.,-=+@: " } // the IC-705 keyer's characters
        ok.chunked(30).forEach { part -> send(0x17, *part.map { it.code }.toIntArray()) }
    }

    /** Stop the keyer's message now (CI-V 17 FF). */
    fun stopCw() { send(0x17, 0xFF) }

    /** Keyer speed, 6-48 WPM (CI-V 14 0C, 0-255 as 4-digit BCD). */
    fun setCwSpeed(wpm: Int) {
        val v = ((wpm.coerceIn(6, 48) - 6) * 255 + 21) / 42 // 6 WPM = 0, 48 WPM = 255
        send(0x14, 0x0C, (v / 100), ((v / 10 % 10) shl 4) or (v % 10)) // BCD: 0x0X 0xYZ
    }

    private inline fun set(f: (RigState) -> RigState) { _state.value = f(_state.value) } // update the state
}
