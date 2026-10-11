package com.relay.app.testutil

import com.relay.app.model.Clock
import com.relay.app.model.DeviceIdentity
import com.relay.app.model.LocationInfo
import com.relay.app.model.MessageDraft
import com.relay.app.model.MessageType
import com.relay.app.model.PeerIdentity
import com.relay.app.model.RelayConfig
import com.relay.app.model.TransportKind
import com.relay.app.protocol.AckStatus
import com.relay.app.protocol.Receipt
import com.relay.app.protocol.WireMessage
import com.relay.app.relay.InMemoryRelayStore
import com.relay.app.relay.QueueProcessor
import com.relay.app.relay.RelayEngine
import com.relay.app.transport.InternetRelay
import com.relay.app.transport.PeerLink
import com.relay.app.transport.Reach
import com.relay.app.transport.SendOutcome
import com.relay.app.transport.UploadOutcome

/** Mutable clock for deterministic tests. */
class FakeClock(var nowMs: Long = 1_760_000_000_000L) : Clock {
    override fun now(): Long = nowMs
    fun advance(ms: Long) {
        nowMs += ms
    }
}

/**
 * One logical phone for tests: the real engine + queue processor over an in-memory store.
 * These are MOCKED devices; passing tests prove the relay logic, not physical radio transfer.
 */
class TestDevice(
    val id: String,
    val name: String = id,
    val clock: FakeClock,
    var config: RelayConfig = RelayConfig(),
) {
    val identity = DeviceIdentity(id, name)
    val peerIdentity = PeerIdentity(id, name)
    val store = InMemoryRelayStore()
    val engine = RelayEngine(store, { identity }, { config }, clock)
    val processor = QueueProcessor(store, engine, { identity }, { config }, clock)

    suspend fun send(
        to: TestDevice?,
        body: String = "hello",
        type: MessageType = MessageType.NORMAL,
        location: LocationInfo? = null,
        recipientId: String? = null,
    ) = engine.createOutgoing(
        MessageDraft(recipientId ?: to?.id ?: "*", to?.name, body, type, location),
    )
}

/**
 * A mocked link from [from] to [to] over [transport] that hands frames straight to the other
 * device's real engine. [failures] makes the next N sends fail; [dropAckAfterStore] simulates
 * "the peer stored it but the ACK was lost" (a timeout after delivery).
 */
class EngineLink(
    private val from: TestDevice,
    private val to: TestDevice,
    override val transport: TransportKind,
    override val openedAtUtc: Long = 0L,
) : PeerLink {
    override val id: String = "${from.id}->${to.id}:${transport.name}"
    override val peer: PeerIdentity = to.peerIdentity
    override val remoteLabel: String = "test:${to.id}"
    var open = true
    var failures = 0
    var dropAckAfterStore = false
    val sentIds = mutableListOf<String>()
    val sentMessages = mutableListOf<WireMessage>()
    override val isOpen: Boolean get() = open

    override suspend fun sendMessage(msg: WireMessage, timeoutMs: Long): SendOutcome {
        if (!open) return SendOutcome.Failed("closed")
        sentIds += msg.id
        sentMessages += msg
        if (failures > 0) {
            failures--
            return SendOutcome.Failed("injected failure")
        }
        val ack = to.engine.onMessage(msg, from.peerIdentity, transport)
        if (dropAckAfterStore) {
            dropAckAfterStore = false
            return SendOutcome.Timeout
        }
        return if (ack.status == AckStatus.REJECTED) SendOutcome.Rejected(ack.reason ?: "rejected") else SendOutcome.Acked(ack)
    }

    override suspend fun sendReceipt(receipt: Receipt): Boolean {
        if (!open) return false
        to.engine.onReceipt(receipt, from.peerIdentity, transport)
        return true
    }

    override fun close(reason: String) {
        open = false
    }
}

/** Mocked internet relay: stores uploads; [deliverTo] simulates the recipient downloading. */
class FakeInternet(var usable: Boolean = true, var reachable: Set<String> = emptySet()) : InternetRelay {
    val uploads = LinkedHashMap<String, WireMessage>()
    var failNext = false
    /** When set, reach() returns this (e.g. Reach.Unknown to simulate a lookup timeout). */
    var reachOverride: Reach? = null
    override fun isUsable(): Boolean = usable
    override suspend fun canReach(recipientId: String): Boolean = usable && recipientId in reachable
    override suspend fun reach(recipientId: String): Reach = reachOverride ?: super.reach(recipientId)
    override suspend fun upload(msg: WireMessage): UploadOutcome {
        if (failNext) {
            failNext = false
            return UploadOutcome.Failed("injected failure")
        }
        if (uploads.containsKey(msg.id)) return UploadOutcome.AlreadyUploaded
        uploads[msg.id] = msg
        return UploadOutcome.Uploaded
    }
}
