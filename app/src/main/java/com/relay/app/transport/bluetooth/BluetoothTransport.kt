package com.relay.app.transport.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Bluetooth Classic RFCOMM transport.
 *
 * - Server ("Listen"): listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID). Started
 *   automatically when Bluetooth is on and the permission is granted, so a phone is ready to
 *   receive without extra taps. Accepts several peers.
 * - Client: createRfcommSocketToServiceRecord(SERVICE_UUID) to a paired or discovered device.
 *   Android shows a pairing prompt automatically if the phones are not paired yet.
 * - Every socket is wrapped in a [StreamPeerLink], which does the HELLO handshake, framing,
 *   ACKs and keepalive. A link is published in [links] only after the handshake succeeds.
 * - Unexpected drops of links WE initiated are retried up to [MAX_AUTO_RECONNECTS] times.
 *
 * All blocking socket calls run on Dispatchers.IO.
 */
class BluetoothTransport(private val deps: TransportDeps) : PeerTransport {

    override val kind: TransportKind = TransportKind.BLUETOOTH

    private val context: Context = deps.context
    private val log = deps.log
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _status = MutableStateFlow(TransportStatus(kind, TransportState.UNAVAILABLE, "Starting…"))
    override val status: StateFlow<TransportStatus> = _status

    private val _links = MutableStateFlow<List<PeerLink>>(emptyList())
    override val links: StateFlow<List<PeerLink>> = _links

    private val _discovered = MutableStateFlow<List<DiscoveredPeer>>(emptyList())

    /** Bonded devices plus devices found by the last discovery. */
    override val discovered: StateFlow<List<DiscoveredPeer>> = _discovered

    private val _listening = MutableStateFlow(false)

    /** True while the RFCOMM server socket is accepting connections. */
    val isListening: StateFlow<Boolean> = _listening

    private val _discovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _discovering

    /** Devices found by inquiry scan since the last startDiscovery(). */
    private val found = ConcurrentHashMap<String, DiscoveredPeer>()
    private val connecting = ConcurrentHashMap<String, Job>()
    private val userDisconnected = ConcurrentHashMap.newKeySet<String>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()

    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var acceptJob: Job? = null
    @Volatile private var wantListening = true
    @Volatile private var lastError: String? = null
    @Volatile private var receiverRegistered = false

    // ------------------------------------------------------------------ capability checks

    override fun isSupported(): Boolean = adapter != null

    fun isEnabled(): Boolean = try {
        adapter?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }

    fun hasConnectPermission(): Boolean = RelayPermissions.allGranted(context, RelayPermissions.bluetoothConnect())

    fun hasDiscoveryPermission(): Boolean = RelayPermissions.allGranted(context, RelayPermissions.bluetoothDiscovery())

