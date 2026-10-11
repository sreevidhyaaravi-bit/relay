package com.relay.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.relay.app.model.MessageType
import com.relay.app.model.TransportStatus
import com.relay.app.ui.format.RelayFormat
import com.relay.app.ui.format.StatusText
import com.relay.app.ui.format.Tone
import com.relay.app.ui.theme.LocalStatusColors

@Composable
fun toneColors(tone: Tone): Pair<Color, Color> {
    val c = LocalStatusColors.current
    return when (tone) {
        Tone.NEUTRAL -> c.neutral to c.neutralContainer
        Tone.INFO -> c.info to c.infoContainer
        Tone.SUCCESS -> c.success to c.successContainer
        Tone.WARNING -> c.warning to c.warningContainer
        Tone.DANGER -> c.sos to c.sosContainer
        Tone.SIMULATED -> c.simulated to c.simulatedContainer
    }
}

/** Small rounded label, e.g. "Connected", "QUEUED — WAITING FOR CONNECTION". */
@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val (fg, bg) = toneColors(tone)
    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** Status line + optional detail, using the shared wording from [RelayFormat.messageStatus]. */
@Composable
fun StatusBlock(status: StatusText, modifier: Modifier = Modifier) {
    Column(modifier) {
        StatusPill(status.text, status.tone)
        status.detail?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.size(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The SOS marker used everywhere (compose, inbox, outbox, notifications, routes, relay lab). */
@Composable
fun SosBadge(modifier: Modifier = Modifier) {
    val c = LocalStatusColors.current
    Text(
        text = "🆘 SOS",
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .semantics { contentDescription = "SOS emergency message" }
            .background(c.sos, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Message type marker: SOS gets the red badge; other types a subtle pill. */
@Composable
fun TypeBadge(type: MessageType, modifier: Modifier = Modifier) {
    when (type) {
        MessageType.SOS -> SosBadge(modifier)
        MessageType.URGENT -> StatusPill("${type.symbol} ${type.label}", Tone.WARNING, modifier)
        MessageType.COORDINATION -> StatusPill("${type.symbol} ${type.label}", Tone.INFO, modifier)
        MessageType.NORMAL -> StatusPill("${type.symbol} ${type.label}", Tone.NEUTRAL, modifier)
    }
}

/** One transport row: name, state pill, detail. */
@Composable
fun TransportStatusRow(status: TransportStatus, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(status.kind.label, style = MaterialTheme.typography.titleSmall)
            val sub = status.lastError ?: status.detail
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(status.state.label, RelayFormat.transportState(status.state))
    }
}

@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Banner that must head every simulated view so it is never mistaken for physical transfer. */
@Composable
fun SimulatedBanner(modifier: Modifier = Modifier) {
    val (fg, bg) = toneColors(Tone.SIMULATED)
    Surface(color = bg, shape = RoundedCornerShape(10.dp), modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Science, contentDescription = null, tint = fg)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("SIMULATED RELAY NETWORK", color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                Text(
                    "Logical devices in memory. Not proof of physical Bluetooth or Wi-Fi transfer.",
                    color = fg,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}
