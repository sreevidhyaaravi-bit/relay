package com.relay.app.transport

import com.relay.app.transport.localwifi.LanAuth
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** LAN mutual authentication over real loopback sockets. */
class LanAuthTest {

    private fun run(serverKey: String, clientKey: String): Pair<Throwable?, Throwable?> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val pool = Executors.newFixedThreadPool(2)
        val serverResult = pool.submit<Throwable?> {
            server.accept().use { s ->
                s.soTimeout = 3_000
                runCatching { LanAuth.authenticateAsServer(s.getInputStream(), s.getOutputStream(), serverKey) }.exceptionOrNull()
            }
        }
        val clientResult = pool.submit<Throwable?> {
            Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                s.soTimeout = 3_000
                runCatching { LanAuth.authenticateAsClient(s.getInputStream(), s.getOutputStream(), clientKey) }.exceptionOrNull()
            }
        }
        val result = serverResult.get(5, TimeUnit.SECONDS) to clientResult.get(5, TimeUnit.SECONDS)
        pool.shutdownNow()
        server.close()
        return result
    }

    @Test
    fun `same team key authenticates both sides`() {
        val (s, c) = run("hackathon-team-7", "hackathon-team-7")
        assertTrue("server: $s", s == null)
        assertTrue("client: $c", c == null)
    }

    @Test
    fun `different keys are rejected on both sides`() {
        val (s, c) = run("team-red", "team-blue")
        assertTrue(s is LanAuth.AuthFailed)
        assertTrue(c is LanAuth.AuthFailed)
    }

    @Test
    fun `proofs differ by role so a reflected proof does not verify`() {
        val key = LanAuth.keyBytes("k")
        assertTrue(LanAuth.proof(key, "C", "n1", "n2") != LanAuth.proof(key, "S", "n1", "n2"))
    }
}
