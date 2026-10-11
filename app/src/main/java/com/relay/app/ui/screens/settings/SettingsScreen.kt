package com.relay.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relay.app.BuildConfig
import com.relay.app.model.MessageType
import com.relay.app.data.RelaySettings
import com.relay.app.protocol.ProtocolLimits
import com.relay.app.ui.components.LabeledValue
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.components.SectionCard
import com.relay.app.ui.components.copyToClipboard
import com.relay.app.ui.nav.RelayNavigator
import com.relay.app.ui.nav.rememberContainer
import com.relay.app.ui.theme.MonoStyle

/** Screen H: identity, location, connectivity, expiry, privacy and testing information. */
@Composable
fun SettingsScreen(nav: RelayNavigator) {
    val c = rememberContainer()
    val context = LocalContext.current
    val s by c.settings.settings.collectAsStateWithLifecycle()
    val relayMessage by c.internet.configurationMessage.collectAsStateWithLifecycle()
    var name by rememberSaveable(s.displayName) { mutableStateOf(s.displayName) }
    var key by rememberSaveable(s.localNetworkKey) { mutableStateOf(s.localNetworkKey) }
    var emergency by rememberSaveable(s.emergencyNumber) { mutableStateOf(s.emergencyNumber) }
    fun update(transform: (RelaySettings) -> RelaySettings) = c.settings.update(transform)

    Scaffold(topBar = { RelayTopBar("Settings", onBack = nav::back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = "This phone") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text("Display name (shown to other phones)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { update { it.copy(displayName = name) } }, enabled = name.isNotBlank() && name != s.displayName) {
                    Text("Save name")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Device ID: ${s.deviceId}", style = MonoStyle, modifier = Modifier.weight(1f))
                    Button(onClick = { copyToClipboard(context, "Device ID", s.deviceId) }) { Text("Copy") }
                }
                Text(
                    "Other phones address you by this ID. It is created once per install and never changes.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard(title = "Location") {
                ToggleRow(
                    title = "Attach location by default",
                    subtitle = "Pre-switches the location toggle on new messages. You still see and can turn it off before every send.",
                    checked = s.shareLocationByDefault,
                ) { on -> update { it.copy(shareLocationByDefault = on) } }
            }

            SectionCard(title = "Alerts") {
                ToggleRow(
                    title = "SOS alarm",
                    subtitle = "Loud alarm when an SOS arrives, on the alarm volume (rings on silent on most phones). Stops after 1 minute or when you tap Stop.",
                    checked = s.sosAlarm,
                ) { on -> update { it.copy(sosAlarm = on) } }
                OutlinedButton(onClick = { c.alerts.testAlarm() }, modifier = Modifier.fillMaxWidth()) { Text("🚨 Test alarm (4 seconds)") }
                ToggleRow(
                    title = "Read messages aloud",
                    subtitle = "The phone speaks incoming messages with its offline voice.",
                    checked = s.readAloud,
                ) { on ->
                    update { it.copy(readAloud = on) }
                    if (on) c.alerts.warmUp()
                }
                ToggleRow(
                    title = "Only read SOS messages",
                    subtitle = "Turn off to hear every message read aloud.",
                    checked = s.readAloudSosOnly,
                    enabled = s.readAloud,
                ) { on -> update { it.copy(readAloudSosOnly = on) } }
                OutlinedButton(onClick = { c.alerts.testVoice() }, modifier = Modifier.fillMaxWidth()) { Text("🗣️ Test voice") }
                OutlinedTextField(
                    value = emergency,
                    onValueChange = { emergency = it.filter { ch -> ch.isDigit() || ch == '+' }.take(15) },
                    label = { Text("Emergency number for the Call button") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { update { it.copy(emergencyNumber = emergency) } }, enabled = emergency.isNotBlank() && emergency != s.emergencyNumber) {
                    Text("Save number")
                }
                Text("112 works across India and the EU. The Call button opens the dialer; you tap Call.", style = MaterialTheme.typography.bodySmall)
            }

            SectionCard(title = "Vibration alerts") {
                ToggleRow(
                    title = "Vibrate when a message arrives",
                    subtitle = "Each type feels different, so you can tell what arrived without looking. " +
                        "SOS vibrates through silent mode on most phones.",
                    checked = s.vibrateOnIncoming,
                ) { on -> update { it.copy(vibrateOnIncoming = on) } }
                if (!c.haptics.isSupported()) {
                    Text("This phone has no vibration motor.", style = MaterialTheme.typography.bodySmall)
                }
                Text("Feel each pattern:", style = MaterialTheme.typography.titleSmall)
                listOf(
                    MessageType.SOS to "🆘 SOS: Morse ··· ––– ··· (twice)",
                    MessageType.URGENT to "⚠️ Urgent: three long pulses",
                    MessageType.COORDINATION to "📋 Coordination: three short taps",
                    MessageType.NORMAL to "💬 Normal: two short taps",
                ).forEach { (type, label) ->
                    OutlinedButton(onClick = { c.haptics.preview(type) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                }
            }

            SectionCard(title = "Connectivity") {
                ToggleRow(
                    title = "Automatic transport (recommended)",
                    subtitle = "RELAY picks Bluetooth, Wi-Fi Direct, local Wi-Fi or the internet relay per message and falls back when one fails. Off: only your first preferred transport is used for phone-to-phone links.",
                    checked = s.autoTransport,
                ) { on -> update { it.copy(autoTransport = on) } }
                Text("Preferred order for direct links", style = MaterialTheme.typography.titleSmall)
                s.preferredOrder.forEachIndexed { index, kind ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${kind.label}", modifier = Modifier.weight(1f))
                        IconButton(onClick = { update { it.copy(preferredOrder = it.preferredOrder.move(index, -1)) } }, enabled = index > 0) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "Move ${kind.label} up")
                        }
                        IconButton(
                            onClick = { update { it.copy(preferredOrder = it.preferredOrder.move(index, 1)) } },
                            enabled = index < s.preferredOrder.lastIndex,
                        ) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "Move ${kind.label} down")
                        }
                    }
                }
                ToggleRow(
                    title = "Relay messages for others",
                    subtitle = "Store and pass on messages addressed to other phones (store-and-forward).",
                    checked = s.relayForOthers,
                ) { on -> update { it.copy(relayForOthers = on) } }
                ToggleRow(
                    title = "Internet relay",
                    subtitle = relayMessage,
                    checked = s.internetRelayEnabled,
                ) { on -> update { it.copy(internetRelayEnabled = on) } }
                ToggleRow(
                    title = "Keep listening in the background",
                    subtitle = "Shows a permanent notification and keeps connections open with the screen off. Uses more battery. Android can still stop it.",
                    checked = s.keepListeningInBackground,
                ) { on -> update { it.copy(keepListeningInBackground = on) } }
            }

            SectionCard(title = "Local Wi-Fi") {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.take(64) },
                    label = { Text("Team network key (same on every phone)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { update { it.copy(localNetworkKey = key) } }, enabled = key != s.localNetworkKey) { Text("Save key") }
                ToggleRow(
                    title = "Use local Wi-Fi",
                    subtitle = if (s.localNetworkKey.isBlank()) "Save a team key first." else "Find RELAY phones on the same Wi-Fi network.",
                    checked = s.localWifiEnabled,
                    enabled = s.localNetworkKey.isNotBlank(),
                ) { on -> update { it.copy(localWifiEnabled = on) } }
                Text(
                    "The key proves phones belong to your team. Messages over local Wi-Fi are not encrypted.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard(title = "Message expiry") {
                Stepper("Normal, urgent and coordination", s.ordinaryExpiryHours, 1, 168, "h") { v -> update { it.copy(ordinaryExpiryHours = v) } }
                Stepper("SOS", s.sosExpiryHours, 1, 168, "h") { v -> update { it.copy(sosExpiryHours = v) } }
                Stepper("Maximum hops", s.maxHops, 1, ProtocolLimits.MAX_HOPS_LIMIT, "") { v -> update { it.copy(maxHops = v) } }
                Text(
                    "Expired messages are kept and marked Expired; RELAY stops forwarding them. Changes apply to new messages.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionCard(title = "Privacy") {
                listOf(
                    "Messages and your location stay on this phone until you send them, and go only to the recipient you choose (or to directly connected phones for a broadcast).",
                    "Relay phones can read messages they carry. RELAY does not provide end-to-end encryption.",
                    "Location is attached only when you switch it on for that message. RELAY never invents coordinates and shows how old a location is.",
                    "Logs never contain message text or coordinates.",
                    "RELAY does not contact emergency services.",
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }

            SectionCard(title = "Testing quick start") {
                listOf(
                    "Install RELAY on two phones and open it on both.",
                    "Turn mobile data off and disconnect from internet Wi-Fi on both phones.",
                    "First time: Phone B taps Nearby devices > Let others find this phone. Phone A: Search for nearby phones > Connect. Afterwards they reconnect by themselves.",
                    "Phone A: New message to Phone B, send. Phone B: check the Inbox.",
                    "Full steps: docs/PHYSICAL_TESTS.md in the project.",
                ).forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodySmall) }
            }

            SectionCard(title = "About") {
                LabeledValue("Version", BuildConfig.VERSION_NAME)
                LabeledValue("Protocol", "v${ProtocolLimits.PROTOCOL_VERSION}")
                LabeledValue("Firebase file", if (BuildConfig.FIREBASE_CONFIGURED) "Included in this build" else "Not included (offline only)")
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, unit: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Button(onClick = { onChange((value - if (value > 24) 12 else 1).coerceAtLeast(min)) }, enabled = value > min) { Text("-") }
        Text(" $value$unit ", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { onChange((value + if (value >= 24) 12 else 1).coerceAtMost(max)) }, enabled = value < max) { Text("+") }
    }
}

private fun <T> List<T>.move(index: Int, delta: Int): List<T> {
    val target = index + delta
    if (target !in indices) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}
