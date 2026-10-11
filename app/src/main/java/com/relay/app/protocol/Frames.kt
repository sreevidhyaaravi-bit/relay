package com.relay.app.protocol

import kotlinx.serialization.Serializable

/**
 * RELAY wire protocol, version 1.
 *
 * Every frame is ONE line of UTF-8 JSON terminated by '\n' (newline-delimited JSON).
 * JSON string escaping guarantees a frame never contains a raw newline, so '\n' is a safe
 * delimiter. The same frames are used over Bluetooth RFCOMM, Wi-Fi Direct TCP and local Wi-Fi
 * TCP. The internet relay stores the same [WireMessage] fields in Firestore.
 *
 * Exchange on every new connection:
 *   1. Both sides send HELLO immediately and wait for the other side's HELLO.
 *   2. MSG frames carry a [WireMessage]; the receiver replies with an ACK for that message id.
 *   3. RCPT frames carry final-delivery receipts back toward the original sender (multi-hop).
 *   4. PING/PONG keep the link alive; BYE closes it politely.
 *
 * A successful socket write is NOT proof of delivery. Only an ACK (or RCPT) counts.
 */
object ProtocolLimits {
    const val PROTOCOL_VERSION = 1
    const val MIN_SUPPORTED_VERSION = 1
    const val MAX_SUPPORTED_VERSION = 1

    /** Maximum encoded size of one frame, including the newline. */
    const val MAX_FRAME_BYTES = 64 * 1024
    const val MAX_BODY_CHARS = 2000
    const val MAX_NAME_CHARS = 64
    const val MAX_ID_CHARS = 64
    const val MAX_LABEL_CHARS = 200
    const val MAX_REASON_CHARS = 200
    const val MAX_ROUTE_HOPS = 32
    const val MAX_HOPS_LIMIT = 16

    /** Messages may not claim a lifetime longer than this. */
    const val MAX_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
}

object FrameType {
    const val HELLO = "HELLO"
    const val MSG = "MSG"
    const val ACK = "ACK"
    const val RCPT = "RCPT"
    const val PING = "PING"
    const val PONG = "PONG"
    const val BYE = "BYE"

    val ALL = setOf(HELLO, MSG, ACK, RCPT, PING, PONG, BYE)
}

object AckKind {
    /** An intermediate relay stored the message. The intended recipient has NOT confirmed yet. */
    const val HOP = "HOP"

    /** The intended recipient (or, for broadcast, this receiving device) stored the message. */
    const val FINAL = "FINAL"
}

object AckStatus {
    const val STORED = "STORED"

    /** Already had this message id. Treated as success (idempotent delivery). */
    const val DUPLICATE = "DUPLICATE"
    const val REJECTED = "REJECTED"
}

/** One line on the wire. Exactly one payload field is set, matching [t]. */
@Serializable
data class Frame(
    val v: Int = ProtocolLimits.PROTOCOL_VERSION,
    val t: String,
    val hello: Hello? = null,
    val msg: WireMessage? = null,
    val ack: Ack? = null,
    val rcpt: Receipt? = null,
    val reason: String? = null,
) {
    companion object {
        fun hello(hello: Hello) = Frame(t = FrameType.HELLO, hello = hello)
        fun msg(msg: WireMessage) = Frame(t = FrameType.MSG, msg = msg)
        fun ack(ack: Ack) = Frame(t = FrameType.ACK, ack = ack)
        fun rcpt(rcpt: Receipt) = Frame(t = FrameType.RCPT, rcpt = rcpt)
        fun ping() = Frame(t = FrameType.PING)
        fun pong() = Frame(t = FrameType.PONG)
        fun bye(reason: String) = Frame(t = FrameType.BYE, reason = reason.take(ProtocolLimits.MAX_REASON_CHARS))
    }
}

@Serializable
data class Hello(
    val deviceId: String,
    val name: String,
    val app: String = "RELAY",
    val appVersion: String = "",
    val protocolMin: Int = ProtocolLimits.MIN_SUPPORTED_VERSION,
    val protocolMax: Int = ProtocolLimits.MAX_SUPPORTED_VERSION,
)

/**
 * The message as it travels. [id], [senderId], [recipientId], [createdAtUtc], [type],
 * [priority], [expiresAtUtc] and [location] are set once by the original sender and are NEVER
 * changed by relays. Relays only increment [hopCount] and append to [route].
 */
@Serializable
data class WireMessage(
    val id: String,
    val senderId: String,
    val senderName: String,
    val recipientId: String,
    val recipientName: String? = null,
    val body: String,
    val type: String,
    val priority: Int,
    /** Original creation time, epoch milliseconds UTC, from the sender's clock. */
    val createdAtUtc: Long,
    val expiresAtUtc: Long,
    val location: WireLocation? = null,
    /** Number of transmissions so far. The sender's first transmission arrives with hopCount = 1. */
    val hopCount: Int = 0,
    val maxHops: Int = 6,
    /** One entry per transmission: who forwarded it, over which transport, and when (forwarder's clock). */
    val route: List<WireHop> = emptyList(),
    /** Sender's battery level when created (0-100), optional; older versions simply ignore it. */
    val batteryPercent: Int? = null,
)

@Serializable
data class WireLocation(
    val lat: Double? = null,
    val lon: Double? = null,
    val accuracyM: Float? = null,
    val capturedAtUtc: Long? = null,
    val label: String? = null,
    /** "DEVICE" or "MANUAL". */
    val source: String,
)

@Serializable
data class WireHop(
    val nodeId: String,
    val nodeName: String,
    /** [com.relay.app.model.TransportKind] name. */
    val transport: String,
    val atUtc: Long,
)

@Serializable
data class Ack(
    val messageId: String,
    /** [AckKind]. */
    val kind: String,
    /** [AckStatus]. */
    val status: String,
    val byDeviceId: String,
    val byName: String,
    /** When the acknowledging device stored the message (its own clock). */
    val receivedAtUtc: Long,
    val reason: String? = null,
)

/**
 * Final-delivery receipt created by the intended recipient when a message reached it through
 * relays. Receipts travel back hop by hop (flooded to connected peers, deduplicated by
 * [messageId]) until the original sender sees one.
 */
@Serializable
data class Receipt(
    val messageId: String,
    val originalSenderId: String,
    val finalRecipientId: String,
    val finalRecipientName: String,
    val receivedAtUtc: Long,
    val expiresAtUtc: Long,
)
