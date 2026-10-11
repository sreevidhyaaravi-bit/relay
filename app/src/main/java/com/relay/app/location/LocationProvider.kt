package com.relay.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import android.os.CancellationSignal
import com.relay.app.model.Clock
import com.relay.app.model.LocationInfo
import com.relay.app.model.LocationSource
import com.relay.app.permissions.RelayPermissions
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

enum class LocationPermissionLevel { NONE, APPROXIMATE, PRECISE }

sealed interface LocationResult {
    /** A real fix from the device. [location].capturedAtUtc is the fix time, not "now". */
    data class Success(val location: LocationInfo, val approximate: Boolean) : LocationResult
    data class Unavailable(val reason: String) : LocationResult
    data object NoPermission : LocationResult
    data object ServicesDisabled : LocationResult
}

/**
 * Framework LocationManager only: GPS works offline, no Google Play services, no internet
 * geocoding. Never invents coordinates and never relabels an old fix as current.
 */
class LocationProvider(private val context: Context, private val clock: Clock) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun permissionLevel(): LocationPermissionLevel = when {
        RelayPermissions.hasPreciseLocation(context) -> LocationPermissionLevel.PRECISE
        RelayPermissions.hasAnyLocation(context) -> LocationPermissionLevel.APPROXIMATE
        else -> LocationPermissionLevel.NONE
    }

    fun isLocationEnabled(): Boolean = lm?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false

    /**
     * Requests a fresh fix, waiting up to [timeoutMs].
     *
     * Every enabled provider is asked AT THE SAME TIME and the first real fix wins. Without
     * internet (airplane mode) only GPS works, and a GPS fix without network assistance can take
     * 30-60 s and needs a view of the sky, so GPS gets the whole budget instead of a slice.
     * Platform requests give up after about 30 s, so each provider keeps re-asking until the
     * overall timeout.
     */
    @SuppressLint("MissingPermission") // permission checked via permissionLevel()
    suspend fun currentLocation(timeoutMs: Long = DEFAULT_TIMEOUT_MS): LocationResult {
        val manager = lm ?: return LocationResult.Unavailable("This phone has no location service")
        val level = permissionLevel()
        if (level == LocationPermissionLevel.NONE) return LocationResult.NoPermission
        if (!isLocationEnabled()) return LocationResult.ServicesDisabled
        val providers = candidateProviders(manager, level)
        if (providers.isEmpty()) return LocationResult.Unavailable("No location provider is enabled")

        val fix = withTimeoutOrNull(timeoutMs) {
            channelFlow {
                for (provider in providers) {
                    launch {
                        while (isActive) {
                            val location = requestSingleFix(manager, provider)
                            if (location != null) {
                                send(location)
                                break
                            }
                            delay(1_000L)
                        }
                    }
                }
            }.first()
        }
        if (fix != null) return LocationResult.Success(fix.toInfo(), approximate = level == LocationPermissionLevel.APPROXIMATE)
        return LocationResult.Unavailable(
            "Couldn't find your location within ${timeoutMs / 1000} s. GPS works without internet but needs a view of the sky: " +
                "try near a window or outdoors, use your last known location, or describe where you are.",
        )
    }

    /** Most recent cached fix with its REAL capture time (callers must show staleness), or null. */
    @SuppressLint("MissingPermission")
    fun lastKnown(): LocationInfo? {
        val manager = lm ?: return null
        val level = permissionLevel()
        if (level == LocationPermissionLevel.NONE) return null
        return candidateProviders(manager, level)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.toInfo()
    }

    /**
     * Continuous location updates (for Rescue Radar). Starts with the last known fix, then live
     * fixes from every enabled provider. Each fix keeps its real capture time.
     */
    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long = 2_000L): Flow<LocationInfo> = callbackFlow {
        val manager = lm
        val level = permissionLevel()
        if (manager == null || level == LocationPermissionLevel.NONE) {
            close()
            return@callbackFlow
        }
        lastKnown()?.let { trySend(it) }
        val listener = LocationListenerCompat { location -> trySend(location.toInfo()) }
        val request = LocationRequestCompat.Builder(intervalMs).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build()
        candidateProviders(manager, level).forEach { provider ->
            runCatching {
                LocationManagerCompat.requestLocationUpdates(manager, provider, request, ContextCompat.getMainExecutor(context), listener)
            }
        }
        awaitClose { runCatching { LocationManagerCompat.removeUpdates(manager, listener) } }
    }

    private fun candidateProviders(manager: LocationManager, level: LocationPermissionLevel): List<String> {
        val wanted = buildList {
            // GPS needs precise permission; it works without any network.
            if (level == LocationPermissionLevel.PRECISE) add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        return wanted.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleFix(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            try {
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(context),
                ) { location -> if (cont.isActive) cont.resume(location) }
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(null)
            } catch (e: IllegalArgumentException) {
                if (cont.isActive) cont.resume(null)
            }
        }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }

    private fun Location.toInfo(): LocationInfo = LocationInfo(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        // Location.time is the UTC wall-clock time of the fix itself.
        capturedAtUtc = time.takeIf { it > 0 } ?: clock.now(),
        label = null,
        source = LocationSource.DEVICE,
    )
}
