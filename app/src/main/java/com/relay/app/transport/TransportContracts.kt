package com.relay.app.transport

import android.content.Context
import com.relay.app.model.Clock
import com.relay.app.model.DeviceIdentity
import com.relay.app.model.DiscoveredPeer
import com.relay.app.model.PeerIdentity
import com.relay.app.model.TransportKind
import com.relay.app.model.TransportStatus
import com.relay.app.protocol.Ack
import com.relay.app.protocol.Receipt
import com.relay.app.protocol.WireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Default time to wait for an ACK after writing a MSG frame. */
const val DEFAULT_ACK_TIMEOUT_MS = 15_000L

/**
 * An established, handshaken, bidirectional connection to ONE peer device over ONE transport.
 * Bluetooth, Wi-Fi Direct and local Wi-Fi all produce [StreamPeerLink]s; the relay lab
 * produces simulated links. The relay engine never cares which.
 */
interface PeerLink {
    /** Unique id of this link instance (transport + address + counter). */
    val id: String
    val transport: TransportKind

    /** Identity from the peer's HELLO. Valid once the link is published in [PeerTransport.links]. */
    val peer: PeerIdentity

    /** Human-readable remote address (Bluetooth MAC, IP:port, ...). */
    val remoteLabel: String
    val openedAtUtc: Long
    val isOpen: Boolean

    /**
     * Writes a MSG frame and waits for the peer's ACK. A successful write alone is never
     * reported as success. On [SendOutcome.Timeout] the peer MAY have stored the message;
     * retrying is safe because receivers deduplicate by message id.
     */
    suspend fun sendMessage(msg: WireMessage, timeoutMs: Long = DEFAULT_ACK_TIMEOUT_MS): SendOutcome

    /** Sends a delivery receipt (fire-and-forget, receivers deduplicate). Returns false if the write failed. */
    suspend fun sendReceipt(receipt: Receipt): Boolean

    /** Closes the link immediately. Safe to call more than once. */
    fun close(reason: String)
}

sealed interface SendOutcome {
    /** The peer replied with an ACK (status STORED or DUPLICATE). Check [Ack.kind] for HOP vs FINAL. */
    data class Acked(val ack: Ack) : SendOutcome

    /** The peer replied REJECTED (expired, hop limit, relay disabled, invalid...). Do not resend to this peer. */
    data class Rejected(val reason: String) : SendOutcome

    /** The write failed or the link dropped before an ACK arrived. */
    data class Failed(val reason: String) : SendOutcome

    /** No ACK within the timeout. */
    data object Timeout : SendOutcome
}

/**
 * Receives everything that arrives from the network. Implemented by
 * [com.relay.app.relay.RelayEngine]. Implementations must NOT perform network I/O on the
 * calling coroutine (they store data and return); forwarding is triggered asynchronously.
 */
interface InboundHandler {
    /** Validate, deduplicate, store and return the ACK to send back for [msg]. */
    suspend fun onMessage(msg: WireMessage, from: PeerIdentity, via: TransportKind): Ack

    suspend fun onReceipt(receipt: Receipt, from: PeerIdentity, via: TransportKind)

    /** An ACK arrived that nobody was waiting for (e.g. after a timeout). It still proves the hop. */
    suspend fun onUnsolicitedAck(ack: Ack, from: PeerIdentity, via: TransportKind)

    /** The internet relay (or another out-of-band path) reports the intended recipient stored [messageId]. */
    suspend fun onRemoteFinalDelivery(
        messageId: String,
        recipientId: String,
        recipientName: String?,
        deliveredAtUtc: Long,
        via: TransportKind,
    )
}

/** Base for every transport, including the internet relay. */
interface Transport {
    val kind: TransportKind
    val status: StateFlow<TransportStatus>

    /** True if the hardware/OS supports this transport at all. */
    fun isSupported(): Boolean

    /** Register receivers / observe radio state. Must NOT start discovery or prompt the user. Idempotent. */
    fun start()

    /** Tear everything down (links, sockets, receivers). Idempotent. */
    fun stop()
}

/** A transport that produces direct device-to-device links (Bluetooth, Wi-Fi Direct, local Wi-Fi). */
interface PeerTransport : Transport {
    /** Live, handshaken links. A link appears here only after HELLO was exchanged. */
    val links: StateFlow<List<PeerLink>>

    /** Devices genuinely found by discovery or pairing. Never invented. */
    val discovered: StateFlow<List<DiscoveredPeer>>
}

/** What the queue processor needs from the internet relay. */
interface InternetRelay {
    /** Configured AND signed in AND the internet is validated as reachable. */
    fun isUsable(): Boolean

    /** True if [recipientId] has registered with the relay service (so an upload can reach it). */
    suspend fun canReach(recipientId: String): Boolean

    /**
     * Like [canReach] but says WHY not: the recipient never registered (wait, no failure) or the
     * lookup itself failed (a real failed attempt, retried with backoff).
     */
    suspend fun reach(recipientId: String): Reach =
        if (canReach(recipientId)) Reach.Reachable else Reach.NotRegistered("the recipient has not registered with the internet relay")

    /**
     * Uploads [msg] (idempotent: document id = message id). [UploadOutcome.Uploaded] means the
     * server confirmed the write; it is NOT proof that the recipient received the message.
     */
    suspend fun upload(msg: WireMessage): UploadOutcome
}

sealed interface Reach {
    data object Reachable : Reach

    /** The recipient's phone has never registered (opened RELAY with internet). Not a failure. */
    data class NotRegistered(val reason: String) : Reach

    /** Could not check (timeout, network error). Counts as a failed attempt. */
    data class Unknown(val reason: String) : Reach
}

sealed interface UploadOutcome {
    data object Uploaded : UploadOutcome
    data object AlreadyUploaded : UploadOutcome
    data class Unreachable(val reason: String) : UploadOutcome
    data class Failed(val reason: String) : UploadOutcome
}

/** Dependencies shared by every peer transport, bundled to keep constructors uniform. */
data class TransportDeps(
    val context: Context,
    val identity: () -> DeviceIdentity,
    val inbound: InboundHandler,
    val log: TransferLog,
    val scope: CoroutineScope,
    val clock: Clock,
)
