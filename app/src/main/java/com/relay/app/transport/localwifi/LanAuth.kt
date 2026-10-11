package com.relay.app.transport.localwifi

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mutual challenge-response that runs on a fresh local Wi-Fi TCP socket BEFORE the RELAY HELLO.
 * Both phones prove they know the same team network key without sending the key itself:
 *
 *   server -> RELAY-AUTH1 <serverNonce>
 *   client -> RELAY-AUTH2 <clientNonce> <HMAC(key, "C|serverNonce|clientNonce")>
 *   server -> RELAY-AUTH3 <HMAC(key, "S|clientNonce|serverNonce")>   (or RELAY-AUTH-FAIL)
 *
 * This authenticates peers; it does NOT encrypt the messages that follow (documented limitation).
 * Lines are read one byte at a time so no RELAY frame bytes are consumed by this step.
 */
object LanAuth {
    private const val MAX_LINE = 256
    private val random = SecureRandom()

    class AuthFailed(message: String) : IOException(message)

    fun keyBytes(networkKey: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(networkKey.trim().toByteArray(Charsets.UTF_8))

    fun proof(key: ByteArray, role: String, first: String, second: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal("$role|$first|$second".toByteArray(Charsets.UTF_8)).toHex()
    }

    fun newNonce(): String = ByteArray(16).also { random.nextBytes(it) }.toHex()

    /** Server side. Throws [AuthFailed] / IOException; caller closes the socket. */
    fun authenticateAsServer(input: InputStream, output: OutputStream, networkKey: String) {
        val key = keyBytes(networkKey)
        val serverNonce = newNonce()
        writeLine(output, "RELAY-AUTH1 $serverNonce")
        val parts = readLine(input).split(' ')
        if (parts.size != 3 || parts[0] != "RELAY-AUTH2") throw AuthFailed("not a RELAY peer")
        val clientNonce = parts[1]
        val expected = proof(key, "C", serverNonce, clientNonce)
        if (!constantTimeEquals(expected, parts[2])) {
            runCatching { writeLine(output, "RELAY-AUTH-FAIL") }
            throw AuthFailed("peer used a different team network key")
        }
        writeLine(output, "RELAY-AUTH3 ${proof(key, "S", clientNonce, serverNonce)}")
    }

    /** Client side. Throws [AuthFailed] / IOException; caller closes the socket. */
    fun authenticateAsClient(input: InputStream, output: OutputStream, networkKey: String) {
        val key = keyBytes(networkKey)
        val first = readLine(input).split(' ')
        if (first.size != 2 || first[0] != "RELAY-AUTH1") throw AuthFailed("not a RELAY peer")
        val serverNonce = first[1]
        val clientNonce = newNonce()
        writeLine(output, "RELAY-AUTH2 $clientNonce ${proof(key, "C", serverNonce, clientNonce)}")
        val reply = readLine(input).split(' ')
        if (reply.firstOrNull() == "RELAY-AUTH-FAIL") throw AuthFailed("the other phone uses a different team network key")
        if (reply.size != 2 || reply[0] != "RELAY-AUTH3") throw AuthFailed("unexpected reply during authentication")
        if (!constantTimeEquals(proof(key, "S", clientNonce, serverNonce), reply[1])) {
            throw AuthFailed("the other phone could not prove it knows the team network key")
        }
    }

    private fun writeLine(output: OutputStream, line: String) {
        output.write((line + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readLine(input: InputStream): String {
        val bytes = ArrayList<Byte>(64)
        while (true) {
            val b = input.read()
            if (b < 0) throw IOException("connection closed during authentication")
            if (b == '\n'.code) break
            if (bytes.size >= MAX_LINE) throw AuthFailed("authentication line too long")
            bytes += b.toByte()
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).trim()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
