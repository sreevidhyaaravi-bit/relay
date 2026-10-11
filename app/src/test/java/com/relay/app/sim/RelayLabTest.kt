package com.relay.app.sim

import com.relay.app.data.db.RouteEvent
import com.relay.app.model.DeliveryState
import com.relay.app.model.MessageType
import com.relay.app.model.TransportKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SIMULATED relay network running the real engine (labelled simulation, not physical proof). */
class RelayLabTest {

    private val lab = RelayLab()
    private fun node(label: String) = lab.state.value.nodes.first { it.label == label }

    @Test
    fun `mixed-medium SOS reaches D and the receipt returns to A`() = runBlocking {
        lab.load(RelayLab.Scenario.MIXED_MEDIUM)
        lab.runUntilIdle()
        val atD = node("D").inbox.single()
        assertEquals("SIM-A", atD.senderId)
        assertEquals(MessageType.SOS, atD.messageType)
        assertEquals(3, atD.hopCount)
        val route = lab.routeText(atD.id)
        assertTrue(route, route.startsWith("SIMULATED:"))
        assertTrue(route, route.contains("Bluetooth") && route.contains("Wi-Fi Direct") && route.contains("Internet Relay"))
        val atA = node("A").outgoing.single()
        assertEquals(DeliveryState.DELIVERED, atA.deliveryState)
        assertEquals(atD.createdAtUtc, atA.createdAtUtc)
        assertEquals(atA.location(), atD.location())
    }

    @Test
    fun `SOS goes first when the link appears`() = runBlocking {
        lab.load(RelayLab.Scenario.PRIORITY)
        lab.step()
        assertTrue(node("B").inbox.isEmpty())
        lab.setLink("A", "B", TransportKind.BLUETOOTH, true)
        lab.step()
        val sends = lab.state.value.log.filter { it.contains("Sim A → Sim B") }.reversed() // oldest first
        assertTrue(sends.first(), sends.first().contains("SOS"))
        assertEquals(3, node("B").inbox.size)
    }

    @Test
    fun `normal message expires while the SOS keeps waiting`() = runBlocking {
        lab.load(RelayLab.Scenario.EXPIRY)
        val out = node("A").outgoing
        assertEquals(DeliveryState.EXPIRED, out.first { it.messageType == MessageType.NORMAL }.deliveryState)
        assertEquals(DeliveryState.PENDING, out.first { it.messageType == MessageType.SOS }.deliveryState)
        assertEquals(2, out.size) // kept, not deleted
    }

    @Test
    fun `hop limit stops forwarding`() = runBlocking {
        lab.load(RelayLab.Scenario.HOP_LIMIT)
        lab.runUntilIdle()
        assertTrue(node("D").inbox.isEmpty())
        assertEquals(1, node("B").carrying.size)
        assertTrue(node("C").carrying.isEmpty())
    }

    @Test
    fun `two paths still give one inbox entry`() = runBlocking {
        lab.load(RelayLab.Scenario.DUPLICATES)
        lab.runUntilIdle()
        val inbox = node("D").inbox
        assertEquals(1, inbox.size)
        val events = lab.routeEvents("D", inbox.single().id)
        assertTrue(events.any { it.event == RouteEvent.DUPLICATE_RECEIVED })
        assertEquals(DeliveryState.DELIVERED, node("A").outgoing.single().deliveryState)
    }

    @Test
    fun `failed transport falls back to the other medium`() = runBlocking {
        lab.load(RelayLab.Scenario.FALLBACK)
        lab.step()
        assertEquals(1, node("B").inbox.size)
        assertEquals(TransportKind.WIFI_DIRECT, node("A").outgoing.single().deliveredVia)
    }

    @Test
    fun `SOS to everyone reaches phones out of the sender's range`() = runBlocking {
        lab.load(RelayLab.Scenario.SOS_SPREAD)
        lab.runUntilIdle()
        listOf("B", "C", "D").forEach { assertEquals("Sim $it inbox", 1, node(it).inbox.size) }
        assertEquals(3, node("D").inbox.single().hopCount)
    }
}
