package app.toque.core

import app.toque.core.machine.MachineLoop
import org.junit.Assert.assertEquals
import org.junit.Test

class RetryTest {
    @Test fun openRetriesSlowDown() {
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L, 60_000L, 60_000L),
            (1..7).map { MachineLoop.retryDelayMs(it) })
        assertEquals(60_000L, MachineLoop.retryDelayMs(1000))
        assertEquals(3_000L, MachineLoop.retryDelayMs(0))
    }
}
