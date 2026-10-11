package com.relay.app.ui.screens.outbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relay.app.data.db.MessageEntity
import com.relay.app.model.DeliveryState
import com.relay.app.ui.components.EmptyState
import com.relay.app.ui.components.LabeledValue
import com.relay.app.ui.components.MessageCard
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.components.rememberNow
import com.relay.app.ui.format.RelayFormat
import com.relay.app.ui.nav.RelayNavigator
import com.relay.app.ui.nav.Routes
import com.relay.app.ui.nav.rememberContainer
import kotlinx.coroutines.launch

/**
 * Screen D: messages created on this phone (pending first) plus messages this phone is
 * carrying for others. Clearly separates "saved locally" from "actually transmitted".
 */
@Composable
fun OutboxScreen(nav: RelayNavigator) {
    val c = rememberContainer()
    val outbox by c.repository.outbox.collectAsStateWithLifecycle(emptyList())
    val carrying by c.repository.relayQueue.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val now by rememberNow()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var toCancel by remember { mutableStateOf<MessageEntity?>(null) }

    val pending = outbox.filter { it.deliveryState == DeliveryState.PENDING }
    val done = outbox.filter { it.deliveryState != DeliveryState.PENDING }
    val shown = when (tab) {
        0 -> pending
        1 -> done
        else -> carrying
    }

    Scaffold(topBar = { RelayTopBar("Outbox", onBack = nav::back) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Waiting (${pending.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Finished (${done.size})") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Carrying (${carrying.size})") })
            }
            if (shown.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Outbox,
                    title = when (tab) {
                        0 -> "Nothing waiting"
                        1 -> "Nothing finished yet"
                        else -> "Not carrying messages for others"
                    },
                    body = when (tab) {
                        0 -> "Messages you send stay here until the intended recipient confirms them."
                        1 -> "Delivered, expired and cancelled messages appear here."
                        else -> "When another phone hands this phone a message for someone else, it waits here until it can be passed on."
                    },
                )
                return@Column
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown, key = { it.id }) { m ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MessageCard(
                            message = m,
                            nowUtc = now,
                            selfName = settings.displayName,
                            maxBodyLines = 3,
                            onClick = { nav.open(Routes.detail(m.id)) },
                            actions = {
                                if (m.deliveryState == DeliveryState.PENDING) {
                                    TextButton(onClick = { scope.launch { c.repository.retryNow(m.id) } }) { Text("Retry now") }
                                    if (tab == 0) TextButton(onClick = { toCancel = m }) { Text("Cancel") }
                                }
                                TextButton(onClick = { nav.open(Routes.detail(m.id)) }) { Text("Details") }
                            },
                        )
                        QueueFacts(m, now)
                    }
                }
            }
        }
    }

    toCancel?.let { m ->
        AlertDialog(
            onDismissRequest = { toCancel = null },
            title = { Text("Cancel this message?") },
            text = {
                Text(
                    "RELAY will stop sending it from this phone." +
                        if (m.hopAckCount > 0 || m.uploadedAtUtc != null) " Copies already handed to relays or the internet relay may still arrive." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { c.repository.cancel(m.id) }
                    toCancel = null
                }) { Text("Cancel message") }
            },
            dismissButton = { TextButton(onClick = { toCancel = null }) { Text("Keep") } },
        )
    }
}

/** The queue facts the spec asks for, in plain words. */
@Composable
private fun QueueFacts(m: MessageEntity, now: Long) {
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val transmitted = m.lastAttemptAtUtc != null
        LabeledValue("Stored", if (transmitted) "Saved on this phone and transmitted at least once" else "Saved on this phone only (not transmitted yet)")
        LabeledValue("Transport", m.lastTransport?.label ?: "none yet")
        LabeledValue("Last attempt", m.lastAttemptAtUtc?.let { RelayFormat.relative(it, now) } ?: "never")
        LabeledValue("Failed rounds", m.retryCount.toString())
        m.lastError?.let { LabeledValue("Last problem", it) }
        LabeledValue("Expiry", RelayFormat.expiry(m, now))
        LabeledValue("Location", if (m.locationSource != null) "Attached" else "Not attached")
        Text(" ", style = MaterialTheme.typography.bodySmall)
    }
}
