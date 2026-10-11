package com.relay.app.relay

import com.relay.app.data.db.MessageEntity
import com.relay.app.model.DeliveryState
import com.relay.app.model.DeviceIdentity
import com.relay.app.model.Direction
import com.relay.app.model.LocationInfo
import com.relay.app.model.LocationSource
import com.relay.app.model.MessageType
import com.relay.app.model.PeerIdentity
import com.relay.app.model.SendState
import com.relay.app.model.TransportKind
import com.relay.app.protocol.ProtocolCodec
import com.relay.app.protocol.WireHop
import com.relay.app.protocol.WireLocation
import com.relay.app.protocol.WireMessage
import kotlinx.serialization.builtins.ListSerializer

/**
 * Converts between the stored [MessageEntity] and the on-the-wire [WireMessage].
 * The original sender, recipient, creation time, priority, expiry and location are copied
 * unchanged in both directions; only hopCount and route grow as the message travels.
 */
object MessageMapper {

    private val routeSerializer = ListSerializer(WireHop.serializer())

    fun encodeRoute(route: List<WireHop>): String = ProtocolCodec.json.encodeToString(routeSerializer, route)

    fun decodeRoute(json: String?): List<WireHop> =
        if (json.isNullOrBlank()) emptyList()
        else runCatching { ProtocolCodec.json.decodeFromString(routeSerializer, json) }.getOrDefault(emptyList())

    fun LocationInfo.toWire(): WireLocation =
        WireLocation(latitude, longitude, accuracyMeters, capturedAtUtc, label, source.name)

    fun WireLocation.toModel(): LocationInfo? {
        val src = LocationSource.entries.firstOrNull { it.name == source } ?: return null
        return LocationInfo(lat, lon, accuracyM, capturedAtUtc, label, src)
    }

    /**
     * Builds the copy to transmit from THIS device ([self]) over [via] at [nowUtc]:
     * hopCount + 1 and this device appended to the carried route.
     */
    fun toWireForSend(entity: MessageEntity, self: DeviceIdentity, via: TransportKind, nowUtc: Long): WireMessage {
        val route = decodeRoute(entity.routeJson) + WireHop(self.deviceId, self.displayName, via.name, nowUtc)
        return WireMessage(
            id = entity.id,
            senderId = entity.senderId,
            senderName = entity.senderName,
            recipientId = entity.recipientId,
            recipientName = entity.recipientName,
            body = entity.body,
            type = entity.messageType.name,
            priority = entity.messageType.priority,
            createdAtUtc = entity.createdAtUtc,
            expiresAtUtc = entity.expiresAtUtc,
            location = entity.location()?.toWire(),
            hopCount = entity.hopCount + 1,
            maxHops = entity.maxHops,
            route = route,
            batteryPercent = entity.batteryPercent,
        )
    }

    /** Creates the row stored by a receiving device. */
    fun fromWire(
        msg: WireMessage,
        direction: Direction,
        from: PeerIdentity,
        via: TransportKind,
        receivedAtUtc: Long,
    ): MessageEntity {
        val type = MessageType.fromName(msg.type) ?: MessageType.NORMAL
        val loc = msg.location?.toModel()
        return MessageEntity(
            id = msg.id,
            senderId = msg.senderId,
            senderName = msg.senderName,
            recipientId = msg.recipientId,
            recipientName = msg.recipientName,
            body = msg.body,
            messageType = type,
            priority = type.priority,
            createdAtUtc = msg.createdAtUtc,
            expiresAtUtc = msg.expiresAtUtc,
            receivedAtUtc = receivedAtUtc,
            latitude = loc?.latitude,
            longitude = loc?.longitude,
            accuracyMeters = loc?.accuracyMeters,
            locationCapturedAtUtc = loc?.capturedAtUtc,
            locationLabel = loc?.label,
            locationSource = loc?.source,
            direction = direction,
            sendState = if (direction == Direction.RELAY) SendState.QUEUED else SendState.NONE,
            deliveryState = if (direction == Direction.RELAY) DeliveryState.PENDING else DeliveryState.RECEIVED,
            hopCount = msg.hopCount,
            maxHops = msg.maxHops,
            routeJson = encodeRoute(msg.route),
            lastTransport = via,
            receivedFromId = from.deviceId,
            receivedFromName = from.displayName,
            batteryPercent = msg.batteryPercent,
        )
    }
}
