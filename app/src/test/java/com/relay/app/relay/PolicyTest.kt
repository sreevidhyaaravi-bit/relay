package com.relay.app.relay

import com.relay.app.data.db.MessageEntity
import com.relay.app.model.DeliveryState
import com.relay.app.model.Direction
import com.relay.app.model.MessageType
import com.relay.app.model.RelayConfig
import com.relay.app.model.SendState
import com.relay.app.model.TransportKind
import com.relay.app.testutil.EngineLink
import com.relay.app.testutil.FakeClock
import com.relay.app.testutil.TestDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure policy tests (no I/O). Links are mocked. */
class PolicyTest {

    private val clock = FakeClock()
    private val a = TestDevice("RLY-A", clock = clock)
    private val b = TestDevice("RLY-B", clock = clock)
    private val c = TestDevice("RLY-C", clock = clock)

    private fun msg(
        id: String = "m1",
        type: MessageType = MessageType.NORMAL,
        created: Long = 0L,
        recipient: String = "RLY-B",
        hopCount: Int = 0,
        maxHops: Int = 6,
    ) = MessageEntity(
        id = id, senderId = "RLY-A", senderName = "A", recipientId = recipient, recipientName = null, body = "x",
        messageType = type, priority = type.priority, createdAtUtc = created, expiresAtUtc = created + 1_000_000,
        direction = Direction.OUTGOING, sendState = SendState.QUEUED, deliveryState = DeliveryState.PENDING,
        hopCount = hopCount, maxHops = maxHops,
    )

    private fun input(
        m: MessageEntity,
        links: List<EngineLink>,
        handed: Set<String> = emptySet(),
        rejected: Set<String> = emptySet(),
        routeNodes: Set<String> = emptySet(),
        sticky: Map<String, TransportKind> = emptyMap(),
    ) = RouteInput(m, "RLY-A", links, handed, rejected, routeNodes, sticky, RelayConfig().preferredOrder)

    // ---------------------------------------------------------------- queue order

    @Test
    fun `SOS is sent first and FIFO within a priority`() {
        val list = listOf(
            msg("n2", created = 20), msg("s1", MessageType.SOS, created = 30), msg("n1", created = 10),
            msg("u1", MessageType.URGENT, created = 5), msg("c1", MessageType.COORDINATION, created = 1),
        )
        assertEquals(listOf("s1", "u1", "c1", "n1", "n2"), list.sortedWith(QueuePolicy.sendOrder).map { it.id })
    }

    @Test
    fun `backoff grows exponentially and is capped`() {
        val cfg = RelayConfig(backoffBaseMs = 10_000, backoffMaxMs = 120_000)
        val delays = (1..8).map { QueuePolicy.backoffMs(it, cfg, "same-id") }
        assertTrue(delays[1] > delays[0])
        assertTrue(delays[2] > delays[1])
        delays.forEach { assertTrue(it <= 120_000L) }
        assertTrue(QueuePolicy.backoffMs(20, cfg, "x") in 108_000L..120_000L)
        // jitter stays within +/-10%
        assertTrue(QueuePolicy.backoffMs(1, cfg, "abc") in 9_000L..11_000L)
    }

    @Test
    fun `timer triggers respect backoff but route changes do not`() {
        val waiting = msg().copy(sendState = SendState.RETRY_WAIT, nextAttemptAtUtc = 5_000)
        assertFalse(QueuePolicy.isDue(waiting, 1_000, ProcessTrigger.RETRY_TIMER))
        assertTrue(QueuePolicy.isDue(waiting, 6_000, ProcessTrigger.RETRY_TIMER))
        assertTrue(QueuePolicy.isDue(waiting, 1_000, ProcessTrigger.LINK_UP))
        val paused = msg().copy(sendState = SendState.PAUSED)
        assertFalse(QueuePolicy.isDue(paused, 1_000, ProcessTrigger.LINK_UP))
        assertTrue(QueuePolicy.isDue(paused, 1_000, ProcessTrigger.MANUAL))
    }

    // ---------------------------------------------------------------- route selection

    @Test
    fun `bluetooth connected to recipient is used`() {
        val bt = EngineLink(a, b, TransportKind.BLUETOOTH)
        val direct = RoutePolicy.directCandidates(input(msg(), listOf(bt)))
        assertEquals(listOf(TransportKind.BLUETOOTH), direct.map { it.transport })
    }

