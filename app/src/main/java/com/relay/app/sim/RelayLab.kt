package com.relay.app.sim

import com.relay.app.data.db.MessageEntity
import com.relay.app.data.db.RouteHopEntity
import com.relay.app.model.BROADCAST_ID
import com.relay.app.model.Clock
import com.relay.app.model.DeviceIdentity
import com.relay.app.model.Direction
import com.relay.app.model.LocationInfo
import com.relay.app.model.LocationSource
import com.relay.app.model.MessageDraft
import com.relay.app.model.MessageType
import com.relay.app.model.PeerIdentity
import com.relay.app.model.RelayConfig
import com.relay.app.model.TransportKind
import com.relay.app.protocol.AckKind
import com.relay.app.protocol.AckStatus
import com.relay.app.protocol.Receipt
import com.relay.app.protocol.WireMessage
import com.relay.app.relay.InMemoryRelayStore
import com.relay.app.relay.ProcessTrigger
import com.relay.app.relay.QueueProcessor
import com.relay.app.relay.RelayEngine
import com.relay.app.transport.InternetRelay
import com.relay.app.transport.PeerLink
import com.relay.app.transport.SendOutcome
import com.relay.app.transport.UploadOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SIMULATED relay network of logical devices A, B, C and D.
 *
 * Each node runs the REAL [RelayEngine] and [QueueProcessor] over its own [InMemoryRelayStore]
 * with a shared virtual clock. Links between nodes are simulated in memory, as is the internet
 * relay. Nothing here touches the real database, real radios or real messages, and every view
 * of it is labelled SIMULATED. It demonstrates the logic (priority, expiry, hop limits,
 * deduplication, mixed-medium routes, receipts); it is not proof of physical transfer.
 */
class RelayLab {

    /** Virtual time so expiry and backoff can be demonstrated in seconds. */
    class VirtualClock(start: Long) : Clock {
        @Volatile var nowMs: Long = start
        override fun now(): Long = nowMs
        fun advance(ms: Long) {
            nowMs += ms
        }
    }

    inner class SimNode(val label: String) {
        val identity = DeviceIdentity("SIM-$label", "Sim $label")
        val store = InMemoryRelayStore()
        val engine = RelayEngine(store, { identity }, { config }, clock)
        val processor = QueueProcessor(store, engine, { identity }, { config }, clock, log = null)
        var internetOnline = false
        var failNextSend = false
    }

    data class NodeView(
        val label: String,
        val deviceId: String,
        val name: String,
        val internetOnline: Boolean,
        val failNextSend: Boolean,
        val outgoing: List<MessageEntity>,
        val inbox: List<MessageEntity>,
        val carrying: List<MessageEntity>,
    )

    data class LabState(
        val nowUtc: Long,
        val maxHops: Int,
        val nodes: List<NodeView>,
        /** "A-B" -> media currently linking those two nodes. */
        val links: Map<String, Set<TransportKind>>,
        val log: List<String>,
        val cloudMessages: Int,
    )

    enum class Scenario(val title: String, val description: String) {
        MIXED_MEDIUM("Mixed-medium SOS", "A -> B by Bluetooth, B -> C by Wi-Fi Direct, C -> D by internet relay; receipt travels back."),
        PRIORITY("SOS priority", "Two normal messages and one SOS are queued while offline; the SOS goes first when the link appears."),
        EXPIRY("Expiry", "No route for 25 hours: the normal message expires (kept, not deleted); the SOS (72 h) is still waiting."),
        HOP_LIMIT("Hop limit", "Max 2 hops: A-B-C-D would need 3, so B keeps the message instead of passing it to C."),
        DUPLICATES("Duplicate prevention", "An SOS spreads over two paths and reaches D twice; D stores it once."),
        FALLBACK("Transport fallback", "A-B has Bluetooth and Wi-Fi Direct; the Bluetooth send fails, Wi-Fi Direct delivers."),
        SOS_SPREAD("SOS to everyone spreads", "A sends an SOS to everyone. Only B is in range of A, but the SOS hops B -> C -> D and reaches all."),
    }

    val pairs: List<Pair<String, String>> = listOf("A" to "B", "B" to "C", "C" to "D", "A" to "C", "B" to "D", "A" to "D")

    private var clock = VirtualClock(System.currentTimeMillis())
    private var config = RelayConfig(maxHops = 6)
    private var nodes: List<SimNode> = emptyList()
    private val links = HashMap<String, MutableSet<TransportKind>>()
    private val cloud = LinkedHashMap<String, CloudEntry>()
    private val log = ArrayList<String>()
    private val mutex = Mutex()

