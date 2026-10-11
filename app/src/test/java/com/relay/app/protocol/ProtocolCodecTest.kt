package com.relay.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodecTest {

    private val id = "123e4567-e89b-42d3-a456-426614174000"

    private fun wire(
        location: WireLocation? = WireLocation(12.9716, 77.5946, 8.5f, 1_760_000_000_000L, "Main gate", "DEVICE"),
    ) = WireMessage(
        id = id,
        senderId = "RLY-AAAA1111",
        senderName = "Phone A",
        recipientId = "RLY-BBBB2222",
        recipientName = "Phone B",
        body = "🆘 RELAY TEST 7319 — Please confirm receipt.",
        type = "SOS",
        priority = 3,
        createdAtUtc = 1_760_000_100_000L,
        expiresAtUtc = 1_760_000_100_000L + 3_600_000L,
        location = location,
        hopCount = 1,
        maxHops = 6,
        route = listOf(WireHop("RLY-AAAA1111", "Phone A", "BLUETOOTH", 1_760_000_100_500L)),
    )

    @Test
    fun `message frame round-trips with location and route intact`() {
        val line = ProtocolCodec.encode(Frame.msg(wire()))
        assertFalse("frame must be a single line", line.contains('\n'))
        val result = ProtocolCodec.decode(line)
        assertTrue(result is ProtocolCodec.DecodeResult.Ok)
        val decoded = (result as ProtocolCodec.DecodeResult.Ok).frame.msg!!
        assertEquals(wire(), decoded)
        assertEquals(12.9716, decoded.location!!.lat!!, 0.0)
        assertEquals("Main gate", decoded.location!!.label)
        assertEquals(1_760_000_100_000L, decoded.createdAtUtc)
    }

    @Test
    fun `encodeLine ends with exactly one newline`() {
        val bytes = ProtocolCodec.encodeLine(Frame.ping())
        assertEquals('\n'.code.toByte(), bytes.last())
        assertEquals(1, bytes.count { it == '\n'.code.toByte() })
    }

    @Test
    fun `body with newlines stays one frame`() {
        val line = ProtocolCodec.encode(Frame.msg(wire().copy(body = "line one\nline two")))
        assertFalse(line.contains('\n'))
        val decoded = (ProtocolCodec.decode(line) as ProtocolCodec.DecodeResult.Ok).frame.msg!!
        assertEquals("line one\nline two", decoded.body)
    }

    @Test
    fun `garbage is rejected as malformed`() {
        assertTrue(ProtocolCodec.decode("not json at all") is ProtocolCodec.DecodeResult.Malformed)
        assertTrue(ProtocolCodec.decode("[1,2,3]") is ProtocolCodec.DecodeResult.Malformed)
        assertTrue(ProtocolCodec.decode("{\"t\":\"PING\"}") is ProtocolCodec.DecodeResult.Malformed) // no version
        assertTrue(ProtocolCodec.decode("{\"v\":1,\"t\":\"WHAT\"}") is ProtocolCodec.DecodeResult.Malformed)
        assertTrue(ProtocolCodec.decode("{\"v\":1,\"t\":\"MSG\"}") is ProtocolCodec.DecodeResult.Malformed) // missing payload
    }

    @Test
    fun `unsupported protocol version is reported with the message id`() {
        val line = ProtocolCodec.encode(Frame.msg(wire())).replace("\"v\":1", "\"v\":99")
        val result = ProtocolCodec.decode(line)
        assertTrue(result is ProtocolCodec.DecodeResult.UnsupportedVersion)
        assertEquals(99, (result as ProtocolCodec.DecodeResult.UnsupportedVersion).version)
        assertEquals(id, result.messageId)
    }

    @Test
    fun `invalid message keeps its id so a REJECTED ack can be sent`() {
        val line = ProtocolCodec.encode(Frame.msg(wire().copy(body = "   ")))
        val result = ProtocolCodec.decode(line)
        assertTrue(result is ProtocolCodec.DecodeResult.Malformed)
        assertEquals(id, (result as ProtocolCodec.DecodeResult.Malformed).messageId)
    }

    @Test
    fun `unknown extra fields are ignored for forward compatibility`() {
        val line = ProtocolCodec.encode(Frame.ping()).dropLast(1) + ",\"future\":true}"
        assertTrue(ProtocolCodec.decode(line) is ProtocolCodec.DecodeResult.Ok)
    }

    @Test
    fun `valid ack decodes and invalid ack is rejected`() {
        val ack = Ack(id, AckKind.FINAL, AckStatus.STORED, "RLY-BBBB2222", "Phone B", 1_760_000_200_000L)
        val ok = ProtocolCodec.decode(ProtocolCodec.encode(Frame.ack(ack)))
        assertEquals(ack, (ok as ProtocolCodec.DecodeResult.Ok).frame.ack)
        val bad = ProtocolCodec.encode(Frame.ack(ack.copy(kind = "MAYBE")))
        assertTrue(ProtocolCodec.decode(bad) is ProtocolCodec.DecodeResult.Malformed)
    }

    @Test
    fun `message without location decodes with null location`() {
        val line = ProtocolCodec.encode(Frame.msg(wire(location = null)))
        assertFalse(line.contains("location"))
        assertNull((ProtocolCodec.decode(line) as ProtocolCodec.DecodeResult.Ok).frame.msg!!.location)
    }

    @Test
    fun `hello with wrong app name is rejected`() {
        val line = ProtocolCodec.encode(Frame.hello(Hello("RLY-AAAA1111", "A", app = "OTHER")))
        assertTrue(ProtocolCodec.decode(line) is ProtocolCodec.DecodeResult.Malformed)
    }
}
