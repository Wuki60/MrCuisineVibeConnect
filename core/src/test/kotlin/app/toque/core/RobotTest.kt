package app.toque.core

import app.toque.core.machine.Programs
import app.toque.core.machine.SafetyPolicy
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Operation
import app.toque.core.protocol.Smart
import app.toque.core.protocol.SmartCodec
import app.toque.core.protocol.SmartProgram
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.robot.DeviceFacts
import app.toque.core.robot.Feature
import app.toque.core.robot.RecipeCompat
import app.toque.core.robot.RobotDetector
import app.toque.core.robot.RobotProfiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fiches de robot : détection sans trame, recettes montrées à chaque robot, programmes du Smart. */
class RobotTest {
    @After fun reset() {
        RobotProfiles.active = RobotProfiles.MC_CONNECT
    }

    private fun recipe(vararg m: StepMachine, devices: List<String> = emptyList()) =
        Recipe(id = "t", title = "Essai", steps = m.map { Step("étape", it) }, devices = devices)

    @Test fun eachRobotHasTheFramePeriodOfItsOriginalApp() {
        assertEquals("Connect : inchangé", 100L, RobotProfiles.MC_CONNECT.framePeriodMs)
        assertEquals("Smart : comme l'appli d'origine (issue #59)", 200L, RobotProfiles.MC_SMART.framePeriodMs)
    }

    @Test fun detectionUsesOnlyWhatTheTabletShows() {
        val connect = RobotDetector.detect(DeviceFacts(setOf("de.silpion.mc2"), 23, setOf("/dev/ttyMT0", "/dev/ttyMT1")))
        assertSame(RobotProfiles.MC_CONNECT, connect.profile)
        val smart = RobotDetector.detect(DeviceFacts(setOf("com.tecpal.device.mc30"), 27, setOf("/dev/ttyMT0", "/dev/ttyMT1")))
        assertSame(RobotProfiles.MC_SMART, smart.profile)
        // Appli d'origine désinstallée : la version d'Android et le port de la fiche.
        assertSame(RobotProfiles.MC_SMART, RobotDetector.detect(DeviceFacts(emptySet(), 27, setOf("/dev/ttyMT1"))).profile)
        assertSame(RobotProfiles.MC_CONNECT, RobotDetector.detect(DeviceFacts(emptySet(), 23, setOf("/dev/ttyMT0"))).profile)
        // Rien de sûr : pas de choix à la place de la personne.
        assertNull(RobotDetector.detect(DeviceFacts(emptySet(), 29, setOf("/dev/ttyMT0"))).profile)
        assertNull(RobotDetector.detect(DeviceFacts(setOf("de.silpion.mc2", "com.tecpal.device.mc30"), 23)).profile)
    }

    @Test fun profilesDescribeTheirRobot() {
        val c = RobotProfiles.MC_CONNECT
        val s = RobotProfiles.MC_SMART
        assertEquals("/dev/ttyMT0" to 4800, c.serialDevice to c.baud)
        assertEquals("/dev/ttyMT1" to 9600, s.serialDevice to s.baud)
        assertEquals("MC2.0", c.lidlDevice)
        assertEquals("MC3.0", s.lidlDevice)
        assertTrue(40 in s.capabilities.temperaturesC)
        assertFalse(40 in c.capabilities.temperaturesC)
        assertTrue(c.capabilities.has(Feature.HEAT_NO_STIR))
        assertFalse(s.capabilities.has(Feature.HEAT_NO_STIR))
        assertTrue(s.newCodec() is SmartCodec)
        assertTrue(c.tested && !s.tested)
    }

    @Test fun eachRobotSeesOnlyTheRecipesItCanMake() {
        val c = RobotProfiles.MC_CONNECT
        val s = RobotProfiles.MC_SMART
        val plain = recipe(StepMachine(seconds = 30, speed = 5), StepMachine(StepProgram.STEAM, seconds = 600),
            StepMachine(StepProgram.KNEAD, seconds = 90), StepMachine(StepProgram.TOAST, seconds = 300))
        assertTrue(RecipeCompat.supports(plain, c))
        assertTrue(RecipeCompat.reasons(plain, s).toString(), RecipeCompat.supports(plain, s))
        // Le site la destine au Smart seul : pas montrée sur le Connect.
        assertFalse(RecipeCompat.supports(plain.copy(devices = listOf("MC3.0")), c))
        assertTrue(RecipeCompat.supports(plain.copy(devices = listOf("MC2.0", "MC3.0")), c))
        // 40 °C : seulement sur la table du Smart.
        val warm = recipe(StepMachine(seconds = 300, speed = 1, tempC = 40))
        assertFalse(RecipeCompat.supports(warm, c))
        assertTrue(RecipeCompat.supports(warm, s))
        // Programmes propres au Smart.
        // Programme de recette → durée et température d'essai (celles de l'appli d'origine).
        val essais = mapOf(StepProgram.MIXER to (60 to 0), StepProgram.SMOOTHIE to (60 to 0), StepProgram.SOUS_VIDE to (3600 to 60),
            StepProgram.SLOW_COOK to (3600 to 90), StepProgram.FERMENT to (3600 to 40))
        for ((p, e) in essais) {
            val r = recipe(StepMachine(p, seconds = e.first, tempC = e.second))
            assertFalse("$p", RecipeCompat.supports(r, c))
            assertTrue("$p : ${RecipeCompat.reasons(r, s)}", RecipeCompat.supports(r, s))
        }
        // Chauffe sans mélange : le Connect la fait, pas (encore) le Smart.
        val noStir = recipe(StepMachine(seconds = 300, speed = 0, tempC = 80))
        assertTrue(RecipeCompat.supports(noStir, c))
        assertFalse(RecipeCompat.supports(noStir, s))
        // Sous-vide hors de la plage de l'appli d'origine (40 à 85 °C) : refusé.
        assertFalse(RecipeCompat.supports(recipe(StepMachine(StepProgram.SOUS_VIDE, seconds = 3600, tempC = 95)), s))
        assertTrue(RecipeCompat.supports(recipe(StepMachine(StepProgram.SOUS_VIDE, seconds = 3600, tempC = 63)), s))
    }

