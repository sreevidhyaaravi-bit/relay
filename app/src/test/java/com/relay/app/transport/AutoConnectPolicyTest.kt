package com.relay.app.transport

import com.relay.app.data.db.PeerEntity
import com.relay.app.model.TransportKind
import com.relay.app.testutil.EngineLink
import com.relay.app.testutil.FakeClock
import com.relay.app.testutil.TestDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which known RELAY phones get an automatic Bluetooth reconnection attempt (pure logic). */
class AutoConnectPolicyTest {

    private val now = 1_760_000_000_000L

    private fun peer(id: String, address: String?, seenAgoMs: Long = 60_000) =
        PeerEntity(id, "Phone $id", now - seenAgoMs, TransportKind.BLUETOOTH, bluetoothAddress = address)

    private fun ids(list: List<AutoConnectPolicy.Candidate>) = list.map { it.deviceId }

    @Test
    fun `known RELAY phones with a Bluetooth address are retried, most recent first`() {
        val peers = listOf(peer("B", "AA:00", seenAgoMs = 5_000), peer("C", "AA:01", seenAgoMs = 1_000), peer("D", null))
        assertEquals(listOf("C", "B"), ids(AutoConnectPolicy.candidates(peers, emptyList(), now, emptyMap(), emptySet())))
    }

    @Test
    fun `already connected phones are skipped`() {
        val clock = FakeClock(now)
        val me = TestDevice("A", clock = clock)
        val b = TestDevice("B", clock = clock)
        val link = EngineLink(me, b, TransportKind.WIFI_DIRECT)
        val peers = listOf(peer("B", "AA:00"), peer("C", "AA:01"))
        assertEquals(listOf("C"), ids(AutoConnectPolicy.candidates(peers, listOf(link), now, emptyMap(), emptySet())))
    }

    @Test
    fun `phones the user disconnected on purpose are never auto-reconnected`() {
        val peers = listOf(peer("B", "AA:00"))
        assertTrue(AutoConnectPolicy.candidates(peers, emptyList(), now, emptyMap(), setOf("AA:00")).isEmpty())
    }

    @Test
    fun `backoff and age limits are respected`() {
        val peers = listOf(peer("B", "AA:00"), peer("Old", "AA:09", seenAgoMs = AutoConnectPolicy.MAX_AGE_MS + 1))
        assertTrue(AutoConnectPolicy.candidates(peers, emptyList(), now, mapOf("AA:00" to now + 10_000), emptySet()).isEmpty())
        assertEquals(listOf("B"), ids(AutoConnectPolicy.candidates(peers, emptyList(), now, mapOf("AA:00" to now - 1), emptySet())))
    }

    @Test
    fun `backoff grows then caps at two minutes`() {
        assertEquals(30_000L, AutoConnectPolicy.backoffMs(1))
        assertEquals(60_000L, AutoConnectPolicy.backoffMs(2))
        assertEquals(120_000L, AutoConnectPolicy.backoffMs(3))
        assertEquals(120_000L, AutoConnectPolicy.backoffMs(10))
    }
}
