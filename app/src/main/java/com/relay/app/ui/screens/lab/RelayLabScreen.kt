package com.relay.app.ui.screens.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relay.app.data.db.MessageEntity
import com.relay.app.model.MessageType
import com.relay.app.model.TransportKind
import com.relay.app.sim.RelayLab
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.components.SectionCard
import com.relay.app.ui.components.SimulatedBanner
import com.relay.app.ui.components.StatusPill
import com.relay.app.ui.components.TypeBadge
import com.relay.app.ui.format.RelayFormat
import com.relay.app.ui.format.Tone
import com.relay.app.ui.nav.RelayNavigator
import com.relay.app.ui.nav.rememberContainer
import kotlinx.coroutines.launch

/** Screen I: the clearly labelled SIMULATED relay network (logical devices A-D). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RelayLabScreen(nav: RelayNavigator) {
    val lab = rememberContainer().relayLab
    val state by lab.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var from by rememberSaveable { mutableStateOf("A") }
    var to by rememberSaveable { mutableStateOf<String?>("D") }
    var type by rememberSaveable { mutableStateOf(MessageType.SOS) }
    var body by rememberSaveable { mutableStateOf("🆘 Simulated SOS") }
    var selectedRoute by remember { mutableStateOf<String?>(null) }

    fun showRoute(id: String) {
        scope.launch { selectedRoute = lab.routeText(id) }
    }

    Scaffold(topBar = { RelayTopBar("Relay Lab (simulated)", onBack = nav::back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SimulatedBanner()

            SectionCard(title = "Scenarios") {
                RelayLab.Scenario.entries.forEach { s ->
                    Column(Modifier.fillMaxWidth().clickable { scope.launch { lab.load(s) } }.padding(vertical = 4.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Text(s.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SectionCard(title = "Simulated time: ${RelayFormat.dateTime(state.nowUtc)}") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { lab.step() } }) { Text("Step (1 min)") }
                    Button(onClick = { scope.launch { lab.runUntilIdle() } }) { Text("Run until idle") }
                    OutlinedButton(onClick = { scope.launch { lab.advance(1) } }) { Text("+1 h") }
                    OutlinedButton(onClick = { scope.launch { lab.advance(25) } }) { Text("+25 h") }
                    OutlinedButton(onClick = { scope.launch { lab.reset() } }) { Text("Reset") }
                }
                Text("Max hops: ${state.maxHops} · Internet relay holds ${state.cloudMessages} message(s)", style = MaterialTheme.typography.bodySmall)
            }

            SectionCard(title = "Simulated links") {
                lab.pairs.forEach { (a, b) ->
                    val k = "$a-$b"
                    val media = state.links[k].orEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$a ↔ $b", modifier = Modifier.width(56.dp), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(TransportKind.BLUETOOTH, TransportKind.WIFI_DIRECT, TransportKind.LOCAL_WIFI).forEach { m ->
                                FilterChip(
                                    selected = m in media,
                                    onClick = { scope.launch { lab.setLink(a, b, m, m !in media) } },
                                    label = { Text(short(m)) },
                                )
                            }
                        }
                    }
                }
            }

            SectionCard(title = "Send a simulated message") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("From ", modifier = Modifier.width(48.dp))
                    listOf("A", "B", "C", "D").forEach { l -> FilterChip(selected = from == l, onClick = { from = l }, label = { Text(l) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("To ", modifier = Modifier.width(48.dp))
                    listOf("A", "B", "C", "D").filter { it != from }.forEach { l -> FilterChip(selected = to == l, onClick = { to = l }, label = { Text(l) }) }
                    FilterChip(selected = to == null, onClick = { to = null }, label = { Text("All") })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MessageType.entries.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text("${t.symbol} ${t.label}") }) }
                }
                OutlinedTextField(value = body, onValueChange = { body = it.take(200) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(onClick = { scope.launch { lab.send(from, to.takeIf { it != from }, body, type) } }, enabled = body.isNotBlank()) {
                    Text("Queue on Sim $from")
                }
            }

            selectedRoute?.let { route ->
                SectionCard(title = "Route of selected message") {
                    Text(route, style = MaterialTheme.typography.titleSmall)
                }
            }

            state.nodes.forEach { n ->
                SectionCard(title = "${n.name} (${n.deviceId})") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Internet", modifier = Modifier.weight(1f))
                        Switch(checked = n.internetOnline, onCheckedChange = { on -> scope.launch { lab.setInternet(n.label, on) } })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Next send fails (simulated drop)", modifier = Modifier.weight(1f))
                        Switch(checked = n.failNextSend, onCheckedChange = { on -> scope.launch { lab.setFailNext(n.label, on) } })
                    }
                    SimList("Outbox", n.outgoing, state.nowUtc, ::showRoute)
                    SimList("Inbox", n.inbox, state.nowUtc, ::showRoute)
                    SimList("Carrying for others", n.carrying, state.nowUtc, ::showRoute)
                }
            }

            SectionCard(title = "Simulation log") {
                if (state.log.isEmpty()) Text("Nothing yet.")
                state.log.take(60).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun SimList(title: String, messages: List<MessageEntity>, now: Long, onSelect: (String) -> Unit) {
    if (messages.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge)
    messages.forEach { m ->
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { onSelect(m.id) }
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TypeBadge(m.messageType)
                StatusPill("SIMULATED", Tone.SIMULATED)
            }
            Text("${m.senderName} → ${m.recipientName ?: m.recipientId}: ${m.body}", style = MaterialTheme.typography.bodyMedium)
            Text(
                RelayFormat.messageStatus(m, now).text + " · hops ${m.hopCount}/${m.maxHops} · sent ${RelayFormat.time(m.createdAtUtc)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun short(kind: TransportKind) = when (kind) {
    TransportKind.BLUETOOTH -> "BT"
    TransportKind.WIFI_DIRECT -> "Wi-Fi Direct"
    TransportKind.LOCAL_WIFI -> "LAN"
    else -> kind.label
}
