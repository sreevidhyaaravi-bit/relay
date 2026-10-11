package com.relay.app.relay

import com.relay.app.data.db.MessageEntity
import com.relay.app.model.TransportKind
import com.relay.app.transport.PeerLink

/** One way a message could leave this device right now. */
sealed interface RouteCandidate {
    val transport: TransportKind

    /** A live link to the intended recipient itself (or, for broadcast, to one neighbour). */
    data class Direct(val link: PeerLink) : RouteCandidate {
        override val transport: TransportKind get() = link.transport
    }

    /** A live link to another RELAY device that can carry the message onward. */
    data class Relay(val link: PeerLink) : RouteCandidate {
        override val transport: TransportKind get() = link.transport
    }

    /** The configured internet relay, which can reach the recipient. */
    data object Internet : RouteCandidate {
        override val transport: TransportKind = TransportKind.INTERNET_RELAY
    }
}

/** Everything the policy needs to decide, gathered by the queue processor. */
data class RouteInput(
    val message: MessageEntity,
    val selfId: String,
    /** Open, handshaken links (snapshot). */
    val links: List<PeerLink>,
    /** Peers that already have this message (acked HOP/FINAL/DUPLICATE) or rejected it. */
    val handedPeers: Set<String>,
    /** Peers that rejected this message; never retried, not even directly. */
    val rejectedPeers: Set<String>,
    /** Devices already in the carried route (they had it before us). */
    val routeNodeIds: Set<String>,
    /** Peer id -> transport of the last successful send to that peer (sticky choice). */
    val stickyTransport: Map<String, TransportKind>,
    val preferredOrder: List<TransportKind>,
)

/**
 * Documented route selection policy (see docs/ARCHITECTURE.md section 5):
 *
 * 1. Direct links to the intended recipient come first. With several transports to the same
 *    peer, the one that last succeeded wins (avoids flip-flopping), then the preferred order
 *    (default Bluetooth, Wi-Fi Direct, local Wi-Fi), then the oldest link.
 * 2. Broadcast: one direct attempt per neighbour that does not have it yet. Never relayed.
 * 3. Secondary routes when no direct attempt succeeded: internet relay first (it reaches the
 *    recipient's inbox), then relay peers that are not the recipient, not the original sender,
 *    not already in the route and not already handed the message, and only while hops remain.
 *
 * The policy only ranks candidates; it never claims a route works. Only an ACK proves that.
 */
object RoutePolicy {

    fun directCandidates(input: RouteInput): List<RouteCandidate.Direct> {
        val m = input.message
        val open = usableLinks(input)
        if (m.hopCount + 1 > m.maxHops) return emptyList()
        if (m.isBroadcast) {
            return open
                .filter {
                    val id = it.peer.deviceId
                    // Never back to the sender or to phones that already passed it along.
                    id != m.senderId && id !in input.handedPeers && id !in input.routeNodeIds
                }
                .groupBy { it.peer.deviceId }
                .map { (peerId, links) -> bestLink(links, peerId, input) }
                .sortedBy { it.peer.deviceId }
                .map { RouteCandidate.Direct(it) }
        }
        if (m.recipientId in input.rejectedPeers) return emptyList()
        return open
            .filter { it.peer.deviceId == m.recipientId }
            .sortedWith(linkOrder(m.recipientId, input))
            .map { RouteCandidate.Direct(it) }
    }

    fun secondaryCandidates(input: RouteInput, internetCanReach: Boolean): List<RouteCandidate> {
        val m = input.message
        if (m.isBroadcast) return emptyList()
        val result = mutableListOf<RouteCandidate>()
        if (internetCanReach && m.uploadedAtUtc == null) result += RouteCandidate.Internet
        // A relay receives hopCount + 1 and must still be allowed to transmit once more.
        if (m.hopCount + 1 < m.maxHops) {
            usableLinks(input)
                .filter {
                    val id = it.peer.deviceId
                    id != m.recipientId &&
                        id != m.senderId &&
                        id !in input.routeNodeIds &&
                        id !in input.handedPeers
                }
                .groupBy { it.peer.deviceId }
                .map { (peerId, links) -> bestLink(links, peerId, input) }
                .sortedBy { it.peer.deviceId }
                .forEach { result += RouteCandidate.Relay(it) }
        }
        return result
    }

    /** Convenience for tests and logs: direct first, then secondary. */
    fun plan(input: RouteInput, internetCanReach: Boolean): List<RouteCandidate> =
        directCandidates(input) + secondaryCandidates(input, internetCanReach)

    private fun usableLinks(input: RouteInput): List<PeerLink> =
        input.links.filter { it.isOpen && it.peer.deviceId != input.selfId }

    private fun bestLink(links: List<PeerLink>, peerId: String, input: RouteInput): PeerLink =
        links.sortedWith(linkOrder(peerId, input)).first()

    private fun linkOrder(peerId: String, input: RouteInput): Comparator<PeerLink> {
        val sticky = input.stickyTransport[peerId]
        return compareBy<PeerLink> { if (sticky != null && it.transport == sticky) 0 else 1 }
            .thenBy { input.preferredOrder.indexOf(it.transport).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenBy { it.openedAtUtc }
    }
}
