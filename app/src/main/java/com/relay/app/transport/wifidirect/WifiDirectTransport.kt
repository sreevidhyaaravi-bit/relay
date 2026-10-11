package com.relay.app.transport.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.location.LocationManagerCompat
import com.relay.app.model.DiscoveredPeer
import com.relay.app.model.TransportKind
import com.relay.app.model.TransportState
import com.relay.app.model.TransportStatus
import com.relay.app.permissions.RelayPermissions
import com.relay.app.transport.PeerLink
import com.relay.app.transport.PeerTransport
import com.relay.app.transport.StreamPeerLink
import com.relay.app.transport.TransportDeps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Current Wi-Fi Direct group, if any. */
data class WifiDirectGroupInfo(
    val isGroupOwner: Boolean,
    val groupOwnerAddress: String?,
    val networkName: String?,
    val clientCount: Int,
)

/**
 * Wi-Fi Direct (android.net.wifi.p2p) transport. Works with no internet and no access point.
 *
 * 1. Discover peers (WifiP2pManager.discoverPeers) and show them in [discovered].
 * 2. connect(address) invites a peer; the other phone may show an accept prompt.
 * 3. When the group forms, the group owner runs a ServerSocket on [PORT] and the client
 *    connects to the group owner's address (retrying while the owner's server starts).
 * 4. Each socket becomes a [StreamPeerLink]; it is published after the HELLO handshake.
 *
 * Android 12 and lower need the Location permission AND Location turned on for discovery.
 */
class WifiDirectTransport(private val deps: TransportDeps) : PeerTransport {

    override val kind: TransportKind = TransportKind.WIFI_DIRECT

    private val context: Context = deps.context
    private val log = deps.log
    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _status = MutableStateFlow(TransportStatus(kind, TransportState.UNAVAILABLE, "Starting…"))
    override val status: StateFlow<TransportStatus> = _status

    private val _links = MutableStateFlow<List<PeerLink>>(emptyList())
    override val links: StateFlow<List<PeerLink>> = _links

    private val _discovered = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val discovered: StateFlow<List<DiscoveredPeer>> = _discovered

    private val _discovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _discovering

    private val _groupInfo = MutableStateFlow<WifiDirectGroupInfo?>(null)
    val groupInfo: StateFlow<WifiDirectGroupInfo?> = _groupInfo

    private val _autoDiscover = MutableStateFlow(false)

    /**
     * True while RELAY keeps this phone findable. Android only lets a phone be found (and receive
     * connection invitations) while it is running Wi-Fi Direct discovery itself, so without this
     * the RECEIVING phone would also have to tap "Discover peers".
     */
    val autoDiscover: StateFlow<Boolean> = _autoDiscover
    @Volatile private var autoJob: Job? = null

    @Volatile private var channel: WifiP2pManager.Channel? = null
    @Volatile private var p2pEnabled = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var serverJob: Job? = null
    @Volatile private var clientJob: Job? = null
    @Volatile private var connectingTo: String? = null
    @Volatile private var lastError: String? = null
    @Volatile private var receiverRegistered = false
    @Volatile private var lastGroup: WifiP2pGroup? = null

    // ------------------------------------------------------------------ capability checks

