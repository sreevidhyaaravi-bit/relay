package com.relay.app.ui.format

import com.relay.app.data.db.MessageEntity
import com.relay.app.model.DeliveryState
import com.relay.app.model.Direction
import com.relay.app.model.LocationInfo
import com.relay.app.model.LocationSource
import com.relay.app.model.MessageType
import com.relay.app.model.SendState
import com.relay.app.model.TransportKind
import com.relay.app.relay.MessageMapper
import com.relay.app.protocol.WireHop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The UI must never claim more than the acknowledgements prove. */
class FormattersTest {

    private val now = 1_760_000_000_000L

    private fun outgoing(send: SendState, delivery: DeliveryState = DeliveryState.PENDING) = MessageEntity(
        id = "m", senderId = "RLY-A", senderName = "A", recipientId = "RLY-B", recipientName = "B", body = "x",
        messageType = MessageType.SOS, priority = 3, createdAtUtc = now - 60_000, expiresAtUtc = now + 3_600_000,
        direction = Direction.OUTGOING, sendState = send, deliveryState = delivery,
    )

    @Test
    fun `queued message shows the required label`() {
        assertEquals("QUEUED — WAITING FOR CONNECTION", RelayFormat.messageStatus(outgoing(SendState.QUEUED), now).text)
    }

    @Test
    fun `relay ack and upload never say delivered`() {
        for (state in listOf(SendState.HOP_ACKED, SendState.UPLOADED, SendState.SENDING, SendState.RETRY_WAIT, SendState.PAUSED, SendState.QUEUED)) {
            val text = RelayFormat.messageStatus(outgoing(state), now).text
            assertFalse("$state said: $text", text.contains("Delivered", ignoreCase = true))
        }
        assertTrue(RelayFormat.messageStatus(outgoing(SendState.HOP_ACKED), now).text.contains("relay"))
    }

    @Test
    fun `only a delivered state says delivered`() {
        assertEquals("Delivered to intended recipient", RelayFormat.messageStatus(outgoing(SendState.DONE, DeliveryState.DELIVERED), now).text)
        assertTrue(RelayFormat.messageStatus(outgoing(SendState.DONE, DeliveryState.EXPIRED), now).text.startsWith("Expired"))
    }

    @Test
    fun `missing location says Location unavailable`() {
        assertEquals("Location unavailable", RelayFormat.location(null, now))
    }

    @Test
    fun `old location is marked out of date with its real capture time`() {
        val old = LocationInfo(1.0, 2.0, 10f, now - 30 * 60_000, null, LocationSource.DEVICE)
        assertTrue(RelayFormat.location(old, now).contains("may be out of date"))
        val fresh = old.copy(capturedAtUtc = now - 60_000)
        assertFalse(RelayFormat.location(fresh, now).contains("out of date"))
        val manual = LocationInfo(null, null, null, null, "Library", LocationSource.MANUAL)
        assertTrue(RelayFormat.location(manual, now).contains("entered manually"))
    }

    @Test
    fun `clock skew is flagged only when the sender appears ahead`() {
        val m = outgoing(SendState.NONE).copy(direction = Direction.INCOMING, createdAtUtc = now + 10 * 60_000, receivedAtUtc = now)
        assertNotNull(RelayFormat.clockSkewWarning(m))
        assertNull(RelayFormat.clockSkewWarning(m.copy(createdAtUtc = now - 60_000)))
    }

    @Test
    fun `carried route lists every real hop`() {
        val hops = listOf(
            WireHop("RLY-A", "Phone A", TransportKind.BLUETOOTH.name, 1),
            WireHop("RLY-B", "Phone B", TransportKind.WIFI_DIRECT.name, 2),
            WireHop("RLY-C", "Phone C", TransportKind.INTERNET_RELAY.name, 3),
        )
        val m = outgoing(SendState.NONE).copy(direction = Direction.INCOMING, routeJson = MessageMapper.encodeRoute(hops))
        assertEquals(
            "Phone A → Bluetooth → Phone B → Wi-Fi Direct → Phone C → Internet Relay → Phone D",
            RelayFormat.carriedRoute(m, "Phone D"),
        )
    }
}