    private class CloudEntry(val wire: WireMessage, val uploaderId: String, var deliveredAtUtc: Long? = null, var uploaderNotified: Boolean = false)

    private val _state = MutableStateFlow(LabState(clock.now(), config.maxHops, emptyList(), emptyMap(), emptyList(), 0))
    val state: StateFlow<LabState> = _state

    init {
        resetInternal()
    }

    // ------------------------------------------------------------------ public controls

    suspend fun reset() = mutex.withLock {
        resetInternal()
        publish()
    }

    suspend fun setLink(a: String, b: String, medium: TransportKind, on: Boolean) = mutex.withLock {
        val set = links.getOrPut(key(a, b)) { mutableSetOf() }
        if (on) set += medium else set -= medium
        note("Link ${key(a, b)} ${medium.label} ${if (on) "up" else "down"}")
        publish()
    }

    suspend fun setInternet(label: String, online: Boolean) = mutex.withLock {
        node(label).internetOnline = online
        note("Sim $label internet ${if (online) "online" else "offline"}")
        publish()
    }

    suspend fun setFailNext(label: String, fail: Boolean) = mutex.withLock {
        node(label).failNextSend = fail
        publish()
    }

    /** Creates a message on node [from] exactly like the real app would (saved first, then queued). */
    suspend fun send(from: String, to: String?, body: String, type: MessageType, location: LocationInfo? = null): String? = mutex.withLock {
        val sender = node(from)
        val recipientId = if (to == null) BROADCAST_ID else node(to).identity.deviceId
        val recipientName = if (to == null) "Everyone nearby" else node(to).identity.displayName
        val created = try {
            sender.engine.createOutgoing(MessageDraft(recipientId, recipientName, body, type, location))
        } catch (e: IllegalArgumentException) {
            note("Sim $from could not create message: ${e.message}")
            publish()
            return@withLock null
        }
        note("Sim $from created ${type.symbol} '${body.take(24)}' for $recipientName (queued)")
        publish()
        created.id
    }

    /** One simulated minute: every node runs one real queue pass over its simulated links. */
    suspend fun step(): Int = mutex.withLock { stepInternal() }

    /** Steps until nothing more happens (or [maxSteps]). Returns the number of steps run. */
    suspend fun runUntilIdle(maxSteps: Int = 15): Int = mutex.withLock {
        var steps = 0
        while (steps < maxSteps) {
            steps++
            if (stepInternal() == 0) break
        }
        steps
    }

    suspend fun advance(hours: Int) = mutex.withLock {
        clock.advance(hours * RelayConfig.HOUR_MS)
        note("Clock advanced by $hours h")
        nodes.forEach { it.engine.expireDue() }
        publish()
    }

    /** The carried route of [messageId] as recorded by the node holding the latest copy, labelled SIMULATED. */
    suspend fun routeText(messageId: String): String = mutex.withLock {
        val holder = nodes.mapNotNull { n -> n.store.allMessages().firstOrNull { it.id == messageId }?.let { n to it } }
            .maxByOrNull { (_, m) -> m.hopCount }
            ?: return@withLock "SIMULATED: unknown message"
        val (node, m) = holder
        val hops = com.relay.app.relay.MessageMapper.decodeRoute(m.routeJson)
        val path = if (hops.isEmpty()) node.identity.displayName
        else hops.joinToString(" → ") { "${it.nodeName} → ${TransportKind.fromName(it.transport)?.label}" } + " → " + node.identity.displayName
        "SIMULATED: $path"
    }

    suspend fun routeEvents(label: String, messageId: String): List<RouteHopEntity> = mutex.withLock {
        node(label).store.routeFor(messageId)
    }

