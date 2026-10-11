package com.relay.app.ui.screens.compose

import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.TextButton
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.relay.app.AppContainer
import com.relay.app.data.db.MessageEntity
import com.relay.app.data.RelayGroup
import com.relay.app.data.db.PeerEntity
import com.relay.app.location.LocationResult
import com.relay.app.model.BROADCAST_ID
import com.relay.app.model.LocationInfo
import com.relay.app.model.LocationSource
import com.relay.app.model.MessageDraft
import com.relay.app.model.MessageType
import com.relay.app.model.TransportKind
import com.relay.app.permissions.RelayPermissions
import com.relay.app.protocol.MessageValidator
import com.relay.app.protocol.ProtocolLimits
import com.relay.app.transport.PeerLink
import com.relay.app.ui.components.SectionCard
import com.relay.app.ui.components.rememberPermissionRequester
import com.relay.app.ui.format.RelayFormat
import com.relay.app.ui.theme.MonoStyle
import com.relay.app.util.DeviceActions
import kotlinx.coroutines.launch

/**
 * State and actions shared by the Compose and SOS screens. The message is always saved on this
 * phone first (repository.send) and only then handed to the transport manager.
 */
class ComposeViewModel(private val c: AppContainer) : ViewModel() {
    var recipientId by mutableStateOf<String?>(null)
    /** When set, the message goes to every member of this group (one message each). */
    var selectedGroup by mutableStateOf<RelayGroup?>(null)
    /** How many messages the last send created (more than 1 for a group). */
    var lastSentCount by mutableIntStateOf(0)
    var recipientName by mutableStateOf<String?>(null)
    var manualRecipient by mutableStateOf("")
    var body by mutableStateOf("")
    var type by mutableStateOf(MessageType.NORMAL)

    var attachLocation by mutableStateOf(c.settings.current.shareLocationByDefault)
    var useManualLocation by mutableStateOf(false)
    var manualLocation by mutableStateOf("")
    var deviceLocation by mutableStateOf<LocationInfo?>(null)
    var locationApproximate by mutableStateOf(false)
    var locationMessage by mutableStateOf<String?>(null)
    var locating by mutableStateOf(false)
    /** Seconds spent waiting for the current fix (shown so people know it is still working). */
    var locatingSeconds by mutableIntStateOf(0)
    /** The phone's last known location, offered while waiting. Sent with the time it was saved. */
    var lastKnownLocation by mutableStateOf<LocationInfo?>(null)
    private var locateJob: Job? = null

    var sending by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var initialised = false

    fun init(recipient: String?, typeName: String?, replyToId: String?, defaultBody: String = "") {
        if (initialised) return
        initialised = true
        MessageType.fromName(typeName)?.let { type = it }
        if (defaultBody.isNotEmpty()) body = defaultBody
        if (recipient != null) selectRecipient(recipient, null)
        if (replyToId != null) {
            viewModelScope.launch {
                c.repository.getMessage(replyToId)?.let { original ->
                    selectRecipient(original.senderId, original.senderName)
                }
            }
        }
    }

    fun selectRecipient(id: String, name: String?) {
        recipientId = id
        recipientName = name
        selectedGroup = null
        error = null
    }

    fun selectGroup(group: RelayGroup) {
        selectedGroup = group
        recipientId = null
        recipientName = null
        manualRecipient = ""
        error = null
    }

    /** The recipient actually used: a picked one, or a valid manually typed device id. */
    fun effectiveRecipient(): Pair<String, String?>? {
        recipientId?.let { return it to recipientName }
        val typed = manualRecipient.trim()
        return if (MessageValidator.isValidDeviceId(typed)) typed to null else null
    }

    fun fetchLocation() {
        locateJob?.cancel()
        lastKnownLocation = c.location.lastKnown()
        locateJob = viewModelScope.launch {
            locating = true
            locationMessage = null
            locatingSeconds = 0
            val ticker = launch {
                while (true) {
                    delay(1_000L)
                    locatingSeconds++
                }
            }
            val result = c.location.currentLocation()
            ticker.cancel()
            when (result) {
                is LocationResult.Success -> {
                    deviceLocation = result.location
                    locationApproximate = result.approximate
                }
                LocationResult.NoPermission -> locationMessage = "Location permission not granted. You can describe your location instead."
                LocationResult.ServicesDisabled -> locationMessage = "Location is turned off in the phone settings. Turn it on, or describe your location instead."
                is LocationResult.Unavailable -> locationMessage = result.reason
            }
            locating = false
        }
    }

    fun stopLocating() {
        locateJob?.cancel()
        locating = false
    }

    /** Uses the phone's last known location. It keeps the time it was saved, so receivers see how old it is. */
    fun useLastKnownLocation() {
        val last = lastKnownLocation ?: c.location.lastKnown()
        if (last == null) {
            locationMessage = "No earlier location is stored on this phone. Describe your location instead."
            return
        }
        stopLocating()
        deviceLocation = last
        locationApproximate = false
        locationMessage = "Using your last known location, saved at ${RelayFormat.time(last.capturedAtUtc)}. You may have moved since; the receiver sees that time."
    }

