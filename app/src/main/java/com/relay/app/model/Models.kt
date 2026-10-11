package com.relay.app.model

/**
 * Core domain types shared by the UI, the database, the relay engine, every transport and the
 * simulator. Keep this file free of Android imports so it can be unit tested on the JVM.
 */

/** Recipient id that means "every nearby device I am directly connected to" (one hop only). */
const val BROADCAST_ID = "*"

/** Default number of hops a message may travel before relays stop forwarding it. */
const val DEFAULT_MAX_HOPS = 6

/** Message categories. Higher [priority] is sent first. */
enum class MessageType(val priority: Int, val label: String, val symbol: String) {
    NORMAL(0, "Normal", "💬"),
    COORDINATION(1, "Coordination", "📋"),
    URGENT(2, "Urgent", "⚠️"),
    SOS(3, "SOS", "🆘");

    companion object {
        fun fromName(name: String?): MessageType? = entries.firstOrNull { it.name == name }
    }
}

/** The physical (or simulated) medium used for one hop. */
enum class TransportKind(val label: String) {
    BLUETOOTH("Bluetooth"),
    WIFI_DIRECT("Wi-Fi Direct"),
    LOCAL_WIFI("Local Wi-Fi"),
    INTERNET_RELAY("Internet Relay"),
    SIMULATED("Simulated");

    companion object {
        fun fromName(name: String?): TransportKind? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Route/radio state of one transport. A radio being ON is NOT the same as a usable route:
 * only [CONNECTED] (a live, handshaken link to a peer) or a reachable internet relay counts.
 */
enum class TransportState(val label: String) {
    DISABLED("Disabled"),
    DISCOVERING("Discovering"),
    AVAILABLE_NOT_CONNECTED("Available, not connected"),
    CONNECTING("Connecting"),
    CONNECTED("Connected"),
    SENDING("Sending"),
    FAILED("Failed"),
    UNAVAILABLE("Unavailable"),
}

/** Where a stored message came from, from this device's point of view. */
enum class Direction {
    /** Created on this device. */
    OUTGOING,

    /** Addressed to this device (or broadcast) and stored in the inbox. */
    INCOMING,

    /** Carried by this device on behalf of someone else (store-and-forward). */
    RELAY,
}

/**
 * Transmission progress of a message that this device must forward (OUTGOING or RELAY).
 * Kept separate from [DeliveryState]: a hop acknowledgement is NOT final delivery.
 */
enum class SendState {
    /** Not applicable (INCOMING messages). */
    NONE,

    /** Saved locally, waiting for a usable route. Shown as "QUEUED — WAITING FOR CONNECTION". */
    QUEUED,

    /** A transmission attempt is in progress. */
    SENDING,

    /** An intermediate relay device stored it and acknowledged the hop. Final delivery still pending. */
    HOP_ACKED,

    /** The internet relay confirmed the upload. Final delivery still pending. */
    UPLOADED,

    /** The last attempt failed; another attempt is scheduled at nextAttemptAtUtc. */
    RETRY_WAIT,

    /** Automatic retries stopped after too many failures. The user can tap "Retry now". */
    PAUSED,

    /** Nothing more to send (delivered, expired or cancelled). */
    DONE,
}

/** Final-recipient state. Only a real acknowledgement from the intended recipient sets DELIVERED. */
enum class DeliveryState {
    /** Not yet confirmed by the intended recipient. */
    PENDING,

    /** The intended recipient stored the message and acknowledged it (directly, via receipt, or via the internet relay). */
    DELIVERED,

    /** INCOMING message stored on this device. */
    RECEIVED,

    /** Expired before delivery. Kept (not deleted) so the user can see what happened. */
    EXPIRED,

    /** Cancelled by the user before delivery. */
    CANCELLED,
}

enum class LocationSource { DEVICE, MANUAL }

/**
 * Optional location attached by the SENDER. Never replaced by a relay's location.
 * For [LocationSource.MANUAL] only [label] is required; coordinates are optional.
 */
data class LocationInfo(
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMeters: Float?,
    val capturedAtUtc: Long?,
    val label: String?,
    val source: LocationSource,
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null

    /** A device fix older than this (relative to when the message was created) is shown as stale. */
    fun isStale(referenceUtc: Long, staleAfterMs: Long = STALE_AFTER_MS): Boolean =
        source == LocationSource.DEVICE && capturedAtUtc != null && referenceUtc - capturedAtUtc > staleAfterMs

    companion object {
        const val STALE_AFTER_MS: Long = 10 * 60 * 1000L
    }
}

/** This device's identity, exchanged in the HELLO handshake. */
data class DeviceIdentity(val deviceId: String, val displayName: String)

/** The identity of the device at the other end of a link, learned from its HELLO frame. */
data class PeerIdentity(val deviceId: String, val displayName: String)

/** Something genuinely found by discovery (Bluetooth inquiry, bonded list, Wi-Fi P2P peers, NSD). */
data class DiscoveredPeer(
    val kind: TransportKind,
    /** Bluetooth MAC, Wi-Fi P2P device address, or host:port / NSD service name for LAN. */
    val address: String,
    val name: String,
    val detail: String = "",
    val bonded: Boolean = false,
    val connectable: Boolean = true,
)

/** Snapshot of one transport for the UI and the transport manager. */
data class TransportStatus(
    val kind: TransportKind,
    val state: TransportState,
    val detail: String = "",
    val lastError: String? = null,
    val updatedAtUtc: Long = 0L,
)

/** What the user typed on the compose / SOS screen. */
data class MessageDraft(
    val recipientId: String,
    val recipientName: String?,
    val body: String,
    val type: MessageType,
    val location: LocationInfo?,
    /** Sender's battery level (0-100) at creation, if known. */
    val batteryPercent: Int? = null,
)

/** Tunable relay behaviour. Values come from Settings; the simulator uses its own. */
data class RelayConfig(
    val ordinaryExpiryMs: Long = 24 * HOUR_MS,
    val urgentExpiryMs: Long = 24 * HOUR_MS,
    val sosExpiryMs: Long = 72 * HOUR_MS,
    val maxHops: Int = DEFAULT_MAX_HOPS,
    /** Whether this device stores and forwards messages addressed to other devices. */
    val relayForOthers: Boolean = true,
    /** Non-SOS messages are handed to at most this many relay peers per device. SOS goes to every eligible peer. */
    val relayCopies: Int = 2,
    /** Automatic retries stop (state PAUSED) after this many failed attempts. */
    val maxAutoAttempts: Int = 8,
    val backoffBaseMs: Long = 15_000L,
    val backoffMaxMs: Long = 15 * 60_000L,
    /** Preference among equally suitable DIRECT links (sticky last-success transport wins first). */
    val preferredOrder: List<TransportKind> = listOf(
        TransportKind.BLUETOOTH, TransportKind.WIFI_DIRECT, TransportKind.LOCAL_WIFI,
    ),
) {
    fun expiryFor(type: MessageType): Long = when (type) {
        MessageType.SOS -> sosExpiryMs
        MessageType.URGENT -> urgentExpiryMs
        else -> ordinaryExpiryMs
    }

    companion object {
        const val HOUR_MS: Long = 60 * 60 * 1000L
    }
}

/** Abstract clock so the relay engine and simulator can be tested with virtual time. */
fun interface Clock {
    fun now(): Long
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
}
