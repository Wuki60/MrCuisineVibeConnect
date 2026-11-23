package app.toque.core

import app.toque.core.api.ApiPublisher
import app.toque.core.api.EventBus
import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.recipes.CookEvent
import app.toque.core.recipes.CookSession
import app.toque.core.recipes.SessionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiPublisherTest {
    private val backend = FakeBackend()
    private val bus = EventBus()
    private var now = 1_000_000L
    private val pub = ApiPublisher(backend, bus) { now }

    private fun names(afterId: Long = 0) = bus.since(afterId).map { it.name }
    private fun states() = bus.since(0).filter { it.name == "state" }
    private val session = CookSession("s1", sampleRecipe(), stepIndex = 1)

    @Test
    fun firstSnapshotPublishesState() {
        pub.onSnapshot()
        assertEquals(listOf("state"), names())
    }

    @Test
    fun identicalSnapshotsAreNotRepublished() {
        pub.onSnapshot()
        repeat(20) { now += 100; pub.onSnapshot() }
        assertEquals(1, states().size)
    }

    @Test
    fun smallAnalogChangesAreIgnored() {
        pub.onSnapshot()
        now += 1_500
        backend.snap = backend.snap.copy(rpm = 49, weightG = 1)
        pub.onSnapshot()
        assertEquals(1, states().size)
    }

    @Test
    fun analogChangesAreLimitedToOnePerSecond() {
        pub.onSnapshot()
        now += 400
        backend.snap = backend.snap.copy(actualTempC = 30)
        pub.onSnapshot()
        assertEquals("trop tôt", 1, states().size)
        now += 700
        pub.onSnapshot()
        assertEquals(2, states().size)
        // Écart mesuré par rapport à la dernière valeur publiée, pas la précédente.
        now += 1_100
        backend.snap = backend.snap.copy(actualTempC = 30)
        pub.onSnapshot()
        assertEquals(2, states().size)
        backend.snap = backend.snap.copy(actualTempC = 31)
        pub.onSnapshot()
        assertEquals(3, states().size)
    }

    @Test
    fun restingScaleDriftIsThrottled() {
        pub.onSnapshot()
        now += 2_000
        backend.snap = backend.snap.copy(weightG = 4)
        pub.onSnapshot()
        assertEquals("sous 5 g", 1, states().size)
        backend.snap = backend.snap.copy(weightG = 6)
        pub.onSnapshot()
        assertEquals("pas avant 10 s", 1, states().size)
        now += 10_000
        pub.onSnapshot()
        assertEquals(2, states().size)
        now += 2_000
        backend.snap = backend.snap.copy(weightG = 12)
        pub.onSnapshot()
        assertEquals("trop tôt après la dernière", 2, states().size)
        now += 8_000
        pub.onSnapshot()
        assertEquals(3, states().size)
    }

    @Test
    fun weightAndRpmThresholds() {
        backend.snap = backend.snap.copy(jobActive = true, jobState = "RUNNING", jobKind = "MANUAL")
        pub.onSnapshot()
        now += 2_000
        backend.snap = backend.snap.copy(weightG = 2)
        pub.onSnapshot()
        assertEquals(2, states().size)
        now += 2_000
        backend.snap = backend.snap.copy(rpm = 50)
        pub.onSnapshot()
        assertEquals(3, states().size)
    }

    @Test
    fun discreteChangesGoOutImmediately() {
        pub.onSnapshot()
        now += 10
        backend.snap = backend.snap.copy(lidOpen = true)
        pub.onSnapshot()
        now += 10
        backend.snap = backend.snap.copy(bowlDetected = true)
        pub.onSnapshot()
        now += 10
        backend.snap = backend.snap.copy(motorOn = true)
        pub.onSnapshot()
        now += 10
        backend.snap = backend.snap.copy(heaterOn = true, jobActive = true, jobState = "RUNNING", jobTempLevel = 9)
        pub.onSnapshot()
        now += 10
        backend.screen = false
        pub.onSnapshot()
        assertEquals(6, states().size)
    }

    @Test
    fun theRawHeaterFlagWithoutATargetTemperatureIsNotAChange() {
        backend.snap = backend.snap.copy(jobActive = true, jobState = "RUNNING", jobTempLevel = 0)
        pub.onSnapshot()
        now += 10
        // La base annonce « mode 1 » et garde sa dernière consigne (51 °C relevés), alors que Toque ne demande pas de chauffe.
        backend.snap = backend.snap.copy(heaterOn = true, targetTempC = 51)
        pub.onSnapshot()
        assertEquals(1, states().size)
        now += 10
        backend.snap = backend.snap.copy(jobTempLevel = 6)
        pub.onSnapshot()
        assertEquals(2, states().size)
        assertEquals(true, Json.parse(states().last().data).obj()["machine"].obj()["heaterOn"])
    }

    @Test
    fun aSpeedOrTemperatureChangeBetweenStepsGoesOutAtOnce() {
        // Série enchaînée : la phase change tout de suite, la base confirme la nouvelle vitesse environ 3 s plus tard.
        backend.snap = backend.snap.copy(jobActive = true, jobState = "RUNNING", jobTempLevel = 2, targetSpeedLevel = 2)
        pub.onSnapshot()
        now += 10
        backend.snap = backend.snap.copy(targetSpeedLevel = 3)
        pub.onSnapshot()
        assertEquals(2, states().size)
        assertEquals(3, (Json.parse(states().last().data).obj()["machine"].obj()["targetSpeed"] as Number).toInt())
        now += 10
        backend.snap = backend.snap.copy(jobTempLevel = 6)
        pub.onSnapshot()
        assertEquals(3, states().size)
        // L'écho de la base (consigne différente de la demande) n'est pas un changement.
        now += 10
        backend.snap = backend.snap.copy(targetTempC = 72)
        pub.onSnapshot()
        assertEquals(3, states().size)
    }

    private fun job(state: String) = backend.snap.copy(
        jobActive = true, jobState = state, jobKind = "MANUAL", jobLabel = "Cuisson manuelle", totalMs = 30_000,
        endReason = if (state == "FINISHED") "Terminé" else if (state == "ABORTED") "Arrêté" else "",
    )

    private fun jobDone() = bus.since(0).filter { it.name == "job_done" }

    @Test
    fun aJobThatEndsPublishesJobDoneOnceAndLeavesTheState() {
        pub.onSnapshot()
        for (st in listOf("STARTING", "RUNNING", "STOPPING")) {
            now += 10; backend.snap = job(st); pub.onSnapshot()
        }
        assertEquals(0, jobDone().size)
        now += 10; backend.snap = job("FINISHED"); pub.onSnapshot()
        // L'écran de fin reste affiché un moment : le snapshot garde le travail terminé.
        repeat(5) { now += 100; pub.onSnapshot() }
        now += 10; backend.snap = backend.snap.copy(jobActive = false, jobState = "")   // écran de fin fermé
        pub.onSnapshot()
        val e = Json.parse(jobDone().single().data).obj()
        assertEquals("finished", e["result"])
        assertEquals("MANUAL", e["kind"])
        assertEquals("Cuisson manuelle", e["label"])
        assertEquals("Terminé", e["reason"])
        assertEquals(30, (e["totalS"] as Number).toInt())
        assertEquals("pas de job dans le dernier état", null, Json.parse(states().last().data).obj()["job"])
    }

    @Test
    fun anAbortedJobIsReportedAsAborted() {
        pub.onSnapshot()
        now += 10; backend.snap = job("RUNNING"); pub.onSnapshot()
        now += 10; backend.snap = job("ABORTED"); pub.onSnapshot()
        assertEquals("aborted", Json.parse(jobDone().single().data).obj()["result"])
    }

    @Test
    fun aJobAlreadyEndedAtTheFirstSnapshotProducesNoEvent() {
        backend.snap = job("FINISHED")   // redémarrage de l'application : l'écran de fin était déjà là
        pub.onSnapshot()
        now += 100; pub.onSnapshot()
        assertEquals(0, jobDone().size)
        assertEquals(null, Json.parse(states().single().data).obj()["job"])
    }

    @Test
    fun closingTheEndScreenIsNotAStateChange() {
        pub.onSnapshot()
        now += 10; backend.snap = job("RUNNING"); pub.onSnapshot()
        now += 10; backend.snap = job("FINISHED"); pub.onSnapshot()
        val before = states().size
        now += 10; backend.snap = backend.snap.copy(jobActive = false, jobState = "", jobKind = "", jobLabel = "")
        pub.onSnapshot()
        assertEquals(before, states().size)
    }

    @Test
    fun jobAndPhaseChangesGoOutImmediately() {
        pub.onSnapshot()
        now += 10
        backend.snap = backend.snap.copy(jobActive = true, jobState = "RUNNING", jobKind = "MANUAL", phaseCount = 2)
        pub.onSnapshot()
        now += 10
        backend.snap = backend.snap.copy(phaseIndex = 1)
        pub.onSnapshot()
        assertEquals(3, states().size)
    }

    @Test
    fun idleHeartbeatIsThirtySeconds() {
        pub.onSnapshot()
        now += 29_000
        pub.onSnapshot()
        assertEquals(1, states().size)
        now += 1_000
        pub.onSnapshot()
        assertEquals(2, states().size)
    }

    @Test
    fun heartbeatReportsTheGrowingSnapshotAgeWhenTheMachineIsSilent() {
        backend.snap = backend.snap.copy(link = app.toque.core.machine.LinkState.OFFLINE)
        backend.snapAge = 3_500
        pub.onSnapshot()
        now += 30_000
        backend.snapAge = 33_500
        pub.onSnapshot()   // aucun instantané nouveau, mais le signal de vie part
        val ages = states().map { (Json.parse(it.data).obj()["snapshotAgeMs"] as Number).toLong() }
        assertEquals(listOf(3_500L, 33_500L), ages)
        assertEquals("OFFLINE", Json.parse(states().last().data).obj()["link"])
    }

    @Test
    fun heartbeatIsFiveSecondsDuringAJob() {
        backend.snap = backend.snap.copy(jobActive = true, jobState = "RUNNING", jobKind = "MANUAL")
        pub.onSnapshot()
        now += 4_900
        backend.snap = backend.snap.copy(remainingMs = 10_000)
        pub.onSnapshot()
        assertEquals(1, states().size)
        now += 200
        pub.onSnapshot()
        assertEquals(2, states().size)
    }

    @Test
    fun faultRaisedAndClearedArePublished() {
        pub.onSnapshot()
        now += 10
        backend.snap = backend.snap.copy(faultCode = "E7", faultMessage = "Surchauffe")
        pub.onSnapshot()
        val raised = bus.since(0).first { it.name == "fault" }
        assertEquals(mapOf("code" to "E7", "message" to "Surchauffe", "active" to true), Json.parse(raised.data).obj())
        now += 10
        backend.snap = backend.snap.copy(faultCode = "", faultMessage = "")
        pub.onSnapshot()
        val cleared = bus.since(0).filter { it.name == "fault" }.last()
        assertEquals("E7", Json.parse(cleared.data).obj()["code"])
        assertEquals(false, Json.parse(cleared.data).obj()["active"])
        assertEquals(2, bus.since(0).count { it.name == "fault" })
        // Un défaut qui dure ne se répète pas.
        now += 10
        pub.onSnapshot()
        assertEquals(2, bus.since(0).count { it.name == "fault" })
    }

    @Test
    fun screenChangeIsPublished() {
        pub.onSnapshot()
        backend.screen = false
        pub.onScreenChanged()
        assertEquals(false, Json.parse(states().last().data).obj()["screenOn"])
    }

    @Test
    fun cookEventsBecomeSessionStepDoneAndState() {
        backend.sess = session
        pub.onCookEvent(CookEvent.Changed(session))
        assertEquals(listOf("session", "state"), names())
        assertEquals("recipe", Json.parse(states().last().data).obj()["status"])

        val before = bus.lastId
        pub.onCookEvent(CookEvent.StepChanged(session.copy(stepIndex = 2)))
        assertEquals(listOf("step", "state"), names(before))
        val step = Json.parse(bus.since(before).first().data).obj()
        assertEquals("manual", step["kind"])
        assertEquals("s1", step["sessionId"])
    }

    @Test
    fun endingASessionPublishesDoneAndAnInactiveSession() {
        backend.sess = session
        pub.onCookEvent(CookEvent.Changed(session))
        val before = bus.lastId
        backend.sess = null
        pub.onCookEvent(CookEvent.Ended("s1", "veloute", SessionResult.FINISHED))
        assertEquals(listOf("done", "session", "state"), names(before))
        val events = bus.since(before)
        assertEquals("finished", Json.parse(events[0].data).obj()["result"])
        assertEquals(false, Json.parse(events[1].data).obj()["active"])
        assertEquals("standby", Json.parse(events[2].data).obj()["status"])

        pub.onCookEvent(CookEvent.Ended("s2", "x", SessionResult.ABORTED))
        assertEquals("aborted", Json.parse(bus.since(0).last { it.name == "done" }.data).obj()["result"])
    }

    @Test
    fun recipesEventCarriesRevAndTotal() {
        backend.recipes += sampleRecipe()
        backend.rev = 8
        pub.onRecipesChanged()
        val e = Json.parse(bus.since(0).single().data).obj()
        assertEquals(8, (e["rev"] as Number).toInt())
        assertEquals(1, (e["total"] as Number).toInt())
    }

    @Test
    fun aFailingSubscriberDoesNotBlockTheOthersNorThePublisher() {
        val errors = ArrayList<Exception>()
        val b = EventBus(onListenerError = { errors += it })
        val seen = ArrayList<String>()
        b.subscribe { throw IllegalStateException("boum") }
        b.subscribe { seen += it.name }
        val e = b.publish("x", null)
        assertEquals(listOf("x"), seen)
        assertEquals(1, errors.size)
        assertEquals(e.id, b.lastId)
    }

    @Test
    fun busKeepsOnlyTheLastEventsAndSubscribersGetLiveOnes() {
        val small = EventBus(capacity = 3)
        val seen = ArrayList<String>()
        val off = small.subscribe { seen += it.name }
        repeat(5) { small.publish("e$it", mapOf("n" to it)) }
        assertEquals(listOf("e0", "e1", "e2", "e3", "e4"), seen)
        assertEquals(listOf("e2", "e3", "e4"), small.since(0).map { it.name })
        assertEquals(listOf("e4"), small.since(4).map { it.name })
        assertEquals(5L, small.lastId)
        off()
        small.publish("late", null)
        assertEquals(5, seen.size)
        assertTrue(small.since(5).single().data == "null")
    }
}
