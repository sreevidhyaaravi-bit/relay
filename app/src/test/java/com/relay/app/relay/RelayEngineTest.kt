package com.relay.app.relay

import com.relay.app.data.db.RouteEvent
import com.relay.app.model.DeliveryState
import com.relay.app.model.Direction
import com.relay.app.model.LocationInfo
import com.relay.app.model.LocationSource
import com.relay.app.model.MessageDraft
import com.relay.app.model.MessageType
import com.relay.app.model.RelayConfig
import com.relay.app.model.SendState
import com.relay.app.model.TransportKind
import com.relay.app.protocol.Ack
import com.relay.app.protocol.AckKind
import com.relay.app.protocol.AckStatus
import com.relay.app.protocol.Receipt
import com.relay.app.transport.SendOutcome
import com.relay.app.transport.UploadOutcome
import com.relay.app.testutil.FakeClock
import com.relay.app.testutil.TestDevice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** RelayEngine state machine over the in-memory store (mocked devices, real logic). */
class RelayEngineTest {

    private val clock = FakeClock()
    private val a = TestDevice("RLY-A", "Phone A", clock)
    private val b = TestDevice("RLY-B", "Phone B", clock)
    private val c = TestDevice("RLY-C", "Phone C", clock)

    @Test
    fun `new message is stored before sending with original timestamp and queued state`() = runTest {
        val m = a.send(b, "🆘 RELAY TEST 7319 — Please confirm receipt.", MessageType.SOS)
        val stored = a.store.get(m.id)!!
        assertEquals(clock.now(), stored.createdAtUtc)
        assertEquals(SendState.QUEUED, stored.sendState)
        assertEquals(DeliveryState.PENDING, stored.deliveryState)
        assertEquals(3, stored.priority)
        assertEquals(clock.now() + RelayConfig().sosExpiryMs, stored.expiresAtUtc)
        assertEquals(RouteEvent.CREATED, a.store.routeFor(m.id).single().event)
        assertTrue(Regex("^[0-9a-f-]{36}$").matches(m.id))
    }

