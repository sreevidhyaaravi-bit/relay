package com.relay.app.protocol

import org.junit.Assert.assertTrue
import org.junit.Test

class MessageValidatorTest {

    private val valid = WireMessage(
        id = "123e4567-e89b-42d3-a456-426614174000",
        senderId = "RLY-AAAA1111",
        senderName = "A",
        recipientId = "RLY-BBBB2222",
        body = "hello",
        type = "NORMAL",
        priority = 0,
        createdAtUtc = 1_000L,
        expiresAtUtc = 2_000L,
        hopCount = 1,
        maxHops = 6,
    )

    private fun invalid(m: WireMessage) = MessageValidator.validate(m) is MessageValidator.Result.Invalid

    @Test fun `valid message passes`() = assertTrue(MessageValidator.validate(valid) is MessageValidator.Result.Valid)
    @Test fun `broadcast recipient is allowed`() = assertTrue(!invalid(valid.copy(recipientId = "*")))
    @Test fun `non uuid id is rejected`() = assertTrue(invalid(valid.copy(id = "abc")))
    @Test fun `empty body is rejected`() = assertTrue(invalid(valid.copy(body = " ")))
    @Test fun `too long body is rejected`() = assertTrue(invalid(valid.copy(body = "x".repeat(ProtocolLimits.MAX_BODY_CHARS + 1))))
    @Test fun `unknown type is rejected`() = assertTrue(invalid(valid.copy(type = "PARTY")))
    @Test fun `priority must match type`() = assertTrue(invalid(valid.copy(type = "SOS", priority = 0)))
    @Test fun `expiry before creation is rejected`() = assertTrue(invalid(valid.copy(expiresAtUtc = 500L)))
    @Test fun `lifetime over seven days is rejected`() =
        assertTrue(invalid(valid.copy(expiresAtUtc = 1_000L + ProtocolLimits.MAX_LIFETIME_MS + 1)))
    @Test fun `hop count above max is rejected`() = assertTrue(invalid(valid.copy(hopCount = 7)))
    @Test fun `bad sender id is rejected`() = assertTrue(invalid(valid.copy(senderId = "has space")))
    @Test fun `device location without coordinates is rejected`() =
        assertTrue(invalid(valid.copy(location = WireLocation(source = "DEVICE", capturedAtUtc = 1L))))
    @Test fun `device location without capture time is rejected`() =
        assertTrue(invalid(valid.copy(location = WireLocation(lat = 1.0, lon = 2.0, source = "DEVICE"))))
    @Test fun `out of range latitude is rejected`() =
        assertTrue(invalid(valid.copy(location = WireLocation(lat = 91.0, lon = 2.0, capturedAtUtc = 1L, source = "DEVICE"))))
    @Test fun `manual location needs a description`() =
        assertTrue(invalid(valid.copy(location = WireLocation(source = "MANUAL"))))
    @Test fun `manual location with label is valid`() =
        assertTrue(!invalid(valid.copy(location = WireLocation(label = "Library", source = "MANUAL"))))
    @Test fun `route with unknown transport is rejected`() =
        assertTrue(invalid(valid.copy(route = listOf(WireHop("RLY-AAAA1111", "A", "CARRIER_PIGEON", 5L)))))
}
