package com.relay.app.transport

import com.relay.app.data.db.PeerEntity

/**
 * Which known RELAY phones to try to reconnect to over Bluetooth, and how often.
 *
 * Android only lets a phone be FOUND by a Bluetooth search while it is "visible", but a phone
 * whose address we already know can be CONNECTED to directly. So after two phones have connected
 * once, RELAY reconnects by itself whenever both are nearby, with no searching or tapping.
 * Pure logic, unit tested.
 */
object AutoConnectPolicy {

    data class Candidate(val deviceId: String, val address: String)

    /** Only phones seen recently are worth trying. */
    const val MAX_AGE_MS: Long = 14L * 24 * 60 * 60 * 1000
    private const val MAX_PER_ROUND = 3

    /**
     * @param nextAllowedAt address -> earliest time to try again (backoff after failures).
     * @param userDisconnected addresses the user disconnected on purpose (never auto-reconnect).
     */
    fun candidates(
        peers: List<PeerEntity>,
        openLinks: List<PeerLink>,
        nowUtc: Long,
        nextAllowedAt: Map<String, Long>,
        userDisconnected: Set<String>,
    ): List<Candidate> = peers
        .filter { it.bluetoothAddress != null && nowUtc - it.lastSeenAtUtc <= MAX_AGE_MS }
        .filter { p -> openLinks.none { l -> l.isOpen && (l.peer.deviceId == p.deviceId || l.remoteLabel == p.bluetoothAddress) } }
        .filter { (nextAllowedAt[it.bluetoothAddress] ?: 0L) <= nowUtc }
        .filter { it.bluetoothAddress !in userDisconnected }
        .sortedByDescending { it.lastSeenAtUtc }
        .take(MAX_PER_ROUND)
        .map { Candidate(it.deviceId, it.bluetoothAddress!!) }

    /** 30 s, 60 s, then every 2 min while that phone stays out of range. */
    fun backoffMs(failures: Int): Long = (30_000L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(2 * 60_000L)
}
