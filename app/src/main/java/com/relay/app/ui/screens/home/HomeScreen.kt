package com.relay.app.ui.screens.home

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relay.app.model.TransportKind
import com.relay.app.permissions.RelayPermissions
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.components.SectionCard
import com.relay.app.ui.components.StatusPill
import com.relay.app.ui.components.TransportStatusRow
import com.relay.app.ui.components.copyToClipboard
import com.relay.app.ui.components.rememberPermissionRequester
import com.relay.app.ui.format.Tone
import com.relay.app.ui.nav.RelayNavigator
import com.relay.app.ui.nav.Routes
import com.relay.app.ui.nav.rememberContainer
import com.relay.app.ui.theme.LocalStatusColors
import com.relay.app.ui.theme.MonoStyle
import com.relay.app.ui.theme.RelayColors
import com.relay.app.ui.screens.compose.QuickStatus
import com.relay.app.util.DeviceActions
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.scale

/** Screen A: overview of identity, real route status, queue counts and navigation. */
@Composable
fun HomeScreen(nav: RelayNavigator) {
    val c = rememberContainer()
    val context = LocalContext.current
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val statuses by c.transportManager.statuses.collectAsStateWithLifecycle()
    val links by c.transportManager.links.collectAsStateWithLifecycle()
    val internet by c.connectivity.internet.collectAsStateWithLifecycle()
    val relayMessage by c.internet.configurationMessage.collectAsStateWithLifecycle()
    val active by c.transportManager.activeTransport.collectAsStateWithLifecycle()
    val queued by c.repository.queuedCount.collectAsStateWithLifecycle(0)
    val delivered by c.repository.deliveredCount.collectAsStateWithLifecycle(0)
    val unread by c.repository.unreadCount.collectAsStateWithLifecycle(0)
    val carrying by c.repository.relayCount.collectAsStateWithLifecycle(0)
    val btFound by c.bluetooth.discovered.collectAsStateWithLifecycle()
    val wdFound by c.wifiDirect.discovered.collectAsStateWithLifecycle()
    val lanFound by c.localWifi.discovered.collectAsStateWithLifecycle()

    var notificationsGranted by remember {
        mutableStateOf(RelayPermissions.allGranted(context, RelayPermissions.notifications()))
    }
    val requestNotifications = rememberPermissionRequester { granted -> notificationsGranted = granted }

    // Android 12+ needs "Nearby devices" before this phone can even LISTEN over Bluetooth, so a
    // receiving phone would silently be unreachable without it. Ask from the Home screen.
    val nearbyPermissions = RelayPermissions.bluetoothConnect() +
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RelayPermissions.wifiDirect() else emptyArray()
    var nearbyGranted by remember { mutableStateOf(RelayPermissions.allGranted(context, nearbyPermissions)) }
    val requestNearby = rememberPermissionRequester {
        nearbyGranted = RelayPermissions.allGranted(context, nearbyPermissions)
        c.transportManager.onPermissionsChanged()
    }

    Scaffold(
        topBar = {
            RelayTopBar(title = "RELAY", actions = {
                IconButton(onClick = { nav.open(Routes.SETTINGS) }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            })
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- identity
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(RelayColors.Navy800, RoundedCornerShape(16.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("RELAY", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Offline-first emergency messaging. Messages travel over whichever route really works " +
                        "(Bluetooth, Wi-Fi Direct, local Wi-Fi or the internet) and wait safely when none does.",
                    color = RelayColors.Blue200,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.size(4.dp))
                Text("This phone: ${settings.displayName}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ID ${settings.deviceId}", color = Color.White, style = MonoStyle)
                    IconButton(onClick = { copyToClipboard(context, "Device ID", settings.deviceId) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy device ID", tint = RelayColors.Blue200)
                    }
                }
            }

            // ---- SOS + emergency call
            // Gentle pulse on the SOS button, unless the user turned animations off (accessibility).
            val animationsOn = remember {
                android.provider.Settings.Global.getFloat(
                    context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
                ) > 0f
            }
            val pulse = if (animationsOn) {
                val p by rememberInfiniteTransition(label = "sos").animateFloat(
                    initialValue = 1f,
                    targetValue = 1.04f,
                    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                    label = "sosPulse",
                )
                p
            } else {
                1f
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { nav.open(Routes.SOS) },
                    colors = ButtonDefaults.buttonColors(containerColor = LocalStatusColors.current.sos, contentColor = Color.White),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(2f).heightIn(min = 72.dp).scale(pulse),
                ) {
                    Text("🆘  SOS", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = { DeviceActions.dialEmergency(context, settings.emergencyNumber) },
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(2.dp, LocalStatusColors.current.sos),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalStatusColors.current.sos),
                    modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📞 Call", fontWeight = FontWeight.Bold)
                        Text(settings.emergencyNumber, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // ---- safety check
            SectionCard(title = "Safety check") {
                Text(
                    "Let people know your status in one tap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOf(QuickStatus.SAFE, QuickStatus.MEDICAL, QuickStatus.TRAPPED, QuickStatus.WATER).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { q ->
                            OutlinedButton(
                                onClick = { nav.open(Routes.compose(type = q.type.name, body = q.body)) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            ) { Text(q.label, style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
            }

            // ---- primary actions
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionTile("New message", Icons.AutoMirrored.Filled.Send, Modifier.weight(1f)) { nav.open(Routes.compose()) }
                ActionTile("Inbox", Icons.Filled.Inbox, Modifier.weight(1f), badge = unread) { nav.open(Routes.INBOX) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionTile("Outbox", Icons.Filled.Outbox, Modifier.weight(1f), badge = queued) { nav.open(Routes.OUTBOX) }
                ActionTile("Nearby devices", Icons.Filled.Devices, Modifier.weight(1f)) { nav.open(Routes.NEARBY) }
            }

            if (!nearbyGranted) {
                SectionCard(title = null, containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bluetooth, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Allow Nearby devices so this phone can send and receive over Bluetooth and Wi-Fi Direct.",
                            Modifier.weight(1f),
                        )
                        TextButton(onClick = { requestNearby(nearbyPermissions) }) { Text("Allow") }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted) {
                SectionCard(title = null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Notifications, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Allow notifications to be alerted about incoming SOS messages.", Modifier.weight(1f))
                        TextButton(onClick = { requestNotifications(RelayPermissions.notifications()) }) { Text("Allow") }
                    }
                }
            }

            // ---- counts
            SectionCard(title = "Messages") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Count("Waiting", queued, Tone.WARNING)
                    Count("Delivered", delivered, Tone.SUCCESS)
                    Count("Unread", unread, Tone.INFO)
                    Count("Carrying", carrying, Tone.NEUTRAL)
                }
                Text(
                    "Waiting = saved on this phone, not yet confirmed by the recipient. Carrying = messages for other people this phone is relaying.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- routes
            SectionCard(title = "Routes right now") {
                statuses.forEach { s -> TransportStatusRow(s, icon = iconFor(s.kind)) }
                Text("Internet: ${internet.detail}", style = MaterialTheme.typography.bodySmall)
                Text("Internet relay: $relayMessage", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Last transport used: ${active?.label ?: "none yet"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { nav.open(Routes.ROUTES) }) { Text("Connectivity & routes") }
            }

            // ---- peers (only genuinely connected / discovered devices)
            SectionCard(title = "Nearby RELAY devices") {
                if (links.isEmpty()) {
                    Text(
                        "No device connected right now. Open Nearby devices to connect over Bluetooth or Wi-Fi Direct.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    links.forEach { link ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(iconFor(link.transport), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(link.peer.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(link.peer.deviceId, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            StatusPill(link.transport.label, Tone.SUCCESS)
                        }
                    }
                }
                TextButton(onClick = { nav.open(Routes.MAP) }) { Text("Open live network map") }
                val found = btFound.size + wdFound.size + lanFound.size
                if (found > 0) {
                    Text(
                        "$found device(s) found by discovery or pairing (not necessarily running RELAY).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ---- more
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { nav.open(Routes.MAP) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) { Text("🗺️ Live map", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = { nav.open(Routes.PEOPLE) },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                ) { Text("👥 People & QR", fontWeight = FontWeight.Bold) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { nav.open(Routes.ROUTES) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.Filled.Timeline, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Routes")
                }
                OutlinedButton(onClick = { nav.open(Routes.LAB) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Icon(Icons.Filled.Science, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Relay Lab")
                }
            }
            Text(
                "RELAY is a communication tool. It does not contact emergency services.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionTile(label: String, icon: ImageVector, modifier: Modifier, badge: Int = 0, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 72.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BadgedBox(badge = { if (badge > 0) Badge { Text(badge.toString()) } }) {
                Icon(icon, contentDescription = null)
            }
            Spacer(Modifier.size(4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Count(label: String, value: Int, tone: Tone) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
        StatusPill(label, tone)
    }
}

fun iconFor(kind: TransportKind): ImageVector = when (kind) {
    TransportKind.BLUETOOTH -> Icons.Filled.Bluetooth
    TransportKind.WIFI_DIRECT -> Icons.Filled.WifiTethering
    TransportKind.LOCAL_WIFI -> Icons.Filled.Router
    TransportKind.INTERNET_RELAY -> Icons.Filled.Cloud
    TransportKind.SIMULATED -> Icons.Filled.Hub
}