    @Test
    fun `invalid drafts are refused with readable errors`() = runTest {
        suspend fun refused(draft: MessageDraft) = try {
            a.engine.createOutgoing(draft); false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(refused(MessageDraft("RLY-B", null, "   ", MessageType.NORMAL, null)))
        assertTrue(refused(MessageDraft("RLY-B", null, "x".repeat(2001), MessageType.NORMAL, null)))
        assertTrue(refused(MessageDraft("bad id!", null, "hi", MessageType.NORMAL, null)))
        assertTrue(refused(MessageDraft("RLY-A", null, "hi", MessageType.NORMAL, null))) // to self
        assertTrue(refused(MessageDraft("RLY-B", null, "hi", MessageType.NORMAL, LocationInfo(95.0, 0.0, null, 1, null, LocationSource.DEVICE))))
    }

    @Test
    fun `receiver stores once, keeps sent time, and acks FINAL`() = runTest {
        val m = a.send(b, "hello")
        clock.advance(27 * 60_000) // delivered 27 minutes later
        val wire = MessageMapper.toWireForSend(m, a.identity, TransportKind.BLUETOOTH, clock.now())
        val ack = b.engine.onMessage(wire, a.peerIdentity, TransportKind.BLUETOOTH)
        assertEquals(AckKind.FINAL, ack.kind)
        assertEquals(AckStatus.STORED, ack.status)
        val inbox = b.store.get(m.id)!!
        assertEquals(Direction.INCOMING, inbox.direction)
        assertEquals(m.createdAtUtc, inbox.createdAtUtc)
        assertEquals(clock.now(), inbox.receivedAtUtc)

        // Same id again over another transport: no second row, DUPLICATE (still success).
        val again = b.engine.onMessage(wire, a.peerIdentity, TransportKind.WIFI_DIRECT)
        assertEquals(AckStatus.DUPLICATE, again.status)
        assertEquals(AckKind.FINAL, again.kind)
        assertEquals(1, b.store.allMessages().size)
        assertTrue(b.store.routeFor(m.id).any { it.event == RouteEvent.DUPLICATE_RECEIVED })
    }

    @Test
    fun `intermediate device stores a relay copy and acks HOP only`() = runTest {
        val m = a.send(c, "for C")
        val ack = b.engine.onMessage(MessageMapper.toWireForSend(m, a.identity, TransportKind.BLUETOOTH, clock.now()), a.peerIdentity, TransportKind.BLUETOOTH)
        assertEquals(AckKind.HOP, ack.kind)
        val copy = b.store.get(m.id)!!
        assertEquals(Direction.RELAY, copy.direction)
        assertEquals(DeliveryState.PENDING, copy.deliveryState)
        assertEquals(SendState.QUEUED, copy.sendState)
    }

    @Test
    fun `relay disabled, hop limit, expired and own messages are rejected or ignored`() = runTest {
        val m = a.send(c, "for C")
        val wire = MessageMapper.toWireForSend(m, a.identity, TransportKind.BLUETOOTH, clock.now())

        b.config = RelayConfig(relayForOthers = false)
        assertEquals(AckStatus.REJECTED, b.engine.onMessage(wire, a.peerIdentity, TransportKind.BLUETOOTH).status)
        b.config = RelayConfig()

        val atLimit = wire.copy(hopCount = 6, maxHops = 6)
        assertEquals("hop limit reached", b.engine.onMessage(atLimit, a.peerIdentity, TransportKind.BLUETOOTH).reason)

        clock.advance(RelayConfig().ordinaryExpiryMs + 1)
        assertEquals("expired", b.engine.onMessage(wire, a.peerIdentity, TransportKind.BLUETOOTH).reason)

        val own = a.engine.onMessage(wire.copy(expiresAtUtc = clock.now() + 1000), b.peerIdentity, TransportKind.BLUETOOTH)
        assertEquals(AckStatus.DUPLICATE, own.status)
        assertTrue(b.store.allMessages().isEmpty())
    }

    @Test
    fun `final ack marks delivered, hop ack never does`() = runTest {
        val m = a.send(b, "hi")
        a.engine.onSendOutcome(m.id, TransportKind.BLUETOOTH, c.peerIdentity, SendOutcome.Acked(ack(m.id, AckKind.HOP, c)))
        a.store.get(m.id)!!.let {
            assertEquals(DeliveryState.PENDING, it.deliveryState)
            assertEquals(SendState.HOP_ACKED, it.sendState)
            assertEquals("RLY-C", it.relayAckBy)
        }
        a.engine.onSendOutcome(m.id, TransportKind.WIFI_DIRECT, b.peerIdentity, SendOutcome.Acked(ack(m.id, AckKind.FINAL, b)))
        a.store.get(m.id)!!.let {
            assertEquals(DeliveryState.DELIVERED, it.deliveryState)
            assertEquals(TransportKind.WIFI_DIRECT, it.deliveredVia)
            assertNotNull(it.acknowledgedAtUtc)
            assertEquals(m.createdAtUtc, it.createdAtUtc)
        }
    }

    @Test
    fun `a FINAL ack from someone other than the recipient is treated as a hop`() = runTest {
        val m = a.send(b, "hi")
        a.engine.onSendOutcome(m.id, TransportKind.BLUETOOTH, c.peerIdentity, SendOutcome.Acked(ack(m.id, AckKind.FINAL, c)))
        assertEquals(DeliveryState.PENDING, a.store.get(m.id)!!.deliveryState)
    }

    @Test
    fun `receipt from the final recipient marks delivered`() = runTest {
        val m = a.send(c, "via relay")
        a.engine.onReceipt(Receipt(m.id, "RLY-A", "RLY-C", "Phone C", clock.now(), m.expiresAtUtc), b.peerIdentity, TransportKind.BLUETOOTH)
        val after = a.store.get(m.id)!!
        assertEquals(DeliveryState.DELIVERED, after.deliveryState)
        assertEquals("Phone C", after.deliveredToName)
        // A receipt for the wrong recipient is ignored.
        val m2 = a.send(c, "second")
        a.engine.onReceipt(Receipt(m2.id, "RLY-A", "RLY-B", "Phone B", clock.now(), m2.expiresAtUtc), b.peerIdentity, TransportKind.BLUETOOTH)
        assertEquals(DeliveryState.PENDING, a.store.get(m2.id)!!.deliveryState)
    }

    @Test
    fun `upload is not delivery, remote confirmation is`() = runTest {
        val m = a.send(b, "online")
        a.engine.onUploadOutcome(m.id, UploadOutcome.Uploaded)
        a.store.get(m.id)!!.let {
            assertEquals(SendState.UPLOADED, it.sendState)
            assertEquals(DeliveryState.PENDING, it.deliveryState)
        }
        a.engine.onRemoteFinalDelivery(m.id, "RLY-B", "Phone B", clock.now(), TransportKind.INTERNET_RELAY)
        assertEquals(DeliveryState.DELIVERED, a.store.get(m.id)!!.deliveryState)
    }

    @Test
    fun `failed rounds back off and pause at the retry limit`() = runTest {
        a.config = RelayConfig(maxAutoAttempts = 3, backoffBaseMs = 10_000)
        val m = a.send(b, "flaky")
        a.engine.onAttemptRoundFailed(m.id, "boom")
        a.store.get(m.id)!!.let {
            assertEquals(SendState.RETRY_WAIT, it.sendState)
            assertEquals(1, it.retryCount)
            assertTrue(it.nextAttemptAtUtc!! > clock.now())
            assertEquals("boom", it.lastError)
        }
        a.engine.onAttemptRoundFailed(m.id, "boom")
        a.engine.onAttemptRoundFailed(m.id, "boom")
        assertEquals(SendState.PAUSED, a.store.get(m.id)!!.sendState)
        assertTrue(a.engine.retryNow(m.id))
        a.store.get(m.id)!!.let {
            assertEquals(SendState.QUEUED, it.sendState)
            assertEquals(0, it.retryCount)
            assertNull(it.nextAttemptAtUtc)
        }
    }

    @Test
    fun `expired messages are marked, explained and kept`() = runTest {
        val normal = a.send(b, "normal")
        val sos = a.send(b, "sos", MessageType.SOS)
        clock.advance(25 * RelayConfig.HOUR_MS)
        val expired = a.engine.expireDue()
        assertEquals(listOf(normal.id), expired.map { it.id })
        a.store.get(normal.id)!!.let {
            assertEquals(DeliveryState.EXPIRED, it.deliveryState)
            assertTrue(it.lastError!!.contains("no longer forwarded"))
        }
        assertEquals(DeliveryState.PENDING, a.store.get(sos.id)!!.deliveryState)
        assertEquals(2, a.store.allMessages().size) // nothing deleted
    }

    @Test
    fun `cancel works only before delivery`() = runTest {
        val m = a.send(b, "cancel me")
        assertTrue(a.engine.cancel(m.id))
        assertEquals(DeliveryState.CANCELLED, a.store.get(m.id)!!.deliveryState)
        assertFalse(a.engine.cancel(m.id))
    }

    @Test
    fun `interrupted sends are recovered after restart`() = runTest {
        val m = a.send(b, "x")
        a.engine.onAttemptStarted(m.id, TransportKind.BLUETOOTH, b.peerIdentity)
        assertEquals(SendState.SENDING, a.store.get(m.id)!!.sendState)
        assertEquals(1, a.engine.recoverAfterRestart())
        assertEquals(SendState.QUEUED, a.store.get(m.id)!!.sendState)
    }

    @Test
    fun `incoming message for me via relay creates a receipt for the sender`() = runTest {
        val m = a.send(c, "to C")
        val viaB = MessageMapper.toWireForSend(m, a.identity, TransportKind.BLUETOOTH, clock.now())
        b.engine.onMessage(viaB, a.peerIdentity, TransportKind.BLUETOOTH)
        val forward = MessageMapper.toWireForSend(b.store.get(m.id)!!, b.identity, TransportKind.WIFI_DIRECT, clock.now())
        c.engine.onMessage(forward, b.peerIdentity, TransportKind.WIFI_DIRECT)
        val receipt = c.store.getReceipt(m.id) ?: return@runTest fail("no receipt")
        assertEquals("RLY-A", receipt.originalSenderId)
        assertEquals("RLY-C", receipt.finalRecipientId)
    }

    private fun ack(id: String, kind: String, by: TestDevice) =
        Ack(id, kind, AckStatus.STORED, by.id, by.name, clock.now())
}