    @Test
    fun `bluetooth unavailable and wifi direct connected uses wifi direct`() {
        val bt = EngineLink(a, b, TransportKind.BLUETOOTH).apply { open = false }
        val wd = EngineLink(a, b, TransportKind.WIFI_DIRECT)
        val direct = RoutePolicy.directCandidates(input(msg(), listOf(bt, wd)))
        assertEquals(listOf(TransportKind.WIFI_DIRECT), direct.map { it.transport })
    }

    @Test
    fun `multiple routes follow the preferred order with the alternative as fallback`() {
        val wd = EngineLink(a, b, TransportKind.WIFI_DIRECT)
        val bt = EngineLink(a, b, TransportKind.BLUETOOTH)
        val lan = EngineLink(a, b, TransportKind.LOCAL_WIFI)
        val direct = RoutePolicy.directCandidates(input(msg(), listOf(lan, wd, bt)))
        assertEquals(listOf(TransportKind.BLUETOOTH, TransportKind.WIFI_DIRECT, TransportKind.LOCAL_WIFI), direct.map { it.transport })
    }

    @Test
    fun `last successful transport is preferred to avoid flip-flopping`() {
        val wd = EngineLink(a, b, TransportKind.WIFI_DIRECT)
        val bt = EngineLink(a, b, TransportKind.BLUETOOTH)
        val direct = RoutePolicy.directCandidates(input(msg(), listOf(bt, wd), sticky = mapOf("RLY-B" to TransportKind.WIFI_DIRECT)))
        assertEquals(TransportKind.WIFI_DIRECT, direct.first().transport)
    }

    @Test
    fun `no links means no candidates`() {
        assertTrue(RoutePolicy.plan(input(msg(), emptyList()), internetCanReach = false).isEmpty())
    }

    @Test
    fun `internet relay comes before relay peers when it can reach the recipient`() {
        val toC = EngineLink(a, c, TransportKind.BLUETOOTH)
        val secondary = RoutePolicy.secondaryCandidates(input(msg(), listOf(toC)), internetCanReach = true)
        assertEquals(listOf(TransportKind.INTERNET_RELAY, TransportKind.BLUETOOTH), secondary.map { it.transport })
    }

    @Test
    fun `relay peers exclude sender, route members and peers that already have it`() {
        val toC = EngineLink(a, c, TransportKind.BLUETOOTH)
        assertTrue(RoutePolicy.secondaryCandidates(input(msg(), listOf(toC), handed = setOf("RLY-C")), false).isEmpty())
        assertTrue(RoutePolicy.secondaryCandidates(input(msg(), listOf(toC), routeNodes = setOf("RLY-C")), false).isEmpty())
        assertEquals(1, RoutePolicy.secondaryCandidates(input(msg(), listOf(toC)), false).size)
    }

    @Test
    fun `hop limit stops relaying but still allows the last direct hop`() {
        val toB = EngineLink(a, b, TransportKind.BLUETOOTH)
        val toC = EngineLink(a, c, TransportKind.BLUETOOTH)
        val atLimit = msg(hopCount = 5, maxHops = 6)
        assertTrue(RoutePolicy.secondaryCandidates(input(atLimit, listOf(toC)), false).isEmpty())
        assertEquals(1, RoutePolicy.directCandidates(input(atLimit, listOf(toB))).size)
    }

    @Test
    fun `a recipient that rejected the message is not retried`() {
        val toB = EngineLink(a, b, TransportKind.BLUETOOTH)
        assertTrue(RoutePolicy.directCandidates(input(msg(), listOf(toB), rejected = setOf("RLY-B"))).isEmpty())
    }

    @Test
    fun `broadcast goes once to each neighbour and is never relayed`() {
        val toB1 = EngineLink(a, b, TransportKind.BLUETOOTH)
        val toB2 = EngineLink(a, b, TransportKind.WIFI_DIRECT)
        val toC = EngineLink(a, c, TransportKind.WIFI_DIRECT)
        val bcast = msg(recipient = "*")
        val direct = RoutePolicy.directCandidates(input(bcast, listOf(toB1, toB2, toC), handed = emptySet()))
        assertEquals(listOf("RLY-B", "RLY-C"), direct.map { it.link.peer.deviceId })
        assertTrue(RoutePolicy.secondaryCandidates(input(bcast, listOf(toC)), true).isEmpty())
        assertEquals(listOf("RLY-C"), RoutePolicy.directCandidates(input(bcast, listOf(toB1, toC), handed = setOf("RLY-B"))).map { it.link.peer.deviceId })
    }
}
