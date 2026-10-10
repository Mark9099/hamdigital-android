// FreeDATA (DJ2LS and others, GPL v3): its signalling frames, so the app hears FreeDATA stations and they hear it. Frames
// as FreeDATA's data_frame_factory.py builds them - the frame type first, then fixed fields: callsigns packed in 6 bytes
// with the SSID (helpers.encode_call), the locator in 4 (encode_grid, 6 characters), SNR x10 in one signed byte, CRC-24
// of "CALL-SSID" where a station is addressed - sent in codec2's DATAC13 mode (FreeDATA's "signalling"). Heard: CQ, QRV,
// beacons, pings and their acknowledgements, and the start of sessions (messages and files between two FreeDATA
// stations - shown, not taken part in). Sent: CQ, beacon, ping; with Answer on, a QRV to a CQ (after FreeDATA's random
// 0-5 s) and an acknowledgement to a ping for you, as FreeDATA does.
package uk.hamdigital.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.engine.FreeDataNative

object FreeData {
    const val RATE = 8000                             // codec2's data modems
    // frame types (FreeDATA modem_frametypes.py)
    const val ARQ_SESSION_OPEN = 12; const val ARQ_SESSION_OPEN_ACK = 13; const val P2P_CONNECT = 30
    const val CQ = 200; const val QRV = 201; const val PING = 210; const val PING_ACK = 211; const val BEACON = 250
    private const val LEN = 14                        // LENGTH_SIG0_FRAME: DATAC13's 16 bytes less the CRC

    /** A frame heard: who (if it says), what, their locator, the SNR we heard it at, and for addressed frames whether it was for us. */
    data class Heard(val time: Long, val type: Int, val from: String, val grid: String, val snr: Double, val text: String, val forMe: Boolean)
    val log = MutableStateFlow<List<Heard>>(emptyList())        // newest first
    val stations = MutableStateFlow<Map<String, Heard>>(emptyMap()) // each station's latest (by callsign)
    val sync = MutableStateFlow(false); val snr = MutableStateFlow(0f) // the DATAC13 receiver
    @Volatile var answer = false                      // answer CQs (QRV) and pings for us, as FreeDATA does
    @Volatile var myCall = ""; @Volatile var myGrid = ""; @Volatile var ssid = 0 // you (FreeDATA's mycall / myssid / mygrid)
    @Volatile var level = 0.3                         // transmit level
    private var ctx: Context? = null

    fun full() = "${myCall.uppercase()}-$ssid"        // FreeDATA's "myfullcall"

