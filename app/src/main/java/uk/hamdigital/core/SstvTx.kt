// SSTV send, with SSTV Encoder 2's modes (Olga Miller, Apache 2.0; om.sstvencoder): a picture - cropped to the
// mode's shape and scaled to its size, with your callsign and a line of text written over it - is encoded into audio
// (the VIS header, then the scan lines) and sent with the Transmitter as one transmission (36 s for Robot 36, up to
// 289 s for PD 290).
package uk.hamdigital.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import om.sstvencoder.ModeInterfaces.ModeSize
import om.sstvencoder.Modes.ModeFactory
import om.sstvencoder.Output.IOutput
import uk.hamdigital.audio.Transmitter
import kotlin.math.max
import kotlin.math.min

object SstvTx {
    const val RATE = 12000                            // the audio rate made (SSTV's tones are 1100-2300 Hz)

    /** An SSTV mode the encoder has: its name, picture size, and how long a transmission takes. */
    class Opt(val name: String, val cls: Class<*>, val w: Int, val h: Int, val seconds: Int)

    /** The encoder's modes (Robot 36 first, the encoder's default), with their lengths measured once. */
    val modes: List<Opt> by lazy {
        val secs = mapOf("Robot 36" to 36, "Robot 72" to 72, "Martin 1" to 114, "Martin 2" to 58, "Scottie 1" to 110, "Scottie 2" to 71,
            "Scottie DX" to 269, "PD 50" to 50, "PD 90" to 90, "PD 120" to 126, "PD 160" to 161, "PD 180" to 187, "PD 240" to 248, "PD 290" to 289, "Wraase SC2-180" to 182)
        ModeFactory.getModeInfoList().map { i ->
            val c = Class.forName(i.modeClassName); val s = c.getAnnotation(ModeSize::class.java)!!
            Opt(i.modeName, c, s.width, s.height, secs[i.modeName] ?: 0)
        }.sortedBy { if (it.name == "Robot 36") 0 else 1 }
    }

    /** [src] cropped to [o]'s shape (the middle kept) and scaled to its size, with [top] (your call) and [bottom]
     *  written over it in white with a black edge. A new bitmap. */
    fun compose(src: Bitmap, o: Opt, top: String, bottom: String): Bitmap {
        val out = Bitmap.createBitmap(o.w, o.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.BLACK)
        val a = o.w.toFloat() / o.h; val sa = src.width.toFloat() / src.height // shapes
        val cw = if (sa > a) (src.height * a).toInt() else src.width; val ch = if (sa > a) src.height else (src.width / a).toInt() // the part kept
        val sx = (src.width - cw) / 2; val sy = (src.height - ch) / 2
        c.drawBitmap(src, Rect(sx, sy, sx + cw, sy + ch), Rect(0, 0, o.w, o.h), Paint(Paint.FILTER_BITMAP_FLAG))
        fun text(t: String, size: Float, y: Float) {  // a line of text, outlined so it reads on any picture
            if (t.isBlank()) return
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            while (p.measureText(t) > o.w * 0.94f && p.textSize > 8f) p.textSize -= 1f // (fits the width)
            p.style = Paint.Style.STROKE; p.strokeWidth = max(2f, p.textSize / 9); p.color = Color.BLACK; c.drawText(t, o.w / 2f, y, p)
            p.style = Paint.Style.FILL; p.color = Color.WHITE; c.drawText(t, o.w / 2f, y, p)
        }
        val big = o.h * 0.16f; val small = o.h * 0.09f   // text sizes by the picture's height
        text(top, big, big * 1.05f)
        text(bottom, small, o.h - small * 0.45f)
        return out
    }

    /** The audio for picture [pic] (already [o]'s size) at [level] (0..1), as 16-bit samples at RATE. */
    fun encode(pic: Bitmap, o: Opt, level: Float): ShortArray {
        var buf = ShortArray(0); var n = 0             // the samples (the mode says how many at init)
        val g = level.coerceIn(0f, 1f) * 32767
        val out = object : IOutput {                  // collects what the mode writes
            override fun getSampleRate() = RATE.toDouble()
            override fun init(samples: Int) { buf = ShortArray(samples); n = 0 }
            override fun write(value: Double) { if (n == buf.size) buf = buf.copyOf(buf.size + RATE); buf[n++] = (value * g).toInt().toShort() } // (grows if the count was short)
            override fun finish(cancel: Boolean) {}
        }
        val m = ModeFactory.CreateMode(o.cls, pic.copy(Bitmap.Config.ARGB_8888, false), out) ?: return ShortArray(0) // (it recycles its bitmap at the end)
        m.init(); while (m.process()) { }; m.finish(false)
        return if (n == buf.size) buf else buf.copyOf(n)
    }

    /** Send [pic] (made by compose) in mode [o]: false (with Transmitter.lastError) if it could not start. */
    fun send(ctx: Context, myCall: String, pic: Bitmap, o: Opt, level: Float): Boolean {
        val audio = encode(pic, o, level)
        if (audio.isEmpty()) { Transmitter.lastError.value = "The picture could not be encoded for ${o.name}"; return false }
        return Transmitter.send(ctx, myCall, audio, RATE, 0, Mode.SSTV.name)
    }

    /** A picture's size for showing (keeps memory down for large photos). */
    fun shrink(b: Bitmap, maxSide: Int = 1600): Bitmap {
        val s = min(1f, maxSide.toFloat() / max(b.width, b.height)); if (s >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true)
    }
}
