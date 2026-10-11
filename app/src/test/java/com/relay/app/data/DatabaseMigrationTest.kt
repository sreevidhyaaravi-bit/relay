package com.relay.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.relay.app.data.db.RelayDatabase
import com.relay.app.model.DeliveryState
import com.relay.app.model.SendState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The round-two database upgrade (v1 -> v2, adds battery level) must keep every message that is
 * already on the team's phones. Builds a real v1 database file with a queued SOS, then opens it
 * with the current app exactly like an installed update would.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    /** The v1 schema as Room created it in the round-one app (same tables, no batteryPercent). */
    private val v1Schema = listOf(
            "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `senderId` TEXT NOT NULL, `senderName` TEXT NOT NULL, `recipientId` TEXT NOT NULL, `recipientName` TEXT, `body` TEXT NOT NULL, `messageType` TEXT NOT NULL, `priority` INTEGER NOT NULL, `createdAtUtc` INTEGER NOT NULL, `expiresAtUtc` INTEGER NOT NULL, `receivedAtUtc` INTEGER, `acknowledgedAtUtc` INTEGER, `deliveredAtUtc` INTEGER, `deliveredVia` TEXT, `deliveredToName` TEXT, `latitude` REAL, `longitude` REAL, `accuracyMeters` REAL, `locationCapturedAtUtc` INTEGER, `locationLabel` TEXT, `locationSource` TEXT, `direction` TEXT NOT NULL, `sendState` TEXT NOT NULL, `deliveryState` TEXT NOT NULL, `hopCount` INTEGER NOT NULL, `maxHops` INTEGER NOT NULL, `routeJson` TEXT NOT NULL, `lastTransport` TEXT, `retryCount` INTEGER NOT NULL, `lastAttemptAtUtc` INTEGER, `nextAttemptAtUtc` INTEGER, `lastError` TEXT, `relayAckBy` TEXT, `relayAckByName` TEXT, `relayAckAtUtc` INTEGER, `hopAckCount` INTEGER NOT NULL, `finalAckCount` INTEGER NOT NULL, `uploadedAtUtc` INTEGER, `receivedFromId` TEXT, `receivedFromName` TEXT, `isRead` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_messages_direction` ON `messages` (`direction`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_deliveryState` ON `messages` (`deliveryState`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_recipientId` ON `messages` (`recipientId`)",
            "CREATE TABLE IF NOT EXISTS `route_hops` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `messageId` TEXT NOT NULL, `event` TEXT NOT NULL, `nodeId` TEXT NOT NULL, `nodeName` TEXT NOT NULL, `peerId` TEXT, `peerName` TEXT, `transport` TEXT, `atUtc` INTEGER NOT NULL, `detail` TEXT)",
            "CREATE INDEX IF NOT EXISTS `index_route_hops_messageId` ON `route_hops` (`messageId`)",
            "CREATE TABLE IF NOT EXISTS `peers` (`deviceId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `lastSeenAtUtc` INTEGER NOT NULL, `lastTransport` TEXT, `lastSuccessTransport` TEXT, `bluetoothAddress` TEXT, `wifiDirectAddress` TEXT, `lanAddress` TEXT, PRIMARY KEY(`deviceId`))",
            "CREATE TABLE IF NOT EXISTS `handoffs` (`messageId` TEXT NOT NULL, `peerId` TEXT NOT NULL, `transport` TEXT NOT NULL, `result` TEXT NOT NULL, `atUtc` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `peerId`))",
            "CREATE TABLE IF NOT EXISTS `receipts` (`messageId` TEXT NOT NULL, `originalSenderId` TEXT NOT NULL, `finalRecipientId` TEXT NOT NULL, `finalRecipientName` TEXT NOT NULL, `receivedAtUtc` INTEGER NOT NULL, `expiresAtUtc` INTEGER NOT NULL, `createdLocally` INTEGER NOT NULL, `storedAtUtc` INTEGER NOT NULL, PRIMARY KEY(`messageId`))",
    )

    @Test
    fun `upgrade from round-one database keeps queued messages`(): Unit = runBlocking {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            v1Schema.forEach { db.execSQL(it) }
            db.execSQL(
                "INSERT INTO messages (id, senderId, senderName, recipientId, recipientName, body, messageType, priority, " +
                    "createdAtUtc, expiresAtUtc, direction, sendState, deliveryState, hopCount, maxHops, routeJson, retryCount, " +
                    "hopAckCount, finalAckCount, isRead) VALUES ('123e4567-e89b-42d3-a456-426614174000', 'RLY-A', 'Phone A', " +
                    "'RLY-B', 'Phone B', 'SOS from round one', 'SOS', 3, 1000, 999999999999, 'OUTGOING', 'QUEUED', 'PENDING', " +
                    "0, 6, '[]', 0, 0, 0, 0)",
            )
            db.version = 1
        }

        val upgraded = Room.databaseBuilder(context, RelayDatabase::class.java, name)
            .addMigrations(RelayDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        val message = upgraded.messages().get("123e4567-e89b-42d3-a456-426614174000")!!
        assertEquals("SOS from round one", message.body)
        assertEquals(1000L, message.createdAtUtc)
        assertEquals(SendState.QUEUED, message.sendState)
        assertEquals(DeliveryState.PENDING, message.deliveryState)
        assertNull(message.batteryPercent)
        upgraded.close()
        context.deleteDatabase(name)
    }
}
