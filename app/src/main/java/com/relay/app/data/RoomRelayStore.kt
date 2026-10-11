package com.relay.app.data

import androidx.room.withTransaction
import com.relay.app.data.db.HandoffEntity
import com.relay.app.data.db.MessageEntity
import com.relay.app.data.db.PeerEntity
import com.relay.app.data.db.ReceiptEntity
import com.relay.app.data.db.RelayDatabase
import com.relay.app.data.db.RouteHopEntity
import com.relay.app.relay.RelayStore

/** Persistent [RelayStore] backed by Room: a thin adapter over the DAOs in data/db/Daos.kt. */
class RoomRelayStore(private val db: RelayDatabase) : RelayStore {
    private val messages = db.messages()
    private val routes = db.routes()
    private val peers = db.peers()
    private val handoffs = db.handoffs()
    private val receipts = db.receipts()

    override suspend fun insertIfAbsent(message: MessageEntity): Boolean = messages.insertIgnore(message) != -1L
    override suspend fun get(id: String): MessageEntity? = messages.get(id)
    override suspend fun update(message: MessageEntity) = messages.update(message)
    override suspend fun pendingForwarding(): List<MessageEntity> = messages.pendingForwarding()
    override suspend fun pendingExpired(nowUtc: Long): List<MessageEntity> = messages.pendingExpired(nowUtc)
    override suspend fun nextRetryAt(): Long? = messages.nextRetryAt()
    override suspend fun addRouteEvent(hop: RouteHopEntity) {
        routes.insert(hop)
    }
    override suspend fun routeFor(messageId: String): List<RouteHopEntity> = routes.forMessage(messageId)
    override suspend fun recordHandoff(handoff: HandoffEntity) = handoffs.upsert(handoff)
    override suspend fun handoffsFor(messageId: String): List<HandoffEntity> = handoffs.forMessage(messageId)
    override suspend fun upsertPeer(peer: PeerEntity) = peers.upsert(peer)
    override suspend fun getPeer(deviceId: String): PeerEntity? = peers.get(deviceId)
    override suspend fun knownPeers(): List<PeerEntity> = peers.all()
    override suspend fun insertReceiptIfAbsent(receipt: ReceiptEntity): Boolean = receipts.insertIgnore(receipt) != -1L
    override suspend fun getReceipt(messageId: String): ReceiptEntity? = receipts.get(messageId)
    override suspend fun receiptsToShare(nowUtc: Long, selfId: String, limit: Int): List<ReceiptEntity> =
        receipts.toShare(nowUtc, selfId, limit)
    override suspend fun deleteExpiredReceipts(nowUtc: Long) {
        receipts.deleteExpired(nowUtc)
    }

    /** Room transactions are re-entrant, so nested calls are safe. */
    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }
}
