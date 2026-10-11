package com.relay.app.data

import com.relay.app.data.db.MessageEntity
import com.relay.app.data.db.PeerEntity
import com.relay.app.data.db.RelayDatabase
import com.relay.app.data.db.RouteHopEntity
import com.relay.app.model.MessageDraft
import com.relay.app.relay.ProcessTrigger
import com.relay.app.relay.RelayEngine
import com.relay.app.transport.TransportManager
import kotlinx.coroutines.flow.Flow

/**
 * What the UI uses to read and change messages. Reads come straight from Room (live Flows);
 * every change goes through [RelayEngine] so state transitions stay in one place.
 */
class MessageRepository(
    private val db: RelayDatabase,
    private val engine: RelayEngine,
    private val transports: TransportManager,
) {
    val inbox: Flow<List<MessageEntity>> = db.messages().observeInbox()
    val outbox: Flow<List<MessageEntity>> = db.messages().observeOutbox()
    val relayQueue: Flow<List<MessageEntity>> = db.messages().observeRelayQueue()
    val queuedCount: Flow<Int> = db.messages().observeQueuedCount()
    val deliveredCount: Flow<Int> = db.messages().observeDeliveredCount()
    val unreadCount: Flow<Int> = db.messages().observeUnreadCount()
    val relayCount: Flow<Int> = db.messages().observeRelayCount()
    val peers: Flow<List<PeerEntity>> = db.peers().observeAll()

    fun message(id: String): Flow<MessageEntity?> = db.messages().observe(id)

    fun route(messageId: String): Flow<List<RouteHopEntity>> = db.routes().observeForMessage(messageId)

    fun recentRouteEvents(limit: Int = 100): Flow<List<RouteHopEntity>> = db.routes().observeRecent(limit)

    fun recentFailures(limit: Int = 30): Flow<List<RouteHopEntity>> = db.routes().observeRecentFailures(limit)

    suspend fun getMessage(id: String): MessageEntity? = db.messages().get(id)

    /**
     * Saves the message locally FIRST (so it survives restarts and total disconnection), then
     * asks the transport manager to try to send it. Returns the stored message.
     * @throws IllegalArgumentException if the draft is invalid (message is user-readable).
     */
    suspend fun send(draft: MessageDraft): MessageEntity {
        val stored = engine.createOutgoing(draft)
        transports.requestProcessing(ProcessTrigger.NEW_MESSAGE)
        return stored
    }

    suspend fun retryNow(id: String): Boolean {
        val ok = engine.retryNow(id)
        transports.requestProcessing(ProcessTrigger.MANUAL)
        return ok
    }

    suspend fun cancel(id: String): Boolean = engine.cancel(id)

    suspend fun markRead(id: String) = db.messages().markRead(id)

    /** Adds (or renames) a person from a scanned QR code so they appear as a recipient. */
    suspend fun addContact(deviceId: String, name: String, nowUtc: Long) {
        val existing = db.peers().get(deviceId)
        db.peers().upsert(existing?.copy(displayName = name) ?: PeerEntity(deviceId, name, nowUtc, lastTransport = null))
    }

    suspend fun markAllRead() = db.messages().markAllRead()
}
