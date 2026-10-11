package com.relay.app.transport

import android.content.Context
import com.relay.app.connectivity.ConnectivityMonitor
import com.relay.app.data.RelaySettings
import com.relay.app.data.SettingsStore
import com.relay.app.model.Clock
import com.relay.app.model.SendState
import com.relay.app.model.TransportKind
import com.relay.app.model.TransportState
import com.relay.app.model.TransportStatus
import com.relay.app.relay.ProcessReport
import com.relay.app.relay.ProcessTrigger
import com.relay.app.relay.QueueProcessor
import com.relay.app.relay.RelayEngine
import com.relay.app.relay.RelayEvent
import com.relay.app.relay.RelayStore
import com.relay.app.service.RelayForegroundService
import com.relay.app.transport.bluetooth.BluetoothTransport
import com.relay.app.transport.internet.InternetRelayTransport
import com.relay.app.transport.localwifi.LocalWifiTransport
import com.relay.app.transport.wifidirect.WifiDirectTransport
import com.relay.app.work.RetryScheduler
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A recent failed transmission attempt, for the Connectivity & Routes screen. */
data class TransportFailure(
    val atUtc: Long,
    val kind: TransportKind,
    val peerName: String?,
    val messageIdShort: String?,
    val reason: String,
)

/**
 * Central coordinator: starts transports, merges their links and statuses, and re-evaluates
 * routes whenever anything changes (link up/down, radio change, internet up, app resume, retry
 * due, new message, relay message stored). Queue passes are serialised with a Mutex and
 * requests are merged, so a burst of events causes one pass, not dozens.
 *
 * The user never picks a transport per message: [QueueProcessor] + RoutePolicy choose
 * automatically. With "automatic transport" off in Settings, only the first preferred transport
 * is used for device-to-device links.
 */
