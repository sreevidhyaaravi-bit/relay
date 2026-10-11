package com.relay.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.relay.app.notifications.Notifier
import com.relay.app.relayContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Optional "keep listening in background" mode (foregroundServiceType=connectedDevice).
 * Keeps the process alive so the Bluetooth / Wi-Fi Direct / LAN listeners stay up while the
 * screen is off. Started only by explicit user choice in Settings.
 * It does NOT guarantee delivery: force-stop, battery savers and OEM task killers still stop it.
 */
class RelayForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = applicationContext.relayContainer
        val notification = container.notifier.foregroundNotification("Ready to receive and relay messages nearby")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, Notifier.FOREGROUND_ID, notification, type)
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background.
            container.transferLog.warn(null, "Background listening could not start: ${e.javaClass.simpleName}")
            _running.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        _running.value = true
        container.transportManager.start()
        container.bluetooth.startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        super.onDestroy()
    }

    companion object {
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> get() = _running

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, RelayForegroundService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayForegroundService::class.java))
            _running.value = false
        }
    }
}
