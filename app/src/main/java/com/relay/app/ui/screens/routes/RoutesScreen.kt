package com.relay.app.ui.screens.routes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relay.app.model.MessageType
import com.relay.app.relay.ProcessTrigger
import com.relay.app.ui.components.LabeledValue
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.components.SectionCard
import com.relay.app.ui.components.TransportStatusRow
import com.relay.app.ui.components.TypeBadge
import com.relay.app.ui.components.rememberNow
import com.relay.app.ui.format.RelayFormat
import com.relay.app.ui.nav.RelayNavigator
import com.relay.app.ui.nav.Routes
import com.relay.app.ui.nav.rememberContainer
import com.relay.app.ui.screens.home.iconFor
import com.relay.app.ui.theme.LocalStatusColors

/**
 * Screen G: radio and route state, the queue, recent failures and the ACTUAL route history of
 * recent messages. Only real recorded hops are shown here; simulated routes live in Relay Lab.
 */
@Composable
fun RoutesScreen(nav: RelayNavigator) {
    val c = rememberContainer()
    val statuses by c.transportManager.statuses.collectAsStateWithLifecycle()
    val links by c.transportManager.links.collectAsStateWithLifecycle()
    val active by c.transportManager.activeTransport.collectAsStateWithLifecycle()
    val report by c.transportManager.lastReport.collectAsStateWithLifecycle()
    val failures by c.transportManager.recentFailures.collectAsStateWithLifecycle()
    val internet by c.connectivity.internet.collectAsStateWithLifecycle()
    val btOn by c.connectivity.bluetoothOn.collectAsStateWithLifecycle()
    val wifiOn by c.connectivity.wifiOn.collectAsStateWithLifecycle()
    val onWifiNetwork by c.connectivity.wifiNetworkConnected.collectAsStateWithLifecycle()
    val locationOn by c.connectivity.locationServicesOn.collectAsStateWithLifecycle()
    val relayMessage by c.internet.configurationMessage.collectAsStateWithLifecycle()
    val queued by c.repository.queuedCount.collectAsStateWithLifecycle(0)
    val carrying by c.repository.relayCount.collectAsStateWithLifecycle(0)
    val outbox by c.repository.outbox.collectAsStateWithLifecycle(emptyList())
    val inbox by c.repository.inbox.collectAsStateWithLifecycle(emptyList())
    val relayQueue by c.repository.relayQueue.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val now by rememberNow()

    Scaffold(topBar = { RelayTopBar("Connectivity & routes", onBack = nav::back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = "Transports") {
                statuses.forEach { TransportStatusRow(it, icon = iconFor(it.kind)) }
                Text(
                    "A radio being on is not a route. A transport counts only when it has a live, handshaken connection to another RELAY phone (or the internet relay is online).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = "Radios and networks") {
                LabeledValue("Bluetooth", if (btOn) "On" else "Off")
                LabeledValue("Wi-Fi radio", if (wifiOn) "On" else "Off")
                LabeledValue("Wi-Fi network", if (onWifiNetwork) "Connected to an access point" else "Not connected")
                LabeledValue("Internet", internet.detail)
                LabeledValue("Location", if (locationOn) "On" else "Off")
                LabeledValue("Internet relay", relayMessage)
            }

            SectionCard(title = "Connected peers") {
                if (links.isEmpty()) Text("None right now.", style = MaterialTheme.typography.bodyMedium)
                links.forEach { link ->
                    LabeledValue(link.transport.label, "${link.peer.displayName} (${link.peer.deviceId}) since ${RelayFormat.time(link.openedAtUtc)}")
                }
                LabeledValue("Last used", active?.label ?: "none yet")
            }

            SectionCard(title = "Queue") {
                LabeledValue("Your messages waiting", queued.toString())
                LabeledValue("Carrying for others", carrying.toString())
                report?.let { r ->
                    LabeledValue(
                        "Last check",
                        "${RelayFormat.relative(r.startedAtUtc, now)} (${r.trigger.name.lowercase().replace('_', ' ')}): " +
                            "${r.attempts} attempts, ${r.deliveredFinal} delivered, ${r.hopAcked} relay acks, " +
                            "${r.uploaded} uploads, ${r.failedAttempts} failed, ${r.noRoute} without a route",
                    )
                    r.nextRetryAtUtc?.let { LabeledValue("Next timed retry", RelayFormat.relative(it, now)) }
                }
                Button(onClick = { c.transportManager.requestProcessing(ProcessTrigger.MANUAL) }) { Text("Check routes now") }
            }

            SectionCard(title = "Recent transport failures") {
                if (failures.isEmpty()) Text("None since the app started.", style = MaterialTheme.typography.bodyMedium)
                failures.take(15).forEach { f ->
                    Text(
                        "${RelayFormat.time(f.atUtc)} · ${f.kind.label}${f.peerName?.let { " → $it" } ?: ""} · ${f.reason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalStatusColors.current.warning,
                    )
                }
            }

            SectionCard(title = "Recorded routes") {
                Text(
                    "Built only from real transfers recorded on this phone. Simulated routes appear in Relay Lab, never here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val recent = (outbox + inbox + relayQueue).sortedByDescending { it.receivedAtUtc ?: it.createdAtUtc }.take(20)
                if (recent.isEmpty()) Text("No messages yet.")
                recent.forEach { m ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav.open(Routes.detail(m.id)) }
                            .padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (m.messageType == MessageType.SOS) TypeBadge(m.messageType)
                        Text(RelayFormat.carriedRoute(m, settings.displayName), style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${m.body.take(60)} · ${RelayFormat.messageStatus(m, now).text}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
