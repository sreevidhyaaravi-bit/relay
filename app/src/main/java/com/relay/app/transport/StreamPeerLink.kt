package com.relay.app.transport

import com.relay.app.model.Clock
import com.relay.app.model.DeviceIdentity
import com.relay.app.model.PeerIdentity
import com.relay.app.model.TransportKind
import com.relay.app.protocol.Ack
import com.relay.app.protocol.AckKind
import com.relay.app.protocol.AckStatus
import com.relay.app.protocol.Frame
import com.relay.app.protocol.FrameType
import com.relay.app.protocol.Hello
import com.relay.app.protocol.LineFramer
import com.relay.app.protocol.ProtocolCodec
import com.relay.app.protocol.Receipt
import com.relay.app.protocol.WireMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A [PeerLink] over any pair of blocking byte streams: a Bluetooth RFCOMM socket, a Wi-Fi Direct
 * TCP socket, or a local Wi-Fi TCP socket. All framing, handshake, acknowledgement, keepalive
 * and cleanup logic lives here so each transport only has to open a socket.
 *
 * Usage by a transport:
 * ```
 * val link = StreamPeerLink(kind, remoteLabel, socket.inputStream, socket.outputStream,
 *     closeUnderlying = { socket.close() }, deps.identity, deps.inbound, deps.log, deps.clock,
 *     deps.scope, onClosed = { l, reason -> removeLink(l) })
 * val peer = link.handshake()   // throws IOException on failure (link is closed for you)
 * addLink(link)                 // publish in PeerTransport.links
 * ```
 * All blocking I/O runs on [Dispatchers.IO]; nothing here touches the main thread.
 */
