package com.relay.app.ui.format

import com.relay.app.data.db.MessageEntity
import com.relay.app.data.db.RouteEvent
import com.relay.app.data.db.RouteHopEntity
import com.relay.app.model.DeliveryState
import com.relay.app.model.Direction
import com.relay.app.model.LocationInfo
import com.relay.app.model.LocationSource
import com.relay.app.model.SendState
import com.relay.app.model.TransportKind
import com.relay.app.model.TransportState
import com.relay.app.relay.MessageMapper
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

enum class Tone { NEUTRAL, INFO, SUCCESS, WARNING, DANGER, SIMULATED }

data class StatusText(val text: String, val tone: Tone, val detail: String? = null)

/**
 * All user-visible wording for times, statuses, routes and locations lives here so every screen
 * says the same (accurate) thing. Rule: never claim more than the acknowledgements prove.
 */
object RelayFormat {

    /** Stored times are UTC instants; display in the phone's current time zone. */
    private fun zone(): ZoneId = ZoneId.systemDefault()
    private val timeFmt: DateTimeFormatter get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    private val dateTimeFmt: DateTimeFormatter get() = DateTimeFormatter.ofPattern("MMM d, h:mm:ss a", Locale.getDefault())

    fun time(utc: Long?): String = utc?.let { timeFmt.format(Instant.ofEpochMilli(it).atZone(zone())) } ?: "-"

    fun dateTime(utc: Long?): String = utc?.let { dateTimeFmt.format(Instant.ofEpochMilli(it).atZone(zone())) } ?: "-"

    fun relative(utc: Long?, nowUtc: Long): String {
        if (utc == null) return "-"
        val diff = nowUtc - utc
        val future = diff < 0
        val s = abs(diff) / 1000
        val text = when {
            s < 45 -> return if (future) "in a moment" else "just now"
            s < 3600 -> "${(s + 30) / 60} min"
            s < 86_400 -> "${s / 3600} h ${(s % 3600) / 60} min"
            else -> "${s / 86_400} d"
        }
        return if (future) "in $text" else "$text ago"
    }

