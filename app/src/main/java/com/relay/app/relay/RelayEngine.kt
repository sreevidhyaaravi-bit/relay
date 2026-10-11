package com.relay.app.relay

import com.relay.app.data.db.HandoffEntity
import com.relay.app.data.db.MessageEntity
import com.relay.app.data.db.PeerEntity
import com.relay.app.data.db.ReceiptEntity
import com.relay.app.data.db.RouteEvent
import com.relay.app.data.db.RouteHopEntity
import com.relay.app.model.BROADCAST_ID
import com.relay.app.model.Clock
import com.relay.app.model.DeliveryState
import com.relay.app.model.DeviceIdentity
import com.relay.app.model.Direction
import com.relay.app.model.MessageDraft
import com.relay.app.model.MessageType
import com.relay.app.model.PeerIdentity
import com.relay.app.model.RelayConfig
import com.relay.app.model.SendState
import com.relay.app.model.TransportKind
import com.relay.app.protocol.Ack
import com.relay.app.protocol.AckKind
import com.relay.app.protocol.AckStatus
import com.relay.app.protocol.MessageValidator
import com.relay.app.protocol.ProtocolLimits
import com.relay.app.protocol.Receipt
import com.relay.app.protocol.WireMessage
import com.relay.app.relay.MessageMapper.toWire
import com.relay.app.transport.InboundHandler
import com.relay.app.transport.SendOutcome
import com.relay.app.transport.UploadOutcome
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

/**
 * The single source of truth for message state transitions. Used by the real app (with
 * RoomRelayStore) and by the Relay Lab simulator (with InMemoryRelayStore).
 *
 * Every read-modify-write happens inside [RelayStore.transaction] so concurrent events
 * (an ACK on one link, a receipt on another) cannot overwrite each other.
 */
