package com.relay.app.relay

import com.relay.app.model.MessageType
import com.relay.app.model.TransportKind
import com.relay.app.notifications.AlertCenter
import com.relay.app.protocol.Frame
import com.relay.app.protocol.MessageValidator
import com.relay.app.protocol.ProtocolCodec
import com.relay.app.relay.MessageMapper
import com.relay.app.testutil.EngineLink
import com.relay.app.testutil.FakeClock
import com.relay.app.testutil.TestDevice
import com.relay.app.ui.screens.compose.QuickStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-two additions: battery level on messages, read-aloud text, quick status presets. */
class RoundTwoFeaturesTest {

    private val clock = FakeClock()

    @Test
    fun `battery level travels with the message and survives the wire`() = runTest {
        val a = TestDevice("RLY-A", "Phone A", clock)
        val b = TestDevice("RLY-B", "Phone B", clock)
        val m = a.engine.createOutgoing(
            com.relay.app.model.MessageDraft("RLY-B", "Phone B", "🆘 help", MessageType.SOS, null, batteryPercent = 12),
        )
        val wire = MessageMapper.toWireForSend(m, a.identity, TransportKind.BLUETOOTH, clock.now())
        val decoded = (ProtocolCodec.decode(ProtocolCodec.encode(Frame.msg(wire))) as ProtocolCodec.DecodeResult.Ok).frame.msg!!
        assertEquals(12, decoded.batteryPercent)
        a.processor.processOnce(listOf(EngineLink(a, b, TransportKind.BLUETOOTH)), null, ProcessTrigger.LINK_UP)
        assertEquals(12, b.store.get(m.id)!!.batteryPercent)
    }

    @Test
    fun `battery level outside 0-100 is rejected`() {
        val ok = com.relay.app.protocol.WireMessage(
            id = "123e4567-e89b-42d3-a456-426614174000", senderId = "RLY-A", senderName = "A", recipientId = "RLY-B",
            body = "x", type = "NORMAL", priority = 0, createdAtUtc = 1, expiresAtUtc = 2, batteryPercent = 50,
        )
        assertTrue(MessageValidator.validate(ok) is MessageValidator.Result.Valid)
        assertTrue(MessageValidator.validate(ok.copy(batteryPercent = 140)) is MessageValidator.Result.Invalid)
    }

    @Test
    fun `read-aloud text names the sender, drops emoji and includes the place`() = runTest {
        val a = TestDevice("RLY-A", "Priya", clock)
        val m = a.engine.createOutgoing(
            com.relay.app.model.MessageDraft(
                "RLY-B", "B", "🆘 Trapped in stairwell B", MessageType.SOS,
                com.relay.app.model.LocationInfo(null, null, null, null, "Gate 2", com.relay.app.model.LocationSource.MANUAL),
            ),
        )
        val text = AlertCenter.speechFor(m)
        assertTrue(text, text.startsWith("S O S from Priya."))
        assertTrue(text, text.contains("Trapped in stairwell B"))
        assertTrue(text, text.contains("Location: Gate 2"))
        assertFalse(text, text.contains("🆘"))
    }

    @Test
    fun `quick status presets use sensible priorities`() {
        assertEquals(MessageType.SOS, QuickStatus.TRAPPED.type)
        assertEquals(MessageType.URGENT, QuickStatus.MEDICAL.type)
        assertEquals(MessageType.COORDINATION, QuickStatus.SAFE.type)
        assertTrue(QuickStatus.entries.all { it.body.isNotBlank() && it.body.length < 100 })
    }
}
