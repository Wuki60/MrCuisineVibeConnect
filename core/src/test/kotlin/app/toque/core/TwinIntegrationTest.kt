package app.toque.core

import app.toque.core.machine.EventType
import app.toque.core.machine.FaultCode
import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.MachineLoop
import app.toque.core.machine.Programs
import app.toque.core.machine.Snapshot
import app.toque.core.machine.TcpTransport
import app.toque.core.protocol.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Essais de bout en bout contre le jumeau numérique indépendant du SDK (outils/jumeau.py).
 * Ignorés si le jumeau n'est pas lancé.
 */
class TwinIntegrationTest {
    @Volatile private var snap = Snapshot()

    private fun control(cmd: String): String = Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1", 8770), 2_000)
        s.soTimeout = 3_000
        s.getOutputStream().write((cmd + "\n").toByteArray())
        s.getInputStream().bufferedReader().readLine() ?: ""
    }

    private fun twinState(): Map<String, Any?> =
        @Suppress("UNCHECKED_CAST") (app.toque.core.json.Json.parse(control("state")) as Map<String, Any?>)

    private fun twinUp(): Boolean = try {
        control("state").startsWith("{")
    } catch (_: Exception) {
        false
    }

    private fun waitFor(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(50)
        }
        throw AssertionError("délai dépassé : $what")
    }

    @Test fun endToEndAgainstSdkTwin() {
        assumeTrue("jumeau non lancé", twinUp())
        control("lid 0"); control("error 0"); control("silent 0")
        val events = CopyOnWriteArrayList<MachineEvent>()
        val loop = MachineLoop(
            transportFactory = { TcpTransport("127.0.0.1", 8765) },
            onSnapshot = { snap = it },
            onEvent = { events += it },
        )
        loop.start()
        try {
            waitFor(4_000, "liaison prête") { snap.link == LinkState.READY }

            // 1. cuisson manuelle courte
            val v = loop.call { it.startJob(Programs.manual(4, 0, Direction.CLOCKWISE, 4_000)) }
            assertTrue(v.message, v.ok)
            waitFor(3_000, "moteur du jumeau en marche") { (twinState()["rpm"] as Number).toInt() > 500 }
            waitFor(10_000, "fin de programme") { snap.jobState == JobState.FINISHED.name }
            waitFor(4_000, "moteur du jumeau arrêté") { (twinState()["rpm"] as Number).toInt() == 0 }
            loop.call { it.clearFinished() }

            // 2. couvercle ouvert en cours de route
            assertTrue(loop.call { it.startJob(Programs.manual(3, 13, Direction.CLOCKWISE, 60_000)) }.ok)
            waitFor(3_000, "chauffe en marche") { twinState()["motor_on"] == true }
            control("lid 1")
            waitFor(2_000, "pause couvercle") { snap.jobState == JobState.PAUSED.name && snap.pauseReason == "LID" }
            waitFor(4_000, "jumeau arrêté") { twinState()["motor_on"] == false && (twinState()["rpm"] as Number).toInt() == 0 }
            assertFalse(loop.call { it.resume() }.ok)
            control("lid 0")
            Thread.sleep(500)
            assertTrue(loop.call { it.resume() }.ok)
            waitFor(3_000, "reprise") { snap.jobState == JobState.RUNNING.name }

            // 3. réponses corrompues : la liaison doit tenir
            control("corrupt 5")
            Thread.sleep(1_500)
            assertEquals(JobState.RUNNING.name, snap.jobState)
            assertTrue(snap.invalidFrames >= 1)

            // 4. erreur de la base
            control("error 5")
            waitFor(2_000, "alerte erreur moteur") { snap.faultCode == FaultCode.BOARD_ERROR.name }
            assertEquals(JobState.ABORTED.name, snap.jobState)
            waitFor(4_000, "jumeau arrêté après erreur") { (twinState()["rpm"] as Number).toInt() == 0 }
            control("error 0")
            Thread.sleep(400)
            assertTrue(loop.call { it.acknowledgeFault() }.ok)
            loop.call { it.clearFinished() }

            // 5. base muette pendant un programme
            assertTrue(loop.call { it.startJob(Programs.manual(2, 0, Direction.COUNTER_CLOCKWISE, 60_000)) }.ok)
            waitFor(3_000, "marche") { snap.jobState == JobState.RUNNING.name }
            control("silent 1")
            waitFor(3_000, "perte de liaison détectée") { snap.faultCode == FaultCode.COMM_LOST.name }
            assertEquals(JobState.ABORTED.name, snap.jobState)
            control("silent 0")
            waitFor(4_000, "liaison rétablie") { snap.link == LinkState.READY }
            assertTrue(loop.call { it.acknowledgeFault() }.ok)
            assertTrue(events.any { it.type == EventType.LID_OPENED })
        } finally {
            loop.shutdown()
        }
    }
}
