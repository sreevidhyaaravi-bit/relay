package com.relay.app.ui.screens.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relay.app.model.BROADCAST_ID
import com.relay.app.model.MessageType
import com.relay.app.protocol.ProtocolLimits
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.components.SectionCard
import com.relay.app.ui.components.SosBadge
import com.relay.app.ui.nav.RelayNavigator
import com.relay.app.ui.nav.Routes
import com.relay.app.ui.nav.rememberContainer
import com.relay.app.ui.nav.relayViewModel

/** Screen B: write a message, pick recipient/priority/location, then save + send (or queue). */
@Composable
fun ComposeScreen(nav: RelayNavigator, recipientId: String?, typeName: String?, replyToId: String?, presetBody: String? = null) {
    val c = rememberContainer()
    val vm = relayViewModel(key = "compose-$recipientId-$typeName-$replyToId-$presetBody") { ComposeViewModel(it) }
    LaunchedEffect(Unit) { vm.init(recipientId, typeName, replyToId, defaultBody = presetBody.orEmpty()) }

    val peers by c.repository.peers.collectAsStateWithLifecycle(emptyList())
    val links by c.transportManager.links.collectAsStateWithLifecycle()
    val internetStatus by c.internet.status.collectAsStateWithLifecycle()
    val options = recipientOptions(peers, links)
    val groups by c.groups.groups.collectAsStateWithLifecycle()
    val target = vm.effectiveRecipient()?.first
    val internetUsable = c.internet.isUsable()
    val hasDirect = target != null && links.any { it.peer.deviceId == target }

    Scaffold(topBar = { RelayTopBar(if (replyToId != null) "Reply" else "New message", onBack = nav::back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            RecipientPicker(vm, options, groups = groups, onAddPeople = { nav.open(Routes.PEOPLE) })

            SectionCard(title = "Priority") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MessageType.entries.forEach { t ->
                        FilterChip(
                            selected = vm.type == t,
                            onClick = { vm.type = t },
                            label = { Text("${t.symbol} ${t.label}") },
                        )
                    }
                }
                if (vm.type == MessageType.SOS) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SosBadge()
                        Text(
                            "SOS messages are sent before all others and marked with 🆘 on every screen.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            SectionCard(title = "Quick messages") {
                QuickStatus.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { q ->
                            FilterChip(
                                selected = vm.body == q.body,
                                onClick = {
                                    vm.body = q.body
                                    vm.type = q.type
                                },
                                label = { Text(q.label) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }

            SectionCard(title = "Message") {
                OutlinedTextField(
                    value = vm.body,
                    onValueChange = { vm.body = it.take(ProtocolLimits.MAX_BODY_CHARS) },
                    placeholder = { Text("Type your message") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("${vm.body.length} / ${ProtocolLimits.MAX_BODY_CHARS}") },
                )
            }

            LocationAttachment(vm, isBroadcast = target == BROADCAST_ID)

            SectionCard(title = "What happens next") {
                val group = vm.selectedGroup
                Text(
                    if (group != null) "Goes to ${group.memberIds.size} people in ${group.name}. Each copy is delivered and confirmed separately (see Outbox)."
                    else routeHint(target, links, internetUsable),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Internet relay: ${internetStatus.detail}", style = MaterialTheme.typography.bodySmall)
            }

            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = {
                    vm.send { stored ->
                        nav.back()
                        nav.open(if (vm.lastSentCount > 1) Routes.OUTBOX else Routes.detail(stored.id))
                    }
                },
                enabled = !vm.sending && vm.body.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(if (hasDirect || target == BROADCAST_ID || vm.selectedGroup != null) "Send" else "Save and send when possible")
            }
        }
    }
}
