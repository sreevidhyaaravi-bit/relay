package com.relay.app.protocol

import java.io.ByteArrayOutputStream

/**
 * Splits a byte stream into newline-terminated UTF-8 lines.
 *
 * Handles the realities of stream sockets:
 * - Partial reads: a frame split across several reads is buffered until its '\n' arrives.
 * - Multiple frames in one read: every complete line is returned, in order.
 * - Multi-byte UTF-8 characters split across reads: lines are decoded only once complete.
 * - Oversized frames: a line longer than [maxFrameBytes] is dropped (reported as [Event.Oversize])
 *   and the framer resynchronises at the next '\n'.
 * - "\r\n" line endings are accepted.
 *
 * Not thread safe: use one instance per connection, fed from a single reader.
 */
class LineFramer(private val maxFrameBytes: Int = ProtocolLimits.MAX_FRAME_BYTES) {

    sealed interface Event {
        data class Line(val text: String) : Event
        data class Oversize(val droppedBytes: Int) : Event
    }

    private val buffer = ByteArrayOutputStream()
    private var discarding = false
    private var discardedBytes = 0

    /** Number of bytes currently buffered waiting for a newline (useful for tests). */
    val pendingBytes: Int get() = buffer.size()

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<Event> {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "bad range" }
        val events = ArrayList<Event>(2)
        var start = offset
        val end = offset + length
        var i = offset
        while (i < end) {
            if (bytes[i] == NEWLINE) {
                appendSegment(bytes, start, i - start)
                emitLine(events)
                start = i + 1
            }
            i++
        }
        if (start < end) appendSegment(bytes, start, end - start)
        return events
    }

    /** Clears buffered state (e.g. after a reconnect). */
    fun reset() {
        buffer.reset()
        discarding = false
        discardedBytes = 0
    }

    private fun appendSegment(bytes: ByteArray, start: Int, len: Int) {
        if (len <= 0) return
        if (discarding) {
            discardedBytes += len
            return
        }
        // +1 accounts for the newline that terminates the frame.
        if (buffer.size() + len + 1 > maxFrameBytes) {
            discardedBytes = buffer.size() + len
            buffer.reset()
            discarding = true
            return
        }
        buffer.write(bytes, start, len)
    }

    private fun emitLine(events: MutableList<Event>) {
        if (discarding) {
            events += Event.Oversize(discardedBytes)
            discarding = false
            discardedBytes = 0
            buffer.reset()
            return
        }
        var text = buffer.toString(Charsets.UTF_8.name())
        buffer.reset()
        if (text.endsWith('\r')) text = text.dropLast(1)
        if (text.isNotBlank()) events += Event.Line(text)
    }

    private companion object {
        const val NEWLINE: Byte = '\n'.code.toByte()
    }
}
