// The flat world map (equirectangular, with zoom and pan, wrapping east-west), as HF Propagation's
// (uk.hamprop.map.MapProjection.Flat).
package uk.hamdigital.map

import kotlin.math.floor
import kotlin.math.max

/** [w] x [h] pixels; [zoom] 1 = the whole world's width (or height) across the view; centre [clon], [clat]. */
class FlatMap(val w: Float, val h: Float, val clon: Double, val clat: Double, val zoom: Double) {
    val ppd = max(w / 360.0, h / 180.0) * zoom        // pixels per degree: at 1x the world fills the width or the height, whichever is more
    val lat0: Double                                  // latitude at the view's centre (clamped so the map fills the height)
    init {
        val halfLat = h / 2 / ppd                     // degrees above and below the centre
        lat0 = if (halfLat >= 90) 0.0 else clat.coerceIn(-90 + halfLat, 90 - halfLat) // keep the poles in view's bounds
    }
    /** Screen x of a longitude, for the copy of the world nearest the view centre. */
    fun xOf(lon: Double): Double { var d = lon - clon; d -= 360.0 * floor((d + 180.0) / 360.0); return w / 2 + d * ppd } // wrap
    fun yOf(lat: Double): Double = h / 2 - (lat - lat0) * ppd // down = south
    /** Screen x, y -> lat/lon into [out]; false off the map. */
    fun toGeo(x: Float, y: Float, out: DoubleArray): Boolean {
        val lat = lat0 + (h / 2 - y) / ppd            // latitude
        if (lat < -90 || lat > 90) return false       // off the map
        var lon = clon + (x - w / 2) / ppd            // longitude
        lon -= 360.0 * floor((lon + 180.0) / 360.0)   // -180..180
        out[0] = lat; out[1] = lon; return true
    }
}
