package com.relay.app.util

import com.relay.app.protocol.MessageValidator
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a RELAY QR code contains: relay://add?id=RLY-1A2B3C4D&name=Phone%20A
 * Only the public device ID and display name; nothing secret. Pure logic, unit tested.
 */
object ContactCode {
    data class Contact(val deviceId: String, val name: String)

    private const val PREFIX = "relay://add?"

    fun encode(deviceId: String, name: String): String =
        PREFIX + "id=" + URLEncoder.encode(deviceId, "UTF-8") + "&name=" + URLEncoder.encode(name.take(32), "UTF-8")

    /** Returns null for anything that is not a valid RELAY contact code. */
    fun parse(text: String?): Contact? {
        if (text == null || !text.startsWith(PREFIX)) return null
        val params = text.removePrefix(PREFIX).split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to runCatching { URLDecoder.decode(part.substring(i + 1), "UTF-8") }.getOrNull()
        }.toMap()
        val id = params["id"]?.trim() ?: return null
        if (!MessageValidator.isValidDeviceId(id) || id == "*") return null
        val name = params["name"]?.trim()?.take(32)?.ifBlank { null } ?: id
        return Contact(id, name)
    }
}