    override fun isSupported(): Boolean =
        manager != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun isWifiEnabled(): Boolean = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)

    fun hasPermission(): Boolean = RelayPermissions.allGranted(context, RelayPermissions.wifiDirect())

    // ------------------------------------------------------------------ lifecycle

    override fun start() {
        if (!isSupported()) {
            refreshStatus()
            return
        }
        if (channel == null) {
            channel = manager?.initialize(context, Looper.getMainLooper()) {
                channel = null
                setError("Wi-Fi Direct service disconnected; reopen RELAY to retry")
            }
        }
        registerReceiver()
        // Pick up a group that already exists (e.g. after the app was restarted).
        requestConnectionInfo()
        refreshStatus()
    }

    override fun stop() {
        setAutoDiscover(false)
        stopDiscovery()
        closeAllLinks("Wi-Fi Direct stopped")
        stopServer()
        clientJob?.cancel()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        refreshStatus()
    }

    // ------------------------------------------------------------------ discovery and connection

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val m = manager
        val ch = channel
        if (m == null || ch == null) return setError("Wi-Fi Direct is not available on this phone")
        if (!isWifiEnabled()) return setError("Turn Wi-Fi on (no internet or network needed) to use Wi-Fi Direct")
        if (!hasPermission()) return setError(permissionMessage())
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 && !locationServicesOn()) {
            return setError("On Android 12 and lower, Wi-Fi Direct discovery needs Location turned on")
        }
        try {
            m.discoverPeers(ch, listener("Searching for Wi-Fi Direct peers") { _discovering.value = true })
        } catch (e: SecurityException) {
            setError(permissionMessage())
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching { m.stopPeerDiscovery(ch, null) }
        _discovering.value = false
        refreshStatus()
    }

    /** Invite [deviceAddress] (from [discovered]) to connect; the other phone may show an accept prompt. */
    @SuppressLint("MissingPermission")
    fun connect(deviceAddress: String) {
        val m = manager
        val ch = channel
        if (m == null || ch == null) return setError("Wi-Fi Direct is not available on this phone")
        if (!hasPermission()) return setError(permissionMessage())
        if (!isWifiEnabled()) return setError("Turn Wi-Fi on to use Wi-Fi Direct")
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
        }
        connectingTo = deviceAddress
        refreshStatus()
        try {
            m.connect(
                ch,
                config,
                listener("Invitation sent to ${nameFor(deviceAddress)}. Accept it on the other phone if asked.") {},
            )
        } catch (e: SecurityException) {
            connectingTo = null
            setError(permissionMessage())
        }
        // Give up the "connecting" state if no group forms within a minute.
        deps.scope.launch {
            delay(60_000L)
            if (connectingTo == deviceAddress && _links.value.isEmpty()) {
                connectingTo = null
                setError("No Wi-Fi Direct connection to ${nameFor(deviceAddress)} after 60 s. Try again; accept the prompt on the other phone.")
            }
        }
    }

    /**
     * Keep this phone findable: re-run discovery every [AUTO_DISCOVERY_INTERVAL_MS] while not
     * connected. Called by TransportManager while RELAY is open (or "keep listening" is on).
     * Discovery runs only while idle; it stops automatically when a group forms.
     */
    fun setAutoDiscover(enabled: Boolean) {
        _autoDiscover.value = enabled
        autoJob?.cancel()
        autoJob = null
        if (!enabled) return
        autoJob = deps.scope.launch(Dispatchers.Main) {
            while (isActive) {
                if (canAutoDiscover()) discoverQuietly()
                delay(AUTO_DISCOVERY_INTERVAL_MS)
            }
        }
    }

    private fun canAutoDiscover(): Boolean =
        isSupported() && channel != null && isWifiEnabled() && hasPermission() &&
            connectingTo == null && _groupInfo.value == null && _links.value.none { it.isOpen } &&
            (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2 || locationServicesOn())

    /** Like startDiscovery() but silent: no error banner when the system is briefly busy. */
    @SuppressLint("MissingPermission")
    private fun discoverQuietly() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _discovering.value = true
                    refreshStatus()
                }

                override fun onFailure(reason: Int) = Unit
            })
        } catch (e: SecurityException) {
            // permission revoked; canAutoDiscover() will skip next time
        }
    }

    fun cancelConnect() {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching { m.cancelConnect(ch, null) }
        connectingTo = null
        refreshStatus()
    }

    /** Close links and remove the Wi-Fi Direct group. */
    fun disconnect() {
        closeAllLinks("disconnected by user")
        stopServer()
        clientJob?.cancel()
        connectingTo = null
        val m = manager
        val ch = channel
        if (m != null && ch != null) runCatching { m.removeGroup(ch, listener("Left the Wi-Fi Direct group") {}) }
        _groupInfo.value = null
        refreshStatus()
    }

    // ------------------------------------------------------------------ group handling

    private fun requestConnectionInfo() {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching { m.requestConnectionInfo(ch) { info -> onConnectionInfo(info) } }
    }

    private fun onConnectionInfo(info: WifiP2pInfo?) {
        if (info != null && info.groupFormed) {
            val ownerAddress = info.groupOwnerAddress
            _groupInfo.value = WifiDirectGroupInfo(
                isGroupOwner = info.isGroupOwner,
                groupOwnerAddress = ownerAddress?.hostAddress,
                networkName = lastGroup?.networkName,
                clientCount = lastGroup?.clientList?.size ?: 0,
            )
            connectingTo = null
            if (info.isGroupOwner) {
                startServer()
            } else if (ownerAddress != null) {
                startClient(ownerAddress)
            }
            // Discovery is no longer needed and slows the link down.
            if (_discovering.value) stopDiscovery()
        } else {
            if (_groupInfo.value != null) log.info(kind, "Wi-Fi Direct group ended")
            _groupInfo.value = null
            closeAllLinks("Wi-Fi Direct group ended")
            stopServer()
            clientJob?.cancel()
        }
        refreshStatus()
    }

    private fun startServer() {
        if (serverJob?.isActive == true) return
        serverJob = deps.scope.launch(Dispatchers.IO) {
            val server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                }
            } catch (e: IOException) {
                setError("Could not open port $PORT for Wi-Fi Direct: ${e.message}")
                return@launch
            }
            serverSocket = server
            log.info(kind, "Group owner: waiting for RELAY connections on port $PORT")
            try {
                while (isActive) {
                    val socket = server.accept()
                    launch { onSocketConnected(socket, isClient = false) }
                }
            } catch (e: IOException) {
                // closed
            } finally {
                runCatching { server.close() }
                serverSocket = null
            }
        }
    }

    private fun stopServer() {
        runCatching { serverSocket?.close() }
        serverJob?.cancel()
        serverJob = null
    }

    private fun startClient(owner: InetAddress) {
        if (clientJob?.isActive == true) return
        if (_links.value.any { it.isOpen }) return
        clientJob = deps.scope.launch(Dispatchers.IO) {
            // The owner's server may need a moment after the group forms.
            repeat(CLIENT_CONNECT_ATTEMPTS) { attempt ->
                val socket = Socket()
                try {
                    socket.connect(InetSocketAddress(owner, PORT), CLIENT_CONNECT_TIMEOUT_MS)
                    if (onSocketConnected(socket, isClient = true)) return@launch
                } catch (e: IOException) {
                    runCatching { socket.close() }
                    if (attempt == 0) log.info(kind, "Joined group; connecting to the group owner at ${owner.hostAddress}…")
                }
                delay(1_000L)
            }
            setError("Joined the Wi-Fi Direct group but could not reach RELAY on the other phone (port $PORT). Make sure RELAY is open there.")
        }
    }

    private suspend fun onSocketConnected(socket: Socket, isClient: Boolean): Boolean {
        runCatching {
            socket.tcpNoDelay = true
            socket.keepAlive = true
        }
        val remote = "${socket.inetAddress?.hostAddress ?: "?"}:${socket.port}"
        val streams = try {
            socket.getInputStream() to socket.getOutputStream()
        } catch (e: IOException) {
            runCatching { socket.close() }
            return false
        }
        val link = StreamPeerLink(
            transport = kind,
            remoteLabel = remote,
            input = streams.first,
            output = streams.second,
            closeUnderlying = { socket.close() },
            localIdentity = deps.identity,
            inbound = deps.inbound,
            log = log,
            clock = deps.clock,
            parentScope = deps.scope,
            onClosed = { closed, _ ->
                _links.update { current -> current.filter { it.id != closed.id } }
                refreshStatus()
            },
        )
        val peer = try {
            link.handshake()
        } catch (e: kotlinx.coroutines.CancellationException) {
            link.close("cancelled during handshake")
            throw e
        } catch (e: IOException) {
            log.warn(kind, "Handshake over Wi-Fi Direct failed: ${e.message}")
            return false
        }
        val existing = _links.value.firstOrNull { it.isOpen && it.peer.deviceId == peer.deviceId }
        if (existing != null) {
            val keepNew = isClient == (deps.identity().deviceId < peer.deviceId)
            if (keepNew) existing.close("replaced by a newer link") else {
                link.close("duplicate link")
                return true
            }
        }
        _links.update { current -> current.filter { it.isOpen && it.id != link.id } + link }
        lastError = null
        log.info(kind, "Connected to ${peer.displayName} over Wi-Fi Direct (${if (isClient) "client" else "group owner"})")
        refreshStatus()
        return true
    }

    private fun closeAllLinks(reason: String) {
        _links.value.forEach { it.close(reason) }
        _links.value = emptyList()
    }

    // ------------------------------------------------------------------ broadcasts

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    p2pEnabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!p2pEnabled) {
                        closeAllLinks("Wi-Fi turned off")
                        _groupInfo.value = null
                        _discovering.value = false
                    }
                    refreshStatus()
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    lastGroup = IntentCompat.getParcelableExtra(intent, WifiP2pManager.EXTRA_WIFI_P2P_GROUP, WifiP2pGroup::class.java)
                    requestConnectionInfo()
                }
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    _discovering.value = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                    refreshStatus()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        val m = manager ?: return
        val ch = channel ?: return
        if (!hasPermission()) return
        try {
            m.requestPeers(ch) { list ->
                _discovered.value = list?.deviceList.orEmpty().map { d ->
                    DiscoveredPeer(
                        kind = kind,
                        address = d.deviceAddress,
                        name = d.deviceName?.takeIf { it.isNotBlank() } ?: d.deviceAddress,
                        detail = statusText(d.status),
                        connectable = d.status == WifiP2pDevice.AVAILABLE || d.status == WifiP2pDevice.FAILED,
                    )
                }.sortedBy { it.name.lowercase() }
            }
        } catch (e: SecurityException) {
            // permission revoked
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun listener(successMessage: String, onSuccess: () -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            lastError = null
            log.info(kind, successMessage)
            onSuccess()
            refreshStatus()
        }

        override fun onFailure(reason: Int) {
            connectingTo = null
            setError("Wi-Fi Direct request failed: ${reasonText(reason)}")
        }
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this phone"
        WifiP2pManager.BUSY -> "the Wi-Fi Direct system is busy; wait a few seconds and retry"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        else -> "internal error (try turning Wi-Fi off and on)"
    }

    private fun statusText(status: Int): String = when (status) {
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.FAILED -> "Failed, can retry"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown"
    }

    private fun permissionMessage(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "Allow the Nearby devices permission for Wi-Fi Direct"
        else "Allow the Location permission for Wi-Fi Direct (Android requires it to find nearby phones)"

    private fun locationServicesOn(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    private fun nameFor(address: String): String = _discovered.value.firstOrNull { it.address == address }?.name ?: address

    private fun setError(message: String) {
        lastError = message
        log.warn(kind, message)
        refreshStatus()
    }

    private fun refreshStatus() {
        val now = deps.clock.now()
        val open = _links.value.filter { it.isOpen }
        _status.value = when {
            !isSupported() -> TransportStatus(kind, TransportState.UNAVAILABLE, "Wi-Fi Direct is not supported on this phone", null, now)
            !isWifiEnabled() -> TransportStatus(kind, TransportState.DISABLED, "Wi-Fi is off (Wi-Fi Direct needs Wi-Fi on, not internet)", null, now)
            !hasPermission() -> TransportStatus(kind, TransportState.UNAVAILABLE, permissionMessage(), lastError, now)
            open.isNotEmpty() -> TransportStatus(kind, TransportState.CONNECTED, "Connected to " + open.joinToString { it.peer.displayName }, null, now)
            connectingTo != null -> TransportStatus(kind, TransportState.CONNECTING, "Inviting ${nameFor(connectingTo ?: "")}…", lastError, now)
            _groupInfo.value != null -> TransportStatus(kind, TransportState.CONNECTING, "Group formed; opening the RELAY connection…", lastError, now)
            _discovering.value -> TransportStatus(
                kind, TransportState.DISCOVERING,
                if (_autoDiscover.value) "Findable; looking for nearby phones" else "Searching for peers", lastError, now,
            )
            lastError != null -> TransportStatus(kind, TransportState.FAILED, "Ready", lastError, now)
            _autoDiscover.value -> TransportStatus(kind, TransportState.AVAILABLE_NOT_CONNECTED, "Findable by nearby phones; no peer connected", null, now)
            else -> TransportStatus(kind, TransportState.AVAILABLE_NOT_CONNECTED, "Ready; no peer connected", null, now)
        }
    }

    companion object {
        const val PORT = 47_321
        /** Android discovery lasts about 2 minutes; renew it well before it ends. */
        private const val AUTO_DISCOVERY_INTERVAL_MS = 60_000L
        private const val CLIENT_CONNECT_ATTEMPTS = 10
        private const val CLIENT_CONNECT_TIMEOUT_MS = 5_000
    }
}
