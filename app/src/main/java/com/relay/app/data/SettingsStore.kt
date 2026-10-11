package com.relay.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.relay.app.model.DEFAULT_MAX_HOPS
import com.relay.app.model.DeviceIdentity
import com.relay.app.model.RelayConfig
import com.relay.app.model.TransportKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** User-facing settings. Stored in SharedPreferences, exposed as a StateFlow. */
data class RelaySettings(
    val deviceId: String,
    val displayName: String,
    /** Default state of the "attach location" toggle on compose/SOS. Location is never sent without the toggle on. */
    val shareLocationByDefault: Boolean = false,
    /** Automatic transport selection (default). When false, only [preferredOrder]'s first enabled transport is used for direct links. */
    val autoTransport: Boolean = true,
    val preferredOrder: List<TransportKind> = listOf(TransportKind.BLUETOOTH, TransportKind.WIFI_DIRECT, TransportKind.LOCAL_WIFI),
    val ordinaryExpiryHours: Int = 24,
    val sosExpiryHours: Int = 72,
    val maxHops: Int = DEFAULT_MAX_HOPS,
    val relayForOthers: Boolean = true,
    val internetRelayEnabled: Boolean = true,
    val localWifiEnabled: Boolean = false,
    /** Shared secret for authenticating local Wi-Fi peers. Local Wi-Fi stays off until it is set. */
    val localNetworkKey: String = "",
    val keepListeningInBackground: Boolean = false,
    /** Vibrate when a message arrives (Morse ··· ––– ··· for SOS). */
    val vibrateOnIncoming: Boolean = true,
    /** Loud looping alarm when an SOS arrives. */
    val sosAlarm: Boolean = true,
    /** Read incoming messages aloud (offline text-to-speech). */
    val readAloud: Boolean = false,
    /** When reading aloud, only read SOS messages. */
    val readAloudSosOnly: Boolean = true,
    /** Number the "Call emergency" button dials (112 works in India and the EU). */
    val emergencyNumber: String = "112",
) {
    val identity: DeviceIdentity get() = DeviceIdentity(deviceId, displayName)

    fun relayConfig(): RelayConfig = RelayConfig(
        ordinaryExpiryMs = ordinaryExpiryHours * RelayConfig.HOUR_MS,
        urgentExpiryMs = ordinaryExpiryHours * RelayConfig.HOUR_MS,
        sosExpiryMs = sosExpiryHours * RelayConfig.HOUR_MS,
        maxHops = maxHops,
        relayForOthers = relayForOthers,
        preferredOrder = preferredOrder,
    )
}

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("relay_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<RelaySettings> = _settings

    val current: RelaySettings get() = _settings.value

    fun identity(): DeviceIdentity = current.identity

    fun relayConfig(): RelayConfig = current.relayConfig()

    fun update(transform: (RelaySettings) -> RelaySettings) {
        val next = sanitize(transform(current))
        save(next)
        _settings.value = next
    }

    private fun load(): RelaySettings {
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId.isNullOrBlank()) {
            deviceId = generateDeviceId()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        val defaults = RelaySettings(deviceId = deviceId, displayName = defaultName(deviceId))
        val order = prefs.getString(KEY_ORDER, null)
            ?.split(',')
            ?.mapNotNull { TransportKind.fromName(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: defaults.preferredOrder
        return sanitize(
            RelaySettings(
                deviceId = deviceId,
                displayName = prefs.getString(KEY_NAME, null) ?: defaults.displayName,
                shareLocationByDefault = prefs.getBoolean(KEY_SHARE_LOCATION, defaults.shareLocationByDefault),
                autoTransport = prefs.getBoolean(KEY_AUTO, defaults.autoTransport),
                preferredOrder = order,
                ordinaryExpiryHours = prefs.getInt(KEY_EXPIRY, defaults.ordinaryExpiryHours),
                sosExpiryHours = prefs.getInt(KEY_SOS_EXPIRY, defaults.sosExpiryHours),
                maxHops = prefs.getInt(KEY_MAX_HOPS, defaults.maxHops),
                relayForOthers = prefs.getBoolean(KEY_RELAY, defaults.relayForOthers),
                internetRelayEnabled = prefs.getBoolean(KEY_INTERNET, defaults.internetRelayEnabled),
                localWifiEnabled = prefs.getBoolean(KEY_LAN, defaults.localWifiEnabled),
                localNetworkKey = prefs.getString(KEY_LAN_KEY, null) ?: "",
                keepListeningInBackground = prefs.getBoolean(KEY_BACKGROUND, defaults.keepListeningInBackground),
                vibrateOnIncoming = prefs.getBoolean(KEY_VIBRATE, defaults.vibrateOnIncoming),
                sosAlarm = prefs.getBoolean(KEY_SOS_ALARM, defaults.sosAlarm),
                readAloud = prefs.getBoolean(KEY_READ_ALOUD, defaults.readAloud),
                readAloudSosOnly = prefs.getBoolean(KEY_READ_SOS_ONLY, defaults.readAloudSosOnly),
                emergencyNumber = prefs.getString(KEY_EMERGENCY_NUMBER, null) ?: defaults.emergencyNumber,
            ),
        )
    }

    private fun save(s: RelaySettings) {
        prefs.edit()
            .putString(KEY_DEVICE_ID, s.deviceId)
            .putString(KEY_NAME, s.displayName)
            .putBoolean(KEY_SHARE_LOCATION, s.shareLocationByDefault)
            .putBoolean(KEY_AUTO, s.autoTransport)
            .putString(KEY_ORDER, s.preferredOrder.joinToString(",") { it.name })
            .putInt(KEY_EXPIRY, s.ordinaryExpiryHours)
            .putInt(KEY_SOS_EXPIRY, s.sosExpiryHours)
            .putInt(KEY_MAX_HOPS, s.maxHops)
            .putBoolean(KEY_RELAY, s.relayForOthers)
            .putBoolean(KEY_INTERNET, s.internetRelayEnabled)
            .putBoolean(KEY_LAN, s.localWifiEnabled)
            .putString(KEY_LAN_KEY, s.localNetworkKey)
            .putBoolean(KEY_BACKGROUND, s.keepListeningInBackground)
            .putBoolean(KEY_VIBRATE, s.vibrateOnIncoming)
            .putBoolean(KEY_SOS_ALARM, s.sosAlarm)
            .putBoolean(KEY_READ_ALOUD, s.readAloud)
            .putBoolean(KEY_READ_SOS_ONLY, s.readAloudSosOnly)
            .putString(KEY_EMERGENCY_NUMBER, s.emergencyNumber)
            .apply()
    }

    private fun sanitize(s: RelaySettings): RelaySettings = s.copy(
        displayName = s.displayName.trim().take(32).ifBlank { defaultName(s.deviceId) },
        ordinaryExpiryHours = s.ordinaryExpiryHours.coerceIn(1, 168),
        sosExpiryHours = s.sosExpiryHours.coerceIn(1, 168),
        maxHops = s.maxHops.coerceIn(1, 16),
        localNetworkKey = s.localNetworkKey.trim().take(64),
        emergencyNumber = s.emergencyNumber.filter { it.isDigit() || it == '+' }.take(15).ifBlank { "112" },
        preferredOrder = (s.preferredOrder.distinct().filter { it in DIRECT_KINDS } +
            DIRECT_KINDS.filter { it !in s.preferredOrder }),
    )

    companion object {
        private val DIRECT_KINDS = listOf(TransportKind.BLUETOOTH, TransportKind.WIFI_DIRECT, TransportKind.LOCAL_WIFI)
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_NAME = "display_name"
        private const val KEY_SHARE_LOCATION = "share_location_default"
        private const val KEY_AUTO = "auto_transport"
        private const val KEY_ORDER = "preferred_order"
        private const val KEY_EXPIRY = "ordinary_expiry_hours"
        private const val KEY_SOS_EXPIRY = "sos_expiry_hours"
        private const val KEY_MAX_HOPS = "max_hops"
        private const val KEY_RELAY = "relay_for_others"
        private const val KEY_INTERNET = "internet_relay_enabled"
        private const val KEY_LAN = "local_wifi_enabled"
        private const val KEY_LAN_KEY = "local_network_key"
        private const val KEY_BACKGROUND = "keep_listening_background"
        private const val KEY_VIBRATE = "vibrate_on_incoming"
        private const val KEY_SOS_ALARM = "sos_alarm"
        private const val KEY_READ_ALOUD = "read_aloud"
        private const val KEY_READ_SOS_ONLY = "read_aloud_sos_only"
        private const val KEY_EMERGENCY_NUMBER = "emergency_number"

        /** "RLY-" + 8 hex characters, generated once per install. */
        fun generateDeviceId(): String =
            "RLY-" + UUID.randomUUID().toString().replace("-", "").take(8).uppercase()

        fun defaultName(deviceId: String): String {
            val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Phone"
            return "$model ${deviceId.takeLast(4)}".take(32)
        }
    }
}
