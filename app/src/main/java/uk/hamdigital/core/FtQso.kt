// FT8 / FT4 contacts, the WSJT-X way: the six standard messages, sent in alternate slots, moved on automatically by
// what the other station sends (auto-sequencing), and the contact logged when it is complete.
//   Tx1  DX MYCALL GRID      (answering a CQ)        Tx4  DX MYCALL RR73
//   Tx2  DX MYCALL -12       (a report)              Tx5  DX MYCALL 73
//   Tx3  DX MYCALL R-08      (roger + report)        Tx6  CQ MYCALL GRID
// Start one by tapping a decoded line (a CQ, or a station calling you) or with Call CQ. Transmissions start 0.5 s
// into each slot of the chosen parity (1st = even slots, 2nd = odd), at the TX audio offset. A message unanswered
// after MAX_REPEATS slots stops transmitting (WSJT-X's watchdog idea); Halt stops at once.
package uk.hamdigital.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.engine.Ft8Native
import uk.hamdigital.rig.Ic705
import java.util.Timer
import kotlin.concurrent.schedule

class FtQso(private val ft4: Boolean) {
    data class State(
        val enabled: Boolean = false,                 // transmitting in our slots
        val dx: String = "",                          // the other station ("" = calling CQ / idle)
        val dxGrid: String = "",                      // its locator
        val sent: Int? = null,                        // the report we give (their signal, dB)
        val rcvd: String = "",                        // the report they gave us
        val next: Int = 6,                            // the message to send next (1..6)
        val secondSlot: Boolean = true,               // transmit in the 2nd (odd) slots
        val txHz: Int = 1500,                         // transmit audio offset
        val repeats: Int = 0,                         // times the current message has gone unanswered
        val status: String = "",                      // what is happening
        val logged: Boolean = false,                  // this contact is in the log
        val startMs: Long = 0,                        // when this contact began
    )

    val state = MutableStateFlow(State())             // for the page
    private val period = if (ft4) 7500L else 15000L   // slot length
    private var timer: Timer? = null                  // the next slot's transmission
    private lateinit var app: Context                 // (for the transmitter and the log)
    @Volatile var myCall = ""; @Volatile var myGrid = "" // you
    @Volatile var level = 0.3f                        // transmit amplitude 0..1
    private val mode = if (ft4) "FT4" else "FT8"      // for the log
    private val MAX_REPEATS = 6                       // unanswered slots before giving up

    private fun rep(db: Int?) = (db ?: -10).coerceIn(-30, 30).let { if (it >= 0) "+%02d".format(it) else "-%02d".format(-it) } // "+05", "-12"
    private fun grid4() = myGrid.take(4).uppercase().let { it.take(2) + it.drop(2) } // (FT8 sends 4 characters)

    /** The text of message [n] now. */
    fun text(n: Int, s: State = state.value): String = when (n) {
        1 -> "${s.dx} $myCall ${grid4()}"
        2 -> "${s.dx} $myCall ${rep(s.sent)}"
        3 -> "${s.dx} $myCall R${rep(s.sent)}"
        4 -> "${s.dx} $myCall RR73"
        5 -> "${s.dx} $myCall 73"
        else -> "CQ $myCall ${grid4()}"
    }

    fun attach(ctx: Context) { app = ctx.applicationContext } // (once, from the page)

    private fun set(f: (State) -> State) { state.value = f(state.value) }

    /** Start calling CQ in the chosen slots. */
    fun callCq() { set { it.copy(dx = "", dxGrid = "", sent = null, rcvd = "", next = 6, repeats = 0, logged = false, enabled = true, status = "Calling CQ") }; schedule() }

    /** Answer or call [d]'s station: from a decoded line (CQ, or a message to us). */
    fun pick(d: FtDecode) {
        if (d.from.isEmpty() || d.from == myCall) return
        val theirParityOdd = (d.slotMs / period) % 2 == 1L // they transmit in odd slots: we use the even ones
        set { State(enabled = true, dx = d.from, dxGrid = d.grid, sent = d.snr, next = 1, secondSlot = !theirParityOdd, txHz = it.txHz, status = "Calling ${d.from}", startMs = System.currentTimeMillis()) }
        if (d.toMe) onDecodes(listOf(d))              // they are already calling us: move straight on
        schedule()
    }

