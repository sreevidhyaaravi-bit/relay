package com.relay.app.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Encodes frames to single JSON lines and decodes + validates incoming lines. */
object ProtocolCodec {

    val json: Json = Json {
        ignoreUnknownKeys = true // forward compatible with newer optional fields
        explicitNulls = false
        encodeDefaults = true
    }

    sealed interface DecodeResult {
        data class Ok(val frame: Frame) : DecodeResult

        /** The line was not a valid frame. [messageId] is set when a MSG id could be recovered so a REJECTED ack can be sent. */
        data class Malformed(val reason: String, val messageId: String? = null) : DecodeResult

        data class UnsupportedVersion(val version: Int, val messageId: String? = null) : DecodeResult
    }

    /** Encodes [frame] as one line of JSON WITHOUT the trailing newline. */
    fun encode(frame: Frame): String = json.encodeToString(Frame.serializer(), frame)

    /** Encodes [frame] as UTF-8 bytes INCLUDING the trailing newline, enforcing the size limit. */
    fun encodeLine(frame: Frame): ByteArray {
        val bytes = (encode(frame) + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= ProtocolLimits.MAX_FRAME_BYTES) { "frame exceeds ${ProtocolLimits.MAX_FRAME_BYTES} bytes" }
        return bytes
    }

    fun decode(line: String): DecodeResult {
        val text = line.trim()
        if (text.isEmpty()) return DecodeResult.Malformed("empty frame")
        if (text.toByteArray(Charsets.UTF_8).size > ProtocolLimits.MAX_FRAME_BYTES) {
            return DecodeResult.Malformed("frame too large")
        }
        val obj: JsonObject = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return DecodeResult.Malformed("not valid JSON")
        } catch (e: IllegalArgumentException) {
            return DecodeResult.Malformed("not a JSON object")
        }
        val msgId = runCatching { (obj["msg"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?.takeIf { MessageValidator.isValidMessageId(it) }
        val version = runCatching { obj["v"]?.jsonPrimitive?.intOrNull }.getOrNull()
            ?: return DecodeResult.Malformed("missing protocol version", msgId)
        if (version < ProtocolLimits.MIN_SUPPORTED_VERSION || version > ProtocolLimits.MAX_SUPPORTED_VERSION) {
            return DecodeResult.UnsupportedVersion(version, msgId)
        }
        val frame: Frame = try {
            json.decodeFromJsonElement(Frame.serializer(), obj)
        } catch (e: SerializationException) {
            return DecodeResult.Malformed("invalid frame structure", msgId)
        } catch (e: IllegalArgumentException) {
            return DecodeResult.Malformed("invalid frame structure", msgId)
        }
        if (frame.t !in FrameType.ALL) return DecodeResult.Malformed("unknown frame type", msgId)

        val check: MessageValidator.Result = when (frame.t) {
            FrameType.HELLO -> frame.hello?.let { MessageValidator.validateHello(it) } ?: missing("hello")
            FrameType.MSG -> frame.msg?.let { MessageValidator.validate(it) } ?: missing("msg")
            FrameType.ACK -> frame.ack?.let { MessageValidator.validateAck(it) } ?: missing("ack")
            FrameType.RCPT -> frame.rcpt?.let { MessageValidator.validateReceipt(it) } ?: missing("rcpt")
            else -> MessageValidator.Result.Valid
        }
        return when (check) {
            is MessageValidator.Result.Invalid -> DecodeResult.Malformed(check.reason, msgId)
            MessageValidator.Result.Valid -> DecodeResult.Ok(frame)
        }
    }

    private fun missing(field: String) = MessageValidator.Result.Invalid("frame is missing '$field'")
}
