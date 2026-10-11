package com.relay.app.relay

import com.relay.app.data.db.RouteEvent
import com.relay.app.model.DeliveryState
import com.relay.app.model.LocationInfo
import com.relay.app.model.LocationSource
import com.relay.app.model.MessageType
import com.relay.app.model.RelayConfig
import com.relay.app.model.SendState
import com.relay.app.model.TransportKind
import com.relay.app.testutil.EngineLink
import com.relay.app.testutil.FakeClock
import com.relay.app.testutil.FakeInternet
import com.relay.app.testutil.TestDevice
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Store-and-forward and adaptive transport scenarios from the spec, run through the REAL
 * QueueProcessor + RelayEngine. Links and the internet relay are MOCKED (in-memory), so these
 * tests prove the logic, not physical Bluetooth / Wi-Fi transfer.
 */
class AdaptiveTransportTest {

    private val clock = FakeClock()
    private val a = TestDevice("RLY-A", "Phone A", clock)
    private val b = TestDevice("RLY-B", "Phone B", clock)
    private val c = TestDevice("RLY-C", "Phone C", clock)
    private val d = TestDevice("RLY-D", "Phone D", clock)

    @Test
    fun `all transports unavailable keeps the message queued locally`() = runTest {
        val m = a.send(b, "nobody around", MessageType.SOS)
        val report = a.processor.processOnce(emptyList(), null, ProcessTrigger.NEW_MESSAGE)
        assertEquals(0, report.attempts)
        assertEquals(1, report.noRoute)
        val stored = a.store.get(m.id)!!
        assertEquals(SendState.QUEUED, stored.sendState)
        assertEquals(DeliveryState.PENDING, stored.deliveryState)
        assertEquals(0, stored.retryCount) // no route is not a failure
    }

    @Test
    fun `wifi enabled but no usable peer route claims nothing`() = runTest {
        val m = a.send(b, "x")
        // Radio on, but the only link is closed: not a route.
        val closed = EngineLink(a, b, TransportKind.WIFI_DIRECT).apply { open = false }
        a.processor.processOnce(listOf(closed), null, ProcessTrigger.TRANSPORT_STATE)
        assertEquals(DeliveryState.PENDING, a.store.get(m.id)!!.deliveryState)
        assertTrue(b.store.allMessages().isEmpty())
    }

    @Test
    fun `bluetooth connected delivers over bluetooth`() = runTest {
        val m = a.send(b, "🆘 RELAY TEST 7319 — Please confirm receipt.", MessageType.SOS)
        val bt = EngineLink(a, b, TransportKind.BLUETOOTH)
        a.processor.processOnce(listOf(bt), null, ProcessTrigger.LINK_UP)
        val sent = a.store.get(m.id)!!
        assertEquals(DeliveryState.DELIVERED, sent.deliveryState)
        assertEquals(TransportKind.BLUETOOTH, sent.deliveredVia)
        val received = b.store.get(m.id)!!
        assertEquals("🆘 RELAY TEST 7319 — Please confirm receipt.", received.body)
        assertEquals(m.createdAtUtc, received.createdAtUtc)
    }

    @Test
    fun `bluetooth unavailable and wifi direct connected uses wifi direct`() = runTest {
        val m = a.send(b, "x")
        val bt = EngineLink(a, b, TransportKind.BLUETOOTH).apply { open = false }
        val wd = EngineLink(a, b, TransportKind.WIFI_DIRECT)
        a.processor.processOnce(listOf(bt, wd), null, ProcessTrigger.LINK_UP)
        assertEquals(TransportKind.WIFI_DIRECT, a.store.get(m.id)!!.deliveredVia)
        assertTrue(bt.sentIds.isEmpty())
    }

    @Test
    fun `selected transport fails and the alternative delivers`() = runTest {
        val m = a.send(b, "x")
        val bt = EngineLink(a, b, TransportKind.BLUETOOTH).apply { failures = 1 }
        val wd = EngineLink(a, b, TransportKind.WIFI_DIRECT)
        a.processor.processOnce(listOf(bt, wd), null, ProcessTrigger.LINK_UP)
        val sent = a.store.get(m.id)!!
        assertEquals(DeliveryState.DELIVERED, sent.deliveryState)
        assertEquals(TransportKind.WIFI_DIRECT, sent.deliveredVia)
        assertEquals(1, bt.sentIds.size)
        assertTrue(a.store.routeFor(m.id).any { it.event == RouteEvent.ATTEMPT_FAILED && it.transport == TransportKind.BLUETOOTH })
    }

