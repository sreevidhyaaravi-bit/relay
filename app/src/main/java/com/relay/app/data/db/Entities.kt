package com.relay.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.relay.app.model.DEFAULT_MAX_HOPS
import com.relay.app.model.DeliveryState
import com.relay.app.model.Direction
import com.relay.app.model.LocationInfo
import com.relay.app.model.LocationSource
import com.relay.app.model.MessageType
import com.relay.app.model.SendState
import com.relay.app.model.TransportKind

/**
 * One row per message id on this device. The id is globally unique (UUID) and identical on
 * every device and every transport, which is what makes deduplication work.
 * Enums are stored as their names (Room's built-in enum support).
 */
@Entity(
    tableName = "messages",
    indices = [Index("direction"), Index("deliveryState"), Index("recipientId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val senderId: String,
    val senderName: String,
    val recipientId: String,
    val recipientName: String?,
    val body: String,
    val messageType: MessageType,
    val priority: Int,
    /** Original creation instant (epoch ms UTC, sender's clock). Never changes across hops. */
    val createdAtUtc: Long,
    val expiresAtUtc: Long,
    /** When THIS device received it (this device's clock). Null for messages created here. */
    val receivedAtUtc: Long? = null,
    /** When THIS device (the sender) received the final recipient's acknowledgement (this device's clock). */
    val acknowledgedAtUtc: Long? = null,
    /** When the final recipient stored it, as reported by the recipient (recipient's clock). */
    val deliveredAtUtc: Long? = null,
    /** How the final acknowledgement reached this device. */
    val deliveredVia: TransportKind? = null,
    val deliveredToName: String? = null,

    // ---- sender's optional location (never replaced by a relay's location)
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val locationCapturedAtUtc: Long? = null,
    val locationLabel: String? = null,
    val locationSource: LocationSource? = null,

    // ---- relay / transmission state
    val direction: Direction,
    val sendState: SendState,
    val deliveryState: DeliveryState,
    /** Transmissions so far (as received). */
    val hopCount: Int = 0,
    val maxHops: Int = DEFAULT_MAX_HOPS,
    /** Route carried by the message as received: JSON array of WireHop. "[]" for messages created here. */
    val routeJson: String = "[]",
    /** Transport of the most recent attempt or of the hop that delivered it here. */
    val lastTransport: TransportKind? = null,
    /** Failed attempt rounds counted toward the automatic retry limit. */
    val retryCount: Int = 0,
    val lastAttemptAtUtc: Long? = null,
    /** Earliest time a timer-driven retry may run (route-change events may retry sooner). */
    val nextAttemptAtUtc: Long? = null,
    val lastError: String? = null,
    /** Last intermediate relay that acknowledged a hop (NOT the final recipient). */
    val relayAckBy: String? = null,
    val relayAckByName: String? = null,
    val relayAckAtUtc: Long? = null,
    val hopAckCount: Int = 0,
    /** Number of FINAL acks (1 for direct messages; number of devices for broadcast). */
    val finalAckCount: Int = 0,
    val uploadedAtUtc: Long? = null,
    /** The neighbour that handed this message to this device. */
    val receivedFromId: String? = null,
    val receivedFromName: String? = null,
    val isRead: Boolean = false,
    /** Sender's battery level (0-100) when the message was created; tells rescuers how long the phone may last. */
    val batteryPercent: Int? = null,
) {
    val isBroadcast: Boolean get() = recipientId == com.relay.app.model.BROADCAST_ID

    fun location(): LocationInfo? {
        val source = locationSource ?: return null
        return LocationInfo(latitude, longitude, accuracyMeters, locationCapturedAtUtc, locationLabel, source)
    }
}

/** Events that make up a message's route history on THIS device. */
enum class RouteEvent {
    /** Message created on this device. */
    CREATED,

    /** A hop recorded in the message's carried route (another device forwarded it). */
    FORWARDED_BY,

    /** This device started a transmission attempt. */
    ATTEMPT,

    /** A transmission attempt failed (detail has the reason). */
    ATTEMPT_FAILED,

    /** An intermediate relay acknowledged storing the message. */
    HOP_ACKED,

    /** The internet relay confirmed the upload. */
    UPLOADED,

    /** This device received and stored the message. */
    RECEIVED,

    /** The same message id arrived again (deduplicated, not stored twice). */
    DUPLICATE_RECEIVED,

    /** The intended recipient's FINAL acknowledgement reached this device. */
    FINAL_ACK,

    /** A multi-hop delivery receipt reached this device. */
    RECEIPT,

    EXPIRED,
    CANCELLED,
}

@Entity(tableName = "route_hops", indices = [Index("messageId")])
data class RouteHopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,
    val event: RouteEvent,
    /** Device the event is about (for FORWARDED_BY: the forwarding node; otherwise usually this device). */
    val nodeId: String,
    val nodeName: String,
    /** The other side of the hop, when known. */
    val peerId: String? = null,
    val peerName: String? = null,
    val transport: TransportKind? = null,
    /** Event time. For FORWARDED_BY it is the forwarder's clock; otherwise this device's clock. */
    val atUtc: Long,
    val detail: String? = null,
)

/** Devices this phone has completed a HELLO handshake with. Used as the recipient list. */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val lastSeenAtUtc: Long,
    val lastTransport: TransportKind?,
    /** Transport of the last successful (acked) send to this peer; used to avoid flip-flopping. */
    val lastSuccessTransport: TransportKind? = null,
    val bluetoothAddress: String? = null,
    val wifiDirectAddress: String? = null,
    val lanAddress: String? = null,
)

/** "Message X was already handed to peer Y": prevents re-sending and forwarding loops. */
@Entity(tableName = "handoffs", primaryKeys = ["messageId", "peerId"])
data class HandoffEntity(
    val messageId: String,
    val peerId: String,
    val transport: TransportKind,
    /** HOP, FINAL, DUPLICATE or REJECTED. */
    val result: String,
    val atUtc: Long,
)

/** Final-delivery receipts (created here as final recipient, or carried for others). */
@Entity(tableName = "receipts")
data class ReceiptEntity(
    @PrimaryKey val messageId: String,
    val originalSenderId: String,
    val finalRecipientId: String,
    val finalRecipientName: String,
    val receivedAtUtc: Long,
    val expiresAtUtc: Long,
    val createdLocally: Boolean,
    val storedAtUtc: Long,
)
