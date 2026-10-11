package com.relay.app.relay

import com.relay.app.data.db.HandoffEntity
import com.relay.app.data.db.MessageEntity
import com.relay.app.data.db.PeerEntity
import com.relay.app.data.db.ReceiptEntity
import com.relay.app.data.db.RouteHopEntity
import com.relay.app.model.DeliveryState
import com.relay.app.model.Direction
import com.relay.app.model.SendState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * In-memory [RelayStore] for the Relay Lab simulator and unit tests. Behaves like
 * RoomRelayStore: same ordering, same "insert if absent" deduplication, re-entrant transactions.
 * Data disappears when the object is dropped, which keeps simulated data away from real data.
 */
class InMemoryRelayStore : RelayStore {

    private val lock = Any()
    private val messages = LinkedHashMap<String, MessageEntity>()
    private val routes = ArrayList<RouteHopEntity>()
    private val peers = LinkedHashMap<String, PeerEntity>()
    private val handoffs = LinkedHashMap<Pair<String, String>, HandoffEntity>()
    private val receipts = LinkedHashMap<String, ReceiptEntity>()
    private var nextRouteId = 1L
    private val txMutex = Mutex()

    override suspend fun insertIfAbsent(message: MessageEntity): Boolean = synchronized(lock) {
        if (messages.containsKey(message.id)) false else {
            messages[message.id] = message
            true
        }
    }

    override suspend fun get(id: String): MessageEntity? = synchronized(lock) { messages[id] }

    override suspend fun update(message: MessageEntity) {
        synchronized(lock) { if (messages.containsKey(message.id)) messages[message.id] = message }
    }

    override suspend fun pendingForwarding(): List<MessageEntity> = synchronized(lock) {
        messages.values
            .filter {
                ((it.direction == Direction.OUTGOING || it.direction == Direction.RELAY) && it.deliveryState == DeliveryState.PENDING) ||
                    (it.direction == Direction.INCOMING && it.isBroadcast && it.sendState == SendState.QUEUED)
            }
            .sortedWith(QueuePolicy.sendOrder)
    }

    override suspend fun pendingExpired(nowUtc: Long): List<MessageEntity> = synchronized(lock) {
        messages.values.filter {
            (it.direction == Direction.OUTGOING || it.direction == Direction.RELAY) &&
                it.deliveryState == DeliveryState.PENDING && it.expiresAtUtc <= nowUtc
        }
    }

    override suspend fun nextRetryAt(): Long? = synchronized(lock) {
        messages.values
            .filter {
                it.deliveryState == DeliveryState.PENDING && it.sendState == SendState.RETRY_WAIT &&
                    (it.direction == Direction.OUTGOING || it.direction == Direction.RELAY)
            }
            .mapNotNull { it.nextAttemptAtUtc }
            .minOrNull()
    }

    override suspend fun addRouteEvent(hop: RouteHopEntity) {
        synchronized(lock) { routes += hop.copy(id = nextRouteId++) }
    }

    override suspend fun routeFor(messageId: String): List<RouteHopEntity> =
        synchronized(lock) { routes.filter { it.messageId == messageId } }

    override suspend fun recordHandoff(handoff: HandoffEntity) {
        synchronized(lock) { handoffs[handoff.messageId to handoff.peerId] = handoff }
    }

    override suspend fun handoffsFor(messageId: String): List<HandoffEntity> =
        synchronized(lock) { handoffs.values.filter { it.messageId == messageId } }

    override suspend fun upsertPeer(peer: PeerEntity) {
        synchronized(lock) { peers[peer.deviceId] = peer }
    }

    override suspend fun getPeer(deviceId: String): PeerEntity? = synchronized(lock) { peers[deviceId] }

    override suspend fun knownPeers(): List<PeerEntity> =
        synchronized(lock) { peers.values.sortedByDescending { it.lastSeenAtUtc } }

    override suspend fun insertReceiptIfAbsent(receipt: ReceiptEntity): Boolean = synchronized(lock) {
        if (receipts.containsKey(receipt.messageId)) false else {
            receipts[receipt.messageId] = receipt
            true
        }
    }

    override suspend fun getReceipt(messageId: String): ReceiptEntity? = synchronized(lock) { receipts[messageId] }

    override suspend fun receiptsToShare(nowUtc: Long, selfId: String, limit: Int): List<ReceiptEntity> = synchronized(lock) {
        receipts.values
            .filter { it.expiresAtUtc > nowUtc && it.originalSenderId != selfId }
            .sortedByDescending { it.storedAtUtc }
            .take(limit)
    }

    override suspend fun deleteExpiredReceipts(nowUtc: Long) {
        synchronized(lock) { receipts.values.removeAll { it.expiresAtUtc <= nowUtc } }
    }

    override suspend fun <T> transaction(block: suspend () -> T): T {
        // Re-entrant: a transaction started inside another one just joins it.
        if (coroutineContext[TxElement] != null) return block()
        return txMutex.withLock { withContext(TxElement()) { block() } }
    }

    // ---------------------------------------------------------------- snapshot helpers (simulator UI, tests)

    fun allMessages(): List<MessageEntity> = synchronized(lock) { messages.values.toList() }

    fun allRoutes(): List<RouteHopEntity> = synchronized(lock) { routes.toList() }

    fun allReceipts(): List<ReceiptEntity> = synchronized(lock) { receipts.values.toList() }

    fun allPeers(): List<PeerEntity> = synchronized(lock) { peers.values.toList() }

    fun clear() = synchronized(lock) {
        messages.clear()
        routes.clear()
        peers.clear()
        handoffs.clear()
        receipts.clear()
    }

    private class TxElement : AbstractCoroutineContextElement(TxElement) {
        companion object Key : CoroutineContext.Key<TxElement>
    }
}
