package com.relay.app.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Distance and direction between two GPS points (pure maths, works offline, unit tested). */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in metres (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial compass bearing from point 1 to point 2, 0..360 degrees (0 = north, 90 = east). */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return normalize(Math.toDegrees(atan2(y, x)))
    }

    /** Angle to turn the on-screen arrow: target bearing relative to where the phone points. */
    fun relativeAngle(bearing: Double, heading: Double): Double = normalize(bearing - heading)

    fun normalize(degrees: Double): Double = ((degrees % 360) + 360) % 360

    /** "north-east" for 45 degrees, etc. */
    fun compassWord(bearing: Double): String {
        val words = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
        return words[((normalize(bearing) + 22.5) / 45).toInt() % 8]
    }

    /** "35 m", "240 m", "1.2 km". */
    fun formatDistance(meters: Double): String = when {
        meters < 10 -> "${meters.roundToInt()} m"
        meters < 1000 -> "${(meters / 5).roundToInt() * 5} m"
        meters < 10_000 -> String.format(java.util.Locale.US, "%.1f km", meters / 1000)
        else -> "${(meters / 1000).roundToInt()} km"
    }
}
