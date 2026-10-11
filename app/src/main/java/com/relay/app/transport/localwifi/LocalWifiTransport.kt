package com.relay.app.transport.localwifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.relay.app.model.DiscoveredPeer
import com.relay.app.model.TransportKind
import com.relay.app.model.TransportState
import com.relay.app.model.TransportStatus
import com.relay.app.transport.PeerLink
import com.relay.app.transport.PeerTransport
import com.relay.app.transport.StreamPeerLink
import com.relay.app.transport.TransportDeps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Optional transport between phones on the same Wi-Fi network (no internet needed).
 *
 * - A TCP server, advertised with NSD (mDNS/DNS-SD) as [SERVICE_TYPE].
 * - NSD discovery of other RELAY phones on the network.
 * - [LanAuth] mutual HMAC challenge with the shared team network key BEFORE the HELLO, so a
 *   random device on the same Wi-Fi cannot inject messages.
 *
 * Being on the same Wi-Fi does not guarantee phones can talk: many routers (guest networks,
 * hotspots, campus Wi-Fi) use client isolation. That shows up as a clear connection error.
 */
class LocalWifiTransport(
    private val deps: TransportDeps,
    /** Shared secret from Settings; null/blank means local Wi-Fi cannot be enabled. */
    private val networkKey: () -> String?,
) : PeerTransport {

    override val kind: TransportKind = TransportKind.LOCAL_WIFI

    private val context: Context = deps.context
    private val log = deps.log
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _status = MutableStateFlow(TransportStatus(kind, TransportState.DISABLED, "Off (enable in Settings)"))
    override val status: StateFlow<TransportStatus> = _status

    private val _links = MutableStateFlow<List<PeerLink>>(emptyList())
    override val links: StateFlow<List<PeerLink>> = _links

    private val _discovered = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val discovered: StateFlow<List<DiscoveredPeer>> = _discovered

    private val _active = MutableStateFlow(false)

    /** True while serving + advertising + discovering. */
    val isActive: StateFlow<Boolean> = _active

    private val _localAddress = MutableStateFlow<String?>(null)

    /** "ip:port" this phone is serving on, when active. */
    val localAddress: StateFlow<String?> = _localAddress

    private val found = ConcurrentHashMap<String, DiscoveredPeer>() // serviceName -> peer
    private val connecting = ConcurrentHashMap<String, Job>()
    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var serverJob: Job? = null
    @Volatile private var resolverJob: Job? = null
    @Volatile private var registrationListener: NsdManager.RegistrationListener? = null
    @Volatile private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var lastError: String? = null

    override fun isSupported(): Boolean = nsd != null

    override fun start() {
        refreshStatus()
    }

    override fun stop() {
        disable()
    }

    /** Start server, NSD registration and discovery. Fails with a status error if no network key or no Wi-Fi network. */
    fun enable() {
        if (_active.value) return
        val key = networkKey()?.trim().orEmpty()
        if (key.isEmpty()) return setError("Set a team network key in Settings first (all phones must use the same key)")
        val nsdManager = nsd ?: return setError("Network service discovery is not available on this phone")
        val ip = wifiIpv4()
        if (ip == null) return setError("Connect the phones to the same Wi-Fi network first (internet is not required)")

        _active.value = true
        lastError = null
        val server = try {
            openServer()
        } catch (e: IOException) {
            _active.value = false
            return setError("Could not open a local port: ${e.message}")
        }
        serverSocket = server
        _localAddress.value = "$ip:${server.localPort}"
        serverJob = deps.scope.launch(Dispatchers.IO) { acceptLoop(server) }
        resolverJob = deps.scope.launch { resolveLoop(nsdManager) }

        multicastLock = runCatching {
            wifiManager?.createMulticastLock("relay-nsd")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        register(nsdManager, server.localPort)
        discover(nsdManager)
        log.info(kind, "Local Wi-Fi on: serving at $ip:${server.localPort}, looking for RELAY phones on this network")
        refreshStatus()
    }

    fun disable() {
        val nsdManager = nsd
        registrationListener?.let { l -> runCatching { nsdManager?.unregisterService(l) } }
        discoveryListener?.let { l -> runCatching { nsdManager?.stopServiceDiscovery(l) } }
        registrationListener = null
        discoveryListener = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverJob?.cancel()
        resolverJob?.cancel()
        disconnectAll()
        found.clear()
        _discovered.value = emptyList()
        _localAddress.value = null
        if (_active.value) log.info(kind, "Local Wi-Fi off")
        _active.value = false
        refreshStatus()
    }

    /** Connect to a peer from [discovered] (address is "host:port"). */
    fun connect(address: String) {
        val host = address.substringBeforeLast(':', "")
        val port = address.substringAfterLast(':', "").toIntOrNull()
        if (host.isEmpty() || port == null) return setError("Invalid address '$address' (expected host:port)")
        connectManual(host, port)
    }

    fun connectManual(host: String, port: Int) {
        val key = networkKey()?.trim().orEmpty()
        if (key.isEmpty()) return setError("Set a team network key in Settings first")
        if (port !in 1..65535) return setError("Invalid port")
        val target = "$host:$port"
        if (connecting[target]?.isActive == true) return
        if (_links.value.any { it.isOpen && it.remoteLabel == target }) return
        val job = deps.scope.launch(Dispatchers.IO) {
            val socket = Socket()
            try {
                log.info(kind, "Connecting to $target…")
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = AUTH_TIMEOUT_MS
                LanAuth.authenticateAsClient(socket.getInputStream(), socket.getOutputStream(), key)
                socket.soTimeout = 0
                onAuthenticated(socket, target, isClient = true)
            } catch (e: LanAuth.AuthFailed) {
                runCatching { socket.close() }
                setError("Authentication with $target failed: ${e.message}")
            } catch (e: SocketTimeoutException) {
                runCatching { socket.close() }
                setError(isolationHint(target))
            } catch (e: ConnectException) {
                runCatching { socket.close() }
                setError(isolationHint(target))
            } catch (e: IOException) {
                runCatching { socket.close() }
                setError("Could not connect to $target: ${e.message}")
            }
        }
        connecting[target] = job
        job.invokeOnCompletion {
            connecting.remove(target)
            refreshStatus()
        }
        refreshStatus()
    }

    fun disconnectAll() {
        connecting.values.forEach { it.cancel() }
        _links.value.forEach { it.close("disconnected") }
        _links.value = emptyList()
        refreshStatus()
    }

    // ------------------------------------------------------------------ server side

    private fun openServer(): ServerSocket = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(DEFAULT_PORT))
        }
    } catch (e: IOException) {
        // Port in use: let the OS pick one; NSD advertises whatever port we got.
        ServerSocket(0)
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val socket = server.accept()
                deps.scope.launch(Dispatchers.IO) {
                    val remote = "${socket.inetAddress?.hostAddress}:${socket.port}"
                    try {
                        socket.soTimeout = AUTH_TIMEOUT_MS
                        LanAuth.authenticateAsServer(socket.getInputStream(), socket.getOutputStream(), networkKey()?.trim().orEmpty())
                        socket.soTimeout = 0
                        onAuthenticated(socket, remote, isClient = false)
                    } catch (e: IOException) {
                        log.warn(kind, "Rejected local connection from $remote: ${e.message}")
                        runCatching { socket.close() }
                    }
                }
            }
        } catch (e: IOException) {
            // server closed
        }
    }

    private suspend fun onAuthenticated(socket: Socket, remote: String, isClient: Boolean) {
        runCatching { socket.tcpNoDelay = true; socket.keepAlive = true }
        val link = StreamPeerLink(
            transport = kind,
            remoteLabel = remote,
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
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
            log.warn(kind, "Handshake with $remote failed: ${e.message}")
            return
        }
        val existing = _links.value.firstOrNull { it.isOpen && it.peer.deviceId == peer.deviceId }
        if (existing != null) {
            val keepNew = isClient == (deps.identity().deviceId < peer.deviceId)
            if (keepNew) existing.close("replaced by a newer link") else {
                link.close("duplicate link")
                return
            }
        }
        _links.update { current -> current.filter { it.isOpen && it.id != link.id } + link }
        lastError = null
        log.info(kind, "Connected to ${peer.displayName} over local Wi-Fi (authenticated)")
        refreshStatus()
    }

    // ------------------------------------------------------------------ NSD

    private fun register(nsdManager: NsdManager, port: Int) {
        val me = deps.identity()
        val info = NsdServiceInfo().apply {
            serviceName = "RELAY-${me.deviceId}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", me.deviceId)
            setAttribute("name", me.displayName.take(30))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                log.info(kind, "Advertising on the local network as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                setError("Could not advertise on the local network (error $errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { setError("Could not advertise on the local network: ${it.message}") }
    }

    private fun discover(nsdManager: NsdManager) {
        val ownName = "RELAY-${deps.identity().deviceId}"
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == ownName || !info.serviceName.startsWith("RELAY-")) return
                resolveQueue.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
                publishFound()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                setError("Local discovery failed to start (error $errorCode). You can still connect by IP address.")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { setError("Local discovery failed: ${it.message}") }
    }

    /** Older Android versions allow only one resolve at a time, so resolve sequentially. */
    @Suppress("DEPRECATION")
    private suspend fun resolveLoop(nsdManager: NsdManager) {
        for (info in resolveQueue) {
            val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
                suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                    val listener = object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            if (cont.isActive) cont.resume(serviceInfo)
                        }
                    }
                    try {
                        nsdManager.resolveService(info, listener)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            } ?: continue
            val host = resolved.host?.hostAddress ?: continue
            val attrs = runCatching { resolved.attributes }.getOrNull().orEmpty()
            val name = attrs["name"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: resolved.serviceName
            found[resolved.serviceName] = DiscoveredPeer(
                kind = kind,
                address = "$host:${resolved.port}",
                name = name,
                detail = "On this Wi-Fi network (${attrs["id"]?.toString(Charsets.UTF_8) ?: resolved.serviceName.removePrefix("RELAY-")})",
            )
            publishFound()
        }
    }

    private fun publishFound() {
        _discovered.value = found.values.sortedBy { it.name.lowercase() }
        refreshStatus()
    }

    // ------------------------------------------------------------------ helpers

    /** IPv4 address of the Wi-Fi interface (wlan*), or null when not on a Wi-Fi network. */
    private fun wifiIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private fun isolationHint(target: String) =
        "Could not reach $target. Check that RELAY local Wi-Fi is on there. Many routers block " +
            "phone-to-phone traffic (client isolation); if so, use Wi-Fi Direct or Bluetooth instead."

    private fun setError(message: String) {
        lastError = message
        log.warn(kind, message)
        refreshStatus()
    }

    private fun refreshStatus() {
        val now = deps.clock.now()
        val open = _links.value.filter { it.isOpen }
        _status.value = when {
            !isSupported() -> TransportStatus(kind, TransportState.UNAVAILABLE, "Not supported on this phone", null, now)
            open.isNotEmpty() -> TransportStatus(kind, TransportState.CONNECTED, "Connected to " + open.joinToString { it.peer.displayName }, null, now)
            connecting.isNotEmpty() -> TransportStatus(kind, TransportState.CONNECTING, "Connecting…", lastError, now)
            !_active.value && lastError != null -> TransportStatus(kind, TransportState.FAILED, "Off", lastError, now)
            !_active.value -> TransportStatus(kind, TransportState.DISABLED, "Off (enable in Settings with a team key)", null, now)
            lastError != null -> TransportStatus(kind, TransportState.FAILED, "On at ${_localAddress.value}", lastError, now)
            found.isNotEmpty() -> TransportStatus(kind, TransportState.AVAILABLE_NOT_CONNECTED, "${found.size} RELAY phone(s) found on this network", null, now)
            else -> TransportStatus(kind, TransportState.DISCOVERING, "On at ${_localAddress.value}; looking for RELAY phones", null, now)
        }
    }

    companion object {
        const val SERVICE_TYPE = "_relaymsg._tcp."
        const val DEFAULT_PORT = 47_322
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val AUTH_TIMEOUT_MS = 8_000
        private const val RESOLVE_TIMEOUT_MS = 6_000L
    }
}
