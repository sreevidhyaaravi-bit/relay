package com.relay.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.relay.app.data.db.HandoffEntity
import com.relay.app.data.db.ReceiptEntity
import com.relay.app.data.db.RelayDatabase
import com.relay.app.model.DeliveryState
import com.relay.app.model.DeviceIdentity
import com.relay.app.model.LocationInfo
import com.relay.app.model.LocationSource
import com.relay.app.model.MessageDraft
import com.relay.app.model.MessageType
import com.relay.app.model.RelayConfig
import com.relay.app.model.SendState
import com.relay.app.model.TransportKind
import com.relay.app.relay.MessageMapper
import com.relay.app.relay.RelayEngine
import com.relay.app.testutil.FakeClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real Room database (file-backed, under Robolectric): persistence, restart, dedup, ordering. */
@RunWith(RobolectricTestRunner::class)
class PersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = FakeClock()
    private val me = DeviceIdentity("RLY-A", "Phone A")
    private val dbName = "persistence-test.db"
    private lateinit var db: RelayDatabase

    private fun open() = Room.databaseBuilder(context, RelayDatabase::class.java, dbName).allowMainThreadQueries().build()

    private fun engine(database: RelayDatabase) = RelayEngine(RoomRelayStore(database), { me }, { RelayConfig() }, clock)

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
        db = open()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `queued SOS survives an app restart with all metadata`() = runBlocking {
        val loc = LocationInfo(12.97, 77.59, 4.5f, clock.now() - 120_000, "North gate", LocationSource.DEVICE)
        val created = engine(db).createOutgoing(MessageDraft("RLY-B", "Phone B", "🆘 help at north gate", MessageType.SOS, loc))
        db.close() // the app is force-closed

        db = open() // ... and reopened
        val store = RoomRelayStore(db)
        val restored = store.get(created.id)!!
        assertEquals(created.createdAtUtc, restored.createdAtUtc)
        assertEquals(MessageType.SOS, restored.messageType)
        assertEquals(3, restored.priority)
        assertEquals("🆘 help at north gate", restored.body)
        assertEquals(loc, restored.location())
        assertEquals(SendState.QUEUED, restored.sendState)
        assertEquals(DeliveryState.PENDING, restored.deliveryState)
        assertEquals(listOf(created.id), store.pendingForwarding().map { it.id })
        assertEquals(1, store.routeFor(created.id).size)
    }

    @Test
    fun `duplicate ids are stored once`() = runBlocking {
        val m = engine(db).createOutgoing(MessageDraft("RLY-B", null, "x", MessageType.NORMAL, null))
        val store = RoomRelayStore(db)
        assertFalse(store.insertIfAbsent(m.copy(body = "changed")))
        assertEquals("x", store.get(m.id)!!.body)
    }

    @Test
    fun `pending queue is ordered by priority then age`() = runBlocking {
        val e = engine(db)
        val n1 = e.createOutgoing(MessageDraft("RLY-B", null, "n1", MessageType.NORMAL, null))
        clock.advance(1_000)
        val n2 = e.createOutgoing(MessageDraft("RLY-B", null, "n2", MessageType.NORMAL, null))
        clock.advance(1_000)
        val s = e.createOutgoing(MessageDraft("RLY-B", null, "s", MessageType.SOS, null))
        clock.advance(1_000)
        val u = e.createOutgoing(MessageDraft("RLY-B", null, "u", MessageType.URGENT, null))
        assertEquals(listOf(s.id, u.id, n1.id, n2.id), RoomRelayStore(db).pendingForwarding().map { it.id })
    }

    @Test
    fun `incoming message is deduplicated by the real database`() = runBlocking {
        val sender = RelayEngine(RoomRelayStore(db), { DeviceIdentity("RLY-S", "S") }, { RelayConfig() }, clock)
        val m = sender.createOutgoing(MessageDraft("RLY-A", null, "to A", MessageType.NORMAL, null))
        val wire = MessageMapper.toWireForSend(m, DeviceIdentity("RLY-S", "S"), TransportKind.BLUETOOTH, clock.now())
        // A separate database for the receiver.
        val receiverDb = Room.inMemoryDatabaseBuilder(context, RelayDatabase::class.java).allowMainThreadQueries().build()
        val receiver = engine(receiverDb)
        val first = receiver.onMessage(wire, com.relay.app.model.PeerIdentity("RLY-S", "S"), TransportKind.BLUETOOTH)
        val second = receiver.onMessage(wire, com.relay.app.model.PeerIdentity("RLY-S", "S"), TransportKind.WIFI_DIRECT)
        assertEquals("STORED", first.status)
        assertEquals("DUPLICATE", second.status)
        assertEquals(1, receiverDb.messages().pendingForwarding().size + countInbox(receiverDb))
        receiverDb.close()
    }

    @Test
    fun `receipts and handoffs persist`() = runBlocking {
        val store = RoomRelayStore(db)
        val r = ReceiptEntity("123e4567-e89b-42d3-a456-426614174000", "RLY-X", "RLY-Y", "Y", 1, clock.now() + 60_000, false, clock.now())
        assertTrue(store.insertReceiptIfAbsent(r))
        assertFalse(store.insertReceiptIfAbsent(r))
        assertEquals(1, store.receiptsToShare(clock.now(), "RLY-A", 10).size)
        assertTrue(store.receiptsToShare(clock.now(), "RLY-X", 10).isEmpty()) // addressed to us: not re-shared
        store.recordHandoff(HandoffEntity("m", "RLY-B", TransportKind.BLUETOOTH, "HOP", 1))
        store.recordHandoff(HandoffEntity("m", "RLY-B", TransportKind.WIFI_DIRECT, "FINAL", 2))
        assertEquals("FINAL", store.handoffsFor("m").single().result)
    }

    private suspend fun countInbox(database: RelayDatabase): Int = database.messages().observeInbox().first().size
}