    fun setNext(n: Int) = set { it.copy(next = n, repeats = 0) }                 // choose the message by hand
    fun setSlot(second: Boolean) { set { it.copy(secondSlot = second) }; schedule() } // 1st / 2nd slots
    fun setTxHz(hz: Int) = set { it.copy(txHz = hz.coerceIn(200, 2800)) }         // transmit offset
    fun enable(on: Boolean) { set { it.copy(enabled = on, repeats = 0, status = if (on) "Transmit enabled" else "Transmit off") }; if (on) schedule() else timer?.cancel() }
    fun halt() { timer?.cancel(); Transmitter.halt(); set { it.copy(enabled = false, status = "Halted") } } // stop now
    fun clearDx() = set { State(secondSlot = it.secondSlot, txHz = it.txHz) } // forget the contact

    /** A slot's decodes (the decoder thread): auto-sequencing. */
    fun onDecodes(list: List<FtDecode>) {
        val s = state.value
        for (d in list) {
            if (!d.toMe) continue                     // only messages to us matter
            val w = d.text.split(' ').filter { it.isNotEmpty() }
            val extra = w.getOrNull(2) ?: ""          // what they sent after the calls
            if (s.dx.isEmpty() && s.next == 6 && s.enabled) { // calling CQ: the first to answer becomes the DX
                set { it.copy(dx = d.from, dxGrid = d.grid, sent = d.snr, startMs = System.currentTimeMillis(), logged = false) }
            } else if (d.from != state.value.dx) continue // someone else
            val cur = state.value
            when {
                Locator.valid(extra) && extra != "RR73" -> set { it.copy(dxGrid = extra, next = 2, sent = d.snr, repeats = 0, status = "${d.from} answered: sending a report") } // their Tx1: send our report
                extra.matches(Regex("R[+-]\\d\\d")) -> { set { it.copy(rcvd = extra.drop(1), next = 4, repeats = 0, status = "Report received: sending RR73") }; log() } // R-report: RR73 (contact complete)
                extra.matches(Regex("[+-]\\d\\d")) -> set { it.copy(rcvd = extra, sent = cur.sent ?: d.snr, next = 3, repeats = 0, status = "Report received: sending R + report") } // a report: R-report
                extra == "RR73" || extra == "RRR" -> { set { it.copy(next = 5, repeats = 0, status = "Confirmed: sending 73") }; log() } // RR73: 73 (contact complete)
                extra == "73" -> { log(); set { it.copy(enabled = false, status = "Contact complete (73)") }; timer?.cancel() } // 73: done
            }
        }
    }

    private fun log() {                               // put the contact in the log (once)
        val s = state.value; if (s.logged || s.dx.isEmpty() || !::app.isInitialized) return
        Logbook.add(app, Qso(s.dx, s.dxGrid, mode, rep(s.sent), s.rcvd, if (s.startMs > 0) s.startMs else System.currentTimeMillis(), System.currentTimeMillis(),
            Ic705.state.value.freqHz, myCall, myGrid))
        set { it.copy(logged = true) }
    }

    /** Arrange the next transmission: 0.5 s into the next slot of our parity. */
    private fun schedule() {
        timer?.cancel(); if (!state.value.enabled) return
        val now = System.currentTimeMillis()
        var slot = now - now % period + period        // the next slot
        if (((slot / period) % 2 == 1L) != state.value.secondSlot) slot += period // ours?
        val at = slot + 500                           // transmissions start 0.5 s in
        timer = Timer("ft-tx", true).apply { schedule(maxOf(0L, at - 400 - now)) { transmit(at) } } // (wake 0.4 s early to prepare the audio)
    }

    private fun transmit(at: Long) {
        val s = state.value; if (!s.enabled) return
        if (s.repeats >= MAX_REPEATS) { set { it.copy(enabled = false, status = "No answer after $MAX_REPEATS tries: transmit off") }; return }
        if (s.next == 5 && s.repeats >= 1) { set { it.copy(enabled = false, status = "Contact complete") }; return } // 73 sent once is enough
        val msg = text(s.next, s)
        val audio = Ft8Native.encode(msg, ft4, s.txHz.toFloat(), level)
        if (audio == null) { set { it.copy(enabled = false, status = "Cannot send \"$msg\"") }; return }
        val started = Transmitter.send(app, myCall, audio, 12000, at) { schedule() } // then the next of our slots
        set { it.copy(repeats = if (started) it.repeats + 1 else it.repeats, status = if (started) "Sending: $msg" else Transmitter.lastError.value) }
        if (!started) set { it.copy(enabled = false) }
    }

    companion object {
        val FT8 by lazy { FtQso(false) }              // the app's FT8 contacts
        val FT4 by lazy { FtQso(true) }               // and FT4
    }
}
