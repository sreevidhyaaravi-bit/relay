package com.relay.app.connectivity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/** Internet reachability as reported by Android. Only [validated] networks count as internet. */
data class InternetState(
    val available: Boolean,
    val validated: Boolean,
    val viaWifi: Boolean,
    val viaCellular: Boolean,
    val detail: String,
) {
    val usable: Boolean get() = available && validated

    companion object {
        val NONE = InternetState(available = false, validated = false, viaWifi = false, viaCellular = false, detail = "No network")
    }
}

/**
 * Observes radios and networks. Radio state is NOT route availability; these flows only tell
 * the transport manager when to re-evaluate routes.
 *
 * Internet = a network with NET_CAPABILITY_INTERNET that Android has VALIDATED (it actually
 * reached the internet). Being connected to a Wi-Fi access point is reported separately in
 * [wifiNetworkConnected] and does not imply internet or reachable peers.
 */
class ConnectivityMonitor(private val context: Context, private val scope: CoroutineScope) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _internet = MutableStateFlow(InternetState.NONE)
    val internet: StateFlow<InternetState> = _internet

    private val _bluetoothOn = MutableStateFlow(false)
    val bluetoothOn: StateFlow<Boolean> = _bluetoothOn

    private val _wifiOn = MutableStateFlow(false)
    val wifiOn: StateFlow<Boolean> = _wifiOn

    private val _wifiNetworkConnected = MutableStateFlow(false)

    /** Connected to some Wi-Fi network (an access point). Does NOT mean peers are reachable. */
    val wifiNetworkConnected: StateFlow<Boolean> = _wifiNetworkConnected

    private val _locationServicesOn = MutableStateFlow(false)
    val locationServicesOn: StateFlow<Boolean> = _locationServicesOn

    /** Capabilities of every currently known network. */
    private val networks = ConcurrentHashMap<Network, NetworkCapabilities>()
    private var started = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            networks[network] = caps
            recompute()
        }

        override fun onLost(network: Network) {
            networks.remove(network)
            recompute()
        }

        override fun onUnavailable() {
            recompute()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            refreshRadios()
        }
    }

    fun start() {
        if (started) return
        started = true
        // Track every network (not just the default) so Wi-Fi without internet is still visible.
        val request = NetworkRequest.Builder().build()
        runCatching { cm.registerNetworkCallback(request, networkCallback) }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refreshRadios()
        recompute()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { cm.unregisterNetworkCallback(networkCallback) }
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun refreshRadios() {
        _bluetoothOn.value = try {
            bluetoothAdapter?.isEnabled == true
        } catch (e: SecurityException) {
            false
        }
        _wifiOn.value = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)
        _locationServicesOn.value = locationManager?.let { LocationManagerCompat.isLocationEnabled(it) } ?: false
    }

    private fun recompute() {
        val all = networks.values.toList()
        val withInternet = all.filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        val validated = withInternet.filter { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        val best = validated.firstOrNull() ?: withInternet.firstOrNull()
        val viaWifi = best?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val viaCell = best?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val detail = when {
            validated.isNotEmpty() -> "Internet reachable via " + when {
                viaWifi -> "Wi-Fi"
                viaCell -> "mobile data"
                else -> "network"
            }
            withInternet.isNotEmpty() -> "Connected to a network, but internet is not confirmed"
            all.isNotEmpty() -> "Local network only (no internet)"
            else -> "No network"
        }
        _internet.value = InternetState(
            available = withInternet.isNotEmpty(),
            validated = validated.isNotEmpty(),
            viaWifi = viaWifi,
            viaCellular = viaCell,
            detail = detail,
        )
        _wifiNetworkConnected.value = all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
    }
}