    @Test fun smartProgramsSendTheirExactTemperature() {
        val spec = Programs.smartProgram(SmartProgram.SOUS_VIDE, 4 * 3600_000L, "Sous-vide", celsius = 63)
        // Cuisson longue à basse température : la durée de l'appli d'origine, au-delà de 99 min.
        assertTrue(SafetyPolicy.checkJob(spec, null).toString(), SafetyPolicy.checkJob(spec, null).isEmpty())
        assertFalse(SafetyPolicy.checkJob(Programs.manual(1, 5, Direction.CLOCKWISE, 4 * 3600_000L), null).isEmpty())
        val st = spec.phases[0].setting
        assertEquals(63, st.temperatureC)
        val codec = SmartCodec()
        val f = codec.encodeTx(st.operation, st.speed, st.temperatureLevel, st.direction, false, 4 * 3600,
            st.program, st.smartLevel, st.programCelsius)
        val d = Smart.decodeTx(f)
        assertEquals(9, d.function)
        assertEquals(1, d.temperatureLevel)
        assertEquals(63, d.celsius)
        assertEquals(4, d.hours)
        // Hors plage : refusé par les règles et par le protocole.
        val bad = Programs.programSetting(SmartProgram.SOUS_VIDE, 95)
        assertFalse(codec.accepts(bad.operation, bad.speed, bad.temperatureLevel, bad.direction, bad.program, bad.smartLevel, bad.programCelsius))
        // Mijoter à 80 °C : toutes les étapes, brassage et repos, à 80 °C.
        val m = Programs.smartMijoter(3600_000L, "Mijoter", 80)
        assertTrue(m.phases.all { it.setting.temperatureC == 80 && it.setting.program != null })
        assertTrue(SafetyPolicy.checkJob(m, null).isEmpty())
    }

    @Test fun manualFortyDegreesUsesTheSmartTable() {
        RobotProfiles.active = RobotProfiles.MC_SMART
        val m = StepMachine(seconds = 300, speed = 1, tempC = 40)
        assertTrue(m.violations().toString(), m.valid)
        val s = m.toJob("Tiédir")!!.phases[0].setting
        assertEquals(2, s.smartLevel)
        assertEquals(40, s.temperatureC)
        assertTrue(SmartCodec().accepts(Operation.STANDARD, 1, s.temperatureLevel, Direction.CLOCKWISE, null, s.smartLevel, null))
        // Sur le Connect, 40 °C n'existe pas.
        RobotProfiles.active = RobotProfiles.MC_CONNECT
        assertFalse(m.valid)
    }

    @Test fun sortingTwoThousandRecipesStaysFast() {
        val c = RobotProfiles.MC_CONNECT
        val many = (1..2000).map { i ->
            recipe(StepMachine(seconds = 30, speed = 5), StepMachine(seconds = 600, speed = 1, tempC = 100, reverse = true),
                StepMachine(StepProgram.STEAM, seconds = 600), StepMachine(StepProgram.TURBO, pulses = 2), devices = listOf("MC2.0")).copy(id = "r$i")
        }
        RecipeCompat.supports(many[0], c)
        val t0 = System.nanoTime()
        assertTrue(many.all { RecipeCompat.supports(it, c) })
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("$ms ms", ms < 200)
    }

    @Test fun smartProgramsAdaptAlgorithmicallyForConnect() {
        val specs = listOf(
            Programs.smartProgram(SmartProgram.MIXER, 30_000L, "Mixer"),
            Programs.smartProgram(SmartProgram.SMOOTHIE, 60_000L, "Smoothie"),
            Programs.smartProgram(SmartProgram.EGGS, 8 * 60_000L, "Eggs"),
            Programs.smartProgram(SmartProgram.SOUS_VIDE, 6 * 3600_000L, "Sous-vide", celsius = 60),
            Programs.smartMijoter(4 * 3600_000L, "Slow cook", celsius = 90),
            Programs.smartFermenter(8 * 3600_000L, "Ferment", celsius = 37),
            Programs.smartProgram(SmartProgram.RICE, 30 * 60_000L, "Rice"),
            Programs.smartProgram(SmartProgram.KETTLE, 10 * 60_000L, "Kettle"),
            Programs.smartPrelavage("Prewash"),
            Programs.smartNettoyageLong("Long clean"),
        )
        for (raw in specs) {
            val adapted = Programs.forConnect(raw)
            val violations = SafetyPolicy.checkJob(adapted, null)
            assertTrue("${raw.label}: $violations", violations.isEmpty())
            for (ph in adapted.phases) {
                val st = ph.setting
                ConnectCodec.encodeTx(st.operation, st.speed, st.temperatureLevel, st.direction, false, 60)
            }
        }
    }
}
