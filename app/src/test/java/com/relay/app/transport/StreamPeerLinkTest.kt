package com.relay.app.transport

import com.relay.app.model.DeviceIdentity
import com.relay.app.model.PeerIdentity
import com.relay.app.model.SystemClock
import com.relay.app.model.TransportKind
import com.relay.app.protocol.Ack
import com.relay.app.protocol.AckKind
import com.relay.app.protocol.AckStatus
import com.relay.app.protocol.Frame
import com.relay.app.protocol.Hello
import com.relay.app.protocol.ProtocolCodec
import com.relay.app.protocol.Receipt
import com.relay.app.protocol.WireMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * StreamPeerLink over REAL loopback TCP sockets on this computer. This exercises framing,
 * handshake, ACKs, timeouts and connection loss end to end. It is still not a phone-to-phone
 * radio test: Bluetooth and Wi-Fi Direct must be verified on physical phones.
 */
class StreamPeerLinkTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sockets = mutableListOf<Socket>()

    @After
    fun tearDown() {
        scope.cancel()
        sockets.forEach { runCatching { it.close() } }
    }

    private class RecordingHandler(private val me: () -> DeviceIdentity, var delayMs: Long = 0) : InboundHandler {
        val messages = CopyOnWriteArrayList<WireMessage>()
        val unsolicited = CopyOnWriteArrayList<Ack>()
        val receipts = CopyOnWriteArrayList<Receipt>()
        override suspend fun onMessage(msg: WireMessage, from: PeerIdentity, via: TransportKind): Ack {
            if (delayMs > 0) delay(delayMs)
            messages += msg
            return Ack(msg.id, AckKind.FINAL, AckStatus.STORED, me().deviceId, me().displayName, System.currentTimeMillis())
        }
        override suspend fun onReceipt(receipt: Receipt, from: PeerIdentity, via: TransportKind) {
            receipts += receipt
        }
        override suspend fun onUnsolicitedAck(ack: Ack, from: PeerIdentity, via: TransportKind) {
            unsolicited += ack
        }
        override suspend fun onRemoteFinalDelivery(messageId: String, recipientId: String, recipientName: String?, deliveredAtUtc: Long, via: TransportKind) = Unit
    }

    private fun socketPair(): Pair<Socket, Socket> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val accepted = server.accept()
        server.close()
        sockets += client
        sockets += accepted
        return client to accepted
    }

    private fun link(socket: Socket, id: String, handler: InboundHandler, config: StreamPeerLink.Config = StreamPeerLink.Config(), closed: MutableList<String>? = null) =
        StreamPeerLink(
            transport = TransportKind.BLUETOOTH,
            remoteLabel = "loopback",
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            closeUnderlying = { socket.close() },
            localIdentity = { DeviceIdentity(id, "Phone $id") },
            inbound = handler,
            log = TransferLog(),
            clock = SystemClock,
            parentScope = scope,
            config = config,
            onClosed = { _, reason -> closed?.add(reason) },
        )

    private fun wire(body: String = "🆘 RELAY TEST 7319 — Please confirm receipt.", from: String = "RLY-A", to: String = "RLY-B") = WireMessage(
        id = UUID.randomUUID().toString(), senderId = from, senderName = "Phone A", recipientId = to, body = body,
        type = "SOS", priority = 3, createdAtUtc = System.currentTimeMillis(), expiresAtUtc = System.currentTimeMillis() + 3_600_000, hopCount = 1,
    )

    private suspend fun connected(handlerA: InboundHandler, handlerB: InboundHandler, config: StreamPeerLink.Config = StreamPeerLink.Config()): Pair<StreamPeerLink, StreamPeerLink> {
        val (sa, sb) = socketPair()
        val a = link(sa, "RLY-A", handlerA, config)
        val b = link(sb, "RLY-B", handlerB, config)
        val pa = scope.async { a.handshake() }
        val pb = scope.async { b.handshake() }
        assertEquals("RLY-B", pa.await().deviceId)
        assertEquals("RLY-A", pb.await().deviceId)
        return a to b
    }

    @Test
    fun `handshake exchanges identities and a message is acknowledged`() = runBlocking {
        val hb = RecordingHandler({ DeviceIdentity("RLY-B", "Phone B") })
        val (a, b) = connected(RecordingHandler({ DeviceIdentity("RLY-A", "Phone A") }), hb)
        assertTrue(a.isOpen && b.isOpen)
        val msg = wire()
        val outcome = a.sendMessage(msg, 5_000)
        assertTrue(outcome is SendOutcome.Acked)
        assertEquals(AckKind.FINAL, (outcome as SendOutcome.Acked).ack.kind)
        assertEquals(msg, hb.messages.single())
        assertEquals("🆘 RELAY TEST 7319 — Please confirm receipt.", hb.messages.single().body)
    }

    @Test
    fun `many messages in a burst are all delivered and acknowledged`() = runBlocking {
        val hb = RecordingHandler({ DeviceIdentity("RLY-B", "Phone B") })
        val (a, _) = connected(RecordingHandler({ DeviceIdentity("RLY-A", "Phone A") }), hb)
        val results = (1..25).map { i -> scope.async { a.sendMessage(wire("message $i"), 10_000) } }.map { it.await() }
        assertTrue(results.all { it is SendOutcome.Acked })
        assertEquals(25, hb.messages.size)
    }

    @Test
    fun `no ack in time is a timeout, and the late ack is still reported`() = runBlocking {
        val ha = RecordingHandler({ DeviceIdentity("RLY-A", "Phone A") })
        val hb = RecordingHandler({ DeviceIdentity("RLY-B", "Phone B") }, delayMs = 1_500)
        val (a, _) = connected(ha, hb)
        val outcome = a.sendMessage(wire(), 300)
        assertEquals(SendOutcome.Timeout, outcome)
        withTimeout(5_000) { while (ha.unsolicited.isEmpty()) delay(50) }
        assertEquals(AckStatus.STORED, ha.unsolicited.single().status)
    }

    @Test
    fun `connection loss fails the pending send and closes the link`() = runBlocking {
        val closedReasons = CopyOnWriteArrayList<String>()
        val (sa, sb) = socketPair()
        val a = link(sa, "RLY-A", RecordingHandler({ DeviceIdentity("RLY-A", "A") }), closed = closedReasons)
        val b = link(sb, "RLY-B", RecordingHandler({ DeviceIdentity("RLY-B", "B") }, delayMs = 5_000))
        val pa = scope.async { a.handshake() }
        scope.async { b.handshake() }.await()
        pa.await()
        val pending = scope.async { a.sendMessage(wire(), 10_000) }
        delay(200)
        sb.close() // the other phone disappears
        val outcome = pending.await()
        assertTrue("expected failure, got $outcome", outcome is SendOutcome.Failed)
        withTimeout(3_000) { while (a.isOpen) delay(20) }
        assertFalse(a.isOpen)
        assertTrue(closedReasons.isNotEmpty())
    }

    @Test
    fun `connecting to a device with the same id is refused`() = runBlocking {
        val (sa, sb) = socketPair()
        val a = link(sa, "RLY-SAME", RecordingHandler({ DeviceIdentity("RLY-SAME", "A") }))
        val b = link(sb, "RLY-SAME", RecordingHandler({ DeviceIdentity("RLY-SAME", "B") }))
        val pb = scope.async { runCatching { b.handshake() } }
        try {
            a.handshake()
            fail("handshake should fail")
        } catch (e: IOException) {
            // expected
        }
        assertTrue(pb.await().isFailure)
    }

    @Test
    fun `invalid message from a raw peer gets a REJECTED ack`() = runBlocking {
        val (sa, raw) = socketPair()
        val a = link(sa, "RLY-A", RecordingHandler({ DeviceIdentity("RLY-A", "A") }))
        val out = raw.getOutputStream()
        val reader = BufferedReader(InputStreamReader(raw.getInputStream(), Charsets.UTF_8))
        val handshake = scope.async { a.handshake() }
        out.write(ProtocolCodec.encodeLine(Frame.hello(Hello("RLY-RAW", "Raw peer"))))
        out.flush()
        assertEquals("RLY-RAW", handshake.await().deviceId)
        reader.readLine() // our HELLO from A
        val bad = wire(body = "   ").copy(senderId = "RLY-RAW")
        out.write((ProtocolCodec.encode(Frame.msg(bad)) + "\n").toByteArray())
        out.write("this is not json\n".toByteArray())
        out.flush()
        val reply = withTimeout(5_000) {
            var line: String
            do {
                line = reader.readLine() ?: error("closed")
            } while (!line.contains("\"ACK\""))
            line
        }
        val ack = (ProtocolCodec.decode(reply) as ProtocolCodec.DecodeResult.Ok).frame.ack!!
        assertEquals(bad.id, ack.messageId)
        assertEquals(AckStatus.REJECTED, ack.status)
        assertTrue(a.isOpen) // one bad frame does not kill the link
    }

    @Test
    fun `peer that never says hello times out`() = runBlocking {
        val (sa, _) = socketPair()
        val a = link(sa, "RLY-A", RecordingHandler({ DeviceIdentity("RLY-A", "A") }), StreamPeerLink.Config(handshakeTimeoutMs = 500))
        try {
            a.handshake()
            fail("expected timeout")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("timed out"))
        }
        assertFalse(a.isOpen)
    }

    @Test
    fun `receipts are delivered to the other side`() = runBlocking {
        val hb = RecordingHandler({ DeviceIdentity("RLY-B", "Phone B") })
        val (a, _) = connected(RecordingHandler({ DeviceIdentity("RLY-A", "Phone A") }), hb)
        val r = Receipt(UUID.randomUUID().toString(), "RLY-X", "RLY-Y", "Y", 1L, System.currentTimeMillis() + 60_000)
        assertTrue(a.sendReceipt(r))
        withTimeout(3_000) { while (hb.receipts.isEmpty()) delay(20) }
        assertEquals(r, hb.receipts.single())
    }
}
