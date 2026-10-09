// The map view centred on the station, as HF Propagation's (uk.hamprop.map.MapProjection.Centred): azimuthal
// equidistant - straight lines from the centre are great circles (beam headings) and distance from the centre is true
// distance, so land keeps its shape close in; the antipode is the rim. Here in "unit" coordinates (the rim at radius 1,
// y downwards): the map's outlines are projected once for a centre, then drawn moved and scaled to the view.
package uk.hamdigital.map

import kotlin.math.*

/** Centred on [lat0], [lon0] (degrees). */
class CentredMap(val lat0: Double, val lon0: Double) {
    private val sp = sin(lat0 * D); private val cp = cos(lat0 * D) // centre latitude

    /** lat/lon -> unit x, y into [out] (distance from the centre: 0 here, 1 at the antipode, 20,015 km). */
    fun unit(lat: Double, lon: Double, out: FloatArray) {
        val p = lat * D; val dl = (lon - lon0) * D    // radians
        val cosc = (sp * sin(p) + cp * cos(p) * cos(dl)).coerceIn(-1.0, 1.0) // cos of the angular distance
        val c = acos(cosc)                            // angular distance
        val az = atan2(sin(dl) * cos(p), cp * sin(p) - sp * cos(p) * cos(dl)) // bearing
        val rho = c / PI                              // distance from the centre (1 = the rim)
        out[0] = (rho * sin(az)).toFloat(); out[1] = (-rho * cos(az)).toFloat() // east right, north up
    }

    companion object { const val D = PI / 180.0 }     // degrees -> radians
}
