package app.toque.core

import app.toque.core.json.Json
import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.Snapshot
import app.toque.core.net.StatusServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class StatusServerTest {
    private val token = "0123456789abcdef0123456789abcdef"
    @Volatile private var snap: Snapshot? = Snapshot(
        link = LinkState.READY, actualTempC = 60, targetTempC = 60, weightG = 512, heaterOn = true,
        jobActive = true, jobState = JobState.RUNNING.name, jobLabel = "Béchamel", remainingMs = 61_500,
        rxHex = "55 AA SECRET", txHex = "55 BB SECRET",
    )
    private lateinit var server: StatusServer

    @Before fun open() {
        server = StatusServer(0, token, { snap }, { mapOf("app_version" to "0.8.0") },
            bindAddress = InetAddress.getLoopbackAddress())
        server.start()
    }

    @After fun close() = server.stop()

    private fun raw(request: String): Pair<Int, String> {
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
            s.soTimeout = 3_000
            s.getOutputStream().write(request.toByteArray(StandardCharsets.UTF_8))
            s.getOutputStream().flush()
            val text = s.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
            val code = text.substringAfter(' ').substringBefore(' ').toInt()
            return code to text.substringAfter("\r\n\r\n")
        }
    }

    private fun get(path: String = StatusServer.PATH, auth: String? = "Bearer $token", method: String = "GET") =
        raw("$method $path HTTP/1.1\r\nHost: robot\r\n" + (auth?.let { "Authorization: $it\r\n" } ?: "") + "\r\n")

    @Test fun statusWithTokenIsReadable() {
        val (code, body) = get()
        assertEquals(200, code)
        @Suppress("UNCHECKED_CAST") val m = Json.parse(body) as Map<String, Any?>
        assertEquals(1L, m["api"])
        @Suppress("UNCHECKED_CAST") val state = m["state"] as Map<String, Any?>
        assertEquals(60L, state["temperature_c"])
        assertEquals(512L, state["weight_g"])
        assertEquals(true, state["heater_on"])
        assertEquals("Béchamel", state["job_label"])
        assertEquals(62L, state["remaining_s"])
        assertEquals(true, state["running"])
        assertFalse("trames brutes publiées", body.contains("SECRET"))
    }

    @Test fun tokenIsRequired() {
        assertEquals(401, get(auth = null).first)
        assertEquals(401, get(auth = "Bearer faux").first)
        assertEquals(401, get(auth = "Bearer ${token}x").first)
        assertEquals(401, get(auth = token).first)
    }

    @Test fun nothingButReading() {
        for (m in listOf("POST", "PUT", "DELETE", "PATCH")) assertEquals(m, 405, get(method = m).first)
        assertEquals(404, get("/api/v1/stop").first)
        assertEquals(404, get("/").first)
    }

    @Test fun garbageAndOversizeAreRefusedWithoutKillingTheServer() {
        assertEquals(400, raw("n'importe quoi\r\n\r\n").first)
        Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
            s.getOutputStream().write(ByteArray(StatusServer.MAX_REQUEST + 10) { 'A'.code.toByte() })
            s.getInputStream().readBytes()
        }
        assertEquals(200, get().first)
    }

    @Test fun noMachineYieldsNullState() {
        snap = null
        val (code, body) = get()
        assertEquals(200, code)
        @Suppress("UNCHECKED_CAST") val m = Json.parse(body) as Map<String, Any?>
        assertNull(m["state"])
    }

    @Test fun headIsReadInPieces() {
        val head = StatusServer.readHead(ByteArrayInputStream("GET / HTTP/1.1\r\nA: b\r\n\r\nrest".toByteArray()))
        assertEquals("GET / HTTP/1.1\r\nA: b", head)
        assertNull(StatusServer.readHead(ByteArrayInputStream("GET".toByteArray())))
    }

    @Test fun tokensAreLongAndDistinct() {
        val a = StatusServer.newToken()
        val b = StatusServer.newToken()
        assertEquals(32, a.length)
        assertTrue(a != b)
    }
}
