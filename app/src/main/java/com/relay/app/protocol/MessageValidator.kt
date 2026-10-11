package com.relay.app.protocol

import com.relay.app.model.BROADCAST_ID
import com.relay.app.model.LocationSource
import com.relay.app.model.MessageType
import com.relay.app.model.TransportKind
import com.relay.app.protocol.ProtocolLimits.MAX_BODY_CHARS
import com.relay.app.protocol.ProtocolLimits.MAX_HOPS_LIMIT
import com.relay.app.protocol.ProtocolLimits.MAX_ID_CHARS
import com.relay.app.protocol.ProtocolLimits.MAX_LABEL_CHARS
import com.relay.app.protocol.ProtocolLimits.MAX_LIFETIME_MS
import com.relay.app.protocol.ProtocolLimits.MAX_NAME_CHARS
import com.relay.app.protocol.ProtocolLimits.MAX_ROUTE_HOPS

/**
 * Structural validation of everything that arrives from the network (or the internet relay).
 * Expiry is checked separately by the relay engine because it depends on the local clock.
 *
 * Note on clocks: device clocks are not synchronised. A createdAtUtc in the "future" is NOT
 * rejected (the sender's clock may simply be ahead); the UI flags suspected clock skew instead.
 */
object MessageValidator {

    sealed interface Result {
        data object Valid : Result
        data class Invalid(val reason: String) : Result
    }

    private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val DEVICE_ID_REGEX = Regex("^[A-Za-z0-9._:-]{1,$MAX_ID_CHARS}$")

    fun isValidMessageId(id: String?): Boolean = id != null && UUID_REGEX.matches(id)

    fun isValidDeviceId(id: String?): Boolean = id != null && DEVICE_ID_REGEX.matches(id)

    fun isValidRecipientId(id: String?): Boolean = id == BROADCAST_ID || isValidDeviceId(id)

    fun validate(msg: WireMessage): Result {
        if (!isValidMessageId(msg.id)) return invalid("message id must be a UUID")
        if (!isValidDeviceId(msg.senderId)) return invalid("invalid sender id")
        if (!isValidRecipientId(msg.recipientId)) return invalid("invalid recipient id")
        if (msg.senderName.isBlank() || msg.senderName.length > MAX_NAME_CHARS) return invalid("invalid sender name")
        if (msg.recipientName != null && msg.recipientName.length > MAX_NAME_CHARS) return invalid("recipient name too long")
        if (msg.body.isBlank()) return invalid("empty message body")
        if (msg.body.length > MAX_BODY_CHARS) return invalid("message body longer than $MAX_BODY_CHARS characters")
        val type = MessageType.fromName(msg.type) ?: return invalid("unknown message type '${msg.type.take(20)}'")
        if (msg.priority != type.priority) return invalid("priority does not match message type")
        if (msg.createdAtUtc <= 0) return invalid("missing creation time")
        if (msg.expiresAtUtc <= msg.createdAtUtc) return invalid("expiry must be after creation")
        if (msg.expiresAtUtc - msg.createdAtUtc > MAX_LIFETIME_MS) return invalid("message lifetime too long")
        if (msg.maxHops !in 1..MAX_HOPS_LIMIT) return invalid("maxHops out of range")
        if (msg.hopCount !in 0..msg.maxHops) return invalid("hopCount out of range")
        if (msg.route.size > MAX_ROUTE_HOPS) return invalid("route history too long")
        if (msg.batteryPercent != null && msg.batteryPercent !in 0..100) return invalid("battery level out of range")
        for (hop in msg.route) {
            val r = validateHop(hop)
            if (r is Result.Invalid) return r
        }
        msg.location?.let { loc ->
            val r = validateLocation(loc)
            if (r is Result.Invalid) return r
        }
        return Result.Valid
    }

    fun validateLocation(loc: WireLocation): Result {
        val source = LocationSource.entries.firstOrNull { it.name == loc.source }
            ?: return invalid("unknown location source")
        if ((loc.lat == null) != (loc.lon == null)) return invalid("latitude and longitude must both be set")
        if (loc.lat != null && (loc.lat.isNaN() || loc.lat !in -90.0..90.0)) return invalid("latitude out of range")
        if (loc.lon != null && (loc.lon.isNaN() || loc.lon !in -180.0..180.0)) return invalid("longitude out of range")
        if (loc.accuracyM != null && (loc.accuracyM.isNaN() || loc.accuracyM < 0f)) return invalid("invalid accuracy")
        if (loc.label != null && loc.label.length > MAX_LABEL_CHARS) return invalid("location label too long")
        when (source) {
            LocationSource.DEVICE -> {
                if (loc.lat == null) return invalid("device location without coordinates")
                if (loc.capturedAtUtc == null || loc.capturedAtUtc <= 0) return invalid("device location without capture time")
            }
            LocationSource.MANUAL -> {
                if (loc.label.isNullOrBlank() && loc.lat == null) return invalid("manual location needs a description")
            }
        }
        return Result.Valid
    }

    fun validateHop(hop: WireHop): Result {
        if (!isValidDeviceId(hop.nodeId)) return invalid("invalid route node id")
        if (hop.nodeName.length > MAX_NAME_CHARS) return invalid("route node name too long")
        if (TransportKind.fromName(hop.transport) == null) return invalid("unknown route transport")
        if (hop.atUtc <= 0) return invalid("invalid route timestamp")
        return Result.Valid
    }

    fun validateAck(ack: Ack): Result {
        if (!isValidMessageId(ack.messageId)) return invalid("ack for invalid message id")
        if (ack.kind != AckKind.HOP && ack.kind != AckKind.FINAL) return invalid("unknown ack kind")
        if (ack.status != AckStatus.STORED && ack.status != AckStatus.DUPLICATE && ack.status != AckStatus.REJECTED) {
            return invalid("unknown ack status")
        }
        if (!isValidDeviceId(ack.byDeviceId)) return invalid("ack from invalid device id")
        if (ack.byName.length > MAX_NAME_CHARS) return invalid("ack name too long")
        return Result.Valid
    }

    fun validateReceipt(r: Receipt): Result {
        if (!isValidMessageId(r.messageId)) return invalid("receipt for invalid message id")
        if (!isValidDeviceId(r.originalSenderId)) return invalid("receipt with invalid sender id")
        if (!isValidDeviceId(r.finalRecipientId)) return invalid("receipt with invalid recipient id")
        if (r.finalRecipientName.length > MAX_NAME_CHARS) return invalid("receipt name too long")
        if (r.receivedAtUtc <= 0 || r.expiresAtUtc <= 0) return invalid("receipt with invalid timestamps")
        return Result.Valid
    }

    fun validateHello(h: Hello): Result {
        if (h.app != "RELAY") return invalid("not a RELAY peer")
        if (!isValidDeviceId(h.deviceId)) return invalid("invalid peer device id")
        if (h.name.isBlank() || h.name.length > MAX_NAME_CHARS) return invalid("invalid peer name")
        if (h.protocolMin > h.protocolMax) return invalid("invalid protocol range")
        return Result.Valid
    }

    private fun invalid(reason: String) = Result.Invalid(reason)
}
