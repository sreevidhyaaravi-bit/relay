package com.relay.app.relay

import com.relay.app.data.db.HandoffEntity
import com.relay.app.data.db.MessageEntity
import com.relay.app.data.db.PeerEntity
import com.relay.app.data.db.ReceiptEntity
import com.relay.app.data.db.RouteHopEntity

/**
 * Persistence used by [RelayEngine] and [QueueProcessor]. Two implementations:
 *  - [com.relay.app.data.RoomRelayStore]: the real, persistent Room database.
 *  - [InMemoryRelayStore]: used by the Relay Lab simulator and by unit tests.
 * Using the same interface is what lets the simulator run the REAL relay logic while keeping
 * simulated data completely separate from real messages.
 */
interface RelayStore {
    /** Inserts [message] unless a message with the same id exists. Returns true if inserted. */
    suspend fun insertIfAbsent(message: MessageEntity): Boolean

    suspend fun get(id: String): MessageEntity?

    suspend fun update(message: MessageEntity)

    /**
     * OUTGOING and RELAY messages whose delivery is still PENDING, ordered by
     * priority DESC, createdAtUtc ASC, id ASC.
     */
    suspend fun pendingForwarding(): List<MessageEntity>

    /** PENDING OUTGOING/RELAY messages whose expiresAtUtc <= [nowUtc]. */
    suspend fun pendingExpired(nowUtc: Long): List<MessageEntity>

    /** Earliest nextAttemptAtUtc among messages in RETRY_WAIT, or null. */
    suspend fun nextRetryAt(): Long?

    suspend fun addRouteEvent(hop: RouteHopEntity)

    suspend fun routeFor(messageId: String): List<RouteHopEntity>

    suspend fun recordHandoff(handoff: HandoffEntity)

    suspend fun handoffsFor(messageId: String): List<HandoffEntity>

    suspend fun upsertPeer(peer: PeerEntity)

    suspend fun getPeer(deviceId: String): PeerEntity?

    /** Every device this phone has completed a handshake with, most recent first. */
    suspend fun knownPeers(): List<PeerEntity>

    /** Returns true if inserted (false = already had a receipt for that message). */
    suspend fun insertReceiptIfAbsent(receipt: ReceiptEntity): Boolean

    suspend fun getReceipt(messageId: String): ReceiptEntity?

    suspend fun receiptsToShare(nowUtc: Long, selfId: String, limit: Int): List<ReceiptEntity>

    suspend fun deleteExpiredReceipts(nowUtc: Long)

    /** Runs [block] atomically (a Room transaction, or a mutex for the in-memory store). */
    suspend fun <T> transaction(block: suspend () -> T): T
}
