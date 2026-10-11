package com.relay.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineFramerTest {

    private fun lines(events: List<LineFramer.Event>) = events.filterIsInstance<LineFramer.Event.Line>().map { it.text }

    @Test
    fun `partial reads are buffered until the newline arrives`() {
        val framer = LineFramer()
        assertTrue(framer.feed("{\"a\":".toByteArray()).isEmpty())
        assertTrue(framer.feed("1".toByteArray()).isEmpty())
        assertEquals(listOf("{\"a\":1}"), lines(framer.feed("}\n".toByteArray())))
        assertEquals(0, framer.pendingBytes)
    }

    @Test
    fun `multiple frames in one read are all returned in order`() {
        val framer = LineFramer()
        val out = lines(framer.feed("one\ntwo\nthree\npart".toByteArray()))
        assertEquals(listOf("one", "two", "three"), out)
        assertEquals(listOf("partial"), lines(framer.feed("ial\n".toByteArray())))
    }

    @Test
    fun `utf8 characters split across reads are decoded correctly`() {
        val framer = LineFramer()
        val bytes = "🆘 RELAY TEST 7319 — Please confirm receipt.\n".toByteArray(Charsets.UTF_8)
        val out = mutableListOf<String>()
        // Feed one byte at a time: every multi-byte character is split.
        bytes.forEach { b -> out += lines(framer.feed(byteArrayOf(b))) }
        assertEquals(listOf("🆘 RELAY TEST 7319 — Please confirm receipt."), out)
    }

    @Test
    fun `oversized frame is dropped and the framer recovers`() {
        val framer = LineFramer(maxFrameBytes = 16)
        val events = framer.feed("this line is far too long for the limit\nok\n".toByteArray())
        assertTrue(events.first() is LineFramer.Event.Oversize)
        assertEquals(listOf("ok"), lines(events))
    }

    @Test
    fun `oversized frame split over several reads is dropped`() {
        val framer = LineFramer(maxFrameBytes = 8)
        assertTrue(framer.feed("123456".toByteArray()).isEmpty())
        assertTrue(framer.feed("789012".toByteArray()).isEmpty())
        val events = framer.feed("34\nfine\n".toByteArray())
        assertTrue(events.first() is LineFramer.Event.Oversize)
        assertEquals(listOf("fine"), lines(events))
    }

    @Test
    fun `crlf endings and blank lines are handled`() {
        val framer = LineFramer()
        assertEquals(listOf("a", "b"), lines(framer.feed("a\r\n\n\r\nb\n".toByteArray())))
    }

    @Test
    fun `offset and length are respected`() {
        val framer = LineFramer()
        val buf = "xxhello\nyy".toByteArray()
        assertEquals(listOf("hello"), lines(framer.feed(buf, 2, 6)))
    }
}
