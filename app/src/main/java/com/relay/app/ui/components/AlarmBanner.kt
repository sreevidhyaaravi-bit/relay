package com.relay.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relay.app.ui.nav.rememberContainer
import com.relay.app.ui.theme.LocalStatusColors

/** Shown above every screen while an incoming SOS is ringing. */
@Composable
fun AlarmBanner(onOpen: (String) -> Unit) {
    val alerts = rememberContainer().alerts
    val ringing by alerts.ringing.collectAsStateWithLifecycle()
    val m = ringing ?: return
    Surface(color = LocalStatusColors.current.sos, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("🆘 SOS from ${m.senderName}", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(m.body, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = {
                    alerts.stop()
                    onOpen(m.id)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = LocalStatusColors.current.sos),
            ) { Text("Stop alarm", fontWeight = FontWeight.Bold) }
        }
    }
}