    fun duration(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 60 -> "$m min"
            m < 48 * 60 -> "${m / 60} h"
            else -> "${m / (24 * 60)} days"
        }
    }

    fun transportState(state: TransportState): Tone = when (state) {
        TransportState.CONNECTED, TransportState.SENDING -> Tone.SUCCESS
        TransportState.CONNECTING, TransportState.DISCOVERING -> Tone.INFO
        TransportState.AVAILABLE_NOT_CONNECTED -> Tone.NEUTRAL
        TransportState.FAILED -> Tone.DANGER
        TransportState.DISABLED, TransportState.UNAVAILABLE -> Tone.WARNING
    }

    /**
     * The one status line for a message. Outgoing wording follows the acknowledgement rules:
     * HOP ack = "sent to nearby relay", upload = "uploaded", only a FINAL ack/receipt = "delivered".
     */
    fun messageStatus(m: MessageEntity, nowUtc: Long): StatusText = when (m.direction) {
        Direction.INCOMING -> StatusText("Received ${time(m.receivedAtUtc)}", Tone.SUCCESS, m.lastTransport?.let { "via ${it.label}" })
        Direction.RELAY -> relayStatus(m, nowUtc)
        Direction.OUTGOING -> outgoingStatus(m, nowUtc)
    }

    private fun outgoingStatus(m: MessageEntity, nowUtc: Long): StatusText {
        when (m.deliveryState) {
            DeliveryState.DELIVERED -> return StatusText(
                "Delivered to intended recipient",
                Tone.SUCCESS,
                buildString {
                    m.deliveredToName?.let { append(it).append(" · ") }
                    append("confirmed ").append(time(m.acknowledgedAtUtc))
                    m.deliveredVia?.let { append(" via ").append(it.label) }
                },
            )
            DeliveryState.EXPIRED -> return StatusText(
                "Expired: no longer forwarded",
                Tone.WARNING,
                "Expired ${dateTime(m.expiresAtUtc)}. Create a new message if it is still needed.",
            )
            DeliveryState.CANCELLED -> return StatusText(
                "Cancelled before delivery",
                Tone.NEUTRAL,
                if (m.hopAckCount > 0 || m.uploadedAtUtc != null) "Copies already handed on may still be delivered." else null,
            )
            else -> Unit
        }
        if (m.isBroadcast && m.finalAckCount > 0) {
            return StatusText(
                "Received by ${m.finalAckCount} nearby device${if (m.finalAckCount == 1) "" else "s"}",
                Tone.SUCCESS,
                "Still offered to newly connected devices until ${dateTime(m.expiresAtUtc)}",
            )
        }
        return when (m.sendState) {
            SendState.SENDING -> StatusText("Sending${m.lastTransport?.let { " via ${it.label}" } ?: ""}…", Tone.INFO)
            SendState.HOP_ACKED -> StatusText(
                "Sent to nearby relay, waiting for final recipient",
                Tone.INFO,
                "Relay ${m.relayAckByName ?: m.relayAckBy ?: "device"} stored it ${time(m.relayAckAtUtc)}. Not yet confirmed by the recipient.",
            )
            SendState.UPLOADED -> StatusText(
                "Uploaded to internet relay, waiting for recipient",
                Tone.INFO,
                "Upload confirmed ${time(m.uploadedAtUtc)}. Not yet confirmed by the recipient.",
            )
            SendState.RETRY_WAIT -> StatusText(
                "Send failed, will retry",
                Tone.WARNING,
                buildString {
                    m.lastError?.let { append(it).append(". ") }
                    m.nextAttemptAtUtc?.let { append("Next try ").append(relative(it, nowUtc)).append(" or when a route appears.") }
                },
            )
            SendState.PAUSED -> StatusText(
                "Retries paused after ${m.retryCount} failed attempts",
                Tone.DANGER,
                (m.lastError?.let { "$it. " } ?: "") + "Tap Retry now to try again.",
            )
            SendState.QUEUED, SendState.NONE, SendState.DONE -> StatusText(
                "QUEUED — WAITING FOR CONNECTION",
                Tone.WARNING,
                m.lastError ?: if (m.lastAttemptAtUtc == null) "Saved on this phone. Not transmitted yet." else null,
            )
        }
    }

    private fun relayStatus(m: MessageEntity, nowUtc: Long): StatusText = when (m.deliveryState) {
        DeliveryState.DELIVERED -> StatusText("Carried for others; recipient confirmed delivery", Tone.SUCCESS)
        DeliveryState.EXPIRED -> StatusText("Expired: no longer forwarded", Tone.WARNING)
        DeliveryState.CANCELLED -> StatusText("Stopped", Tone.NEUTRAL)
        else -> when (m.sendState) {
            SendState.HOP_ACKED -> StatusText("Carrying for others; handed to next relay", Tone.INFO)
            SendState.UPLOADED -> StatusText("Carrying for others; uploaded to internet relay", Tone.INFO)
            else -> StatusText("Carrying for others; waiting for next hop", Tone.WARNING, "Expires ${relative(m.expiresAtUtc, nowUtc)}")
        }
    }

    /** "Expires in 23 h" / "Expired 2 h ago". */
    fun expiry(m: MessageEntity, nowUtc: Long): String =
        if (m.expiresAtUtc > nowUtc) "Expires ${relative(m.expiresAtUtc, nowUtc)}" else "Expired ${relative(m.expiresAtUtc, nowUtc)}"

    /**
     * Location line for a card. Says "Location unavailable" when none was attached, and marks
     * stale fixes with their real capture time. Never shows the receiver's/relay's location.
     */
    fun location(loc: LocationInfo?, messageCreatedUtc: Long): String {
        if (loc == null) return "Location unavailable"
        val parts = mutableListOf<String>()
        if (loc.hasCoordinates) {
            parts += String.format(Locale.US, "%.5f, %.5f", loc.latitude, loc.longitude)
            loc.accuracyMeters?.let { parts += "±${it.toInt()} m" }
        }
        loc.label?.takeIf { it.isNotBlank() }?.let { parts += "\"$it\"" }
        when (loc.source) {
            LocationSource.MANUAL -> parts += "entered manually"
            LocationSource.DEVICE -> {
                parts += "captured ${time(loc.capturedAtUtc)}"
                if (loc.isStale(messageCreatedUtc)) {
                    val age = messageCreatedUtc - (loc.capturedAtUtc ?: messageCreatedUtc)
                    parts += "taken ${duration(age)} before sending, may be out of date"
                }
            }
        }
        return parts.joinToString(" · ")
    }

    /** Warns when the sender's clock seems ahead of ours (sent time after our receive time). */
    fun clockSkewWarning(m: MessageEntity): String? {
        val received = m.receivedAtUtc ?: return null
        val ahead = m.createdAtUtc - received
        return if (ahead > 2 * 60_000) "Sender's clock appears ~${duration(ahead)} ahead of this phone" else null
    }

    /**
     * Physical route as actually recorded: the hops carried inside the message plus this device.
     * e.g. "Phone A → Bluetooth → Phone B → Wi-Fi Direct → Phone C".
     */
    fun carriedRoute(m: MessageEntity, selfName: String): String {
        val hops = MessageMapper.decodeRoute(m.routeJson)
        if (hops.isEmpty()) return if (m.direction == Direction.OUTGOING) selfName else "${m.senderName} → $selfName"
        val sb = StringBuilder()
        hops.forEach { hop ->
            sb.append(hop.nodeName).append(" → ").append(TransportKind.fromName(hop.transport)?.label ?: hop.transport).append(" → ")
        }
        sb.append(selfName)
        return sb.toString()
    }

    fun routeEventLabel(e: RouteHopEntity): String = when (e.event) {
        RouteEvent.CREATED -> "Created on ${e.nodeName}"
        RouteEvent.FORWARDED_BY -> "${e.nodeName} forwarded via ${e.transport?.label ?: "?"}"
        RouteEvent.ATTEMPT -> "Trying ${e.transport?.label ?: "?"}${e.peerName?.let { " → $it" } ?: ""}"
        RouteEvent.ATTEMPT_FAILED -> "Failed ${e.transport?.label ?: ""}${e.peerName?.let { " → $it" } ?: ""}${e.detail?.let { ": $it" } ?: ""}"
        RouteEvent.HOP_ACKED -> "Relay ${e.peerName ?: e.peerId ?: "?"} stored it (hop ack via ${e.transport?.label ?: "?"})"
        RouteEvent.UPLOADED -> "Uploaded to internet relay"
        RouteEvent.RECEIVED -> "Received by ${e.nodeName}${e.peerName?.let { " from $it" } ?: ""} via ${e.transport?.label ?: "?"}"
        RouteEvent.DUPLICATE_RECEIVED -> "Duplicate copy ignored (via ${e.transport?.label ?: "?"})"
        RouteEvent.FINAL_ACK -> "Final recipient ${e.peerName ?: e.peerId ?: ""} confirmed (via ${e.transport?.label ?: "?"})"
        RouteEvent.RECEIPT -> "Delivery receipt from ${e.peerName ?: e.peerId ?: "recipient"}${e.transport?.let { " (arrived via ${it.label})" } ?: ""}"
        RouteEvent.EXPIRED -> "Expired; forwarding stopped"
        RouteEvent.CANCELLED -> "Cancelled by user"
    }
}