    /** Location to attach, or null. Nothing is attached unless the user turned it on. */
    fun locationToAttach(): LocationInfo? {
        if (!attachLocation) return null
        if (useManualLocation) {
            val text = manualLocation.trim()
            return if (text.isEmpty()) null else LocationInfo(null, null, null, null, text.take(ProtocolLimits.MAX_LABEL_CHARS), LocationSource.MANUAL)
        }
        return deviceLocation
    }

    fun send(onSent: (MessageEntity) -> Unit) {
        val group = selectedGroup
        val me = c.settings.current.deviceId
        val targets: List<Pair<String, String?>> = if (group != null) {
            group.memberIds.zip(group.memberNames).filter { it.first != me }
        } else {
            listOfNotNull(effectiveRecipient())
        }
        if (targets.isEmpty()) {
            error = if (group != null) "This group has no other members." else "Choose a recipient, or type a device id like RLY-1A2B3C4D."
            return
        }
        val text = if (group != null) "👥 ${group.name}: $body" else body
        // The spec is explicit: never block a message because location is unavailable.
        // Send it without location; the receiver sees "Location unavailable".
        val location = locationToAttach()
        val missingLocation = attachLocation && location == null
        stopLocating()
        sending = true
        error = null
        viewModelScope.launch {
            try {
                val battery = DeviceActions.batteryPercent(c.appContext)
                // A group message is one message per member, each delivered and confirmed separately.
                val sent = targets.map { (id, name) ->
                    c.repository.send(MessageDraft(id, name, text, type, location, battery))
                }
                lastSentCount = sent.size
                val stored = sent.first()
                if (missingLocation) {
                    Toast.makeText(c.appContext, "Sent without location: none was available.", Toast.LENGTH_LONG).show()
                }
                onSent(stored)
            } catch (e: IllegalArgumentException) {
                error = e.message
            } catch (e: Exception) {
                error = "Could not save the message: ${e.message}"
            } finally {
                sending = false
            }
        }
    }
}

/** One-tap status messages (safety check). The type sets priority: Trapped is an SOS. */
enum class QuickStatus(val label: String, val body: String, val type: MessageType) {
    SAFE("✅ I'm safe", "✅ I'm safe.", MessageType.COORDINATION),
    MEDICAL("🩹 Need medical help", "🩹 I need medical help.", MessageType.URGENT),
    TRAPPED("🚧 Trapped", "🚧 I'm trapped and need rescue.", MessageType.SOS),
    WATER("💧 Need water / food", "💧 I need water and food.", MessageType.URGENT),
}

/** A selectable recipient: a device we have handshaken with, or broadcast. */
data class RecipientOption(
    val id: String,
    val name: String,
    val detail: String,
    val connectedVia: List<TransportKind>,
)

fun recipientOptions(peers: List<PeerEntity>, links: List<PeerLink>): List<RecipientOption> {
    val byPeer = links.groupBy { it.peer.deviceId }
    val known = peers.map { p ->
        val via = byPeer[p.deviceId].orEmpty().map { it.transport }.distinct()
        RecipientOption(
            id = p.deviceId,
            name = p.displayName,
            detail = when {
                via.isNotEmpty() -> "Connected now via " + via.joinToString { it.label }
                p.lastTransport == null -> "Added by QR code"
                else -> "Last seen ${RelayFormat.dateTime(p.lastSeenAtUtc)}"
            },
            connectedVia = via,
        )
    }
    // Links whose peer row is not stored yet (just connected).
    val extra = byPeer.filterKeys { id -> peers.none { it.deviceId == id } }.map { (id, ls) ->
        RecipientOption(id, ls.first().peer.displayName, "Connected now via " + ls.joinToString { it.transport.label }, ls.map { it.transport })
    }
    return (extra + known).sortedWith(compareByDescending<RecipientOption> { it.connectedVia.isNotEmpty() }.thenBy { it.name.lowercase() })
}