class RelayEngine(
    private val store: RelayStore,
    private val identity: () -> DeviceIdentity,
    private val config: () -> RelayConfig,
    private val clock: Clock,
) : InboundHandler {

    private val _events = MutableSharedFlow<RelayEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Notifications/processing triggers. Hot flow, extra buffer, never suspends the engine. */
    val events: SharedFlow<RelayEvent> get() = _events

    private fun emit(event: RelayEvent) {
        _events.tryEmit(event)
    }

    // ------------------------------------------------------------------ creating messages

    /**
     * Validates [draft], creates a new OUTGOING message and stores it BEFORE any transmission.
     * @throws IllegalArgumentException with a user-readable message if the draft is invalid.
     */
    suspend fun createOutgoing(draft: MessageDraft): MessageEntity {
        val me = identity()
        val cfg = config()
        val now = clock.now()
        val body = draft.body.trim()
        require(body.isNotEmpty()) { "The message is empty." }
        require(body.length <= ProtocolLimits.MAX_BODY_CHARS) {
            "The message is longer than ${ProtocolLimits.MAX_BODY_CHARS} characters."
        }
        val recipient = draft.recipientId.trim()
        require(MessageValidator.isValidRecipientId(recipient)) { "Choose a valid recipient." }
        require(recipient != me.deviceId) { "You cannot send a message to this same phone." }
        val location = draft.location
        if (location != null) {
            val check = MessageValidator.validateLocation(location.toWire())
            require(check is MessageValidator.Result.Valid) {
                "Location problem: ${(check as MessageValidator.Result.Invalid).reason}"
            }
        }
        val lifetime = cfg.expiryFor(draft.type).coerceIn(60_000L, ProtocolLimits.MAX_LIFETIME_MS)
        val message = MessageEntity(
            id = UUID.randomUUID().toString(),
            senderId = me.deviceId,
            senderName = me.displayName,
            recipientId = recipient,
            recipientName = draft.recipientName?.trim()?.take(ProtocolLimits.MAX_NAME_CHARS)?.ifBlank { null },
            body = body,
            messageType = draft.type,
            priority = draft.type.priority,
            createdAtUtc = now,
            expiresAtUtc = now + lifetime,
            latitude = location?.latitude,
            longitude = location?.longitude,
            accuracyMeters = location?.accuracyMeters,
            locationCapturedAtUtc = location?.capturedAtUtc,
            locationLabel = location?.label?.trim()?.take(ProtocolLimits.MAX_LABEL_CHARS)?.ifBlank { null },
            locationSource = location?.source,
            direction = Direction.OUTGOING,
            sendState = SendState.QUEUED,
            deliveryState = DeliveryState.PENDING,
            hopCount = 0,
            maxHops = cfg.maxHops.coerceIn(1, ProtocolLimits.MAX_HOPS_LIMIT),
            routeJson = "[]",
            batteryPercent = draft.batteryPercent?.coerceIn(0, 100),
        )
        store.transaction {
            check(store.insertIfAbsent(message)) { "Message id collision" }
            store.addRouteEvent(event(message.id, RouteEvent.CREATED, me, atUtc = now))
        }
        return message
    }

    // ------------------------------------------------------------------ inbound (from links)

    override suspend fun onMessage(msg: WireMessage, from: PeerIdentity, via: TransportKind): Ack {
        val me = identity()
        val cfg = config()
        val now = clock.now()
        fun ack(kind: String, status: String, reason: String? = null) =
            Ack(msg.id, kind, status, me.deviceId, me.displayName, now, reason)

        val check = MessageValidator.validate(msg)
        if (check is MessageValidator.Result.Invalid) return ack(AckKind.HOP, AckStatus.REJECTED, check.reason)
        if (msg.expiresAtUtc <= now) return ack(AckKind.HOP, AckStatus.REJECTED, "expired")
        // Our own message came back through the mesh: nothing to store.
        if (msg.senderId == me.deviceId) return ack(AckKind.HOP, AckStatus.DUPLICATE, "originated on this device")

        val forMe = msg.recipientId == me.deviceId
        val broadcast = msg.recipientId == BROADCAST_ID
        val kind = if (forMe || broadcast) AckKind.FINAL else AckKind.HOP
        if (!forMe && !broadcast) {
            if (!cfg.relayForOthers) return ack(AckKind.HOP, AckStatus.REJECTED, "relay disabled on this device")
            if (msg.hopCount >= msg.maxHops) return ack(AckKind.HOP, AckStatus.REJECTED, "hop limit reached")
        }
        val direction = if (forMe || broadcast) Direction.INCOMING else Direction.RELAY

        var stored: MessageEntity? = null
        var receipt: ReceiptEntity? = null
        store.transaction {
            if (store.get(msg.id) != null) {
                // Deduplication: the same id arrived again (other transport, retry or relay).
                store.addRouteEvent(event(msg.id, RouteEvent.DUPLICATE_RECEIVED, me, from, via, now))
                return@transaction
            }
            val received = MessageMapper.fromWire(msg, direction, from, via, now)
            // An SOS sent to everyone keeps spreading: this phone passes it on to its own
            // neighbours (hop limit and deduplication stop it). Other broadcasts stay one hop.
            val spreads = broadcast && received.messageType == MessageType.SOS &&
                msg.hopCount < msg.maxHops && cfg.relayForOthers
            val entity = if (spreads) received.copy(sendState = SendState.QUEUED) else received
            if (!store.insertIfAbsent(entity)) return@transaction
            msg.route.forEach { hop ->
                store.addRouteEvent(
                    RouteHopEntity(
                        messageId = msg.id,
                        event = RouteEvent.FORWARDED_BY,
                        nodeId = hop.nodeId,
                        nodeName = hop.nodeName,
                        transport = TransportKind.fromName(hop.transport),
                        atUtc = hop.atUtc,
                    ),
                )
            }
            store.addRouteEvent(event(msg.id, RouteEvent.RECEIVED, me, from, via, now))
            // Reached us through relays: the sender cannot see our ACK, so create a receipt
            // that travels back hop by hop.
            if (forMe && from.deviceId != msg.senderId) {
                val r = ReceiptEntity(
                    messageId = msg.id,
                    originalSenderId = msg.senderId,
                    finalRecipientId = me.deviceId,
                    finalRecipientName = me.displayName,
                    receivedAtUtc = now,
                    expiresAtUtc = msg.expiresAtUtc,
                    createdLocally = true,
                    storedAtUtc = now,
                )
                if (store.insertReceiptIfAbsent(r)) receipt = r
            }
            stored = entity
        }
        val newMessage = stored
        if (newMessage != null) {
            emit(if (direction == Direction.INCOMING) RelayEvent.IncomingStored(newMessage) else RelayEvent.RelayStored(newMessage))
            receipt?.let { emit(RelayEvent.ReceiptAvailable(it)) }
        }
        return ack(kind, if (newMessage != null) AckStatus.STORED else AckStatus.DUPLICATE)
    }

    override suspend fun onReceipt(receipt: Receipt, from: PeerIdentity, via: TransportKind) {
        val me = identity()
        val now = clock.now()
        if (receipt.expiresAtUtc <= now) return
        if (MessageValidator.validateReceipt(receipt) is MessageValidator.Result.Invalid) return
        var delivered: MessageEntity? = null
        var share: ReceiptEntity? = null
        store.transaction {
            val entity = ReceiptEntity(
                messageId = receipt.messageId,
                originalSenderId = receipt.originalSenderId,
                finalRecipientId = receipt.finalRecipientId,
                finalRecipientName = receipt.finalRecipientName,
                receivedAtUtc = receipt.receivedAtUtc,
                expiresAtUtc = receipt.expiresAtUtc,
                createdLocally = false,
                storedAtUtc = now,
            )
            val isNew = store.insertReceiptIfAbsent(entity)
            val m = store.get(receipt.messageId)
            if (m != null && m.deliveryState == DeliveryState.PENDING && !m.isBroadcast &&
                m.recipientId == receipt.finalRecipientId && m.senderId == receipt.originalSenderId
            ) {
                val updated = m.copy(
                    deliveryState = DeliveryState.DELIVERED,
                    sendState = SendState.DONE,
                    acknowledgedAtUtc = now,
                    deliveredAtUtc = receipt.receivedAtUtc,
                    deliveredVia = via,
                    deliveredToName = receipt.finalRecipientName,
                    finalAckCount = m.finalAckCount + 1,
                    nextAttemptAtUtc = null,
                    lastError = null,
                )
                store.update(updated)
                store.addRouteEvent(
                    event(
                        m.id, RouteEvent.RECEIPT, me,
                        PeerIdentity(receipt.finalRecipientId, receipt.finalRecipientName), via, now,
                        detail = "receipt passed on by ${from.displayName}",
                    ),
                )
                if (m.direction == Direction.OUTGOING) delivered = updated
            }
            if (isNew && receipt.originalSenderId != me.deviceId) share = entity
        }
        delivered?.let { emit(RelayEvent.Delivered(it)) }
        share?.let { emit(RelayEvent.ReceiptAvailable(it)) }
    }

    override suspend fun onUnsolicitedAck(ack: Ack, from: PeerIdentity, via: TransportKind) {
        val outcome = if (ack.status == AckStatus.REJECTED) {
            SendOutcome.Rejected(ack.reason ?: "rejected by peer")
        } else {
            SendOutcome.Acked(ack)
        }
        onSendOutcome(ack.messageId, via, from, outcome)
    }

    override suspend fun onRemoteFinalDelivery(
        messageId: String,
        recipientId: String,
        recipientName: String?,
        deliveredAtUtc: Long,
        via: TransportKind,
    ) {
        val me = identity()
        val now = clock.now()
        var delivered: MessageEntity? = null
        var share: ReceiptEntity? = null
        store.transaction {
            val m = store.get(messageId) ?: return@transaction
            if (m.deliveryState != DeliveryState.PENDING || m.recipientId != recipientId || m.isBroadcast) return@transaction
            val name = recipientName ?: m.recipientName ?: recipientId
            val updated = m.copy(
                deliveryState = DeliveryState.DELIVERED,
                sendState = SendState.DONE,
                acknowledgedAtUtc = now,
                deliveredAtUtc = deliveredAtUtc,
                deliveredVia = via,
                deliveredToName = name,
                finalAckCount = m.finalAckCount + 1,
                nextAttemptAtUtc = null,
                lastError = null,
            )
            store.update(updated)
            store.addRouteEvent(
                event(m.id, RouteEvent.FINAL_ACK, me, PeerIdentity(recipientId, name), via, now, detail = "reported by ${via.label}"),
            )
            if (m.direction == Direction.OUTGOING) {
                delivered = updated
            } else {
                share = relayReceipt(m, recipientId, name, deliveredAtUtc, now)
            }
        }
        delivered?.let { emit(RelayEvent.Delivered(it)) }
        share?.let { emit(RelayEvent.ReceiptAvailable(it)) }
    }

    // ------------------------------------------------------------------ outbound (from the queue processor)

    suspend fun onAttemptStarted(messageId: String, via: TransportKind, peer: PeerIdentity?) {
        val me = identity()
        val now = clock.now()
        store.transaction {
            val m = store.get(messageId) ?: return@transaction
            if (m.deliveryState != DeliveryState.PENDING) return@transaction
            store.update(m.copy(sendState = SendState.SENDING, lastAttemptAtUtc = now, lastTransport = via))
            store.addRouteEvent(event(messageId, RouteEvent.ATTEMPT, me, peer, via, now))
        }
    }

    suspend fun onSendOutcome(messageId: String, via: TransportKind, peer: PeerIdentity, outcome: SendOutcome) {
        val me = identity()
        val now = clock.now()
        val toEmit = mutableListOf<RelayEvent>()
        store.transaction {
            val m = store.get(messageId) ?: return@transaction
            // Received messages are only ever sent on when they are spreading SOS broadcasts;
            // for those, record which neighbours have it so it is not sent to them again.
            val spreading = m.direction == Direction.INCOMING && m.isBroadcast && m.sendState == SendState.QUEUED
            if (m.direction == Direction.INCOMING && !spreading) return@transaction
            when (outcome) {
                is SendOutcome.Acked -> handleAck(m, outcome.ack, via, peer, me, now, toEmit)
                is SendOutcome.Rejected -> {
                    store.recordHandoff(HandoffEntity(messageId, peer.deviceId, via, RESULT_REJECTED, now))
                    val reason = "${peer.displayName} rejected it: ${outcome.reason}"
                    markAttemptFailed(m, via, peer, reason, me, now, toEmit)
                }
                is SendOutcome.Failed -> markAttemptFailed(m, via, peer, "${via.label} to ${peer.displayName}: ${outcome.reason}", me, now, toEmit)
                SendOutcome.Timeout -> markAttemptFailed(
                    m, via, peer, "${via.label} to ${peer.displayName}: no acknowledgement in time", me, now, toEmit,
                )
            }
        }
        toEmit.forEach { emit(it) }
    }

    suspend fun onUploadOutcome(messageId: String, outcome: UploadOutcome) {
        val me = identity()
        val now = clock.now()
        val toEmit = mutableListOf<RelayEvent>()
        store.transaction {
            val m = store.get(messageId) ?: return@transaction
            when (outcome) {
                UploadOutcome.Uploaded, UploadOutcome.AlreadyUploaded -> {
                    if (m.deliveryState != DeliveryState.PENDING) return@transaction
                    val updated = m.copy(
                        sendState = SendState.UPLOADED,
                        uploadedAtUtc = m.uploadedAtUtc ?: now,
                        lastTransport = TransportKind.INTERNET_RELAY,
                        lastError = null,
                    )
                    store.update(updated)
                    store.addRouteEvent(
                        event(messageId, RouteEvent.UPLOADED, me, null, TransportKind.INTERNET_RELAY, now,
                            detail = if (outcome == UploadOutcome.AlreadyUploaded) "already on the relay" else null),
                    )
                    if (m.direction == Direction.OUTGOING) toEmit += RelayEvent.Uploaded(updated)
                }
                is UploadOutcome.Unreachable -> markAttemptFailed(
                    m, TransportKind.INTERNET_RELAY, null, "Internet relay: ${outcome.reason}", me, now, toEmit,
                )
                is UploadOutcome.Failed -> markAttemptFailed(
                    m, TransportKind.INTERNET_RELAY, null, "Internet relay: ${outcome.reason}", me, now, toEmit,
                )
            }
        }
        toEmit.forEach { emit(it) }
    }

    /**
     * Every candidate route failed in this pass: count a failed round and schedule the next
     * timer retry with bounded exponential backoff, or pause automatic retries at the limit.
     * Messages already carried by a relay or uploaded keep that state and are never paused.
     */
    suspend fun onAttemptRoundFailed(messageId: String, reason: String) {
        val cfg = config()
        val now = clock.now()
        var paused: MessageEntity? = null
        store.transaction {
            val m = store.get(messageId) ?: return@transaction
            if (m.deliveryState != DeliveryState.PENDING) return@transaction
            val rounds = m.retryCount + 1
            val progressing = (m.hopAckCount > 0 && !m.isBroadcast) || m.uploadedAtUtc != null
            val updated = if (rounds >= cfg.maxAutoAttempts && !progressing) {
                m.copy(sendState = SendState.PAUSED, retryCount = rounds, nextAttemptAtUtc = null, lastError = reason)
                    .also { paused = it }
            } else {
                val next = now + QueuePolicy.backoffMs(rounds, cfg, m.id)
                val base = m.copy(retryCount = rounds, nextAttemptAtUtc = next, lastError = reason)
                base.copy(sendState = if (progressing) QueuePolicy.restingState(base) else SendState.RETRY_WAIT)
            }
            store.update(updated)
        }
        paused?.let { emit(RelayEvent.Paused(it)) }
    }

    /**
     * No usable route existed in this pass. Stays QUEUED; does NOT count as a failed attempt.
     * [reason] (for example "the recipient has not registered") is shown to the user.
     */
    suspend fun onNoRoute(messageId: String, reason: String? = null) {
        store.transaction {
            val m = store.get(messageId) ?: return@transaction
            if (m.deliveryState != DeliveryState.PENDING) return@transaction
            val next = when (m.sendState) {
                SendState.SENDING -> QueuePolicy.restingState(m)
                // The retry time passed but there is still no route: say so honestly.
                SendState.RETRY_WAIT -> if ((m.nextAttemptAtUtc ?: 0L) <= clock.now()) SendState.QUEUED else SendState.RETRY_WAIT
                else -> m.sendState
            }
            val error = reason?.take(200) ?: m.lastError
            if (next != m.sendState || error != m.lastError) store.update(m.copy(sendState = next, lastError = error))
        }
    }

    suspend fun cancel(messageId: String): Boolean {
        val me = identity()
        val now = clock.now()
        return store.transaction {
            val m = store.get(messageId) ?: return@transaction false
            if (m.direction != Direction.OUTGOING || m.deliveryState != DeliveryState.PENDING) return@transaction false
            store.update(m.copy(deliveryState = DeliveryState.CANCELLED, sendState = SendState.DONE, nextAttemptAtUtc = null))
            store.addRouteEvent(event(messageId, RouteEvent.CANCELLED, me, atUtc = now))
            true
        }
    }

    suspend fun retryNow(messageId: String): Boolean = store.transaction {
        val m = store.get(messageId) ?: return@transaction false
        if (m.deliveryState != DeliveryState.PENDING || m.direction == Direction.INCOMING) return@transaction false
        val cleared = m.copy(
            retryCount = if (m.sendState == SendState.PAUSED) 0 else m.retryCount,
            nextAttemptAtUtc = null,
            sendState = SendState.QUEUED,
        )
        store.update(cleared.copy(sendState = QueuePolicy.restingState(cleared)))
        true
    }

    /** Marks every pending message past its expiry as EXPIRED (never deletes). Returns them. */
    suspend fun expireDue(): List<MessageEntity> {
        val me = identity()
        val now = clock.now()
        val expired = store.transaction {
            store.pendingExpired(now).map { m ->
                val updated = m.copy(
                    deliveryState = DeliveryState.EXPIRED,
                    sendState = SendState.DONE,
                    nextAttemptAtUtc = null,
                    lastError = "Expired: no longer forwarded",
                )
                store.update(updated)
                store.addRouteEvent(event(m.id, RouteEvent.EXPIRED, me, atUtc = now))
                updated
            }.also { store.deleteExpiredReceipts(now) }
        }
        expired.filter { it.direction == Direction.OUTGOING }.forEach { emit(RelayEvent.Expired(it)) }
        return expired
    }

    /** Records/updates the peer after a successful HELLO handshake. */
    suspend fun onPeerConnected(peer: PeerIdentity, via: TransportKind, address: String?) {
        val now = clock.now()
        store.transaction {
            val existing = store.getPeer(peer.deviceId)
            store.upsertPeer(
                PeerEntity(
                    deviceId = peer.deviceId,
                    displayName = peer.displayName,
                    lastSeenAtUtc = now,
                    lastTransport = via,
                    lastSuccessTransport = existing?.lastSuccessTransport,
                    bluetoothAddress = if (via == TransportKind.BLUETOOTH) address else existing?.bluetoothAddress,
                    wifiDirectAddress = if (via == TransportKind.WIFI_DIRECT) address else existing?.wifiDirectAddress,
                    lanAddress = if (via == TransportKind.LOCAL_WIFI) address else existing?.lanAddress,
                ),
            )
        }
    }

    /**
     * After an app restart nothing is actually transmitting, so a message saved as SENDING (the
     * app was killed mid-attempt) goes back to its resting state. Call once at startup.
     */
    suspend fun recoverAfterRestart(): Int = store.transaction {
        var fixed = 0
        store.pendingForwarding().filter { it.sendState == SendState.SENDING }.forEach { m ->
            store.update(m.copy(sendState = QueuePolicy.restingState(m)))
            fixed++
        }
        fixed
    }

    // ------------------------------------------------------------------ helpers (call inside a transaction)

    private suspend fun handleAck(
        m: MessageEntity,
        ack: Ack,
        via: TransportKind,
        peer: PeerIdentity,
        me: DeviceIdentity,
        now: Long,
        toEmit: MutableList<RelayEvent>,
    ) {
        if (ack.status == AckStatus.REJECTED) {
            store.recordHandoff(HandoffEntity(m.id, peer.deviceId, via, RESULT_REJECTED, now))
            markAttemptFailed(m, via, peer, "${peer.displayName} rejected it: ${ack.reason ?: "no reason"}", me, now, toEmit)
            return
        }
        // A FINAL ack only counts if it really comes from the intended recipient (or, for a
        // broadcast, from the neighbour we sent it to). Anything else is treated as a hop.
        val fromIntended = ack.byDeviceId == peer.deviceId &&
            (m.isBroadcast || (peer.deviceId == m.recipientId))
        val isFinal = ack.kind == AckKind.FINAL && fromIntended
        val result = (if (isFinal) AckKind.FINAL else AckKind.HOP) +
            (if (ack.status == AckStatus.DUPLICATE) "_DUPLICATE" else "")
        store.recordHandoff(HandoffEntity(m.id, peer.deviceId, via, result, now))
        rememberSuccess(peer, via, now)

        if (m.deliveryState != DeliveryState.PENDING) return

        when {
            isFinal && m.isBroadcast -> {
                val updated = m.copy(
                    finalAckCount = m.finalAckCount + 1,
                    sendState = SendState.QUEUED,
                    lastTransport = via,
                    lastError = null,
                    deliveredToName = ack.byName,
                    acknowledgedAtUtc = now,
                )
                store.update(updated)
                store.addRouteEvent(event(m.id, RouteEvent.FINAL_ACK, me, peer, via, now))
                if (m.direction == Direction.OUTGOING) toEmit += RelayEvent.Delivered(updated)
            }
            isFinal -> {
                val updated = m.copy(
                    deliveryState = DeliveryState.DELIVERED,
                    sendState = SendState.DONE,
                    acknowledgedAtUtc = now,
                    deliveredAtUtc = ack.receivedAtUtc,
                    deliveredVia = via,
                    deliveredToName = ack.byName,
                    finalAckCount = m.finalAckCount + 1,
                    lastTransport = via,
                    nextAttemptAtUtc = null,
                    lastError = null,
                )
                store.update(updated)
                store.addRouteEvent(event(m.id, RouteEvent.FINAL_ACK, me, peer, via, now))
                if (m.direction == Direction.OUTGOING) {
                    toEmit += RelayEvent.Delivered(updated)
                } else {
                    // We carried it for someone else and the recipient confirmed: pass a receipt back.
                    relayReceipt(m, peer.deviceId, ack.byName, ack.receivedAtUtc, now)?.let { toEmit += RelayEvent.ReceiptAvailable(it) }
                }
            }
            else -> {
                val updated = m.copy(
                    sendState = if (m.isBroadcast) SendState.QUEUED else SendState.HOP_ACKED,
                    relayAckBy = peer.deviceId,
                    relayAckByName = ack.byName,
                    relayAckAtUtc = now,
                    hopAckCount = m.hopAckCount + 1,
                    lastTransport = via,
                    lastError = null,
                )
                store.update(updated)
                store.addRouteEvent(event(m.id, RouteEvent.HOP_ACKED, me, peer, via, now))
                if (m.direction == Direction.OUTGOING) toEmit += RelayEvent.HopAcked(updated, ack.byName)
            }
        }
    }

    private suspend fun markAttemptFailed(
        m: MessageEntity,
        via: TransportKind,
        peer: PeerIdentity?,
        reason: String,
        me: DeviceIdentity,
        now: Long,
        toEmit: MutableList<RelayEvent>,
    ) {
        val short = reason.take(200)
        if (m.deliveryState == DeliveryState.PENDING) {
            store.update(m.copy(sendState = QueuePolicy.restingState(m), lastError = short))
        }
        store.addRouteEvent(event(m.id, RouteEvent.ATTEMPT_FAILED, me, peer, via, now, detail = short))
        toEmit += RelayEvent.AttemptFailed(m.id, via, peer?.displayName, short, now)
    }

    private suspend fun rememberSuccess(peer: PeerIdentity, via: TransportKind, now: Long) {
        val existing = store.getPeer(peer.deviceId)
        store.upsertPeer(
            (existing ?: PeerEntity(peer.deviceId, peer.displayName, now, via)).copy(
                lastSuccessTransport = via,
                lastSeenAtUtc = now,
                lastTransport = via,
            ),
        )
    }

    /** Creates (if new) a receipt for the original sender of a message we relayed. */
    private suspend fun relayReceipt(
        m: MessageEntity,
        recipientId: String,
        recipientName: String,
        receivedAtUtc: Long,
        now: Long,
    ): ReceiptEntity? {
        val r = ReceiptEntity(
            messageId = m.id,
            originalSenderId = m.senderId,
            finalRecipientId = recipientId,
            finalRecipientName = recipientName.take(ProtocolLimits.MAX_NAME_CHARS),
            receivedAtUtc = receivedAtUtc,
            expiresAtUtc = m.expiresAtUtc,
            createdLocally = false,
            storedAtUtc = now,
        )
        return if (store.insertReceiptIfAbsent(r)) r else null
    }

    private fun event(
        messageId: String,
        type: RouteEvent,
        me: DeviceIdentity,
        peer: PeerIdentity? = null,
        via: TransportKind? = null,
        atUtc: Long,
        detail: String? = null,
    ) = RouteHopEntity(
        messageId = messageId,
        event = type,
        nodeId = me.deviceId,
        nodeName = me.displayName,
        peerId = peer?.deviceId,
        peerName = peer?.displayName,
        transport = via,
        atUtc = atUtc,
        detail = detail,
    )

    companion object {
        const val RESULT_REJECTED = "REJECTED"
    }
}
