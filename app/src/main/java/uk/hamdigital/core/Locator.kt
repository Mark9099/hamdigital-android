// Maidenhead locators: to latitude / longitude (the square's centre) and the great-circle distance and bearing between two.
package uk.hamdigital.core

import kotlin.math.*

object Locator {
    private val GRID = Regex("^[A-Ra-r]{2}[0-9]{2}([A-Xa-x]{2})?$") // 4 or 6 characters

    fun valid(s: String) = GRID.matches(s)            // a locator?

    /** Latitude, longitude (degrees) of the locator's centre, or null. */
    fun toLatLon(s: String): Pair<Double, Double>? {
        if (!valid(s)) return null
        val u = s.uppercase()
        var lon = (u[0] - 'A') * 20.0 - 180 + (u[2] - '0') * 2.0 // field, square
        var lat = (u[1] - 'A') * 10.0 - 90 + (u[3] - '0') * 1.0
        if (u.length == 6) { lon += (u[4] - 'A') * (5.0 / 60) + 2.5 / 60; lat += (u[5] - 'A') * (2.5 / 60) + 1.25 / 60 } // subsquare centre
        else { lon += 1.0; lat += 0.5 }               // square centre
        return lat to lon
    }

    /** Great-circle distance in km between two locators, or null. */
    fun km(a: String, b: String): Int? {
        val (la1, lo1) = toLatLon(a) ?: return null; val (la2, lo2) = toLatLon(b) ?: return null
        val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2); val dl = Math.toRadians(lo2 - lo1)
        val c = acos((sin(p1) * sin(p2) + cos(p1) * cos(p2) * cos(dl)).coerceIn(-1.0, 1.0)) // central angle
        return (c * 6371.0).roundToInt()             // Earth's mean radius
    }

    /** Great-circle distance in km between two points (degrees): APRS positions. */
    fun distanceKm(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2); val dl = Math.toRadians(lo2 - lo1)
        return acos((sin(p1) * sin(p2) + cos(p1) * cos(p2) * cos(dl)).coerceIn(-1.0, 1.0)) * 6371.0
    }

    /** Bearing in degrees from a to b, or null. */
    fun bearing(a: String, b: String): Int? {
        val (la1, lo1) = toLatLon(a) ?: return null; val (la2, lo2) = toLatLon(b) ?: return null
        val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2); val dl = Math.toRadians(lo2 - lo1)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).roundToInt()
    }
}
