package com.relay.app.relay

import com.relay.app.data.db.MessageEntity
import com.relay.app.model.RelayConfig
import com.relay.app.model.SendState

/**
 * Pure queue rules: send order, retry timing and expiry. No I/O, easy to unit test.
 */
object QueuePolicy {

    /**
     * Send order: higher priority first (SOS before everything), then oldest first so messages
     * of the same priority are served fairly (FIFO), then id so the order is always stable.
     */
    val sendOrder: Comparator<MessageEntity> =
        compareByDescending<MessageEntity> { it.priority }
            .thenBy { it.createdAtUtc }
            .thenBy { it.id }

    fun isExpired(message: MessageEntity, nowUtc: Long): Boolean = message.expiresAtUtc <= nowUtc

    /**
     * Delay before the next timer-driven retry after [failedRounds] failed rounds (1, 2, 3...):
     * base * 2^(n-1), capped at the maximum, with a small deterministic jitter (+/-10%, derived
     * from the message id) so several phones do not retry in lockstep.
     */
    fun backoffMs(failedRounds: Int, config: RelayConfig, messageId: String): Long {
        val exponent = (failedRounds - 1).coerceIn(0, 20)
        val raw = (config.backoffBaseMs shl exponent).coerceAtMost(config.backoffMaxMs)
        val jitterPercent = Math.floorMod(messageId.hashCode(), 21) - 10 // -10..+10
        return (raw + raw * jitterPercent / 100).coerceIn(1_000L, config.backoffMaxMs)
    }

    /**
     * Whether a message should be attempted in a pass started by [trigger].
     * Route-change triggers (a peer connected, internet returned, user tapped retry...) skip the
     * backoff because they bring new information; timer triggers respect nextAttemptAtUtc.
     * PAUSED messages are only retried manually.
     */
    fun isDue(message: MessageEntity, nowUtc: Long, trigger: ProcessTrigger): Boolean {
        if (message.sendState == SendState.PAUSED) return trigger == ProcessTrigger.MANUAL
        if (trigger.ignoresBackoff) return true
        val next = message.nextAttemptAtUtc ?: return true
        return next <= nowUtc
    }

    /**
     * The state a pending message rests in when no transmission is running: it keeps showing the
     * furthest real progress (relay accepted it, or it was uploaded), otherwise waiting.
     */
    fun restingState(message: MessageEntity): SendState = when {
        message.sendState == SendState.PAUSED -> SendState.PAUSED
        message.hopAckCount > 0 && !message.isBroadcast -> SendState.HOP_ACKED
        message.uploadedAtUtc != null -> SendState.UPLOADED
        message.retryCount > 0 && message.nextAttemptAtUtc != null -> SendState.RETRY_WAIT
        else -> SendState.QUEUED
    }
}