@Composable
fun RecipientPicker(
    vm: ComposeViewModel,
    options: List<RecipientOption>,
    allowBroadcast: Boolean = true,
    groups: List<RelayGroup> = emptyList(),
    onAddPeople: (() -> Unit)? = null,
) {
    SectionCard(title = "To") {
        groups.forEach { g ->
            RecipientRow(
                selected = vm.selectedGroup?.id == g.id,
                title = "👥 ${g.name}",
                subtitle = "${g.memberIds.size} people: " + g.memberNames.joinToString(),
                onClick = { vm.selectGroup(g) },
            )
        }
        if (options.isEmpty()) {
            Text(
                "No known devices yet. Connect to a phone in Nearby devices once, or type its device id below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        options.forEach { option ->
            RecipientRow(
                selected = vm.recipientId == option.id,
                title = option.name,
                subtitle = "${option.id} · ${option.detail}",
                onClick = { vm.selectRecipient(option.id, option.name) },
            )
        }
        if (allowBroadcast) {
            RecipientRow(
                selected = vm.recipientId == BROADCAST_ID,
                title = "Everyone nearby (broadcast)",
                subtitle = "Reaches every phone directly connected now or later, until it expires. An SOS sent to everyone is also passed on phone to phone across the crowd.",
                onClick = { vm.selectRecipient(BROADCAST_ID, "Everyone nearby") },
            )
        }
        if (onAddPeople != null) {
            TextButton(onClick = onAddPeople) { Text("➕ Add someone by QR code, or make a group") }
        }
        OutlinedTextField(
            value = vm.manualRecipient,
            onValueChange = {
                vm.manualRecipient = it.uppercase().take(ProtocolLimits.MAX_ID_CHARS)
                if (it.isNotBlank()) {
                    vm.recipientId = null
                    vm.recipientName = null
                }
            },
            label = { Text("Or type a device id (e.g. RLY-1A2B3C4D)") },
            singleLine = true,
            textStyle = MonoStyle,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            isError = vm.manualRecipient.isNotBlank() && !MessageValidator.isValidDeviceId(vm.manualRecipient.trim()),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RecipientRow(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Opt-in location attachment with preview, capture time, staleness and a manual fallback. */
@Composable
fun LocationAttachment(vm: ComposeViewModel, isBroadcast: Boolean) {
    val context = LocalContext.current
    val requestLocation = rememberPermissionRequester { granted ->
        // Approximate-only is fine: the user may grant coarse but not precise location.
        if (granted || RelayPermissions.hasAnyLocation(context)) {
            vm.fetchLocation()
        } else {
            vm.locationMessage = "Location permission denied. You can describe your location manually instead."
            vm.useManualLocation = true
        }
    }
    SectionCard(title = "Location") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Attach my location", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (vm.attachLocation) {
                        "Included in this message only. If no location is available, the message is still sent, marked \"Location unavailable\"."
                    } else {
                        "Not attached. Nothing about your location is sent."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = vm.attachLocation, onCheckedChange = { vm.attachLocation = it })
        }
        if (!vm.attachLocation) return@SectionCard
        if (isBroadcast) {
            Text(
                "Warning: this is a broadcast. Every nearby phone that receives it will see your location.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    vm.useManualLocation = false
                    requestLocation(RelayPermissions.location())
                },
                enabled = !vm.locating,
            ) { Text(if (vm.deviceLocation == null) "Get my location" else "Refresh") }
            OutlinedButton(onClick = { vm.useManualLocation = !vm.useManualLocation }) {
                Text(if (vm.useManualLocation) "Use GPS instead" else "Describe manually")
            }
        }
        if (vm.locating) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Getting your location… ${vm.locatingSeconds} s. GPS works without internet but needs a view " +
                        "of the sky and can take up to a minute.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.stopLocating() }) { Text("Stop") }
            }
        }
        val earlier = vm.lastKnownLocation
        if (!vm.useManualLocation && vm.deviceLocation == null && earlier != null && (vm.locating || vm.locationMessage != null)) {
            OutlinedButton(onClick = { vm.useLastKnownLocation() }) {
                Text("Use my last known location (${RelayFormat.relative(earlier.capturedAtUtc, System.currentTimeMillis())})")
            }
        }
        if (vm.useManualLocation) {
            OutlinedTextField(
                value = vm.manualLocation,
                onValueChange = { vm.manualLocation = it.take(ProtocolLimits.MAX_LABEL_CHARS) },
                label = { Text("Where are you? (e.g. Library, 2nd floor, north stairs)") },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            val loc = vm.deviceLocation
            if (loc != null) {
                Text(RelayFormat.location(loc, System.currentTimeMillis()), style = MaterialTheme.typography.bodyMedium)
                if (vm.locationApproximate) {
                    Text("Approximate location only (precise permission not granted).", style = MaterialTheme.typography.bodySmall)
                }
            }
            vm.locationMessage?.let { msg ->
                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Short text describing what will happen to the message right now. Never claims success. */
fun routeHint(recipientId: String?, links: List<PeerLink>, internetUsable: Boolean): String {
    if (recipientId == null) return "Choose a recipient."
    if (recipientId == BROADCAST_ID) {
        return if (links.isEmpty()) "No phone is connected. It will be saved and offered to phones as they connect."
        else "Will be offered to ${links.map { it.peer.deviceId }.distinct().size} connected phone(s) now."
    }
    val direct = links.filter { it.peer.deviceId == recipientId }.map { it.transport.label }.distinct()
    return when {
        direct.isNotEmpty() -> "Direct link open via ${direct.joinToString()}. RELAY will try it right away and show Delivered only after the recipient confirms."
        links.isNotEmpty() || internetUsable ->
            "No direct link to the recipient. RELAY will hand it to " +
                listOfNotNull(
                    if (links.isNotEmpty()) "${links.map { it.peer.deviceId }.distinct().size} nearby relay phone(s)" else null,
                    if (internetUsable) "the internet relay" else null,
                ).joinToString(" and ") + " and keep it until the recipient confirms."
        else -> "No route right now. It will be saved on this phone (QUEUED — WAITING FOR CONNECTION) and sent automatically when a route appears."
    }
}
