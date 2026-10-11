package com.relay.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    /** Returns the new rowId, or -1 if a message with this id already exists (deduplication). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :id")
    fun observe(id: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE direction = 'INCOMING' ORDER BY receivedAtUtc DESC, createdAtUtc DESC")
    fun observeInbox(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE direction = 'OUTGOING' ORDER BY createdAtUtc DESC")
    fun observeOutbox(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE direction = 'RELAY' ORDER BY priority DESC, createdAtUtc ASC")
    fun observeRelayQueue(): Flow<List<MessageEntity>>

    /**
     * Messages this device still has to forward, in send order:
     * priority (SOS first), then oldest first (FIFO within a priority = fair), then id (stable).
     * Includes received SOS broadcasts marked QUEUED: those spread phone to phone.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE (direction IN ('OUTGOING', 'RELAY') AND deliveryState = 'PENDING')
           OR (direction = 'INCOMING' AND recipientId = '*' AND sendState = 'QUEUED')
        ORDER BY priority DESC, createdAtUtc ASC, id ASC
        """,
    )
    suspend fun pendingForwarding(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE deliveryState = 'PENDING' AND direction IN ('OUTGOING', 'RELAY') AND expiresAtUtc <= :nowUtc")
    suspend fun pendingExpired(nowUtc: Long): List<MessageEntity>

    @Query("SELECT MIN(nextAttemptAtUtc) FROM messages WHERE deliveryState = 'PENDING' AND direction IN ('OUTGOING', 'RELAY') AND sendState = 'RETRY_WAIT'")
    suspend fun nextRetryAt(): Long?

    @Query("SELECT COUNT(*) FROM messages WHERE direction = 'OUTGOING' AND deliveryState = 'PENDING' AND finalAckCount = 0")
    fun observeQueuedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE direction = 'OUTGOING' AND (deliveryState = 'DELIVERED' OR finalAckCount > 0)")
    fun observeDeliveredCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE direction = 'INCOMING' AND isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE direction = 'RELAY' AND deliveryState = 'PENDING'")
    fun observeRelayCount(): Flow<Int>

    @Query("UPDATE messages SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE messages SET isRead = 1 WHERE direction = 'INCOMING'")
    suspend fun markAllRead()
}

@Dao
interface RouteDao {
    @Insert
    suspend fun insert(hop: RouteHopEntity): Long

    @Query("SELECT * FROM route_hops WHERE messageId = :messageId ORDER BY id ASC")
    suspend fun forMessage(messageId: String): List<RouteHopEntity>

    @Query("SELECT * FROM route_hops WHERE messageId = :messageId ORDER BY id ASC")
    fun observeForMessage(messageId: String): Flow<List<RouteHopEntity>>

    @Query("SELECT * FROM route_hops ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RouteHopEntity>>

    @Query("SELECT * FROM route_hops WHERE event = 'ATTEMPT_FAILED' ORDER BY id DESC LIMIT :limit")
    fun observeRecentFailures(limit: Int): Flow<List<RouteHopEntity>>
}

@Dao
interface PeerDao {
    @Upsert
    suspend fun upsert(peer: PeerEntity)

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId")
    suspend fun get(deviceId: String): PeerEntity?

    @Query("SELECT * FROM peers ORDER BY lastSeenAtUtc DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers ORDER BY lastSeenAtUtc DESC")
    suspend fun all(): List<PeerEntity>
}

@Dao
interface HandoffDao {
    @Upsert
    suspend fun upsert(handoff: HandoffEntity)

    @Query("SELECT * FROM handoffs WHERE messageId = :messageId")
    suspend fun forMessage(messageId: String): List<HandoffEntity>
}

@Dao
interface ReceiptDao {
    /** Returns -1 if a receipt for this message already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(receipt: ReceiptEntity): Long

    @Query("SELECT * FROM receipts WHERE messageId = :messageId")
    suspend fun get(messageId: String): ReceiptEntity?

    /** Receipts worth passing on to neighbours: not expired and not addressed to this device. */
    @Query(
        """
        SELECT * FROM receipts
        WHERE expiresAtUtc > :nowUtc AND originalSenderId != :selfId
        ORDER BY storedAtUtc DESC LIMIT :limit
        """,
    )
    suspend fun toShare(nowUtc: Long, selfId: String, limit: Int): List<ReceiptEntity>

    @Query("DELETE FROM receipts WHERE expiresAtUtc <= :nowUtc")
    suspend fun deleteExpired(nowUtc: Long): Int
}
