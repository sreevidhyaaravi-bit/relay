package com.relay.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relay.app.data.db.MessageEntity
import com.relay.app.model.BROADCAST_ID
import com.relay.app.model.Direction
import com.relay.app.model.MessageType
import com.relay.app.ui.format.RelayFormat
import com.relay.app.ui.theme.LocalStatusColors

/**
 * One message, as shown in the Inbox, Outbox and on the SOS screen. Shows the original sent
 * time (sender's clock) separately from the received time (this phone's clock), the sender's
 * location with its own capture time, and the honest delivery status.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageCard(
    message: MessageEntity,
    nowUtc: Long,
    selfName: String,
    modifier: Modifier = Modifier,
    maxBodyLines: Int = Int.MAX_VALUE,
    onClick: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val sos = message.messageType == MessageType.SOS
    val status = LocalStatusColors.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        shape = RoundedCornerShape(14.dp),
        border = if (sos) BorderStroke(2.dp, status.sos) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (sos && message.direction == Direction.INCOMING) status.sosContainer else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(message.messageType)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = peopleLine(message, selfName),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = message.body,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (sos) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = maxBodyLines,
                overflow = TextOverflow.Ellipsis,
            )
            TimesBlock(message)
            LocationLine(message)
            message.batteryPercent?.let { pct ->
                val low = pct <= 20
                Text(
                    text = "🔋 Sender's battery: $pct%" + if (low) " (low: the phone may switch off soon)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (low) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (low) status.sos else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RelayFormat.clockSkewWarning(message)?.let { warning ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = status.warning, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(warning, style = MaterialTheme.typography.bodySmall, color = status.warning)
                }
            }
            HorizontalDivider()
            StatusBlock(RelayFormat.messageStatus(message, nowUtc))
            if (message.direction == Direction.INCOMING) {
                Text(
                    text = "Route: " + RelayFormat.carriedRoute(message, selfName) + " · ${message.hopCount} hop" +
                        if (message.hopCount == 1) "" else "s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (actions != null) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    actions()
                }
            }
        }
    }
}

private fun peopleLine(m: MessageEntity, selfName: String): String {
    val to = when (m.recipientId) {
        BROADCAST_ID -> "everyone nearby"
        else -> m.recipientName ?: m.recipientId
    }
    return when (m.direction) {
        Direction.OUTGOING -> "To $to"
        Direction.INCOMING -> "From ${m.senderName}" + if (m.recipientId == BROADCAST_ID) " (broadcast)" else ""
        Direction.RELAY -> "${m.senderName} → $to (carried by $selfName)"
    }
}

@Composable
private fun TimesBlock(m: MessageEntity) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        val text = buildString {
            append("Sent ").append(RelayFormat.dateTime(m.createdAtUtc))
            if (m.receivedAtUtc != null) append("  ·  Received ").append(RelayFormat.dateTime(m.receivedAtUtc))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LocationLine(m: MessageEntity) {
    val loc = m.location()
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (loc != null) Icons.Filled.LocationOn else Icons.Filled.LocationOff,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (loc != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            RelayFormat.location(loc, m.createdAtUtc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
