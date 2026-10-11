package com.relay.app.ui.screens.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relay.app.model.TransportKind
import com.relay.app.model.TransportState
import com.relay.app.ui.components.LabeledValue
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.components.SectionCard
import com.relay.app.ui.components.rememberNow
import com.relay.app.ui.format.RelayFormat
import com.relay.app.ui.nav.RelayNavigator
import com.relay.app.ui.nav.rememberContainer
import com.relay.app.ui.theme.RelayColors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Colour per connection type, used on the map and its legend. */
fun transportColor(kind: TransportKind): Color = when (kind) {
    TransportKind.BLUETOOTH -> Color(0xFF2A62C9)
    TransportKind.WIFI_DIRECT -> Color(0xFF1B7F3B)
    TransportKind.LOCAL_WIFI -> Color(0xFFE07B00)
    TransportKind.INTERNET_RELAY -> Color(0xFF6A3FB5)
    TransportKind.SIMULATED -> Color.Gray
}

private data class MapPeer(val name: String, val transports: List<TransportKind>)

/**
 * Live network map: this phone in the centre, phones connected right now around it (coloured by
 * connection type), and phones seen before but not connected on the outer ring. Only real,
 * current connections are drawn; phones further away (reached through others) are not shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkMapScreen(nav: RelayNavigator) {
    val c = rememberContainer()
    val context = LocalContext.current
    val links by c.transportManager.links.collectAsStateWithLifecycle()
    val peers by c.repository.peers.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val internet by c.internet.status.collectAsStateWithLifecycle()
    val carrying by c.repository.relayCount.collectAsStateWithLifecycle(0)
    val waiting by c.repository.queuedCount.collectAsStateWithLifecycle(0)
    val now by rememberNow(10_000L)

    val connected = links.filter { it.isOpen }.groupBy { it.peer.deviceId }
        .map { (_, ls) -> MapPeer(ls.first().peer.displayName, ls.map { it.transport }.distinct()) }
        .sortedBy { it.name.lowercase() }
    val connectedIds = links.map { it.peer.deviceId }.toSet()
    val known = peers.filter { it.deviceId !in connectedIds }.take(8)
    val internetOnline = internet.state == TransportState.CONNECTED

    val animationsOn = remember {
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val sweep = if (animationsOn) {
        val s by rememberInfiniteTransition(label = "mesh").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart),
            label = "meshPulse",
        )
        s
    } else {
        0f
    }

    Scaffold(topBar = { RelayTopBar("Live network map", onBack = nav::back) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val measurer = rememberTextMeasurer()
            val ink = MaterialTheme.colorScheme.onSurface
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            val surface = MaterialTheme.colorScheme.surface
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawMesh(settings.displayName, connected, known.map { it.displayName }, internetOnline, sweep, measurer, ink, muted, surface)
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(TransportKind.BLUETOOTH, TransportKind.WIFI_DIRECT, TransportKind.LOCAL_WIFI, TransportKind.INTERNET_RELAY).forEach { k ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).background(transportColor(k), CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(k.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(Color.Gray, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("Seen before, not connected", style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionCard(title = "Right now") {
                LabeledValue("Connected", "${connected.size} phone(s)")
                LabeledValue("Internet relay", if (internetOnline) "Online" else internet.detail)
                LabeledValue("Carrying", "$carrying message(s) for other people")
                LabeledValue("Your messages", "$waiting waiting for confirmation")
                Text(
                    "Shows only direct connections of this phone. Messages can still travel further: each connected phone passes them on to its own neighbours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (connected.isNotEmpty()) {
                SectionCard(title = "Connected phones") {
                    connected.forEach { p -> LabeledValue(p.name, p.transports.joinToString { it.label }) }
                }
            }
            if (known.isNotEmpty()) {
                SectionCard(title = "Seen before") {
                    known.forEach { p -> LabeledValue(p.displayName, "Last seen ${RelayFormat.relative(p.lastSeenAtUtc, now)}") }
                }
            }
        }
    }
}

private fun DrawScope.drawMesh(
    me: String,
    connected: List<MapPeer>,
    known: List<String>,
    internetOnline: Boolean,
    sweep: Float,
    measurer: TextMeasurer,
    ink: Color,
    muted: Color,
    surface: Color,
) {
    val center = Offset(size.width / 2, size.height / 2)
    val maxR = min(size.width, size.height) / 2
    val inner = maxR * 0.52f
    val outer = maxR * 0.86f
    val nodeR = maxR * 0.1f

    // Expanding "signal" rings from this phone.
    if (sweep > 0f) {
        drawCircle(RelayColors.Blue600.copy(alpha = (1f - sweep) * 0.35f), radius = maxR * sweep, center = center, style = Stroke(4f))
    }
    drawCircle(muted.copy(alpha = 0.25f), radius = inner, center = center, style = Stroke(2f))
    drawCircle(muted.copy(alpha = 0.15f), radius = outer, center = center, style = Stroke(2f))

    fun label(text: String, at: Offset, color: Color, bold: Boolean = false) {
        val layout = measurer.measure(text.take(14), TextStyle(color = color, fontSize = 12.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
        drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y))
    }

    fun position(i: Int, n: Int, radius: Float, offsetDeg: Double = -90.0): Offset {
        val angle = Math.toRadians(offsetDeg + i * 360.0 / n.coerceAtLeast(1))
        return Offset(center.x + (radius * cos(angle)).toFloat(), center.y + (radius * sin(angle)).toFloat())
    }

    // Phones seen before (grey, dashed: not connected now).
    known.forEachIndexed { i, name ->
        val p = position(i, known.size, outer, offsetDeg = -60.0)
        drawLine(Color.Gray.copy(alpha = 0.5f), center, p, strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 12f)))
        drawCircle(Color.Gray, radius = nodeR * 0.6f, center = p)
        label(name, Offset(p.x, p.y + nodeR * 0.7f), muted)
    }

    // Connected phones, one coloured line per connection type.
    connected.forEachIndexed { i, peer ->
        val p = position(i, connected.size, inner)
        peer.transports.forEachIndexed { t, kind ->
            val shift = (t - (peer.transports.size - 1) / 2f) * 8f
            drawLine(transportColor(kind), Offset(center.x + shift, center.y + shift), Offset(p.x + shift, p.y + shift), strokeWidth = 7f)
        }
        drawCircle(surface, radius = nodeR, center = p)
        drawCircle(transportColor(peer.transports.first()), radius = nodeR, center = p, style = Stroke(6f))
        label(peer.name, Offset(p.x, p.y + nodeR + 4f), ink, bold = true)
    }

    // Internet relay node, top right.
    if (internetOnline) {
        val p = Offset(center.x + outer * 0.72f, center.y - outer * 0.72f)
        drawLine(transportColor(TransportKind.INTERNET_RELAY), center, p, strokeWidth = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
        drawCircle(transportColor(TransportKind.INTERNET_RELAY), radius = nodeR * 0.7f, center = p)
        label("Internet", Offset(p.x, p.y + nodeR * 0.8f), ink)
    }

    // This phone.
    drawCircle(RelayColors.Navy800, radius = nodeR * 1.25f, center = center)
    label("You", Offset(center.x, center.y - 8f), Color.White, bold = true)
    label(me, Offset(center.x, center.y + nodeR * 1.35f), ink, bold = true)
}