    /** Paired devices (empty without permission). Never invented. */
    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<DiscoveredPeer> {
        val a = adapter ?: return emptyList()
        if (!hasConnectPermission()) return emptyList()
        return try {
            a.bondedDevices.orEmpty().map { device ->
                DiscoveredPeer(
                    kind = kind,
                    address = device.address,
                    name = safeName(device),
                    detail = "Paired",
                    bonded = true,
                )
            }.sortedBy { it.name.lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun start() {
        registerReceiver()
        refreshDiscovered()
        if (wantListening && canUseRadio()) startListening()
        refreshStatus()
    }

    override fun stop() {
        cancelDiscovery()
        stopListening()
        disconnectAll()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        refreshStatus()
    }

    // ------------------------------------------------------------------ server

    /** Start the RFCOMM server ("Listen" mode). Safe to call repeatedly. */
    @SuppressLint("MissingPermission")
    fun startListening() {
        wantListening = true
        if (acceptJob?.isActive == true) return
        val a = adapter ?: return setError("This phone has no Bluetooth")
        if (!hasConnectPermission()) return setError("Allow the Nearby devices permission to listen")
        if (!isEnabled()) return setError("Turn Bluetooth on to listen")

        acceptJob = deps.scope.launch(Dispatchers.IO) {
            val server = try {
                a.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
            } catch (e: IOException) {
                setError("Could not start listening: ${e.message}")
                return@launch
            } catch (e: SecurityException) {
                setError("Bluetooth permission missing")
                return@launch
            }
            serverSocket = server
            _listening.value = true
            lastError = null
            log.info(kind, "Listening for RELAY connections (RFCOMM)")
            refreshStatus()
            try {
                while (isActive) {
                    val socket = server.accept() // blocks until a peer connects or the server is closed
                    launch { onSocketConnected(socket, isClient = false) }
                }
            } catch (e: IOException) {
                if (wantListening && isEnabled()) log.warn(kind, "Listening stopped: ${e.message}")
            } finally {
                runCatching { server.close() }
                serverSocket = null
                _listening.value = false
                refreshStatus()
            }
        }
    }

    fun stopListening() {
        wantListening = false
        runCatching { serverSocket?.close() }
        acceptJob?.cancel()
        acceptJob = null
        _listening.value = false
        refreshStatus()
    }

    // ------------------------------------------------------------------ discovery

    /** Start inquiry scan. Returns false (and sets a status error) if not enabled/permitted. */
    @SuppressLint("MissingPermission")
    fun startDiscovery(): Boolean {
        val a = adapter ?: run { setError("This phone has no Bluetooth"); return false }
        if (!hasDiscoveryPermission()) {
            setError("Allow the Nearby devices permission (or Location on Android 11 and lower) to discover phones")
            return false
        }
        if (!isEnabled()) {
            setError("Turn Bluetooth on to discover phones")
            return false
        }
        found.clear()
        refreshDiscovered()
        val ok = try {
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
        } catch (e: SecurityException) {
            false
        }
        if (!ok) {
            setError("Discovery did not start. On Android 11 and lower, turn Location on and try again.")
        } else {
            lastError = null
            log.info(kind, "Discovering nearby Bluetooth devices (about 12 seconds)")
        }
        refreshStatus()
        return ok
    }

    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        try {
            if (hasDiscoveryPermission() && adapter?.isDiscovering == true) adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            // ignore
        }
        _discovering.value = false
        refreshStatus()
    }

    // ------------------------------------------------------------------ client connections

    /** Connect to [address] (MAC) in the background; progress is reported through [status] and the log. */
    fun connect(address: String) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return setError("Invalid Bluetooth address")
        if (!canUseRadio()) {
            setError(if (!hasConnectPermission()) "Allow the Nearby devices permission first" else "Turn Bluetooth on first")
            return
        }
        if (_links.value.any { it.isOpen && it.remoteLabel == address }) {
            log.info(kind, "Already connected to $address")
            return
        }
        if (connecting[address]?.isActive == true) return
        userDisconnected.remove(address)
        val job = deps.scope.launch(Dispatchers.IO) { connectWithRetries(address, CONNECT_ATTEMPTS) }
        connecting[address] = job
        job.invokeOnCompletion {
            connecting.remove(address)
            refreshStatus()
        }
        refreshStatus()
    }

    /**
     * One quiet connection attempt to a phone RELAY already knows (automatic reconnection).
     * Needs no search and no "visible" phone: connecting to a known address always works when
     * the other phone is in range with Bluetooth on. Returns true if a link is now open.
     * Never reconnects to a phone the user disconnected on purpose.
     */
    suspend fun tryConnect(address: String): Boolean {
        if (!canUseRadio() || _discovering.value || address in userDisconnected) return false
        if (_links.value.any { it.isOpen && it.remoteLabel == address }) return true
        if (connecting[address]?.isActive == true) return false
        return withContext(Dispatchers.IO) { connectOnce(address, quiet = true) == null }
    }

    /** Addresses the user disconnected on purpose (cleared when they tap Connect again). */
    fun userDisconnectedAddresses(): Set<String> = userDisconnected.toSet()

    /** Politely close one link (BYE, then close). */
    fun disconnect(linkId: String) {
        val link = _links.value.firstOrNull { it.id == linkId } ?: return
        userDisconnected += link.remoteLabel
        deps.scope.launch(Dispatchers.IO) {
            (link as? StreamPeerLink)?.closeGracefully("disconnected by user") ?: link.close("disconnected by user")
        }
    }

    fun disconnectAll() {
        connecting.values.forEach { it.cancel() }
        _links.value.forEach { link ->
            userDisconnected += link.remoteLabel
            link.close("disconnected")
        }
        _links.value = emptyList()
        refreshStatus()
    }

    /** Intent asking the user to turn Bluetooth on (needs BLUETOOTH_CONNECT on Android 12+). */
    fun enableIntent(): Intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    /** Intent asking the user to make this phone discoverable (needs BLUETOOTH_ADVERTISE on Android 12+). */
    fun discoverableIntent(durationSec: Int = 300): Intent =
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, durationSec.coerceIn(30, 300))

    // ------------------------------------------------------------------ internals

    private fun canUseRadio(): Boolean = adapter != null && hasConnectPermission() && isEnabled()

