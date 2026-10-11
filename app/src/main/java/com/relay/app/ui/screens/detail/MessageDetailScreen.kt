package com.relay.app.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relay.app.data.db.RouteEvent
import com.relay.app.model.DeliveryState
import com.relay.app.model.Direction
import com.relay.app.ui.components.LabeledValue
import com.relay.app.ui.components.MessageCard
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.components.SectionCard
import com.relay.app.ui.components.rememberNow
import com.relay.app.ui.format.RelayFormat
import com.relay.app.ui.nav.RelayNavigator
import com.relay.app.ui.nav.Routes
import com.relay.app.ui.nav.rememberContainer
import com.relay.app.ui.theme.MonoStyle
import kotlinx.coroutines.launch

/** Full metadata and the real route history of one message, as recorded on this phone. */
@Composable
fun MessageDetailScreen(nav: RelayNavigator, messageId: String) {
    val c = rememberContainer()
    val messageFlow = remember(messageId) { c.repository.message(messageId) }
    val routeFlow = remember(messageId) { c.repository.route(messageId) }
    val message by messageFlow.collectAsStateWithLifecycle(null)
    val route by routeFlow.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val now by rememberNow()
    val scope = rememberCoroutineScope()

    Scaffold(topBar = { RelayTopBar("Message details", onBack = nav::back) }) { padding ->
        val m = message
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (m == null) {
                Text("Message not found on this phone.")
                return@Column
            }
            MessageCard(message = m, nowUtc = now, selfName = settings.displayName)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (m.direction == Direction.INCOMING) {
                    Button(onClick = { nav.open(Routes.compose(recipientId = m.senderId, replyTo = m.id)) }) { Text("Reply") }
                    if (m.latitude != null && m.longitude != null) {
                        OutlinedButton(onClick = { nav.open(Routes.radar(m.id)) }) { Text("🧭 Find sender") }
                    }
                }
                if (m.direction != Direction.INCOMING && m.deliveryState == DeliveryState.PENDING) {
                    Button(onClick = { scope.launch { c.repository.retryNow(m.id) } }) { Text("Retry now") }
                }
                if (m.direction == Direction.OUTGOING && m.deliveryState == DeliveryState.PENDING) {
                    OutlinedButton(onClick = { scope.launch { c.repository.cancel(m.id) } }) { Text("Cancel") }
                }
            }

            SectionCard(title = "Timestamps") {
                LabeledValue("Created (sent)", RelayFormat.dateTime(m.createdAtUtc) + "  (sender's clock)")
                m.receivedAtUtc?.let { LabeledValue("Received here", RelayFormat.dateTime(it) + "  (this phone's clock)") }
                m.relayAckAtUtc?.let { LabeledValue("Relay accepted", RelayFormat.dateTime(it) + " by ${m.relayAckByName ?: m.relayAckBy}") }
                m.uploadedAtUtc?.let { LabeledValue("Uploaded", RelayFormat.dateTime(it)) }
                m.deliveredAtUtc?.let { LabeledValue("Recipient stored it", RelayFormat.dateTime(it) + "  (recipient's clock)") }
                m.acknowledgedAtUtc?.let { LabeledValue("Confirmation here", RelayFormat.dateTime(it)) }
                LabeledValue("Expires", RelayFormat.dateTime(m.expiresAtUtc))
                Text(
                    "Phone clocks are not synchronised, so times from different phones can differ by a few minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "Route") {
                Text(
                    "Recorded on this phone from real transfers and acknowledgements.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(RelayFormat.carriedRoute(m, settings.displayName), style = MaterialTheme.typography.titleSmall)
                LabeledValue("Hops", "${m.hopCount} of max ${m.maxHops}")
                if (route.isEmpty()) Text("No route events recorded.")
                route.forEach { e ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            RelayFormat.time(e.atUtc) + "  " + RelayFormat.routeEventLabel(e),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (e.event == RouteEvent.ATTEMPT_FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            SectionCard(title = "Identifiers") {
                LabeledValue("Message id", m.id)
                LabeledValue("Sender", "${m.senderName} (${m.senderId})")
                LabeledValue("Recipient", "${m.recipientName ?: "-"} (${m.recipientId})")
                m.receivedFromName?.let { LabeledValue("Handed over by", "$it (${m.receivedFromId})") }
                Text("Same id on every phone and every transport; that is how duplicates are ignored.", style = MonoStyle)
            }
        }
    }
}