    @Test
    fun `queued message is delivered when a transport becomes usable`() = runTest {
        val m = a.send(b, "wait for me")
        a.processor.processOnce(emptyList(), null, ProcessTrigger.NEW_MESSAGE)
        assertEquals(SendState.QUEUED, a.store.get(m.id)!!.sendState)
        clock.advance(10 * 60_000)
        a.processor.processOnce(listOf(EngineLink(a, b, TransportKind.BLUETOOTH)), null, ProcessTrigger.LINK_UP)
        assertEquals(DeliveryState.DELIVERED, a.store.get(m.id)!!.deliveryState)
        assertEquals(m.createdAtUtc, b.store.get(m.id)!!.createdAtUtc)
    }

    @Test
    fun `same message over two transports is stored once`() = runTest {
        val m = a.send(b, "dup")
        val bt = EngineLink(a, b, TransportKind.BLUETOOTH).apply { dropAckAfterStore = true } // stored, ACK lost
        a.processor.processOnce(listOf(bt), null, ProcessTrigger.LINK_UP)
        assertEquals(DeliveryState.PENDING, a.store.get(m.id)!!.deliveryState) // no ACK, no claim
        val wd = EngineLink(a, b, TransportKind.WIFI_DIRECT)
        a.processor.processOnce(listOf(wd), null, ProcessTrigger.LINK_UP)
        assertEquals(DeliveryState.DELIVERED, a.store.get(m.id)!!.deliveryState)
        assertEquals(1, b.store.allMessages().size)
    }

    @Test
    fun `intermediate acknowledgement is not final delivery`() = runTest {
        val m = a.send(c, "for C via B")
        a.processor.processOnce(listOf(EngineLink(a, b, TransportKind.BLUETOOTH)), null, ProcessTrigger.LINK_UP)
        val sent = a.store.get(m.id)!!
        assertEquals(SendState.HOP_ACKED, sent.sendState)
        assertEquals(DeliveryState.PENDING, sent.deliveryState)
        assertEquals("Phone B", sent.relayAckByName)
    }

    @Test
    fun `mixed-medium route preserves metadata and the receipt travels back`() = runTest {
        val loc = LocationInfo(12.9716, 77.5946, 6f, clock.now() - 30_000, null, LocationSource.DEVICE)
        val m = a.send(d, "🆘 help", MessageType.SOS, loc)
        val internet = FakeInternet(usable = true, reachable = setOf("RLY-D"))

        // A -> B over Bluetooth
        a.processor.processOnce(listOf(EngineLink(a, b, TransportKind.BLUETOOTH)), null, ProcessTrigger.LINK_UP)
        clock.advance(60_000)
        // B -> C over Wi-Fi Direct
        b.processor.processOnce(listOf(EngineLink(b, c, TransportKind.WIFI_DIRECT)), null, ProcessTrigger.LINK_UP)
        clock.advance(60_000)
        // C -> D over the internet relay
        c.processor.processOnce(emptyList(), internet, ProcessTrigger.INTERNET_UP)
        val uploaded = internet.uploads[m.id]!!
        assertEquals(listOf("BLUETOOTH", "WIFI_DIRECT", "INTERNET_RELAY"), uploaded.route.map { it.transport })
        // D downloads and confirms; C learns about it.
        d.engine.onMessage(uploaded, c.peerIdentity, TransportKind.INTERNET_RELAY)
        c.engine.onRemoteFinalDelivery(m.id, "RLY-D", "Phone D", clock.now(), TransportKind.INTERNET_RELAY)

        val atD = d.store.get(m.id)!!
        assertEquals(m.createdAtUtc, atD.createdAtUtc)
        assertEquals("RLY-A", atD.senderId)
        assertEquals("RLY-D", atD.recipientId)
        assertEquals(MessageType.SOS, atD.messageType)
        assertEquals(loc, atD.location())
        assertEquals(3, atD.hopCount)
        assertEquals(DeliveryState.PENDING, a.store.get(m.id)!!.deliveryState) // A has not heard yet

        // Receipt goes back C -> B -> A.
        c.processor.shareReceipts(listOf(EngineLink(c, b, TransportKind.WIFI_DIRECT)))
        b.processor.shareReceipts(listOf(EngineLink(b, a, TransportKind.BLUETOOTH)))
        val atA = a.store.get(m.id)!!
        assertEquals(DeliveryState.DELIVERED, atA.deliveredState())
        assertEquals("Phone D", atA.deliveredToName)
    }