class StreamPeerLink(
    override val transport: TransportKind,
    override val remoteLabel: String,
    private val input: InputStream,
    private val output: OutputStream,
    private val closeUnderlying: () -> Unit,
    private val localIdentity: () -> DeviceIdentity,
    private val inbound: InboundHandler,
    private val log: TransferLog,
    private val clock: Clock,
    parentScope: CoroutineScope,
    private val config: Config = Config(),
    private val onClosed: (StreamPeerLink, String) -> Unit = { _, _ -> },
) : PeerLink {

    data class Config(
        val handshakeTimeoutMs: Long = 10_000L,
        val writeTimeoutMs: Long = 10_000L,
        val pingIntervalMs: Long = 15_000L,
        /** Close the link if nothing at all is received for this long. */
        val idleTimeoutMs: Long = 45_000L,
        val maxMalformedFrames: Int = 25,
        val appVersion: String = "",
    )

    override val id: String = "${transport.name}-$remoteLabel-${COUNTER.incrementAndGet()}"
    override val openedAtUtc: Long = clock.now()

    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO,
    )
    private val framer = LineFramer()
    private val writeMutex = Mutex()
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Ack>>()
    private val helloReceived = CompletableDeferred<Hello>()
    private val closed = AtomicBoolean(false)
    private val lastInboundAt = AtomicLong(clock.now())
    private var malformedCount = 0

    @Volatile
    private var remote: PeerIdentity? = null

    @Volatile
    var closeReason: String? = null
        private set

    override val peer: PeerIdentity
        get() = remote ?: throw IllegalStateException("Link $id has not completed the handshake")

    override val isOpen: Boolean
        get() = !closed.get() && remote != null

    /**
     * Starts reading, sends our HELLO and waits for the peer's HELLO.
     * Throws [IOException] (after closing the link) on timeout, protocol mismatch or self-connection.
     */
    suspend fun handshake(): PeerIdentity {
        scope.launch { readLoop() }
        val me = localIdentity()
        try {
            writeFrame(Frame.hello(Hello(deviceId = me.deviceId, name = me.displayName, appVersion = config.appVersion)))
        } catch (e: IOException) {
            close("handshake write failed: ${e.message}")
            throw e
        }
        val hello = try {
            withTimeoutOrNull(config.handshakeTimeoutMs) { helloReceived.await() }
        } catch (e: Exception) {
            close("handshake failed: ${e.message}")
            throw IOException("Handshake failed: ${e.message}", e)
        }
        if (hello == null) {
            close("handshake timed out")
            throw IOException("Handshake timed out after ${config.handshakeTimeoutMs / 1000}s (is RELAY open on the other phone?)")
        }
        if (hello.deviceId == me.deviceId) {
            close("connected to self")
            throw IOException("Connected to this same device")
        }
        val identity = PeerIdentity(hello.deviceId, hello.name)
        remote = identity
        log.info(transport, "Handshake OK with ${identity.displayName} (${identity.deviceId}) at $remoteLabel")
        scope.launch { keepaliveLoop() }
        return identity
    }

    override suspend fun sendMessage(msg: WireMessage, timeoutMs: Long): SendOutcome {
        if (!isOpen) return SendOutcome.Failed("link closed")
        val deferred = CompletableDeferred<Ack>()
        pendingAcks.put(msg.id, deferred)?.completeExceptionally(IOException("superseded by a newer send of the same message"))
        try {
            writeFrame(Frame.msg(msg))
        } catch (e: kotlinx.coroutines.CancellationException) {
            pendingAcks.remove(msg.id, deferred)
            throw e
        } catch (e: Exception) {
            pendingAcks.remove(msg.id, deferred)
            return SendOutcome.Failed("write failed: ${e.message ?: e.javaClass.simpleName}")
        }
        log.info(transport, "MSG ${TransferLog.shortId(msg.id)} written to ${remote?.displayName}; waiting for ACK")
        return try {
            val ack = withTimeoutOrNull(timeoutMs) { deferred.await() }
            when {
                ack == null -> SendOutcome.Timeout
                ack.status == AckStatus.REJECTED -> SendOutcome.Rejected(ack.reason ?: "rejected by peer")
                else -> SendOutcome.Acked(ack)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SendOutcome.Failed(e.message ?: "link closed before ACK")
        } finally {
            pendingAcks.remove(msg.id, deferred)
        }
    }

    override suspend fun sendReceipt(receipt: Receipt): Boolean {
        if (!isOpen) return false
        return try {
            writeFrame(Frame.rcpt(receipt))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Sends BYE (best effort, short timeout) and closes. */
    suspend fun closeGracefully(reason: String) {
        if (closed.get()) return
        withTimeoutOrNull(1_500L) { runCatching { writeFrame(Frame.bye(reason)) } }
        close(reason)
    }

    override fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        closeReason = reason
        runCatching { closeUnderlying() }
        val error = IOException("Link closed: $reason")
        pendingAcks.values.forEach { it.completeExceptionally(error) }
        pendingAcks.clear()
        helloReceived.completeExceptionally(error)
        log.info(transport, "Link to ${remote?.displayName ?: remoteLabel} closed: $reason")
        runCatching { onClosed(this, reason) }
        scope.cancel()
    }

    // ---------------------------------------------------------------- internals

    private suspend fun writeFrame(frame: Frame) {
        val bytes = ProtocolCodec.encodeLine(frame)
        writeMutex.withLock {
            if (closed.get()) throw IOException("link closed")
            // Blocking socket writes cannot be interrupted; a watchdog closes the socket instead,
            // which makes the blocked write throw.
            val watchdog = scope.launch {
                delay(config.writeTimeoutMs)
                close("write timed out")
            }
            try {
                withContext(Dispatchers.IO) {
                    output.write(bytes)
                    output.flush()
                }
            } finally {
                watchdog.cancel()
            }
        }
    }

    private suspend fun readLoop() {
        val buf = ByteArray(4096)
        try {
            while (scope.isActive && !closed.get()) {
                val n = input.read(buf)
                if (n < 0) {
                    close("remote closed the connection")
                    return
                }
                if (n == 0) continue
                lastInboundAt.set(clock.now())
                for (event in framer.feed(buf, 0, n)) {
                    when (event) {
                        is LineFramer.Event.Line -> handleLine(event.text)
                        is LineFramer.Event.Oversize -> onMalformed("oversized frame (${event.droppedBytes} bytes) dropped", null)
                    }
                }
            }
        } catch (e: IOException) {
            close("connection lost: ${e.message ?: "I/O error"}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(transport, "Reader crashed: ${e.javaClass.simpleName}: ${e.message}")
            close("reader error")
        }
    }

    private suspend fun handleLine(line: String) {
        when (val result = ProtocolCodec.decode(line)) {
            is ProtocolCodec.DecodeResult.Malformed -> onMalformed(result.reason, result.messageId)
            is ProtocolCodec.DecodeResult.UnsupportedVersion -> {
                log.warn(transport, "Peer uses unsupported protocol version ${result.version}")
                if (remote == null) {
                    close("unsupported protocol version ${result.version}")
                } else if (result.messageId != null) {
                    sendReject(result.messageId, "unsupported protocol version ${result.version}")
                }
            }
            is ProtocolCodec.DecodeResult.Ok -> handleFrame(result.frame)
        }
    }

    private suspend fun handleFrame(frame: Frame) {
        when (frame.t) {
            FrameType.HELLO -> frame.hello?.let { helloReceived.complete(it) }
            FrameType.PING -> runCatching { writeFrame(Frame.pong()) }
            FrameType.PONG -> Unit
            FrameType.BYE -> close("peer disconnected${frame.reason?.let { ": $it" } ?: ""}")
            FrameType.ACK -> {
                val ack = frame.ack ?: return
                val waiter = pendingAcks.remove(ack.messageId)
                if (waiter != null) {
                    waiter.complete(ack)
                } else {
                    val from = remote ?: return
                    inbound.onUnsolicitedAck(ack, from, transport)
                }
            }
            FrameType.MSG -> {
                val msg = frame.msg ?: return
                val from = remote ?: return // ignore data before the handshake completes
                val ack = try {
                    inbound.onMessage(msg, from, transport)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error(transport, "Failed to store MSG ${TransferLog.shortId(msg.id)}: ${e.message}")
                    // Do not acknowledge: the sender will retry.
                    return
                }
                runCatching { writeFrame(Frame.ack(ack)) }
                    .onFailure { log.warn(transport, "Could not send ACK for ${TransferLog.shortId(msg.id)}: ${it.message}") }
            }
            FrameType.RCPT -> {
                val rcpt = frame.rcpt ?: return
                val from = remote ?: return
                runCatching { inbound.onReceipt(rcpt, from, transport) }
                    .onFailure { log.warn(transport, "Failed to process receipt: ${it.message}") }
            }
        }
    }

    private suspend fun onMalformed(reason: String, messageId: String?) {
        malformedCount++
        log.warn(transport, "Rejected malformed frame from ${remote?.displayName ?: remoteLabel}: $reason")
        if (messageId != null && remote != null) sendReject(messageId, reason)
        if (malformedCount >= config.maxMalformedFrames) close("too many malformed frames")
    }

    private suspend fun sendReject(messageId: String, reason: String) {
        val me = localIdentity()
        val ack = Ack(
            messageId = messageId,
            kind = AckKind.HOP,
            status = AckStatus.REJECTED,
            byDeviceId = me.deviceId,
            byName = me.displayName,
            receivedAtUtc = clock.now(),
            reason = reason.take(200),
        )
        runCatching { writeFrame(Frame.ack(ack)) }
    }

    private suspend fun keepaliveLoop() {
        while (scope.isActive && !closed.get()) {
            delay(config.pingIntervalMs)
            val silentFor = clock.now() - lastInboundAt.get()
            if (silentFor > config.idleTimeoutMs) {
                close("peer silent for ${silentFor / 1000}s")
                return
            }
            try {
                writeFrame(Frame.ping())
            } catch (e: Exception) {
                close("keepalive failed: ${e.message}")
                return
            }
        }
    }

    private companion object {
        val COUNTER = AtomicInteger(0)
    }
}
