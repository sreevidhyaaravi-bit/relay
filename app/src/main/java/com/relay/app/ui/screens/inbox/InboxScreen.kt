package com.relay.app.ui.screens.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.relay.app.model.MessageType
import com.relay.app.util.DeviceActions
import com.relay.app.ui.components.EmptyState
import com.relay.app.ui.components.MessageCard
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.components.rememberNow
import com.relay.app.ui.nav.RelayNavigator
import com.relay.app.ui.nav.Routes
import com.relay.app.ui.nav.rememberContainer
import kotlinx.coroutines.launch

/** Screen C: messages addressed to this phone (or broadcast), newest first. */
@Composable
fun InboxScreen(nav: RelayNavigator) {
    val c = rememberContainer()
    val messages by c.repository.inbox.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val now by rememberNow()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Opening the inbox marks everything as read.
    LaunchedEffect(messages.size) { if (messages.any { !it.isRead }) c.repository.markAllRead() }

    Scaffold(topBar = { RelayTopBar("Inbox", onBack = nav::back) }) { padding ->
        if (messages.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Inbox,
                title = "No messages yet",
                body = "Messages from nearby RELAY phones appear here, with the time they were sent and the time they arrived.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(messages, key = { it.id }) { m ->
                MessageCard(
                    message = m,
                    nowUtc = now,
                    selfName = settings.displayName,
                    onClick = {
                        scope.launch { c.repository.markRead(m.id) }
                        nav.open(Routes.detail(m.id))
                    },
                    actions = {
                        if (m.latitude != null && m.longitude != null) {
                            TextButton(onClick = { nav.open(Routes.radar(m.id)) }) { Text("🧭 Find sender") }
                        }
                        if (m.messageType == MessageType.SOS) {
                            TextButton(onClick = { DeviceActions.dialEmergency(context, settings.emergencyNumber) }) {
                                Text("📞 Call ${settings.emergencyNumber}")
                            }
                        }
                        TextButton(onClick = { nav.open(Routes.compose(recipientId = m.senderId, replyTo = m.id)) }) {
                            Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null)
                            Text(" Reply")
                        }
                    },
                )
            }
        }
    }
}
