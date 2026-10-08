// Slot-timed receive audio for the modes that transmit in fixed UTC slots (FT8, FT4, JS8): keeps the last 30 s of
// 12 kHz audio with its UTC time and, [decodeAtMs] into each [periodMs] slot (slots start on the UTC clock), hands that
// slot's audio - from the slot's start, silence for any part before the audio started - to [onSlot] on a single
// background thread, one slot at a time.
package uk.hamdigital.core

import java.util.concurrent.Executors

class SlotAudio(val periodMs: Long, private val decodeAtMs: Long, private val onSlot: (slotMs: Long, audio: ShortArray) -> Unit) {
    private val rate = 12000                          // samples a second
    private val ring = ShortArray(rate * 30)          // the last 30 s
    private var written = 0L                          // samples written since the start
    private var endMs = 0L                            // UTC time of the newest sample
    private var startedMs = 0L                        // UTC time of the first sample (audio started)
    private var lastSlot = 0L                         // the last slot handed on
    private val worker = Executors.newSingleThreadExecutor() // decodes, one slot at a time

    /** Audio in (the capture thread): 12 kHz mono. */
    fun feed(b: ShortArray, n: Int) {
        val now = System.currentTimeMillis()          // the newest sample's time (capture delay is a few ms)
        if (written == 0L || now - endMs > 2000) { startedMs = now - n * 1000L / rate; written = 0 } // (re)start: after a gap the old audio does not join on
        for (i in 0 until n) ring[((written + i) % ring.size).toInt()] = b[i] // into the ring
        written += n; endMs = now
        val slot = now - now % periodMs               // this slot's start
        if (now - slot >= decodeAtMs && slot != lastSlot) { lastSlot = slot; take(slot) } // nearly over: decode it
    }

    private fun take(slot: Long) {
        if (startedMs > slot + 3000) return           // too little of the slot was heard
        val n = ((endMs - slot) * rate / 1000).toInt().coerceAtMost(rate * 15) // samples from the slot start to now
        val first = written - n                        // the slot start's sample number
        val out = ShortArray(n)
        for (i in 0 until n) { val k = first + i; if (k >= 0 && k >= written - ring.size) out[i] = ring[(k % ring.size).toInt()] } // (before the audio: silence)
        worker.execute { onSlot(slot, out) }          // decode in the background
    }
}
