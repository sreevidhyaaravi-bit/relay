package com.relay.app.relay

import com.relay.app.data.db.MessageEntity
import com.relay.app.model.Clock
import com.relay.app.model.DeliveryState
import com.relay.app.model.DeviceIdentity
import com.relay.app.model.MessageType
import com.relay.app.model.RelayConfig
import com.relay.app.model.TransportKind
import com.relay.app.protocol.AckKind
import com.relay.app.protocol.Receipt
import com.relay.app.transport.InternetRelay
import com.relay.app.transport.PeerLink
import com.relay.app.transport.Reach
import com.relay.app.transport.SendOutcome
import com.relay.app.transport.TransferLog
import com.relay.app.transport.UploadOutcome
import kotlinx.coroutines.CancellationException

/**
 * One pass over the persistent queue: for each pending message (SOS first, FIFO within a
 * priority), pick routes with [RoutePolicy], attempt them over the given live links / internet
 * relay, and report every outcome to [RelayEngine]. Callers serialise passes (TransportManager
 * uses a Mutex; the simulator runs one node at a time).
 */
class QueueProcessor(
    private val store: RelayStore,
    private val engine: RelayEngine,
    private val identity: () -> DeviceIdentity,
    private val config: () -> RelayConfig,
    private val clock: Clock,
    private val log: TransferLog? = null,
) {

    /** Per-message tally for one pass. */
    private class Tally {
        var attempts = 0
        var failures = 0
        var finals = 0
        var hops = 0
        var uploads = 0
        var lastFailure: String? = null
        /** Why nothing could be attempted (shown to the user; not counted as a failure). */
        var noRouteReason: String? = null
        val successes get() = finals + hops + uploads
    }

    /**
     * @param links currently open, handshaken links (snapshot).
     * @param internet the internet relay, or null when it is not configured/usable.
     */
    suspend fun processOnce(links: List<PeerLink>, internet: InternetRelay?, trigger: ProcessTrigger): ProcessReport {
        val startedAt = clock.now()
        val expired = engine.expireDue()
        val me = identity()
        val cfg = config()
        val openLinks = links.filter { it.isOpen }
        val internetUsable = internet != null && runCatching { internet.isUsable() }.getOrDefault(false)
        val reachCache = HashMap<String, Reach>()

        var report = ProcessReport(trigger = trigger, startedAtUtc = startedAt, expired = expired.size)
        for (queued in store.pendingForwarding()) {
            report = report.copy(examined = report.examined + 1)
            val now = clock.now()
            if (QueuePolicy.isExpired(queued, now)) continue // expired mid-pass; the next pass marks it
            if (!QueuePolicy.isDue(queued, now, trigger)) continue

            val tally = Tally()
            val input = routeInput(queued, me, openLinks, cfg)
            val direct = RoutePolicy.directCandidates(input)

            if (queued.isBroadcast) {
                for (candidate in direct) sendOverLink(queued.id, candidate.link, me, tally)
            } else {
                for (candidate in direct) {
                    if (!stillPending(queued.id)) break
                    val outcome = sendOverLink(queued.id, candidate.link, me, tally)
                    if (outcome is SendOutcome.Acked && outcome.ack.kind == AckKind.FINAL) break
                }
                if (tally.finals == 0 && stillPending(queued.id)) {
                    trySecondary(queued.id, me, cfg, openLinks, internet, internetUsable, reachCache, tally)
                }
            }

            when {
                tally.attempts == 0 -> {
                    engine.onNoRoute(queued.id, tally.noRouteReason)
                    report = report.copy(noRoute = report.noRoute + 1)
                }
                tally.successes == 0 -> engine.onAttemptRoundFailed(queued.id, tally.lastFailure ?: "all routes failed")
            }
            report = report.copy(
                attempts = report.attempts + tally.attempts,
                deliveredFinal = report.deliveredFinal + tally.finals,
                hopAcked = report.hopAcked + tally.hops,
                uploaded = report.uploaded + tally.uploads,
                failedAttempts = report.failedAttempts + tally.failures,
            )
        }
        report = report.copy(nextRetryAtUtc = store.nextRetryAt())
        if (report.attempts > 0 || report.expired > 0) {
            log?.info(
                null,
                "Queue pass (${trigger.name}): ${report.attempts} attempts, ${report.deliveredFinal} delivered, " +
                    "${report.hopAcked} relay acks, ${report.uploaded} uploads, ${report.failedAttempts} failed, " +
                    "${report.noRoute} waiting for a route",
            )
        }
        return report
    }

    /** Sends shareable receipts (not expired, not addressed to us) to each link. Returns the number sent. */
    suspend fun shareReceipts(links: List<PeerLink>): Int {
        val me = identity()
        val receipts = store.receiptsToShare(clock.now(), me.deviceId, MAX_RECEIPTS_PER_LINK)
        if (receipts.isEmpty()) return 0
        var sent = 0
        for (link in links.filter { it.isOpen }) {
            for (r in receipts) {
                val ok = link.sendReceipt(
                    Receipt(r.messageId, r.originalSenderId, r.finalRecipientId, r.finalRecipientName, r.receivedAtUtc, r.expiresAtUtc),
                )
                if (ok) sent++ else break
            }
        }
        if (sent > 0) log?.info(null, "Shared $sent delivery receipt(s) with ${links.size} link(s)")
        return sent
    }

    // ------------------------------------------------------------------ internals

    private suspend fun trySecondary(
        messageId: String,
        me: DeviceIdentity,
        cfg: RelayConfig,
        openLinks: List<PeerLink>,
        internet: InternetRelay?,
        internetUsable: Boolean,
        reachCache: MutableMap<String, Reach>,
        tally: Tally,
    ) {
        val current = store.get(messageId) ?: return
        val reach: Reach? = if (internetUsable && internet != null && current.uploadedAtUtc == null) {
            reachCache.getOrPut(current.recipientId) {
                try {
                    internet.reach(current.recipientId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Reach.Unknown(e.message ?: e.javaClass.simpleName)
                }
            }
        } else {
            null
        }
        when (reach) {
            is Reach.NotRegistered -> tally.noRouteReason = "Internet relay is online, but ${reach.reason}"
            is Reach.Unknown -> {
                // A real network attempt that failed: record it so the retry timer kicks in.
                tally.attempts++
                tally.failures++
                tally.lastFailure = "Internet relay: ${reach.reason}"
                engine.onUploadOutcome(messageId, UploadOutcome.Failed(reach.reason))
                log?.warn(TransportKind.INTERNET_RELAY, "MSG ${TransferLog.shortId(messageId)}: ${reach.reason}")
            }
            else -> Unit
        }
        val canReach = reach is Reach.Reachable
        val candidates = RoutePolicy.secondaryCandidates(routeInput(current, me, openLinks, cfg), canReach)
        val isSos = current.messageType == MessageType.SOS
        // Earlier hand-offs count toward the copy limit for non-SOS messages.
        var accepted = current.hopAckCount + (if (current.uploadedAtUtc != null) 1 else 0)
        for (candidate in candidates) {
            if (!isSos && accepted >= cfg.relayCopies) break
            if (!stillPending(messageId)) break
            when (candidate) {
                RouteCandidate.Internet -> if (internet != null && upload(messageId, me, internet, tally)) accepted++
                is RouteCandidate.Relay -> {
                    val outcome = sendOverLink(messageId, candidate.link, me, tally)
                    if (outcome is SendOutcome.Acked) accepted++
                }
                is RouteCandidate.Direct -> Unit // never produced by secondaryCandidates
            }
        }
    }

    private suspend fun sendOverLink(messageId: String, link: PeerLink, me: DeviceIdentity, tally: Tally): SendOutcome? {
        if (!link.isOpen) return null
        val peer = link.peer
        engine.onAttemptStarted(messageId, link.transport, peer)
        val fresh = store.get(messageId) ?: return null
        val wire = MessageMapper.toWireForSend(fresh, me, link.transport, clock.now())
        tally.attempts++
        val outcome = try {
            link.sendMessage(wire)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SendOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        engine.onSendOutcome(messageId, link.transport, peer, outcome)
        val shortId = TransferLog.shortId(messageId)
        when (outcome) {
            is SendOutcome.Acked -> {
                val final = outcome.ack.kind == AckKind.FINAL && outcome.ack.byDeviceId == peer.deviceId &&
                    (fresh.isBroadcast || fresh.recipientId == peer.deviceId)
                if (final) tally.finals++ else tally.hops++
                log?.info(
                    link.transport,
                    "MSG $shortId acknowledged by ${peer.displayName} (${if (final) "final recipient" else "relay hop"}, ${outcome.ack.status})",
                )
            }
            is SendOutcome.Rejected -> {
                tally.failures++
                tally.lastFailure = "${peer.displayName} rejected it: ${outcome.reason}"
                log?.warn(link.transport, "MSG $shortId rejected by ${peer.displayName}: ${outcome.reason}")
            }
            is SendOutcome.Failed -> {
                tally.failures++
                tally.lastFailure = "${link.transport.label} to ${peer.displayName}: ${outcome.reason}"
                log?.warn(link.transport, "MSG $shortId to ${peer.displayName} failed: ${outcome.reason}")
            }
            SendOutcome.Timeout -> {
                tally.failures++
                tally.lastFailure = "${link.transport.label} to ${peer.displayName}: no acknowledgement in time"
                log?.warn(link.transport, "MSG $shortId to ${peer.displayName}: no ACK before timeout (will retry; receiver deduplicates)")
            }
        }
        return outcome
    }

    private suspend fun upload(messageId: String, me: DeviceIdentity, internet: InternetRelay, tally: Tally): Boolean {
        engine.onAttemptStarted(messageId, TransportKind.INTERNET_RELAY, null)
        val fresh = store.get(messageId) ?: return false
        val wire = MessageMapper.toWireForSend(fresh, me, TransportKind.INTERNET_RELAY, clock.now())
        tally.attempts++
        val outcome = try {
            internet.upload(wire)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UploadOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
        engine.onUploadOutcome(messageId, outcome)
        val shortId = TransferLog.shortId(messageId)
        return when (outcome) {
            UploadOutcome.Uploaded, UploadOutcome.AlreadyUploaded -> {
                tally.uploads++
                log?.info(TransportKind.INTERNET_RELAY, "MSG $shortId uploaded (not yet confirmed by the recipient)")
                true
            }
            is UploadOutcome.Unreachable -> {
                tally.failures++
                tally.lastFailure = "Internet relay: ${outcome.reason}"
                false
            }
            is UploadOutcome.Failed -> {
                tally.failures++
                tally.lastFailure = "Internet relay: ${outcome.reason}"
                log?.warn(TransportKind.INTERNET_RELAY, "MSG $shortId upload failed: ${outcome.reason}")
                false
            }
        }
    }

    private suspend fun routeInput(m: MessageEntity, me: DeviceIdentity, links: List<PeerLink>, cfg: RelayConfig): RouteInput {
        val handoffs = store.handoffsFor(m.id)
        val peerIds = links.mapNotNull { runCatching { it.peer.deviceId }.getOrNull() }.distinct()
        val sticky = peerIds.mapNotNull { id -> store.getPeer(id)?.lastSuccessTransport?.let { id to it } }.toMap()
        return RouteInput(
            message = m,
            selfId = me.deviceId,
            links = links,
            handedPeers = handoffs.map { it.peerId }.toSet(),
            rejectedPeers = handoffs.filter { it.result == RelayEngine.RESULT_REJECTED }.map { it.peerId }.toSet(),
            routeNodeIds = MessageMapper.decodeRoute(m.routeJson).map { it.nodeId }.toSet(),
            stickyTransport = sticky,
            preferredOrder = cfg.preferredOrder,
        )
    }

    private suspend fun stillPending(messageId: String): Boolean =
        store.get(messageId)?.deliveryState == DeliveryState.PENDING

    private companion object {
        const val MAX_RECEIPTS_PER_LINK = 50
    }
}
