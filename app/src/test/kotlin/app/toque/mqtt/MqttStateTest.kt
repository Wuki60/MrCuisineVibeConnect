package app.toque.mqtt

import app.toque.core.machine.JobState
import app.toque.core.machine.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttStateTest {

    @Test fun targetTempUsesJobTempCNotBaseEcho() {
        val s = Snapshot(
            jobActive = true,
            jobState = JobState.RUNNING.name,
            jobTempLevel = 2,
            jobTempC = 60,
            targetTempC = 72, // écho base
            remainingMs = 60_000,
        )
        val m = MqttState.from(s, nowMs = 1_000_000L)
        assertEquals(60, m.targetTempC)
        assertTrue(m.expectedEndAt != null)
    }

    @Test fun targetTempZeroWhenNotHeating() {
        val s = Snapshot(
            jobActive = true,
            jobState = JobState.RUNNING.name,
            jobTempC = 0,
            jobTempLevel = 0,
            remainingMs = 30_000,
        )
        assertEquals(0, MqttState.from(s).targetTempC)
    }

    @Test fun expectedEndAtNullWhilePaused() {
        val s = Snapshot(
            jobActive = true,
            jobState = JobState.PAUSED.name,
            jobTempC = 60,
            remainingMs = 120_000,
        )
        val m = MqttState.from(s, nowMs = 5_000_000L)
        assertNull(m.expectedEndAt)
        assertEquals(JobState.PAUSED.name, m.jobState)
        assertEquals(60, m.targetTempC)
    }

    @Test fun expectedEndAtFromRemainingWhileRunning() {
        val nowMs = 1_700_000_000_000L
        val s = Snapshot(
            jobActive = true,
            jobState = JobState.RUNNING.name,
            jobTempC = 45,
            remainingMs = 90_500, // → 91 s
        )
        val m = MqttState.from(s, nowMs = nowMs)
        val expectedMs = ((nowMs / 1000L) + 91L) * 1000L
        assertEquals(MqttState.formatEnd(expectedMs), m.expectedEndAt)
        val parsed = MqttState.parseEnd(m.expectedEndAt!!)!!
        assertEquals(m.expectedEndAt, MqttState.formatEnd(parsed))
    }

    @Test fun finishedHasNoEndAt() {
        val s = Snapshot(
            jobActive = false,
            jobState = JobState.FINISHED.name,
            remainingMs = 0,
            endReason = "DONE",
        )
        val m = MqttState.from(s)
        assertNull(m.expectedEndAt)
        assertEquals("DONE", m.endReason)
    }
}