    // ---- FreeDATA helpers.py, as written ----
    fun crc24(s: String): ByteArray {                 // get_crc_24: CRC-24-OpenPGP
        var crc = 0xB704CE
        for (b in s.toByteArray()) { crc = crc xor ((b.toInt() and 0xFF) shl 16); repeat(8) { crc = if (crc and 0x800000 != 0) (crc shl 1) xor 0x864CFB else crc shl 1; crc = crc and 0xFFFFFF } }
        return byteArrayOf((crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
    }
    fun encodeCall(callSsid: String): ByteArray {      // callsign_to_bytes / encode_call: 6 bits a character, the SSID raw in the last 6
        val parts = callSsid.uppercase().split('-'); val call = parts[0]; val sid = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val s = call + sid.toChar()
        var w = 0L
        for (c in s) { w = (w shl 6) or ((c.code - 48).toLong() and 63) }
        w = (w shr 6) shl 6; w = w or (s.last().code.toLong() and 63)
        return ByteArray(6) { i -> (w shr (8 * (5 - i))).toByte() }
    }
    fun decodeCall(b: ByteArray, off: Int): String {  // bytes_to_callsign / decode_call
        var w = 0L; for (i in 0 until 6) w = (w shl 8) or (b[off + i].toLong() and 0xFF)
        val sid = (w and 63).toInt(); val sb = StringBuilder()
        while (w != 0L) { sb.insert(0, ((w and 63) + 48).toInt().toChar()); w = w shr 6 }
        val call = if (sb.isNotEmpty()) sb.substring(0, sb.length - 1) else ""
        return "$call-$sid"
    }
    fun encodeGrid(grid: String): ByteArray {          // encode_grid (6 characters; FreeDATA fills a 4-character one out at random - here with its middle, LL)
        val g = (if (grid.length >= 6) grid.substring(0, 6) else grid.take(4).padEnd(4, '0') + "LL").uppercase()
        var w = ((g[0].code - 65) * 18 + (g[1].code - 65)).toLong() and 0x1FF
        w = (w shl 9) or (g.substring(2, 4).toLong() and 0x7F)   // (FreeDATA shifts by 9 after the letters, then 7: kept)
        w = (w shl 7) or ((g[4].code - 65).toLong() and 0x1F)
        w = (w shl 5) or ((g[5].code - 65).toLong() and 0x1F)
        return ByteArray(4) { i -> (w shr (8 * (3 - i))).toByte() }
    }
    fun decodeGrid(b: ByteArray, off: Int): String {  // decode_grid
        var w = 0L; for (i in 0 until 4) w = (w shl 8) or (b[off + i].toLong() and 0xFF)
        var grid = ((w and 31) + 65).toInt().toChar().toString(); w = w shr 5
        grid = ((w and 31) + 65).toInt().toChar() + grid; w = w shr 7
        val num = (w and 127).toInt(); grid = (if (num < 10) "0$num" else "$num") + grid; w = w shr 9
        val v = (w and 511).toInt(); return "${(v / 18 + 65).toChar()}${(v % 18 + 65).toChar()}$grid"
    }
    private fun snrByte(s: Double) = (s * 10).coerceIn(-127.0, 127.0).toInt().toByte() // snr_to_bytes
    private fun hex(b: ByteArray, off: Int, n: Int) = (0 until n).joinToString("") { "%02x".format(b[off + it]) }

    // ---- frames (data_frame_factory.py) ----
    private fun frame(type: Int, vararg fields: ByteArray): ByteArray { val f = ByteArray(LEN); f[0] = type.toByte(); var p = 1; for (x in fields) { x.copyInto(f, p); p += x.size }; return f }
    fun buildCq() = frame(CQ, encodeCall(full()), encodeGrid(myGrid))
    fun buildQrv(snr: Double) = frame(QRV, encodeCall(full()), encodeGrid(myGrid), byteArrayOf(snrByte(snr)))
    fun buildBeacon() = frame(BEACON, encodeCall(full()), encodeGrid(myGrid), byteArrayOf(0))
    fun buildPing(to: String) = frame(PING, crc24(if ('-' in to) to.uppercase() else "${to.uppercase()}-0"), crc24(full()), encodeCall(full()))
    fun buildPingAck(originCrc: ByteArray, snr: Double) = frame(PING_ACK, originCrc, crc24(full()), encodeGrid(myGrid), byteArrayOf(snrByte(snr))) // (to the pinging station's own CRC: what FreeDATA checks)

    /** What a received frame says (FreeDATA deconstruct, for the frames shown). */
    fun parse(mode: Int, snrDb: Double, b: ByteArray, now: Long): Heard? {
        if (mode == FreeDataNative.DATAC14) return Heard(now, -1, "", "", snrDb, "acknowledgement (a session under way)", false) // (signalling ack: ARQ burst acks)
        if (b.isEmpty()) return null
        val t = b[0].toInt() and 0xFF
        val me = crc24(full())
        fun mine(off: Int) = (0 until 3).all { b[off + it] == me[it] } || hex(b, off, 3) == hex(crc24(hex(me, 0, 3)), 0, 3) // (and FreeDATA's own ping-ack form)
        return when (t) {
            CQ -> Heard(now, t, decodeCall(b, 1), decodeGrid(b, 7), snrDb, "CQ", false)
            QRV -> Heard(now, t, decodeCall(b, 1), decodeGrid(b, 7), snrDb, "QRV (ready), hears us at %.1f dB".format(b[11] / 10.0), false)
            BEACON -> Heard(now, t, decodeCall(b, 1), decodeGrid(b, 7), snrDb, if (b[11].toInt() and 1 != 0) "beacon (away from the radio)" else "beacon", false)
            PING -> Heard(now, t, decodeCall(b, 7), "", snrDb, if (mine(1)) "ping to you" else "ping", mine(1))
            PING_ACK -> Heard(now, t, "", decodeGrid(b, 7), snrDb, if (mine(1)) "ping answered, heard us at %.1f dB".format(b[11] / 10.0) else "ping answered", mine(1))
            ARQ_SESSION_OPEN -> Heard(now, t, decodeCall(b, 4), "", snrDb, if (mine(1)) "session opening to you (not supported here)" else "opens a session", mine(1))
            ARQ_SESSION_OPEN_ACK -> Heard(now, t, decodeCall(b, 2), "", snrDb, "session accepted", false)
            P2P_CONNECT -> Heard(now, t, decodeCall(b, 7), "", snrDb, "connects to ${decodeCall(b, 1)}", false)
            else -> Heard(now, t, "", "", snrDb, "FreeDATA frame (type $t)", false)
        }
    }

    fun attach(c: Context) { ctx = c.applicationContext }

    /** Audio in (the capture thread): 8 kHz mono. */
    fun feed(b: ShortArray, n: Int) {
        val now = System.currentTimeMillis()
        if (Transmitter.sentDuring(now - 600, now)) return // (our own frame going out)
        FreeDataNative.process(b, n)
        val st = FreeDataNative.stats(); sync.value = st[0] > 0.5f; snr.value = st[1]
        val r = FreeDataNative.take(); var i = 0
        while (i + 3 <= r.size) {
            val mode = r[i].toInt() and 0xFF; val s = r[i + 1] / 10.0; val len = r[i + 2].toInt() and 0xFF
            if (i + 3 + len > r.size) break
            val f = r.copyOfRange(i + 3, i + 3 + len); i += 3 + len
            val h = parse(mode, s, f, now) ?: continue
            log.value = (listOf(h) + log.value).take(300)
            if (h.from.isNotEmpty()) stations.value = stations.value + (h.from to h)
            if (answer) reply(h, f)
        }
    }

    private fun reply(h: Heard, f: ByteArray) {        // FreeDATA's frame handlers: QRV to a CQ, ack to a ping for us
        val c = ctx ?: return
        if (myCall.isBlank() || Transmitter.streaming) return
        when (h.type) {
            CQ -> Thread { Thread.sleep((Math.random() * 6000).toLong()); send(c, buildQrv(h.snr), "QRV to ${h.from}") }.start() // (FreeDATA: random 0-5 s, against collisions)
            PING -> if (h.forMe) Thread { Thread.sleep(500); send(c, buildPingAck(f.copyOfRange(4, 7), h.snr), "ping answered to ${h.from}") }.start()
        }
    }

    /** Send one signalling frame (DATAC13). Null if it went, else why not. */
    fun send(c: Context, frame: ByteArray, what: String): String? {
        val a = FreeDataNative.encode(FreeDataNative.DATAC13, frame) ?: return "Could not make the frame"
        val g = level.coerceIn(0.0, 1.0) * 2                  // (the modem's audio peaks near half full scale)
        for (k in a.indices) a[k] = (a[k] * g).toInt().coerceIn(-32768, 32767).toShort()
        if (!Transmitter.send(c, myCall, a, RATE, 0, Mode.FREEDATA.name)) return Transmitter.lastError.value
        log.value = (listOf(Heard(System.currentTimeMillis(), -2, "", "", 0.0, "sent: $what", false)) + log.value).take(300)
        return null
    }
}
