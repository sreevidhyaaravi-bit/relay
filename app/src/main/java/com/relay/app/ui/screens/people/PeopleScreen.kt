package com.relay.app.ui.screens.people

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.relay.app.ui.components.LabeledValue
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.components.SectionCard
import com.relay.app.ui.nav.RelayNavigator
import com.relay.app.ui.nav.rememberContainer
import com.relay.app.ui.theme.MonoStyle
import com.relay.app.util.ContactCode
import kotlinx.coroutines.launch

/**
 * Add people without typing IDs: show your QR code, scan someone else's. Make groups (family,
 * rescue team) to message several people at once. Works offline; the camera is used only while
 * scanning.
 */
@Composable
fun PeopleScreen(nav: RelayNavigator) {
    val c = rememberContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val peers by c.repository.peers.collectAsStateWithLifecycle(emptyList())
    val groups by c.groups.groups.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    val qr = remember(settings.deviceId, settings.displayName) {
        runCatching {
            BarcodeEncoder().encodeBitmap(ContactCode.encode(settings.deviceId, settings.displayName), BarcodeFormat.QR_CODE, 600, 600)
        }.getOrNull()
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult // cancelled
        val contact = ContactCode.parse(text)
        when {
            contact == null -> Toast.makeText(context, "That is not a RELAY QR code.", Toast.LENGTH_LONG).show()
            contact.deviceId == settings.deviceId -> Toast.makeText(context, "That is this phone's own code.", Toast.LENGTH_SHORT).show()
            else -> scope.launch {
                c.repository.addContact(contact.deviceId, contact.name, System.currentTimeMillis())
                Toast.makeText(context, "Added ${contact.name}. You can message them now.", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(topBar = { RelayTopBar("People & groups", onBack = nav::back) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = "Your RELAY code") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (qr != null) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "QR code for ${settings.displayName}",
                            modifier = Modifier.size(220.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp),
                        )
                    }
                    Text(settings.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(settings.deviceId, style = MonoStyle)
                }
                Text(
                    "Show this to someone. When they scan it, they can message you, even with no internet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = {
                    scanner.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Point the camera at a RELAY QR code")
                            .setBeepEnabled(false)
                            .setOrientationLocked(false),
                    )
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("📷 Scan someone's QR code") }

            SectionCard(title = "Groups") {
                if (groups.isEmpty()) {
                    Text("No groups yet. A group sends one message to each member.", style = MaterialTheme.typography.bodySmall)
                }
                groups.forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("👥 ${g.name}", style = MaterialTheme.typography.titleSmall)
                            Text(g.memberNames.joinToString(), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { c.groups.delete(g.id) }) { Text("Delete") }
                    }
                }
                OutlinedButton(onClick = { creating = true }, enabled = peers.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (peers.isEmpty()) "Add people first to make a group" else "➕ New group")
                }
            }

            SectionCard(title = "People you can message") {
                if (peers.isEmpty()) {
                    Text(
                        "Nobody yet. Scan a QR code, or connect to a phone once in Nearby devices.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                peers.forEach { p ->
                    LabeledValue(p.displayName, p.deviceId + if (p.lastTransport == null) " · added by QR" else " · ${p.lastTransport.label}")
                }
            }
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        val chosen = remember { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New group") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(32) },
                        label = { Text("Group name (e.g. Family)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    peers.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (p.deviceId in chosen) chosen.remove(p.deviceId) else chosen.add(p.deviceId)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = p.deviceId in chosen, onCheckedChange = null)
                            Text(p.displayName)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        c.groups.create(name, peers.filter { it.deviceId in chosen }.map { it.deviceId to it.displayName })
                        creating = false
                    },
                    enabled = name.isNotBlank() && chosen.isNotEmpty(),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } },
        )
    }
}