class TransportManager(
    private val context: Context,
    val bluetooth: BluetoothTransport,
    val wifiDirect: WifiDirectTransport,
    val localWifi: LocalWifiTransport,
    val internet: InternetRelayTransport,
    private val processor: QueueProcessor,
    private val engine: RelayEngine,
    private val store: RelayStore,
    private val connectivity: ConnectivityMonitor,
    private val settings: SettingsStore,
    private val log: TransferLog,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    val peerTransports: List<PeerTransport> get() = listOf(bluetooth, wifiDirect, localWifi)

    /** One entry per transport (Bluetooth, Wi-Fi Direct, Local Wi-Fi, Internet Relay). */
    val statuses: StateFlow<List<TransportStatus>> =
        combine(bluetooth.status, wifiDirect.status, localWifi.status, internet.status) { b, w, l, i -> listOf(b, w, l, i) }
            .stateIn(scope, SharingStarted.Eagerly, initialStatuses())

    /** All open handshaken links across peer transports. */
    val links: StateFlow<List<PeerLink>> =
        combine(bluetooth.links, wifiDirect.links, localWifi.links) { b, w, l -> (b + w + l).filter { it.isOpen } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _activeTransport = MutableStateFlow<TransportKind?>(null)

    /** Transport that most recently carried an acknowledged transmission. */
    val activeTransport: StateFlow<TransportKind?> = _activeTransport

    private val _lastReport = MutableStateFlow<ProcessReport?>(null)
    val lastReport: StateFlow<ProcessReport?> = _lastReport

    private val _recentFailures = MutableStateFlow<List<TransportFailure>>(emptyList())
    val recentFailures: StateFlow<List<TransportFailure>> = _recentFailures

    private val started = AtomicBoolean(false)
    private val passMutex = Mutex()
    private val pendingTrigger = AtomicReference<ProcessTrigger?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var timerJob: Job? = null
    /** Re-checks the queue every minute while the internet relay is online and messages wait. */
    private var relayPollJob: Job? = null
    /** Reconnects to known RELAY phones over Bluetooth while the app is open. */
    private var autoConnectJob: Job? = null
    private val btFailures = HashMap<String, Int>()
    private val btNextTry = HashMap<String, Long>()
    private var applied: RelaySettings? = null
    @Volatile private var appInForeground = false

    /** Keeps this phone findable over Wi-Fi Direct while RELAY is on screen. */
    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appInForeground = true
            updateAutoDiscovery()
        }

        override fun onStop(owner: LifecycleOwner) {
            appInForeground = false
            updateAutoDiscovery()
        }
    }

    /** Idempotent. Starts connectivity monitoring, transports and the event wiring. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            val fixed = runCatching { engine.recoverAfterRestart() }.getOrDefault(0)
            if (fixed > 0) log.info(null, "Recovered $fixed message(s) interrupted while sending")
        }
        connectivity.start()
        bluetooth.start()
        wifiDirect.start()
        localWifi.start()
        internet.setEnabled(settings.current.internetRelayEnabled)
        internet.start()
        applySettings(settings.current)
        // Must be registered on the main thread; start() is called from Application.onCreate.
        runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver) }

        scope.launch { processingLoop() }
        scope.launch { watchLinks() }
        scope.launch {
            connectivity.internet.map { it.usable }.distinctUntilChanged().collect { usable ->
                if (usable) requestProcessing(ProcessTrigger.INTERNET_UP)
            }
        }
        scope.launch {
            internet.status.map { it.state == TransportState.CONNECTED }.distinctUntilChanged().collect { ready ->
                if (ready) requestProcessing(ProcessTrigger.INTERNET_UP)
            }
        }
        scope.launch {
            combine(connectivity.bluetoothOn, connectivity.wifiOn) { b, w -> b to w }
                .distinctUntilChanged()
                .drop(1)
                .collect { requestProcessing(ProcessTrigger.TRANSPORT_STATE) }
        }
        scope.launch { engine.events.collect { onEngineEvent(it) } }
        scope.launch { settings.settings.drop(1).collect { applySettings(it) } }

        RetryScheduler.schedulePeriodic(context)
        requestProcessing(ProcessTrigger.APP_RESUMED)
    }

    /** Call after the user grants permissions so transports can start listening. */
    fun onPermissionsChanged() {
        bluetooth.start()
        wifiDirect.start()
        updateAutoDiscovery()
        requestProcessing(ProcessTrigger.TRANSPORT_STATE)
    }

    /**
     * While RELAY is open (or always, with "keep listening" on): stay findable over Wi-Fi Direct
     * and reconnect to known RELAY phones over Bluetooth, so nobody has to search or tap.
     */
    private fun updateAutoDiscovery() {
        val active = appInForeground || settings.current.keepListeningInBackground
        wifiDirect.setAutoDiscover(active)
        if (active) {
            if (autoConnectJob?.isActive != true) {
                autoConnectJob = scope.launch {
                    while (true) {
                        runCatching { autoConnectRound() }
                        delay(AUTO_CONNECT_INTERVAL_MS)
                    }
                }
            }
        } else {
            autoConnectJob?.cancel()
            autoConnectJob = null
        }
    }

    private suspend fun autoConnectRound() {
        if (!bluetooth.isEnabled() || !bluetooth.hasConnectPermission() || bluetooth.isDiscovering.value) return
        val candidates = AutoConnectPolicy.candidates(
            peers = store.knownPeers(),
            openLinks = links.value,
            nowUtc = clock.now(),
            nextAllowedAt = btNextTry,
            userDisconnected = bluetooth.userDisconnectedAddresses(),
        )
        for (candidate in candidates) {
            if (bluetooth.tryConnect(candidate.address)) {
                btFailures.remove(candidate.address)
                btNextTry.remove(candidate.address)
                log.info(TransportKind.BLUETOOTH, "Reconnected automatically to ${candidate.deviceId}")
            } else {
                val failures = (btFailures[candidate.address] ?: 0) + 1
                btFailures[candidate.address] = failures
                btNextTry[candidate.address] = clock.now() + AutoConnectPolicy.backoffMs(failures)
            }
        }
    }

    /** Non-blocking: asks for a processing pass. Bursts are merged into one pass. */
    fun requestProcessing(trigger: ProcessTrigger) {
        pendingTrigger.getAndUpdate { current -> merge(current, trigger) }
        wake.trySend(Unit)
    }

    /** Runs a pass now (waits for any running pass first). Used by RetryWorker. */
    suspend fun processNow(trigger: ProcessTrigger): ProcessReport = passMutex.withLock {
        val currentLinks = effectiveLinks()
        val relay = internet.takeIf { settings.current.internetRelayEnabled && it.isUsable() }
        val report = processor.processOnce(currentLinks, relay, trigger)
        _lastReport.value = report
        scheduleNext(report)
        relayPollJob?.cancel()
        if (relay != null && report.noRoute > 0) {
            // e.g. the recipient has not registered yet: try again soon instead of waiting for
            // some other event (it may register any minute).
            relayPollJob = scope.launch {
                delay(RELAY_POLL_MS)
                requestProcessing(ProcessTrigger.RETRY_TIMER)
            }
        }
        report
    }

    // ------------------------------------------------------------------ internals

    private suspend fun processingLoop() {
        for (signal in wake) {
            val trigger = pendingTrigger.getAndSet(null) ?: continue
            try {
                processNow(trigger)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(null, "Queue pass failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** A trigger that skips backoff wins over one that respects it. */
    private fun merge(current: ProcessTrigger?, incoming: ProcessTrigger): ProcessTrigger = when {
        current == null -> incoming
        incoming.ignoresBackoff && !current.ignoresBackoff -> incoming
        else -> current
    }

    private suspend fun watchLinks() {
        var known = emptySet<String>()
        links.collect { list ->
            val ids = list.map { it.id }.toSet()
            val added = list.filter { it.id !in known }
            val removed = known - ids
            known = ids
            for (link in added) {
                runCatching { engine.onPeerConnected(link.peer, link.transport, link.remoteLabel) }
                scope.launch { runCatching { processor.shareReceipts(listOf(link)) } }
            }
            if (added.isNotEmpty()) requestProcessing(ProcessTrigger.LINK_UP)
            if (removed.isNotEmpty()) requestProcessing(ProcessTrigger.LINK_DOWN)
        }
    }

    private fun effectiveLinks(): List<PeerLink> {
        val open = links.value.filter { it.isOpen }
        val s = settings.current
        if (s.autoTransport) return open
        val only = s.preferredOrder.firstOrNull() ?: return open
        return open.filter { it.transport == only }
    }

    private fun scheduleNext(report: ProcessReport) {
        val now = clock.now()
        val next = report.nextRetryAtUtc
        timerJob?.cancel()
        if (next != null) {
            timerJob = scope.launch {
                delay((next - now).coerceAtLeast(0L) + 250L)
                requestProcessing(ProcessTrigger.RETRY_TIMER)
            }
            RetryScheduler.scheduleAt(context, next, now)
        }
        val waiting = report.noRoute > 0 || report.failedAttempts > 0
        if (waiting && settings.current.internetRelayEnabled && internet.isConfigured && !internet.isUsable()) {
            RetryScheduler.scheduleWhenInternet(context)
        }
    }

    private fun onEngineEvent(event: RelayEvent) {
        when (event) {
            is RelayEvent.RelayStored -> requestProcessing(ProcessTrigger.INCOMING_RELAY)
            // A received SOS broadcast that should keep spreading.
            is RelayEvent.IncomingStored -> if (event.message.sendState == SendState.QUEUED) requestProcessing(ProcessTrigger.INCOMING_RELAY)
            is RelayEvent.ReceiptAvailable -> scope.launch { runCatching { processor.shareReceipts(links.value) } }
            is RelayEvent.AttemptFailed -> _recentFailures.update { current ->
                (listOf(TransportFailure(event.atUtc, event.transport, event.peerName, TransferLog.shortId(event.messageId), event.reason)) + current)
                    .take(MAX_FAILURES)
            }
            is RelayEvent.Delivered -> _activeTransport.value = event.message.lastTransport ?: _activeTransport.value
            is RelayEvent.HopAcked -> _activeTransport.value = event.message.lastTransport ?: _activeTransport.value
            is RelayEvent.Uploaded -> _activeTransport.value = TransportKind.INTERNET_RELAY
            else -> Unit
        }
    }

    /** Applies only the settings that changed since last time. */
    private fun applySettings(s: RelaySettings) {
        val previous = applied
        applied = s
        if (previous == null || previous.internetRelayEnabled != s.internetRelayEnabled) {
            internet.setEnabled(s.internetRelayEnabled)
        }
        val wantLan = s.localWifiEnabled && s.localNetworkKey.isNotBlank()
        val hadLan = previous != null && previous.localWifiEnabled && previous.localNetworkKey.isNotBlank()
        if (previous == null || wantLan != hadLan || previous.localNetworkKey != s.localNetworkKey) {
            if (wantLan) {
                if (hadLan) localWifi.disable()
                localWifi.enable()
            } else if (previous != null) {
                localWifi.disable()
            }
        }
        if (previous == null || previous.keepListeningInBackground != s.keepListeningInBackground) {
            if (previous != null) updateAutoDiscovery()
            if (s.keepListeningInBackground) {
                if (!RelayForegroundService.running.value) RelayForegroundService.start(context)
            } else if (previous != null) {
                RelayForegroundService.stop(context)
            }
        }
        if (previous != null && (previous.autoTransport != s.autoTransport || previous.preferredOrder != s.preferredOrder ||
                previous.relayForOthers != s.relayForOthers)
        ) {
            requestProcessing(ProcessTrigger.TRANSPORT_STATE)
        }
    }

    private fun initialStatuses(): List<TransportStatus> = listOf(
        bluetooth.status.value,
        wifiDirect.status.value,
        localWifi.status.value,
        internet.status.value,
    )

    private companion object {
        const val MAX_FAILURES = 30
        const val RELAY_POLL_MS = 60_000L
        const val AUTO_CONNECT_INTERVAL_MS = 20_000L
    }
}
