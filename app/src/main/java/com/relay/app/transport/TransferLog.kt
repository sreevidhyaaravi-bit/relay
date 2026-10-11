package com.relay.app.transport

import android.util.Log
import com.relay.app.model.Clock
import com.relay.app.model.SystemClock
import com.relay.app.model.TransportKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory ring buffer of connection and transfer events, shown on the Nearby Devices and
 * Connectivity screens and mirrored to logcat (tag "RELAY").
 *
 * Privacy rule: NEVER log message bodies or coordinates. Use [shortId] for message ids.
 */
class TransferLog(
    private val capacity: Int = 300,
    private val clock: Clock = SystemClock,
) {
    enum class Level { INFO, WARN, ERROR }

    data class Entry(
        val atUtc: Long,
        val kind: TransportKind?,
        val level: Level,
        val text: String,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<Entry>> = _entries

    fun info(kind: TransportKind?, text: String) = add(kind, Level.INFO, text)
    fun warn(kind: TransportKind?, text: String) = add(kind, Level.WARN, text)
    fun error(kind: TransportKind?, text: String) = add(kind, Level.ERROR, text)

    fun clear() {
        _entries.value = emptyList()
    }

    private fun add(kind: TransportKind?, level: Level, text: String) {
        val entry = Entry(clock.now(), kind, level, text)
        _entries.update { (listOf(entry) + it).take(capacity) }
        val line = "[${kind?.name ?: "CORE"}] $text"
        when (level) {
            Level.INFO -> Log.i(TAG, line)
            Level.WARN -> Log.w(TAG, line)
            Level.ERROR -> Log.e(TAG, line)
        }
    }

    companion object {
        const val TAG = "RELAY"

        /** First 8 characters of a message id, safe for logs. */
        fun shortId(id: String): String = id.take(8)
    }
}
