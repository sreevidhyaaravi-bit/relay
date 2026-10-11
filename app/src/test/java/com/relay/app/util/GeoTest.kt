package com.relay.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rescue Radar maths, checked against known distances and directions. */
class GeoTest {

    @Test
    fun `one degree of latitude is about 111 km`() {
        assertEquals(111_195.0, Geo.distanceMeters(0.0, 0.0, 1.0, 0.0), 50.0)
    }

    @Test
    fun `Delhi to Mumbai is about 1150 km`() {
        val km = Geo.distanceMeters(28.6139, 77.2090, 19.0760, 72.8777) / 1000
        assertTrue("was $km km", km in 1130.0..1170.0)
    }

    @Test
    fun `short distances are accurate to the metre`() {
        assertEquals(100.0, Geo.distanceMeters(12.9716, 77.5946, 12.9716 + 0.0008993, 77.5946), 1.0)
    }

    @Test
    fun `bearings point the right way`() {
        assertEquals(0.0, Geo.bearingDegrees(0.0, 0.0, 1.0, 0.0), 0.01)
        assertEquals(90.0, Geo.bearingDegrees(0.0, 0.0, 0.0, 1.0), 0.01)
        assertEquals(180.0, Geo.bearingDegrees(1.0, 0.0, 0.0, 0.0), 0.01)
        assertEquals(270.0, Geo.bearingDegrees(0.0, 1.0, 0.0, 0.0), 0.01)
        assertEquals(45.0, Geo.bearingDegrees(0.0, 0.0, 0.001, 0.001), 0.1)
    }

    @Test
    fun `arrow angle accounts for where the phone points`() {
        assertEquals(0.0, Geo.relativeAngle(90.0, 90.0), 1e-9)
        assertEquals(270.0, Geo.relativeAngle(0.0, 90.0), 1e-9)
        assertEquals(10.0, Geo.relativeAngle(5.0, 355.0), 1e-9)
    }

    @Test
    fun `words and distances read naturally`() {
        assertEquals("north", Geo.compassWord(350.0))
        assertEquals("north-east", Geo.compassWord(40.0))
        assertEquals("south-west", Geo.compassWord(225.0))
        assertEquals("7 m", Geo.formatDistance(7.2))
        assertEquals("240 m", Geo.formatDistance(238.0))
        assertEquals("1.2 km", Geo.formatDistance(1_234.0))
        assertEquals("15 km", Geo.formatDistance(15_400.0))
    }
}
