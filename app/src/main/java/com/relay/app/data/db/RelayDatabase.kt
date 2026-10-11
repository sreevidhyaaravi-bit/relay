package com.relay.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MessageEntity::class,
        RouteHopEntity::class,
        PeerEntity::class,
        HandoffEntity::class,
        ReceiptEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class RelayDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun routes(): RouteDao
    abstract fun peers(): PeerDao
    abstract fun handoffs(): HandoffDao
    abstract fun receipts(): ReceiptDao

    companion object {
        const val NAME = "relay.db"

        /** v2 (round two): sender battery level on messages. Keeps every existing message. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN batteryPercent INTEGER")
            }
        }

        fun build(context: Context): RelayDatabase =
            Room.databaseBuilder(context.applicationContext, RelayDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                // Safety net only for unknown future versions.
                .fallbackToDestructiveMigration()
                .build()

        /** For tests: a fresh in-memory database. */
        fun inMemory(context: Context): RelayDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, RelayDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