    @Test
    fun `internet relay unconfigured still delivers locally`() = runTest {
        val m = a.send(b, "local only")
        a.processor.processOnce(listOf(EngineLink(a, b, TransportKind.BLUETOOTH)), null, ProcessTrigger.LINK_UP)
        assertEquals(DeliveryState.DELIVERED, a.store.get(m.id)!!.deliveryState)
    }

    @Test
    fun `upload marks uploaded but not delivered`() = runTest {
        val m = a.send(b, "online")
        val internet = FakeInternet(usable = true, reachable = setOf("RLY-B"))
        a.processor.processOnce(emptyList(), internet, ProcessTrigger.INTERNET_UP)
        val sent = a.store.get(m.id)!!
        assertEquals(SendState.UPLOADED, sent.sendState)
        assertEquals(DeliveryState.PENDING, sent.deliveryState)
        // A second pass does not upload again.
        a.processor.processOnce(emptyList(), internet, ProcessTrigger.INTERNET_UP)
        assertEquals(1, internet.uploads.size)
    }

    @Test
    fun `failure schedules a backoff retry that timers respect`() = runTest {
        val m = a.send(b, "flaky")
        val bt = EngineLink(a, b, TransportKind.BLUETOOTH).apply { failures = 1 }
        a.processor.processOnce(listOf(bt), null, ProcessTrigger.LINK_UP)
        val waiting = a.store.get(m.id)!!
        assertEquals(SendState.RETRY_WAIT, waiting.sendState)
        val report = a.processor.processOnce(listOf(bt), null, ProcessTrigger.RETRY_TIMER)
        assertEquals(0, report.attempts) // not due yet
        clock.nowMs = waiting.nextAttemptAtUtc!! + 1
        a.processor.processOnce(listOf(bt), null, ProcessTrigger.RETRY_TIMER)
        assertEquals(DeliveryState.DELIVERED, a.store.get(m.id)!!.deliveryState)
    }

    @Test
    fun `retry limit pauses automatic retries`() = runTest {
        a.config = RelayConfig(maxAutoAttempts = 2)
        val m = a.send(b, "never")
        val bt = EngineLink(a, b, TransportKind.BLUETOOTH).apply { failures = 100 }
        a.processor.processOnce(listOf(bt), null, ProcessTrigger.LINK_UP)
        a.processor.processOnce(listOf(bt), null, ProcessTrigger.LINK_UP)
        assertEquals(SendState.PAUSED, a.store.get(m.id)!!.sendState)
        val attemptsBefore = bt.sentIds.size
        a.processor.processOnce(listOf(bt), null, ProcessTrigger.LINK_UP)
        assertEquals(attemptsBefore, bt.sentIds.size) // paused: no more automatic attempts
    }

    @Test
    fun `SOS is transmitted before older ordinary messages`() = runTest {
        a.send(b, "normal 1")
        clock.advance(1_000)
        a.send(b, "normal 2")
        clock.advance(1_000)
        val sos = a.send(b, "SOS", MessageType.SOS)
        val bt = EngineLink(a, b, TransportKind.BLUETOOTH)
        a.processor.processOnce(listOf(bt), null, ProcessTrigger.LINK_UP)
        assertEquals(sos.id, bt.sentIds.first())
        assertEquals(listOf("SOS", "normal 1", "normal 2"), bt.sentMessages.map { it.body })
    }

