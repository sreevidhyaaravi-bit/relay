package com.relay.app.relay

import com.relay.app.model.DeviceIdentity
import com.relay.app.model.Direction
import com.relay.app.model.LocationInfo
import com.relay.app.model.LocationSource
import com.relay.app.model.MessageType
import com.relay.app.model.PeerIdentity
import com.relay.app.model.TransportKind
import com.relay.app.protocol.Frame
import com.relay.app.protocol.ProtocolCodec
import com.relay.app.testutil.FakeClock
import com.relay.app.testutil.TestDevice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageMapperTest {

    private val clock = FakeClock()

    @Test
    fun `original metadata survives send, wire and receive unchanged`() = runTest {
        val a = TestDevice("RLY-A", "Phone A", clock)
        val loc = LocationInfo(12.97, 77.59, 5f, clock.now() - 60_000, "Gate 2", LocationSource.DEVICE)
        val created = a.send(null, "🆘 help", MessageType.SOS, loc, recipientId = "RLY-D")

        clock.advance(10 * 60_000) // forwarded much later
        val wire = MessageMapper.toWireForSend(created, a.identity, TransportKind.BLUETOOTH, clock.now())
        val decoded = (ProtocolCodec.decode(ProtocolCodec.encode(Frame.msg(wire))) as ProtocolCodec.DecodeResult.Ok).frame.msg!!
        val stored = MessageMapper.fromWire(decoded, Direction.RELAY, PeerIdentity("RLY-A", "Phone A"), TransportKind.BLUETOOTH, clock.now())

        assertEquals(created.id, stored.id)
        assertEquals(created.createdAtUtc, stored.createdAtUtc) // NOT the forwarding time
        assertEquals(created.expiresAtUtc, stored.expiresAtUtc)
        assertEquals("RLY-A", stored.senderId)
        assertEquals("RLY-D", stored.recipientId)
        assertEquals(MessageType.SOS, stored.messageType)
        assertEquals(3, stored.priority)
        assertEquals(loc, stored.location()) // sender's location, with its own capture time
        assertEquals(1, stored.hopCount)
        assertEquals(clock.now(), stored.receivedAtUtc)
    }

    @Test
    fun `each forward appends one hop and increments hop count`() {
        val b = DeviceIdentity("RLY-B", "Phone B")
        val c = DeviceIdentity("RLY-C", "Phone C")
        val fromA = MessageMapper.fromWire(
            com.relay.app.protocol.WireMessage(
                id = "123e4567-e89b-42d3-a456-426614174000", senderId = "RLY-A", senderName = "Phone A",
                recipientId = "RLY-D", body = "x", type = "NORMAL", priority = 0, createdAtUtc = 1, expiresAtUtc = 10_000_000,
                hopCount = 1, route = listOf(com.relay.app.protocol.WireHop("RLY-A", "Phone A", "BLUETOOTH", 2)),
            ),
            Direction.RELAY, PeerIdentity("RLY-A", "Phone A"), TransportKind.BLUETOOTH, 3,
        )
        val toC = MessageMapper.toWireForSend(fromA, b, TransportKind.WIFI_DIRECT, 4)
        assertEquals(2, toC.hopCount)
        assertEquals(listOf("BLUETOOTH", "WIFI_DIRECT"), toC.route.map { it.transport })
        assertEquals(listOf("RLY-A", "RLY-B"), toC.route.map { it.nodeId })
        val atC = MessageMapper.fromWire(toC, Direction.RELAY, PeerIdentity(b.deviceId, b.displayName), TransportKind.WIFI_DIRECT, 5)
        val toD = MessageMapper.toWireForSend(atC, c, TransportKind.INTERNET_RELAY, 6)
        assertEquals(listOf("BLUETOOTH", "WIFI_DIRECT", "INTERNET_RELAY"), toD.route.map { it.transport })
        assertEquals(1L, toD.createdAtUtc)
    }

    @Test
    fun `route json round-trips and tolerates garbage`() {
        val hops = listOf(com.relay.app.protocol.WireHop("RLY-A", "A", "BLUETOOTH", 1))
        assertEquals(hops, MessageMapper.decodeRoute(MessageMapper.encodeRoute(hops)))
        assertEquals(emptyList<Any>(), MessageMapper.decodeRoute("not json"))
        assertEquals(emptyList<Any>(), MessageMapper.decodeRoute(null))
    }
}
