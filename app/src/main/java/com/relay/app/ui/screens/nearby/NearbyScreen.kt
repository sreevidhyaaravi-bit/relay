package com.relay.app.ui.screens.nearby

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relay.app.model.DiscoveredPeer
import com.relay.app.model.TransportKind
import com.relay.app.permissions.RelayPermissions
import com.relay.app.transport.PeerLink
import com.relay.app.transport.TransferLog
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.components.SectionCard
import com.relay.app.ui.components.StatusPill
import com.relay.app.ui.components.TransportStatusRow
import com.relay.app.ui.components.rememberPermissionRequester
import com.relay.app.ui.format.RelayFormat
import com.relay.app.ui.format.Tone
import com.relay.app.ui.nav.RelayNavigator
import com.relay.app.ui.nav.Routes
import com.relay.app.ui.nav.rememberContainer
import com.relay.app.ui.theme.LocalStatusColors
import com.relay.app.ui.theme.MonoStyle

/** Screen E: discover, connect and disconnect real peers; see the transfer log. */
@Composable
fun NearbyScreen(nav: RelayNavigator) {
    Scaffold(topBar = { RelayTopBar("Nearby devices", onBack = nav::back) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BluetoothSection()
            WifiDirectSection()
            LocalWifiSection(nav)
            TransferLogSection()
            TroubleshootingSection()
        }
    }
}