    suspend fun load(scenario: Scenario) {
        mutex.withLock {
            resetInternal()
            note("Scenario: ${scenario.title}")
        }
        when (scenario) {
            Scenario.MIXED_MEDIUM -> {
                setLink("A", "B", TransportKind.BLUETOOTH, true)
                setLink("B", "C", TransportKind.WIFI_DIRECT, true)
                setInternet("C", true)
                setInternet("D", true)
                send(
                    "A", "D", "🆘 RELAY TEST 7319 — Please confirm receipt.", MessageType.SOS,
                    LocationInfo(null, null, null, null, "Sim location: Building 3, east exit", LocationSource.MANUAL),
                )
            }
            Scenario.PRIORITY -> {
                send("A", "B", "Normal message 1", MessageType.NORMAL)
                send("A", "B", "Normal message 2", MessageType.NORMAL)
                send("A", "B", "🆘 Trapped in stairwell B", MessageType.SOS)
                mutex.withLock { note("Now turn on the A-B link and press Step: watch the SOS go first.") ; publish() }
            }
            Scenario.EXPIRY -> {
                send("A", "D", "Meet at the north gate", MessageType.NORMAL)
                send("A", "D", "🆘 Need water and first aid", MessageType.SOS)
                advance(25)
            }
            Scenario.HOP_LIMIT -> {
                mutex.withLock { config = config.copy(maxHops = 2); note("Max hops set to 2") }
                setLink("A", "B", TransportKind.BLUETOOTH, true)
                setLink("B", "C", TransportKind.WIFI_DIRECT, true)
                setLink("C", "D", TransportKind.BLUETOOTH, true)
                send("A", "D", "🆘 Hop limit test", MessageType.SOS)
            }
            Scenario.DUPLICATES -> {
                setLink("A", "B", TransportKind.BLUETOOTH, true)
                setLink("A", "C", TransportKind.WIFI_DIRECT, true)
                setLink("B", "D", TransportKind.WIFI_DIRECT, true)
                setLink("C", "D", TransportKind.BLUETOOTH, true)
                send("A", "D", "🆘 Two paths to D", MessageType.SOS)
            }
            Scenario.SOS_SPREAD -> {
                setLink("A", "B", TransportKind.BLUETOOTH, true)
                setLink("B", "C", TransportKind.WIFI_DIRECT, true)
                setLink("C", "D", TransportKind.BLUETOOTH, true)
                send("A", null, "🆘 Building collapse at block 4, need rescue", MessageType.SOS)
            }
            Scenario.FALLBACK -> {
                setLink("A", "B", TransportKind.BLUETOOTH, true)
                setLink("A", "B", TransportKind.WIFI_DIRECT, true)
                setFailNext("A", true)
                send("A", "B", "Fallback test", MessageType.URGENT)
                mutex.withLock { note("A's next send fails (simulated Bluetooth drop). Press Step.") ; publish() }
            }
        }
    }

    // ------------------------------------------------------------------ internals (call with the mutex held)

    private fun resetInternal() {
        clock = VirtualClock(System.currentTimeMillis())
        config = RelayConfig(maxHops = 6)
        nodes = listOf("A", "B", "C", "D").map { SimNode(it) }
        links.clear()
        cloud.clear()
        log.clear()
        note("SIMULATED network reset: 4 logical devices, no links")
    }

    /** Returns how many things happened (attempts + cloud deliveries), 0 when idle. */
    private suspend fun stepInternal(): Int {
        var activity = 0
        for (n in nodes) {
            val nodeLinks = linksFor(n)
            if (nodeLinks.isNotEmpty()) activity += n.processor.shareReceipts(nodeLinks).coerceAtMost(1)
            val report = n.processor.processOnce(nodeLinks, if (n.internetOnline) SimInternet(n) else null, ProcessTrigger.RETRY_TIMER)
            activity += report.attempts
        }
        activity += deliverCloud()
        clock.advance(STEP_MS)
        publish()
        return activity
    }

    private suspend fun deliverCloud(): Int {
        var activity = 0
        for ((id, entry) in cloud) {
            if (entry.deliveredAtUtc == null) {
                val recipient = nodes.firstOrNull { it.identity.deviceId == entry.wire.recipientId } ?: continue
                if (!recipient.internetOnline) continue
                val uploader = nodes.first { it.identity.deviceId == entry.uploaderId }
                val ack = recipient.engine.onMessage(entry.wire, PeerIdentity(uploader.identity.deviceId, uploader.identity.displayName), TransportKind.INTERNET_RELAY)
                if (ack.status != AckStatus.REJECTED && ack.kind == AckKind.FINAL) {
                    entry.deliveredAtUtc = ack.receivedAtUtc
                    note("Internet relay: ${recipient.identity.displayName} downloaded and confirmed ${id.take(8)}")
                    activity++
                }
            }
            val delivered = entry.deliveredAtUtc
            if (delivered != null && !entry.uploaderNotified) {
                val uploader = nodes.first { it.identity.deviceId == entry.uploaderId }
                if (uploader.internetOnline) {
                    val recipientName = nodes.firstOrNull { it.identity.deviceId == entry.wire.recipientId }?.identity?.displayName
                    uploader.engine.onRemoteFinalDelivery(id, entry.wire.recipientId, recipientName, delivered, TransportKind.INTERNET_RELAY)
                    entry.uploaderNotified = true
                    activity++
                }
            }
        }
        return activity
    }

