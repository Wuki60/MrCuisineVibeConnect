package app.toque.core

import app.toque.core.machine.JobKind
import app.toque.core.machine.Programs
import app.toque.core.machine.SafetyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramsTest {
    @Test fun cleaningIsAValidColdRamp() {
        val job = Programs.clean()
        assertEquals(JobKind.MANUAL, job.kind)
        assertEquals(listOf(4, 6, 8, 10), job.phases.map { it.setting.speed })
        assertTrue(job.phases.none { it.setting.heated })
        assertEquals(90_000L, job.totalMs)
        assertTrue(SafetyPolicy.checkJob(job, null).isEmpty())
        // Bol encore chaud : la vitesse 4 demande une confirmation, comme pour tout mixage chaud.
        assertTrue(SafetyPolicy.checkJob(job, 70).any { it.code == SafetyPolicy.HOT_BLEND })
        for (ms in longArrayOf(60_000L, 180_000L)) assertTrue(SafetyPolicy.checkJob(Programs.clean(ms), null).isEmpty())
    }

    @Test fun rinseRecipeIsFoundInEveryLanguage() {
        assertTrue(Programs.isRinseRecipe("*Rinçage"))
        assertTrue(Programs.isRinseRecipe("*Limpieza"))
        assertTrue(Programs.isRinseRecipe("Reinigung"))
        assertFalse(Programs.isRinseRecipe("Rinçage des légumes"))
        assertFalse(Programs.isRinseRecipe("Velouté de potimarron"))
    }
}