@Composable
private fun BluetoothSection() {
    val c = rememberContainer()
    val bt = c.bluetooth
    val status by bt.status.collectAsStateWithLifecycle()
    val listening by bt.isListening.collectAsStateWithLifecycle()
    val discovering by bt.isDiscovering.collectAsStateWithLifecycle()
    val devices by bt.discovered.collectAsStateWithLifecycle()
    val links by bt.links.collectAsStateWithLifecycle()
    val knownPeers by c.repository.peers.collectAsStateWithLifecycle(emptyList())

    // RELAY phones this phone has connected to before, by Bluetooth address. They can be
    // reconnected directly (and are, automatically) without searching or "visible" mode.
    val relayByAddress = knownPeers.filter { it.bluetoothAddress != null }.associateBy { it.bluetoothAddress!! }
    val listed = (
        devices + relayByAddress.values
            .filter { p -> devices.none { it.address == p.bluetoothAddress } }
            .map { p -> DiscoveredPeer(TransportKind.BLUETOOTH, p.bluetoothAddress!!, p.displayName, "Seen ${RelayFormat.relative(p.lastSeenAtUtc, System.currentTimeMillis())}") }
        )
        .map { d ->
            val relay = relayByAddress[d.address]
            if (relay == null) d else d.copy(name = relay.displayName, detail = "RELAY phone · ${d.detail}")
        }
        .sortedByDescending { it.address in relayByAddress }

    val requestConnect = rememberPermissionRequester { c.transportManager.onPermissionsChanged() }
    val requestDiscovery = rememberPermissionRequester { granted -> if (granted) bt.startDiscovery() }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        c.transportManager.onPermissionsChanged()
    }
    val discoverableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val requestAdvertise = rememberPermissionRequester { granted ->
        if (granted) runCatching { discoverableLauncher.launch(bt.discoverableIntent()) }
    }

    SectionCard(title = "Bluetooth") {
        TransportStatusRow(status, icon = Icons.Filled.Bluetooth)
        if (!bt.isSupported()) return@SectionCard
        if (!bt.hasConnectPermission()) {
            Button(onClick = { requestConnect(RelayPermissions.bluetoothConnect()) }) { Text("Allow Nearby devices permission") }
            return@SectionCard
        }
        if (!bt.isEnabled()) {
            Button(onClick = { runCatching { enableLauncher.launch(bt.enableIntent()) } }) { Text("Turn on Bluetooth") }
            return@SectionCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Listen for connections", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (listening) "Other phones can connect to this one" else "Turn on so other phones can connect",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = listening, onCheckedChange = { on -> if (on) bt.startListening() else bt.stopListening() })
        }
        Text(
            "First time only: Android lets a phone be found only while it is visible. On the OTHER phone tap " +
                "\"Let others find this phone\", then search here. After one connection, the two phones reconnect " +
                "by themselves whenever they are near each other with RELAY open.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (discovering) {
                OutlinedButton(onClick = { bt.cancelDiscovery() }, modifier = Modifier.fillMaxWidth()) { Text("Stop search") }
            } else {
                Button(onClick = { requestDiscovery(RelayPermissions.bluetoothDiscovery()) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Search for nearby phones")
                }
            }
            OutlinedButton(onClick = { requestAdvertise(RelayPermissions.bluetoothAdvertise()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Let others find this phone (5 min)")
            }
        }
        if (discovering) LinearProgressIndicator(Modifier.fillMaxWidth())

        LinksList(links, onDisconnect = { bt.disconnect(it.id) })
        if (listed.isEmpty()) {
            Text(
                "No phones found yet. Make sure RELAY is open on the other phone and that it tapped \"Let others find this phone\", " +
                    "then search again. Pairing the phones in Android Settings > Bluetooth also works.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        listed.forEach { d ->
            val linked = links.any { it.remoteLabel == d.address }
            DeviceRow(d, actionLabel = if (linked) "Connected" else "Connect", enabled = !linked) { bt.connect(d.address) }
        }
        Text(
            "If Android asks to pair, accept on both phones (one time only).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WifiDirectSection() {
    val c = rememberContainer()
    val wd = c.wifiDirect
    val status by wd.status.collectAsStateWithLifecycle()
    val discovering by wd.isDiscovering.collectAsStateWithLifecycle()
    val peers by wd.discovered.collectAsStateWithLifecycle()
    val links by wd.links.collectAsStateWithLifecycle()
    val group by wd.groupInfo.collectAsStateWithLifecycle()
    val requestPermission = rememberPermissionRequester { granted ->
        c.transportManager.onPermissionsChanged()
        if (granted) wd.startDiscovery()
    }

    SectionCard(title = "Wi-Fi Direct") {
        TransportStatusRow(status, icon = Icons.Filled.WifiTethering)
        if (!wd.isSupported()) return@SectionCard
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (discovering) {
                OutlinedButton(onClick = { wd.stopDiscovery() }) { Text("Stop search") }
            } else {
                Button(onClick = { requestPermission(RelayPermissions.wifiDirect()) }) { Text("Discover peers") }
            }
            if (group != null || links.isNotEmpty()) {
                OutlinedButton(onClick = { wd.disconnect() }) { Text("Disconnect") }
            }
        }
        if (discovering) LinearProgressIndicator(Modifier.fillMaxWidth())
        group?.let { g ->
            Text(
                "Group: ${if (g.isGroupOwner) "this phone is the owner" else "joined, owner at ${g.groupOwnerAddress}"}" +
                    (g.networkName?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LinksList(links, onDisconnect = { wd.disconnect() })
        peers.forEach { d ->
            DeviceRow(d, actionLabel = "Connect", enabled = d.connectable && links.isEmpty()) { wd.connect(d.address) }
        }
        val auto by wd.autoDiscover.collectAsStateWithLifecycle()
        Text(
            (if (auto) "This phone stays findable automatically while RELAY is open. " else "") +
                "Works with no internet and no router: keep Wi-Fi ON on both phones and RELAY open. " +
                "Tap Connect on ONE phone only; the other phone may show an invitation to accept. " +
                "Allow the permission once on each phone (on Android 12 and lower Location must also be ON).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocalWifiSection(nav: RelayNavigator) {
    val c = rememberContainer()
    val lan = c.localWifi
    val status by lan.status.collectAsStateWithLifecycle()
    val active by lan.isActive.collectAsStateWithLifecycle()
    val address by lan.localAddress.collectAsStateWithLifecycle()
    val found by lan.discovered.collectAsStateWithLifecycle()
    val links by lan.links.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("47322") }

    SectionCard(title = "Local Wi-Fi (optional)") {
        TransportStatusRow(status, icon = Icons.Filled.Router)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use local Wi-Fi", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (settings.localNetworkKey.isBlank()) "Needs a team network key (Settings)" else "Phones on the same Wi-Fi with the same key",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = settings.localWifiEnabled,
                onCheckedChange = { on -> c.settings.update { it.copy(localWifiEnabled = on) } },
                enabled = settings.localNetworkKey.isNotBlank(),
            )
        }
        if (settings.localNetworkKey.isBlank()) {
            TextButton(onClick = { nav.open(Routes.SETTINGS) }) { Text("Set team network key") }
            return@SectionCard
        }
        if (!active) return@SectionCard
        address?.let { Text("This phone: $it", style = MonoStyle) }
        LinksList(links, onDisconnect = { lan.disconnectAll() })
        found.forEach { d ->
            val linked = links.any { it.remoteLabel == d.address }
            DeviceRow(d, actionLabel = if (linked) "Connected" else "Connect", enabled = !linked) { lan.connect(d.address) }
        }
        Text("Connect by address (if discovery is blocked):", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("IP address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Button(onClick = { lan.connectManual(host, port.toIntOrNull() ?: 0) }, enabled = host.isNotBlank()) { Text("Connect") }
        Text(
            "Same Wi-Fi does not always mean phones can talk: many routers block device-to-device traffic. If so, use Wi-Fi Direct.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LinksList(links: List<PeerLink>, onDisconnect: (PeerLink) -> Unit) {
    links.filter { it.isOpen }.forEach { link ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(link.peer.displayName, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    StatusPill("Connected", Tone.SUCCESS)
                }
                Text("${link.peer.deviceId} · ${link.remoteLabel}", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onDisconnect(link) }) { Text("Disconnect") }
        }
    }
}

@Composable
private fun DeviceRow(device: DiscoveredPeer, actionLabel: String, enabled: Boolean, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge)
            Text("${device.detail} · ${device.address}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
    }
}

@Composable
private fun TransferLogSection() {
    val c = rememberContainer()
    val entries by c.transferLog.entries.collectAsStateWithLifecycle()
    val colors = LocalStatusColors.current
    SectionCard(title = "Transfer log") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Connection and transfer events on this phone (message text is never logged).",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { c.transferLog.clear() }) { Text("Clear") }
        }
        if (entries.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
        entries.take(80).forEach { e ->
            val color = when (e.level) {
                TransferLog.Level.INFO -> MaterialTheme.colorScheme.onSurface
                TransferLog.Level.WARN -> colors.warning
                TransferLog.Level.ERROR -> colors.sos
            }
            Text(
                "${RelayFormat.time(e.atUtc)} [${e.kind?.label ?: "Core"}] ${e.text}",
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}

@Composable
private fun TroubleshootingSection() {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionCard(title = "Troubleshooting") {
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide tips" else "Show tips") }
        if (!open) return@SectionCard
        listOf(
            "Bluetooth will not connect: open RELAY on BOTH phones, check Listen is on for the other phone, then tap Connect again. If it still fails, pair the phones in Android Settings > Bluetooth first.",
            "\"Handshake timed out\": the other phone is reachable but RELAY is not running there. Open it and retry.",
            "Search finds nothing: on the other phone tap \"Let others find this phone\" (visible for 5 minutes), then search again. Only needed the first time. On Android 11 and lower, turn Location on.",
            "Wi-Fi Direct: both phones need Wi-Fi ON (no network needed). Disconnect from hotspots if it keeps failing. Accept the invitation on the other phone.",
            "Wi-Fi Direct says BUSY: wait 10 seconds, or toggle Wi-Fi off and on.",
            "Messages stay queued: a message is only sent when a real route exists. Watch the transfer log; it never pretends.",
            "Battery savers can pause RELAY in the background. Keep it open during demos, or enable Keep listening in Settings.",
        ).forEach { tip ->
            Text("• $tip", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
        }
    }
}
