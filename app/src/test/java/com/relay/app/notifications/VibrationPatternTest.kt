package com.relay.app.notifications

import com.relay.app.model.MessageType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Morse-code vibration timings (pure logic; how it feels must still be checked on a phone). */
class VibrationPatternTest {

    @Test
    fun `SOS is dot dot dot, dash dash dash, dot dot dot in ITU timing`() {
        val u = 100L
        val expected = longArrayOf(
            0,
            u, u, u, u, u, 3 * u, // S: ...  then letter gap
            3 * u, u, 3 * u, u, 3 * u, 3 * u, // O: ---  then letter gap
            u, u, u, u, u, // S: ...
        )
        assertArrayEquals(expected, MorsePattern.timings("SOS", u))
    }

    @Test
    fun `repeats are separated by a word gap and never end on a pause`() {
        val u = 100L
        val once = MorsePattern.timings("SOS", u)
        val twice = MorsePattern.timings("SOS", u, repeats = 2)
        assertEquals(once.size * 2, twice.size)
        assertEquals(7 * u, twice[once.size]) // the gap between the two SOS words
        assertTrue(twice.size % 2 == 0) // [wait, on, off, ..., on]: ends with a vibration
    }

    @Test
    fun `each message type has a distinct pattern and SOS is the longest`() {
        val patterns = MessageType.entries.map { VibrationPatterns.forType(it).toList() }
        assertEquals(patterns.size, patterns.toSet().size)
        assertTrue(MessageType.entries.all { VibrationPatterns.forType(it)[0] == 0L })
        val sosMs = VibrationPatterns.SOS.sum()
        assertTrue(MessageType.entries.filter { it != MessageType.SOS }.all { VibrationPatterns.forType(it).sum() < sosMs })
        assertTrue("SOS should take about 9 s, was $sosMs ms", sosMs in 8_000L..10_000L)
    }

    @Test
    fun `unknown characters are ignored`() {
        assertArrayEquals(MorsePattern.timings("SOS", 100), MorsePattern.timings("S-O-S!", 100))
    }
}