    private suspend fun connectWithRetries(address: String, attempts: Int) {
        var error: String? = null
        for (attempt in 1..attempts) {
            error = connectOnce(address) ?: return // null = success
            log.warn(kind, "Connect attempt $attempt/$attempts to $address failed: $error")
            if (attempt < attempts) delay(1_500L * attempt)
        }
        setError(
            "Could not connect to ${nameFor(address)}: $error. Check that RELAY is open on the other phone, " +
                "Bluetooth is on, and the phones are paired.",
        )
    }

    /** Returns null on success, or an error message. [quiet] = automatic attempt, no log noise. */
    @SuppressLint("MissingPermission")
    private suspend fun connectOnce(address: String, quiet: Boolean = false): String? {
        val a = adapter ?: return "no Bluetooth adapter"
        try {
            // Discovery slows down and destabilises connections.
            if (hasDiscoveryPermission() && a.isDiscovering) a.cancelDiscovery()
        } catch (e: SecurityException) {
            // ignore
        }
        if (!quiet) log.info(kind, "Connecting to ${nameFor(address)} ($address)…")
        val socket: BluetoothSocket = try {
            a.getRemoteDevice(address).createRfcommSocketToServiceRecord(SERVICE_UUID)
        } catch (e: IOException) {
            return e.message ?: "could not create socket"
        } catch (e: SecurityException) {
            return "Bluetooth permission missing"
        } catch (e: IllegalArgumentException) {
            return "invalid address"
        }
        // connect() blocks and cannot be cancelled; closing the socket from a watchdog unblocks it.
        val watchdog = deps.scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            runCatching { socket.close() }
        }
        try {
            socket.connect()
        } catch (e: IOException) {
            runCatching { socket.close() }
            return e.message ?: "connection failed"
        } catch (e: SecurityException) {
            runCatching { socket.close() }
            return "Bluetooth permission missing"
        } finally {
            watchdog.cancel()
        }
        return if (onSocketConnected(socket, isClient = true)) null else "handshake failed"
    }

    /** Wraps a connected socket in a link, performs the handshake and publishes it. */
    private suspend fun onSocketConnected(socket: BluetoothSocket, isClient: Boolean): Boolean {
        val address = runCatching { socket.remoteDevice.address }.getOrNull() ?: "unknown"
        val streams = try {
            socket.inputStream to socket.outputStream
        } catch (e: IOException) {
            runCatching { socket.close() }
            return false
        }
        val link = StreamPeerLink(
            transport = kind,
            remoteLabel = address,
            input = streams.first,
            output = streams.second,
            closeUnderlying = { socket.close() },
            localIdentity = deps.identity,
            inbound = deps.inbound,
            log = log,
            clock = deps.clock,
            parentScope = deps.scope,
            onClosed = { closed, reason -> onLinkClosed(closed, reason, isClient) },
        )
        val peer = try {
            link.handshake()
        } catch (e: kotlinx.coroutines.CancellationException) {
            link.close("cancelled during handshake")
            throw e
        } catch (e: IOException) {
            log.warn(kind, "Handshake with $address failed: ${e.message}")
            if (!isClient || connecting.containsKey(address)) {
                setError("Connected to $address but the RELAY handshake failed: ${e.message}")
            }
            return false
        }

        // Both phones may connect to each other at the same moment. Keep exactly one link:
        // the one initiated by the device with the smaller id. Both sides apply the same rule.
        val existing = _links.value.firstOrNull { it.isOpen && it.peer.deviceId == peer.deviceId }
        if (existing != null) {
            val myId = deps.identity().deviceId
            val keepNew = isClient == (myId < peer.deviceId)
            if (keepNew) {
                existing.close("replaced by a newer link")
            } else {
                link.close("duplicate link")
                return true
            }
        }
        _links.update { current -> current.filter { it.isOpen && it.id != link.id } + link }
        reconnectAttempts.remove(address)
        lastError = null
        log.info(kind, "Connected to ${peer.displayName} (${peer.deviceId}) as ${if (isClient) "client" else "server"}")
        refreshStatus()
        return true
    }

    private fun onLinkClosed(link: StreamPeerLink, reason: String, wasClient: Boolean) {
        _links.update { current -> current.filter { it.id != link.id } }
        refreshStatus()
        val address = link.remoteLabel
        val unexpected = reason.startsWith("connection lost") || reason.startsWith("peer silent") ||
            reason.startsWith("keepalive failed") || reason.startsWith("remote closed")
        if (wasClient && unexpected && address !in userDisconnected && canUseRadio()) {
            val attempt = (reconnectAttempts[address] ?: 0) + 1
            if (attempt <= MAX_AUTO_RECONNECTS) {
                reconnectAttempts[address] = attempt
                val wait = RECONNECT_DELAYS_MS[attempt - 1]
                log.info(kind, "Link to ${nameFor(address)} dropped; reconnect attempt $attempt/$MAX_AUTO_RECONNECTS in ${wait / 1000}s")
                deps.scope.launch {
                    delay(wait)
                    if (address !in userDisconnected && _links.value.none { it.isOpen && it.remoteLabel == address }) connect(address)
                }
            } else {
                log.warn(kind, "Gave up reconnecting to ${nameFor(address)} after $MAX_AUTO_RECONNECTS attempts")
                reconnectAttempts.remove(address)
            }
        }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // System broadcasts; exported so the OS can deliver them on every API level.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_ON -> {
                            log.info(kind, "Bluetooth turned on")
                            refreshDiscovered()
                            if (wantListening) startListening()
                        }
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                            log.info(kind, "Bluetooth turned off; closing Bluetooth links")
                            val keepWanting = wantListening
                            runCatching { serverSocket?.close() }
                            _links.value.forEach { it.close("Bluetooth turned off") }
                            _listening.value = false
                            _discovering.value = false
                            wantListening = keepWanting
                        }
                    }
                    refreshStatus()
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    _discovering.value = true
                    refreshStatus()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _discovering.value = false
                    log.info(kind, "Discovery finished: ${found.size} device(s) found")
                    refreshStatus()
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        ?: return
                    val address = runCatching { device.address }.getOrNull() ?: return
                    val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
                    found[address] = DiscoveredPeer(
                        kind = kind,
                        address = address,
                        name = safeName(device),
                        detail = if (bonded) "Paired, in range" else "Found nearby (not paired)",
                        bonded = bonded,
                    )
                    refreshDiscovered()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refreshDiscovered()
            }
        }
    }

    private fun refreshDiscovered() {
        val bonded = bondedDevices().associateBy { it.address }
        val merged = LinkedHashMap<String, DiscoveredPeer>()
        found.values.forEach { merged[it.address] = it }
        bonded.values.forEach { b ->
            val inRange = found.containsKey(b.address)
            merged[b.address] = b.copy(detail = if (inRange) "Paired, in range" else "Paired")
        }
        _discovered.value = merged.values.sortedWith(compareByDescending<DiscoveredPeer> { it.bonded }.thenBy { it.name.lowercase() })
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String =
        (if (hasConnectPermission()) runCatching { device.name }.getOrNull() else null)
            ?.takeIf { it.isNotBlank() } ?: runCatching { device.address }.getOrDefault("Unknown device")

    private fun nameFor(address: String): String =
        _discovered.value.firstOrNull { it.address == address }?.name ?: address

    private fun setError(message: String) {
        lastError = message
        log.warn(kind, message)
        refreshStatus()
    }

    private fun refreshStatus() {
        val now = deps.clock.now()
        val open = _links.value.filter { it.isOpen }
        val status = when {
            adapter == null -> TransportStatus(kind, TransportState.UNAVAILABLE, "This phone has no Bluetooth", null, now)
            !hasConnectPermission() -> TransportStatus(kind, TransportState.UNAVAILABLE, "Permission needed: Nearby devices", lastError, now)
            !isEnabled() -> TransportStatus(kind, TransportState.DISABLED, "Bluetooth is off", null, now)
            open.isNotEmpty() -> TransportStatus(
                kind, TransportState.CONNECTED,
                "Connected to " + open.joinToString { it.peer.displayName }, null, now,
            )
            connecting.isNotEmpty() -> TransportStatus(kind, TransportState.CONNECTING, "Connecting…", lastError, now)
            _discovering.value -> TransportStatus(kind, TransportState.DISCOVERING, "Searching for nearby devices", lastError, now)
            lastError != null -> TransportStatus(kind, TransportState.FAILED, if (_listening.value) "Listening" else "Not listening", lastError, now)
            else -> TransportStatus(
                kind, TransportState.AVAILABLE_NOT_CONNECTED,
                if (_listening.value) "On and listening; no peer connected" else "On; not listening", null, now,
            )
        }
        _status.value = status
    }

    companion object {
        /** Shared by every RELAY install. Do not change, or old and new builds cannot talk. */
        val SERVICE_UUID: UUID = UUID.fromString("7c4f6a2e-3b1d-4e8a-9f2c-5d6e7a8b9c01")
        const val SERVICE_NAME = "RELAY-RFCOMM"

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val CONNECT_ATTEMPTS = 2
        private const val MAX_AUTO_RECONNECTS = 3
        private val RECONNECT_DELAYS_MS = longArrayOf(2_000L, 5_000L, 10_000L)
    }
}
