// JS8 sending: a message (heartbeat, CQ, directed, text) is packed into frames by JS8Call's packers (Js8Native.build)
// and the frames go out one a slot in consecutive 15 s slots, each starting 0.5 s in (JS8 Normal), at the receive
// offset (as JS8Call transmits on its selected offset). Halt stops at once and drops the rest.
package uk.hamdigital.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import uk.hamdigital.audio.Transmitter
import uk.hamdigital.engine.Js8Native
import java.util.Timer
import kotlin.concurrent.schedule

object Js8Tx {
    val status = MutableStateFlow("")                 // what is happening
    val queued = MutableStateFlow(0)                  // frames still to send
    private val frames = ArrayDeque<Pair<String, Int>>() // frame, bits
    private var timer: Timer? = null                  // the next frame's slot
    private lateinit var app: Context
    @Volatile var myCall = ""; @Volatile var myGrid = ""; @Volatile var level = 0.3f; @Volatile var txHz = 1500 // you, the level, the offset

    fun attach(ctx: Context) { app = ctx.applicationContext }

    /** Queue a message (see Js8Native.build) and start sending it in the next slot. False if it could not be packed. */
    @Synchronized fun send(kind: Int, to: String = "", cmd: Int = 0, num: String = "", text: String = "", label: String): Boolean {
        val f = Js8Native.build(kind, myCall, myGrid, to, cmd, num, text).mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }
        if (f.isEmpty()) { status.value = "Cannot send that (callsigns must be standard; text: letters, figures and . - + ? ! \" /)"; return false }
        frames.clear(); frames.addAll(f); queued.value = f.size
        status.value = "Queued: $label (${f.size} frame${if (f.size > 1) "s" else ""})"
        schedule(); return true
    }

    @Synchronized fun halt() { frames.clear(); queued.value = 0; timer?.cancel(); Transmitter.halt(); status.value = "Halted" }

    private fun schedule() {                          // the next frame, 0.5 s into the next slot
        timer?.cancel(); if (frames.isEmpty()) return
        val now = System.currentTimeMillis()
        val at = now - now % 15_000 + 15_000 + 500
        timer = Timer("js8-tx", true).apply { schedule(maxOf(0L, at - 400 - now)) { next(at) } }
    }

    @Synchronized private fun next(at: Long) {
        val f = frames.removeFirstOrNull() ?: return
        queued.value = frames.size
        val audio = Js8Native.audio(f.first, f.second, txHz.toDouble(), level.toDouble())
        val ok = Transmitter.send(app, myCall, audio, 12000, at, Mode.JS8.name) { schedule() } // then the next frame
        status.value = if (ok) "Sending frame [${f.first}]${if (frames.isNotEmpty()) ", ${frames.size} more" else ""}" else Transmitter.lastError.value
        if (!ok) { frames.clear(); queued.value = 0 }
    }
}
