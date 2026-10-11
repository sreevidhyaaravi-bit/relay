package com.relay.app.ui.screens.sos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relay.app.data.db.MessageEntity
import com.relay.app.model.BROADCAST_ID
import com.relay.app.model.DeliveryState
import com.relay.app.model.MessageType
import com.relay.app.protocol.ProtocolLimits
import com.relay.app.ui.components.MessageCard
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.components.SectionCard
import com.relay.app.ui.components.rememberNow
import com.relay.app.ui.format.RelayFormat
import com.relay.app.ui.nav.RelayNavigator
import com.relay.app.ui.nav.Routes
import com.relay.app.ui.nav.rememberContainer
import com.relay.app.ui.nav.relayViewModel
import com.relay.app.ui.screens.compose.ComposeViewModel
import com.relay.app.ui.screens.compose.LocationAttachment
import com.relay.app.ui.screens.compose.RecipientPicker
import com.relay.app.ui.screens.compose.recipientOptions
import com.relay.app.ui.screens.compose.routeHint
import com.relay.app.ui.theme.LocalStatusColors
import com.relay.app.util.DeviceActions
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * Screen F: a large SOS action with a confirmation step (no accidental sends), editable text,
 * opt-in location, then live status of the SOS: queue state, transport and acknowledgements.
 */
@Composable
fun SosScreen(nav: RelayNavigator) {
    val c = rememberContainer()
    val vm = relayViewModel(key = "sos") { ComposeViewModel(it) }
    LaunchedEffect(Unit) { vm.init(null, MessageType.SOS.name, null, defaultBody = "🆘 SOS: I need help.") }

    val peers by c.repository.peers.collectAsStateWithLifecycle(emptyList())
    val links by c.transportManager.links.collectAsStateWithLifecycle()
    val active by c.transportManager.activeTransport.collectAsStateWithLifecycle()
    val now by rememberNow()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var confirming by rememberSaveable { mutableStateOf(false) }
    var sentId by rememberSaveable { mutableStateOf<String?>(null) }
    val target = vm.effectiveRecipient()

    Scaffold(topBar = { RelayTopBar("🆘 SOS", onBack = nav::back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // remember() keeps one Flow per message id; a new Flow each recomposition would loop.
            val sentFlow = remember(sentId) { sentId?.let { id -> c.repository.message(id) } }
            val sent: MessageEntity? = sentFlow?.collectAsStateWithLifecycle(null)?.value
            if (sent != null) {
                Text("Your SOS", style = MaterialTheme.typography.titleLarge)
                MessageCard(
                    message = sent,
                    nowUtc = now,
                    selfName = c.settings.current.displayName,
                    onClick = { nav.open(Routes.detail(sent.id)) },
                    actions = {
                        if (sent.deliveryState == DeliveryState.PENDING) {
                            TextButton(onClick = { scope.launch { c.repository.retryNow(sent.id) } }) { Text("Retry now") }
                            TextButton(onClick = { scope.launch { c.repository.cancel(sent.id) } }) { Text("Cancel") }
                        }
                        TextButton(onClick = { nav.open(Routes.detail(sent.id)) }) { Text("Route details") }
                    },
                )
                Text(
                    "Created ${RelayFormat.dateTime(sent.createdAtUtc)}. Last transport used: ${active?.label ?: "none yet"}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { sentId = null }) { Text("Send another SOS") }
            } else {
                Text(
                    "Press the button to review and send an emergency message. It is saved on this phone first " +
                        "and sent before all other messages over any route that works.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { confirming = true },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = LocalStatusColors.current.sos, contentColor = Color.White),
                    modifier = Modifier
                        .size(180.dp)
                        .semantics { contentDescription = "Review and send SOS" },
                ) {
                    Text("🆘\nSOS", fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }

                OutlinedButton(
                    onClick = { DeviceActions.dialEmergency(context, settings.emergencyNumber) },
                    border = androidx.compose.foundation.BorderStroke(2.dp, LocalStatusColors.current.sos),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalStatusColors.current.sos),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("📞 Call emergency services (${settings.emergencyNumber})", fontWeight = FontWeight.Bold) }
                OutlinedButton(onClick = { nav.open(Routes.FIND_ME) }, modifier = Modifier.fillMaxWidth()) {
                    Text("🔦 Find me mode (flashlight + screen flash SOS)", fontWeight = FontWeight.Bold)
                }
                Text(
                    "Calls need a mobile network. Emergency calls can use any carrier's network in range.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionCard(title = "Emergency message") {
                    OutlinedTextField(
                        value = vm.body,
                        onValueChange = { vm.body = it.take(ProtocolLimits.MAX_BODY_CHARS) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val groups by c.groups.groups.collectAsStateWithLifecycle()
                RecipientPicker(vm, recipientOptions(peers, links), groups = groups, onAddPeople = { nav.open(Routes.PEOPLE) })
                LocationAttachment(vm, isBroadcast = target?.first == BROADCAST_ID)
                SectionCard(title = "What happens next") {
                    Text(routeHint(target?.first, links, c.internet.isUsable()), style = MaterialTheme.typography.bodyMedium)
                }
                vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "RELAY is a communication tool. It does not contact emergency services or guarantee delivery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirming) {
        val loc = vm.locationToAttach()
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Send this SOS?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("To: " + (vm.selectedGroup?.let { "👥 ${it.name} (${it.memberIds.size} people)" } ?: target?.second ?: target?.first ?: "no recipient chosen"))
                    Text("Message: ${vm.body.trim()}")
                    Text(
                        "Location: " + if (!vm.attachLocation) "not attached" else RelayFormat.location(loc, System.currentTimeMillis()),
                    )
                    if (target == null && vm.selectedGroup == null) Text("Choose a recipient first.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirming = false
                        vm.send { stored -> sentId = stored.id }
                    },
                    enabled = (target != null || vm.selectedGroup != null) && vm.body.isNotBlank() && !vm.sending,
                    colors = ButtonDefaults.buttonColors(containerColor = LocalStatusColors.current.sos, contentColor = Color.White),
                ) { Text("Send SOS") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
