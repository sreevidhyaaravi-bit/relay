package com.relay.app.notifications

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.relay.app.MainActivity
import com.relay.app.R
import com.relay.app.data.db.MessageEntity
import com.relay.app.model.MessageType
import com.relay.app.permissions.RelayPermissions

/**
 * Local notifications. Silently does nothing if POST_NOTIFICATIONS is not granted.
 * Wording never overclaims: RELAY is a messaging tool, not an emergency dispatch service.
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Earlier builds used channels with Android's default vibration, which would cut across
        // RELAY's Morse-code patterns (see Haptics). Channel settings cannot be changed once
        // created, so those channels are replaced.
        LEGACY_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SOS, "SOS messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming SOS messages from nearby RELAY users"
                enableVibration(false) // RELAY plays its own Morse SOS vibration
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Incoming RELAY messages"
                enableVibration(false) // RELAY plays its own vibration pattern per message type
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Delivery status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Delivered, expired or paused messages"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Background listening", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while RELAY keeps listening in the background"
            },
        )
    }

    fun canPost(): Boolean =
        RelayPermissions.allGranted(context, RelayPermissions.notifications()) && manager.areNotificationsEnabled()

    fun notifyIncoming(message: MessageEntity, withStopAction: Boolean = false) {
        val sos = message.messageType == MessageType.SOS
        val title = if (sos) "🆘 SOS from ${message.senderName}" else "${message.messageType.symbol} ${message.senderName}"
        val battery = message.batteryPercent?.let { "  ·  🔋 $it%" } ?: ""
        post(
            id = message.id.hashCode(),
            channel = if (sos) CHANNEL_SOS else CHANNEL_MESSAGES,
            title = title + battery,
            text = message.body,
            priority = if (sos) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT,
            category = if (sos) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_MESSAGE,
            stopAlarm = sos && withStopAction,
        )
    }

    fun notifyDelivered(message: MessageEntity) {
        val who = message.deliveredToName ?: message.recipientName ?: message.recipientId
        val prefix = if (message.messageType == MessageType.SOS) "🆘 " else ""
        post(
            id = message.id.hashCode(),
            channel = CHANNEL_STATUS,
            title = "${prefix}Delivered to $who",
            text = message.body.take(80),
            priority = NotificationCompat.PRIORITY_LOW,
            category = NotificationCompat.CATEGORY_STATUS,
        )
    }

    fun notifyExpired(message: MessageEntity) {
        val prefix = if (message.messageType == MessageType.SOS) "🆘 " else ""
        post(
            id = message.id.hashCode(),
            channel = CHANNEL_STATUS,
            title = "${prefix}Message expired before delivery",
            text = "No longer forwarded. Open RELAY to send a new one if it is still needed.",
            priority = NotificationCompat.PRIORITY_DEFAULT,
            category = NotificationCompat.CATEGORY_STATUS,
        )
    }

    fun notifyPaused(message: MessageEntity) {
        val prefix = if (message.messageType == MessageType.SOS) "🆘 " else ""
        post(
            id = message.id.hashCode(),
            channel = CHANNEL_STATUS,
            title = "${prefix}Sending paused after repeated failures",
            text = "Open the Outbox and tap Retry now.",
            priority = NotificationCompat.PRIORITY_DEFAULT,
            category = NotificationCompat.CATEGORY_STATUS,
        )
    }

    /** Ongoing notification for RelayForegroundService. */
    fun foregroundNotification(text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle("RELAY is listening")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent())
            .build()

    @SuppressLint("MissingPermission") // checked by canPost()
    private fun post(id: Int, channel: String, title: String, text: String, priority: Int, category: String, stopAlarm: Boolean = false) {
        if (!canPost()) return
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(priority)
            .setCategory(category)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
        if (stopAlarm) {
            val stop = PendingIntent.getBroadcast(
                context, 0, Intent(context, AlarmStopReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(R.drawable.ic_stat_relay, "Stop alarm", stop)
            builder.setDeleteIntent(stop) // swiping the notification away also silences it
        }
        runCatching { manager.notify(id, builder.build()) }
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_SOS = "relay_sos_v2"
        const val CHANNEL_MESSAGES = "relay_messages_v2"
        private val LEGACY_CHANNELS = listOf("relay_sos", "relay_messages")
        const val CHANNEL_STATUS = "relay_status"
        const val CHANNEL_SERVICE = "relay_service"
        const val FOREGROUND_ID = 1001
    }
}