    private fun linksFor(n: SimNode): List<PeerLink> = links.flatMap { (k, media) ->
        val (a, b) = k.split("-")
        val other = when (n.label) {
            a -> b
            b -> a
            else -> return@flatMap emptyList()
        }
        media.map { medium -> SimPeerLink(n, node(other), medium, k) }
    }

    private fun node(label: String): SimNode = nodes.first { it.label == label }

    private fun key(a: String, b: String) = listOf(a, b).sorted().joinToString("-")

    private fun note(text: String) {
        val minutes = (clock.now() / 60_000) % 1440
        log.add(0, "%02d:%02d SIM  %s".format(minutes / 60, minutes % 60, text))
        while (log.size > 200) log.removeAt(log.lastIndex)
    }

    private fun publish() {
        _state.value = LabState(
            nowUtc = clock.now(),
            maxHops = config.maxHops,
            nodes = nodes.map { n ->
                val all = n.store.allMessages()
                NodeView(
                    label = n.label,
                    deviceId = n.identity.deviceId,
                    name = n.identity.displayName,
                    internetOnline = n.internetOnline,
                    failNextSend = n.failNextSend,
                    outgoing = all.filter { it.direction == Direction.OUTGOING }.sortedByDescending { it.createdAtUtc },
                    inbox = all.filter { it.direction == Direction.INCOMING }.sortedByDescending { it.receivedAtUtc },
                    carrying = all.filter { it.direction == Direction.RELAY },
                )
            },
            links = links.mapValues { it.value.toSet() },
            log = log.toList(),
            cloudMessages = cloud.size,
        )
    }

    /** A simulated link. Delivers straight into the other node's real engine. */
    private inner class SimPeerLink(
        private val from: SimNode,
        private val to: SimNode,
        override val transport: TransportKind,
        private val linkKey: String,
    ) : PeerLink {
        override val id: String = "SIM-${from.label}-${to.label}-${transport.name}"
        override val peer: PeerIdentity = PeerIdentity(to.identity.deviceId, to.identity.displayName)
        override val remoteLabel: String = "sim:${to.label}"
        override val openedAtUtc: Long = 0L
        override val isOpen: Boolean get() = links[linkKey]?.contains(transport) == true

        override suspend fun sendMessage(msg: WireMessage, timeoutMs: Long): SendOutcome {
            if (!isOpen) return SendOutcome.Failed("simulated link down")
            if (from.failNextSend) {
                from.failNextSend = false
                note("${from.identity.displayName} → ${to.identity.displayName} over ${transport.label}: simulated failure")
                return SendOutcome.Failed("simulated radio failure")
            }
            val ack = to.engine.onMessage(msg, PeerIdentity(from.identity.deviceId, from.identity.displayName), transport)
            val what = when {
                ack.status == AckStatus.REJECTED -> "REJECTED (${ack.reason})"
                ack.kind == AckKind.FINAL -> "final ACK ${ack.status}"
                else -> "relay ACK ${ack.status}"
            }
            note("${from.identity.displayName} → ${to.identity.displayName} over ${transport.label}: ${msg.type} ${msg.id.take(8)} → $what")
            return if (ack.status == AckStatus.REJECTED) SendOutcome.Rejected(ack.reason ?: "rejected") else SendOutcome.Acked(ack)
        }

        override suspend fun sendReceipt(receipt: Receipt): Boolean {
            if (!isOpen) return false
            to.engine.onReceipt(receipt, PeerIdentity(from.identity.deviceId, from.identity.displayName), transport)
            return true
        }

        override fun close(reason: String) = Unit
    }

    /** The simulated cloud as seen by one node. */
    private inner class SimInternet(private val node: SimNode) : InternetRelay {
        override fun isUsable(): Boolean = node.internetOnline
        override suspend fun canReach(recipientId: String): Boolean = nodes.any { it.identity.deviceId == recipientId }
        override suspend fun upload(msg: WireMessage): UploadOutcome {
            if (cloud.containsKey(msg.id)) return UploadOutcome.AlreadyUploaded
            cloud[msg.id] = CloudEntry(msg, node.identity.deviceId)
            note("${node.identity.displayName} uploaded ${msg.id.take(8)} to the simulated internet relay")
            return UploadOutcome.Uploaded
        }
    }

    private companion object {
        const val STEP_MS = 60_000L
    }
}