    @Test
    fun `expired message is not forwarded`() = runTest {
        val m = a.send(b, "too late")
        clock.advance(25 * RelayConfig.HOUR_MS)
        val bt = EngineLink(a, b, TransportKind.BLUETOOTH)
        val report = a.processor.processOnce(listOf(bt), null, ProcessTrigger.LINK_UP)
        assertEquals(1, report.expired)
        assertTrue(bt.sentIds.isEmpty())
        assertEquals(DeliveryState.EXPIRED, a.store.get(m.id)!!.deliveryState)
    }

    @Test
    fun `relay does not send a message back to devices already on its route`() = runTest {
        val m = a.send(c, "loop?")
        a.processor.processOnce(listOf(EngineLink(a, b, TransportKind.BLUETOOTH)), null, ProcessTrigger.LINK_UP)
        val backToA = EngineLink(b, a, TransportKind.BLUETOOTH)
        b.processor.processOnce(listOf(backToA), null, ProcessTrigger.LINK_UP)
        assertTrue(backToA.sentIds.isEmpty())
        assertNull(a.store.get(m.id)!!.deliveredAtUtc)
    }

    @Test
    fun `non-SOS spreads to at most relayCopies relays, SOS to all`() = runTest {
        val e = TestDevice("RLY-E", "Phone E", clock)
        val f = TestDevice("RLY-F", "Phone F", clock)
        val target = TestDevice("RLY-Z", "Phone Z", clock)
        val links = listOf(EngineLink(a, b, TransportKind.BLUETOOTH), EngineLink(a, c, TransportKind.BLUETOOTH), EngineLink(a, e, TransportKind.BLUETOOTH), EngineLink(a, f, TransportKind.BLUETOOTH))
        a.send(target, "normal")
        a.processor.processOnce(links, null, ProcessTrigger.LINK_UP)
        assertEquals(2, links.sumOf { it.sentIds.size })
        links.forEach { it.sentIds.clear() }
        a.send(target, "sos", MessageType.SOS)
        a.processor.processOnce(links, null, ProcessTrigger.LINK_UP)
        assertEquals(4, links.count { l -> l.sentMessages.any { it.body == "sos" } })
    }

    @Test
    fun `message queued offline is uploaded when internet comes back`() = runTest {
        val m = a.send(b, "queued while offline")
        val internet = FakeInternet(usable = false, reachable = setOf("RLY-B"))
        a.processor.processOnce(emptyList(), internet, ProcessTrigger.NEW_MESSAGE)
        assertEquals(SendState.QUEUED, a.store.get(m.id)!!.sendState)
        internet.usable = true // internet restored and the relay signed in
        a.processor.processOnce(emptyList(), internet, ProcessTrigger.INTERNET_UP)
        val sent = a.store.get(m.id)!!
        assertEquals(SendState.UPLOADED, sent.sendState)
        assertEquals(DeliveryState.PENDING, sent.deliveryState)
        assertEquals(m.createdAtUtc, internet.uploads[m.id]!!.createdAtUtc)
    }

    @Test
    fun `recipient not registered keeps the message queued with a visible reason`() = runTest {
        val m = a.send(b, "B never went online")
        val internet = FakeInternet(usable = true, reachable = emptySet())
        val report = a.processor.processOnce(emptyList(), internet, ProcessTrigger.INTERNET_UP)
        assertEquals(1, report.noRoute)
        val stored = a.store.get(m.id)!!
        assertEquals(SendState.QUEUED, stored.sendState)
        assertEquals(0, stored.retryCount) // waiting is not a failure
        assertTrue(stored.lastError!!.contains("has not registered"))
        assertTrue(internet.uploads.isEmpty())
        // B registers later: the next pass uploads it.
        internet.reachable = setOf("RLY-B")
        a.processor.processOnce(emptyList(), internet, ProcessTrigger.RETRY_TIMER)
        assertEquals(SendState.UPLOADED, a.store.get(m.id)!!.sendState)
    }

