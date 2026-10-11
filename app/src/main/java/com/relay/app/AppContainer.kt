package com.relay.app

import android.content.Context
import com.relay.app.connectivity.ConnectivityMonitor
import com.relay.app.data.GroupStore
import com.relay.app.data.MessageRepository
import com.relay.app.data.RoomRelayStore
import com.relay.app.data.SettingsStore
import com.relay.app.data.db.RelayDatabase
import com.relay.app.location.LocationProvider
import com.relay.app.model.Clock
import com.relay.app.model.SystemClock
import com.relay.app.notifications.AlertCenter
import com.relay.app.notifications.Haptics
import com.relay.app.notifications.Notifier
import com.relay.app.relay.QueueProcessor
import com.relay.app.relay.RelayEngine
import com.relay.app.relay.RelayEvent
import com.relay.app.relay.RelayStore
import com.relay.app.sim.RelayLab
import com.relay.app.transport.TransferLog
import com.relay.app.transport.TransportDeps
import com.relay.app.transport.TransportManager
import com.relay.app.transport.bluetooth.BluetoothTransport
import com.relay.app.transport.internet.InternetRelayTransport
import com.relay.app.transport.localwifi.LocalWifiTransport
import com.relay.app.transport.wifidirect.WifiDirectTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency injection: every long-lived object is created exactly once here.
 * Beginners: start reading the app from this file.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /** Lives as long as the process. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val clock: Clock = SystemClock
    val settings = SettingsStore(appContext)
    val groups = GroupStore(appContext)
    val database: RelayDatabase = RelayDatabase.build(appContext)
    val store: RelayStore = RoomRelayStore(database)
    val transferLog = TransferLog(clock = clock)

    val engine = RelayEngine(
        store = store,
        identity = { settings.identity() },
        config = { settings.relayConfig() },
        clock = clock,
    )

    val connectivity = ConnectivityMonitor(appContext, appScope)
    val location = LocationProvider(appContext, clock)
    val notifier = Notifier(appContext)
    val haptics = Haptics(appContext)
    val alerts = AlertCenter(appContext, settings, appScope)

    private val transportDeps = TransportDeps(
        context = appContext,
        identity = { settings.identity() },
        inbound = engine,
        log = transferLog,
        scope = appScope,
        clock = clock,
    )

    val bluetooth = BluetoothTransport(transportDeps)
    val wifiDirect = WifiDirectTransport(transportDeps)
    val localWifi = LocalWifiTransport(transportDeps, networkKey = { settings.current.localNetworkKey })
    val internet = InternetRelayTransport(
        context = appContext,
        identity = { settings.identity() },
        inbound = engine,
        internetState = connectivity.internet,
        log = transferLog,
        scope = appScope,
        clock = clock,
    )

    val processor = QueueProcessor(
        store = store,
        engine = engine,
        identity = { settings.identity() },
        config = { settings.relayConfig() },
        clock = clock,
        log = transferLog,
    )

    val transportManager = TransportManager(
        context = appContext,
        bluetooth = bluetooth,
        wifiDirect = wifiDirect,
        localWifi = localWifi,
        internet = internet,
        processor = processor,
        engine = engine,
        store = store,
        connectivity = connectivity,
        settings = settings,
        log = transferLog,
        clock = clock,
        scope = appScope,
    )

    val repository = MessageRepository(database, engine, transportManager)

    /** Simulated relay network. Completely separate in-memory data; never touches real messages. */
    val relayLab: RelayLab by lazy { RelayLab() }

    private var started = false

    /** Called once from [RelayApp.onCreate]. */
    fun start() {
        if (started) return
        started = true
        notifier.ensureChannels()
        transportManager.start()
        alerts.warmUp()
        appScope.launch {
            engine.events.collect { event ->
                when (event) {
                    is RelayEvent.IncomingStored -> {
                        notifier.notifyIncoming(event.message, withStopAction = settings.current.sosAlarm)
                        if (settings.current.vibrateOnIncoming) haptics.forIncoming(event.message.messageType)
                        alerts.onIncoming(event.message)
                    }
                    is RelayEvent.Delivered -> notifier.notifyDelivered(event.message)
                    is RelayEvent.Expired -> notifier.notifyExpired(event.message)
                    is RelayEvent.Paused -> notifier.notifyPaused(event.message)
                    else -> Unit
                }
            }
        }
    }
}
