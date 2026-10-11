package com.relay.app.relay

import com.relay.app.data.db.MessageEntity
import com.relay.app.data.db.ReceiptEntity
import com.relay.app.model.TransportKind

/** Things the relay engine reports. The app turns them into notifications and processing triggers. */
sealed interface RelayEvent {
    /** A new message addressed to this device (or broadcast) was stored. Not emitted for duplicates. */
    data class IncomingStored(val message: MessageEntity) : RelayEvent

    /** A message for someone else was stored for store-and-forward. Not emitted for duplicates. */
    data class RelayStored(val message: MessageEntity) : RelayEvent

    /** One of OUR messages was confirmed by its intended recipient. */
    data class Delivered(val message: MessageEntity) : RelayEvent

    /** An intermediate relay accepted one of our messages (not final delivery). */
    data class HopAcked(val message: MessageEntity, val relayName: String) : RelayEvent

    data class Uploaded(val message: MessageEntity) : RelayEvent

    data class Expired(val message: MessageEntity) : RelayEvent

    /** A transmission attempt failed. */
    data class AttemptFailed(
        val messageId: String,
        val transport: TransportKind,
        val peerName: String?,
        val reason: String,
        val atUtc: Long,
    ) : RelayEvent

    /** Automatic retries stopped for this message after too many failures. */
    data class Paused(val message: MessageEntity) : RelayEvent

    /** A delivery receipt was created or learned that neighbours may need. */
    data class ReceiptAvailable(val receipt: ReceiptEntity) : RelayEvent
}

/** Why the queue is being processed. Route-change triggers ignore backoff; timers respect it. */
enum class ProcessTrigger(val ignoresBackoff: Boolean) {
    NEW_MESSAGE(true),
    MANUAL(true),
    APP_RESUMED(true),
    LINK_UP(true),
    LINK_DOWN(false),
    INTERNET_UP(true),
    TRANSPORT_STATE(false),
    INCOMING_RELAY(true),
    RETRY_TIMER(false),
    WORKER(false),
}

/** Summary of one processing pass, for logs and the Connectivity screen. */
data class ProcessReport(
    val trigger: ProcessTrigger,
    val startedAtUtc: Long,
    val examined: Int = 0,
    val attempts: Int = 0,
    val deliveredFinal: Int = 0,
    val hopAcked: Int = 0,
    val uploaded: Int = 0,
    val failedAttempts: Int = 0,
    /** Messages that had no usable route at all (they stay QUEUED). */
    val noRoute: Int = 0,
    val expired: Int = 0,
    /** Earliest nextAttemptAtUtc after this pass, for scheduling the next timer retry. */
    val nextRetryAtUtc: Long? = null,
)
