package com.relay.app.ui.screens.radar

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.relay.app.location.Compass
import com.relay.app.location.LocationPermissionLevel
import com.relay.app.model.LocationInfo
import com.relay.app.permissions.RelayPermissions
import com.relay.app.ui.components.RelayTopBar
import com.relay.app.ui.components.SectionCard
import com.relay.app.ui.components.TypeBadge
import com.relay.app.ui.components.rememberNow
import com.relay.app.ui.components.rememberPermissionRequester
import com.relay.app.ui.format.RelayFormat
import com.relay.app.ui.nav.RelayNavigator
import com.relay.app.ui.nav.rememberContainer
import com.relay.app.ui.theme.LocalStatusColors
import com.relay.app.util.DeviceActions
import com.relay.app.util.Geo

/**
 * Rescue Radar: points to the sender of a message and says how far away they are, using this
 * phone's GPS and compass and the sender's GPS location in the message. Works with no internet.
 */
@Composable
fun RadarScreen(nav: RelayNavigator, messageId: String) {
    val c = rememberContainer()
    val context = LocalContext.current
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val messageFlow = remember(messageId) { c.repository.message(messageId) }
    val message by messageFlow.collectAsStateWithLifecycle(null)
    val now by rememberNow(5_000L)

    // The compass maths assumes the phone is upright, so keep this screen in portrait.
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose { if (before != null) activity.requestedOrientation = before }
    }

    var hasPermission by remember { mutableStateOf(c.location.permissionLevel() != LocationPermissionLevel.NONE) }
    val requestLocation = rememberPermissionRequester { hasPermission = c.location.permissionLevel() != LocationPermissionLevel.NONE }

    val mine by produceState<LocationInfo?>(null, hasPermission) {
        if (hasPermission) c.location.updates().collect { fix -> value = betterFix(value, fix) }
    }
    val compass = remember { Compass(context) }
    val heading by produceState<Compass.Reading?>(null) { compass.readings().collect { value = it } }

    Scaffold(topBar = { RelayTopBar("Rescue Radar", onBack = nav::back) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val m = message
            if (m == null) {
                Text("Message not found.")
                return@Column
            }
            val target = m.location()
            SectionCard(title = null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeBadge(m.messageType)
                    Text(m.senderName, style = MaterialTheme.typography.titleMedium)
                }
                Text(m.body, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                Text(RelayFormat.location(target, m.createdAtUtc), style = MaterialTheme.typography.bodySmall)
                target?.capturedAtUtc?.let {
                    Text(
                        "Their location is from ${RelayFormat.relative(it, now)}. They may have moved since.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                m.batteryPercent?.let { Text("🔋 Their battery: $it%", style = MaterialTheme.typography.bodySmall) }
            }

            if (target == null || !target.hasCoordinates) {
                Text(
                    "This message has no GPS location, so the radar cannot point to it." +
                        (target?.label?.let { " Their description: \"$it\"." } ?: ""),
                    style = MaterialTheme.typography.bodyLarge,
                )
                return@Column
            }
            if (!hasPermission) {
                Text("The radar needs your location to work out direction and distance.")
                Button(onClick = { requestLocation(RelayPermissions.location()) }) { Text("Allow location") }
                return@Column
            }

            val me = mine
            if (me?.latitude == null || me.longitude == null) {
                Text("Getting your location… GPS works offline but needs a view of the sky.", style = MaterialTheme.typography.bodyLarge)
            } else {
                val distance = Geo.distanceMeters(me.latitude, me.longitude, target.latitude!!, target.longitude!!)
                val bearing = Geo.bearingDegrees(me.latitude, me.longitude, target.latitude, target.longitude)
                val h = heading
                RadarDial(
                    relativeAngle = if (h != null) Geo.relativeAngle(bearing, h.headingDegrees) else bearing,
                    headingDegrees = h?.headingDegrees ?: 0.0,
                    compassOn = h != null,
                )
                Text(Geo.formatDistance(distance), fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text("to the ${Geo.compassWord(bearing)}", style = MaterialTheme.typography.titleMedium)
                val uncertainty = (me.accuracyMeters ?: 0f) + (target.accuracyMeters ?: 0f)
                if (distance <= uncertainty.coerceAtLeast(15f)) {
                    Text(
                        "You are very close (within GPS accuracy). Call out and look around.",
                        color = LocalStatusColors.current.success,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    when {
                        h == null -> "No compass on this phone: the arrow shows the direction with north at the top."
                        !h.reliable -> "Compass needs calibrating: move the phone in a figure 8."
                        else -> "Hold the phone flat and upright; the arrow points to ${m.senderName}."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Your GPS: ±${me.accuracyMeters?.toInt() ?: "?"} m · theirs: ±${target.accuracyMeters?.toInt() ?: "?"} m",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(
                onClick = { DeviceActions.dialEmergency(context, settings.emergencyNumber) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = LocalStatusColors.current.sos),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("📞 Call ${settings.emergencyNumber}") }
            Text(
                "Works without internet. Accuracy depends on both phones' GPS; indoors it can be off by tens of metres.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Keeps the more trustworthy of two fixes: a newer one unless it is much less accurate. */
private fun betterFix(current: LocationInfo?, new: LocationInfo): LocationInfo {
    if (current == null) return new
    val newer = (new.capturedAtUtc ?: 0) > (current.capturedAtUtc ?: 0) + 10_000
    val moreAccurate = (new.accuracyMeters ?: Float.MAX_VALUE) <= (current.accuracyMeters ?: Float.MAX_VALUE)
    return if (newer || moreAccurate) new else current
}

/** Compass ring (turns with the phone) and an arrow pointing at the target. */
@Composable
private fun RadarDial(relativeAngle: Double, headingDegrees: Double, compassOn: Boolean) {
    val ring = MaterialTheme.colorScheme.outline
    val arrow = LocalStatusColors.current.sos
    val label = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.size(260.dp)) {
        val r = size.minDimension / 2
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(ring, radius = r - 4f, center = center, style = Stroke(width = 6f))
        drawCircle(ring.copy(alpha = 0.35f), radius = r * 0.62f, center = center, style = Stroke(width = 2f))
        if (compassOn) {
            rotate(-headingDegrees.toFloat(), center) {
                for (deg in 0 until 360 step 30) {
                    rotate(deg.toFloat(), center) {
                        drawLine(ring, Offset(center.x, center.y - r + 6f), Offset(center.x, center.y - r + 26f), strokeWidth = 4f)
                    }
                }
                val north = measurer.measure("N", TextStyle(color = Color(0xFFC62828), fontSize = 18.sp, fontWeight = FontWeight.Bold))
                drawText(north, topLeft = Offset(center.x - north.size.width / 2f, center.y - r + 30f))
            }
        }
        rotate(relativeAngle.toFloat(), center) {
            val tip = Offset(center.x, center.y - r * 0.78f)
            val path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(center.x + r * 0.2f, center.y + r * 0.25f)
                lineTo(center.x, center.y + r * 0.1f)
                lineTo(center.x - r * 0.2f, center.y + r * 0.25f)
                close()
            }
            drawPath(path, arrow)
        }
        drawCircle(label, radius = 6f, center = center)
    }
}