    @Test
    fun `failed recipient lookup is a failed attempt that is retried`() = runTest {
        val m = a.send(b, "slow network")
        val internet = FakeInternet(usable = true, reachable = setOf("RLY-B")).apply {
            reachOverride = com.relay.app.transport.Reach.Unknown("timed out")
        }
        a.processor.processOnce(emptyList(), internet, ProcessTrigger.INTERNET_UP)
        val waiting = a.store.get(m.id)!!
        assertEquals(SendState.RETRY_WAIT, waiting.sendState)
        assertEquals(1, waiting.retryCount)
        assertTrue(waiting.nextAttemptAtUtc!! > clock.now())
        assertTrue(a.store.routeFor(m.id).any { it.event == RouteEvent.ATTEMPT_FAILED && it.transport == TransportKind.INTERNET_RELAY })
        // Network recovers; the timed retry uploads it.
        internet.reachOverride = null
        clock.nowMs = waiting.nextAttemptAtUtc!! + 1
        a.processor.processOnce(emptyList(), internet, ProcessTrigger.RETRY_TIMER)
        assertEquals(SendState.UPLOADED, a.store.get(m.id)!!.sendState)
    }

    @Test
    fun `SOS to everyone spreads phone to phone without looping back`() = runTest {
        val m = a.send(null, "🆘 Fire on floor 3, need help", MessageType.SOS, recipientId = "*")
        a.processor.processOnce(listOf(EngineLink(a, b, TransportKind.BLUETOOTH)), null, ProcessTrigger.LINK_UP)
        assertEquals(SendState.QUEUED, b.store.get(m.id)!!.sendState) // B will pass it on
        val bToC = EngineLink(b, c, TransportKind.WIFI_DIRECT)
        val bToA = EngineLink(b, a, TransportKind.BLUETOOTH)
        b.processor.processOnce(listOf(bToA, bToC), null, ProcessTrigger.INCOMING_RELAY)
        assertTrue(bToA.sentIds.isEmpty()) // never back to the sender
        val atC = c.store.get(m.id)!!
        assertEquals(2, atC.hopCount)
        assertEquals(com.relay.app.model.Direction.INCOMING, atC.direction)
        val cToB = EngineLink(c, b, TransportKind.WIFI_DIRECT)
        c.processor.processOnce(listOf(cToB), null, ProcessTrigger.INCOMING_RELAY)
        assertTrue(cToB.sentIds.isEmpty()) // B already had it
        // B does not send it to C a second time.
        b.processor.processOnce(listOf(bToA, bToC), null, ProcessTrigger.LINK_UP)
        assertEquals(1, bToC.sentIds.size)
    }

    @Test
    fun `ordinary broadcasts stay one hop`() = runTest {
        val m = a.send(null, "Meeting at the north gate", MessageType.NORMAL, recipientId = "*")
        a.processor.processOnce(listOf(EngineLink(a, b, TransportKind.BLUETOOTH)), null, ProcessTrigger.LINK_UP)
        assertEquals(SendState.NONE, b.store.get(m.id)!!.sendState)
        val bToC = EngineLink(b, c, TransportKind.WIFI_DIRECT)
        b.processor.processOnce(listOf(bToC), null, ProcessTrigger.INCOMING_RELAY)
        assertTrue(bToC.sentIds.isEmpty())
    }

    @Test
    fun `spreading SOS stops at the hop limit`() = runTest {
        a.config = RelayConfig(maxHops = 2)
        val m = a.send(null, "🆘 limited", MessageType.SOS, recipientId = "*")
        a.processor.processOnce(listOf(EngineLink(a, b, TransportKind.BLUETOOTH)), null, ProcessTrigger.LINK_UP)
        b.processor.processOnce(listOf(EngineLink(b, c, TransportKind.BLUETOOTH)), null, ProcessTrigger.INCOMING_RELAY)
        val atC = c.store.get(m.id)!!
        assertEquals(2, atC.hopCount)
        assertEquals(SendState.NONE, atC.sendState) // at the limit: C keeps it but does not pass it on
        val cToD = EngineLink(c, d, TransportKind.BLUETOOTH)
        c.processor.processOnce(listOf(cToD), null, ProcessTrigger.INCOMING_RELAY)
        assertTrue(cToD.sentIds.isEmpty())
    }

    private fun com.relay.app.data.db.MessageEntity.deliveredState() = deliveryState
}
