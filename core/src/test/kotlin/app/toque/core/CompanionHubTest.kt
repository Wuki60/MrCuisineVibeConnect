package app.toque.core

import app.toque.core.companion.ActivityWire
import app.toque.core.companion.Alerts
import app.toque.core.companion.ApiError
import app.toque.core.companion.CatalogFilter
import app.toque.core.companion.CatalogPageWire
import app.toque.core.companion.CatalogQuery
import app.toque.core.companion.CatalogSearch
import app.toque.core.companion.CommandResult
import app.toque.core.companion.CompanionHost
import app.toque.core.companion.CompanionHub
import app.toque.core.companion.CookSession
import app.toque.core.companion.Hello
import app.toque.core.companion.JobWire
import app.toque.core.companion.Journal
import app.toque.core.companion.JournalEntry
import app.toque.core.companion.JournalPage
import app.toque.core.companion.MachineWire
import app.toque.core.companion.Origin
import app.toque.core.companion.PairReply
import app.toque.core.companion.PauseWire
import app.toque.core.companion.Permission
import app.toque.core.companion.Permissions
import app.toque.core.companion.PhoneWire
import app.toque.core.companion.RecipeDetail
import app.toque.core.companion.RecipeSummary
import app.toque.core.companion.RobotWire
import app.toque.core.companion.SessionWire
import app.toque.core.companion.StateWire
import app.toque.core.companion.SystemCompanionClock
import app.toque.core.companion.WaitingEnd
import app.toque.core.companion.WaitingFor
import app.toque.core.companion.WaitingWire
import app.toque.core.companion.WhoAmI
import app.toque.core.companion.Wires
import app.toque.core.companion.AdjustRequest
import app.toque.core.companion.CommandReply
import app.toque.core.json.Json
import app.toque.core.machine.EventType
import app.toque.core.machine.JobState
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.Programs
import app.toque.core.protocol.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CompanionHubTest {

    private fun state(f: HubFixture, token: String) = StateWire.fromMap(f.json(f.req("GET", "/api/state", token)))
    private fun error(f: HubFixture, r: app.toque.core.companion.HttpResponse) = ApiError.fromMap(f.json(r))

    /** Toutes les routes protégées, avec un corps plausible. */
    private val routes = listOf(
        Triple("GET", "/api/robot", null), Triple("GET", "/api/state", null), Triple("GET", "/api/journal", null),
        Triple("GET", "/api/recipes", null), Triple("GET", "/api/recipes/perso-veloute", null),
        Triple("GET", "/api/recipes/perso-veloute/image", null),
        Triple("GET", "/api/recipes/perso-veloute/steps", null),
        Triple("POST", "/api/recipes/perso-veloute/steps/2", mapOf("key" to "0123456789abcdef", "machine" to null)),
        Triple("DELETE", "/api/recipes/perso-veloute/steps/2", null),
        Triple("POST", "/api/waiting", mapOf("recipeId" to "perso-veloute")), Triple("DELETE", "/api/waiting", null),
        Triple("POST", "/api/machine/pause", null), Triple("POST", "/api/machine/resume", null),
        Triple("POST", "/api/machine/stop", null), Triple("POST", "/api/machine/tare", null),
        Triple("POST", "/api/machine/adjust", mapOf("speed" to 2)),
        Triple("POST", "/api/phones/me", mapOf("name" to "Cuisine")), Triple("DELETE", "/api/phones/me", null),
        Triple("GET", "/api/nothing-here", null), Triple("POST", "/api/machine/start", null),
    )

    @Test fun everyRouteRefusesAClientWithoutAValidToken() {
        val f = HubFixture()
        f.hub.updateMachine(Snaps.running())
        val good = f.pair()
        val removed = f.pair()
        f.hub.pairing.remove(f.hub.pairing.authenticate(removed)!!.id)
        for (token in listOf(null, "", "0".repeat(64), good.dropLast(1) + "x", removed)) {
            for ((method, path, body) in routes) {
                val r = f.req(method, path, token, body)
                assertEquals("$method $path ($token)", 401, r.status)
                val text = String(r.body)
                assertFalse(text.contains(f.hub.pairing.robotId))
                assertFalse(text.contains("Velouté"))
            }
        }
        assertTrue("aucune commande ne doit atteindre la machine", f.host.calls.isEmpty())
        // Sans jeton : seulement hello (qui ne dit rien du robot) et l'appairage.
        val hello = f.req("GET", "/api/hello")
        assertEquals(200, hello.status)
        assertEquals(Hello(pairing = false), Hello.fromMap(f.json(hello)))
        assertEquals(setOf("service", "protocol", "pairing"), f.json(hello).keys)
        assertEquals(410, f.req("POST", "/api/pair", body = mapOf("code" to "123456")).status)
        // Avec le bon jeton, les mêmes routes répondent.
        assertEquals(200, f.req("GET", "/api/state", good).status)
        assertEquals(404, f.req("GET", "/api/nothing-here", good).status)
        // Le lancement d'une cuisson manuelle existe, sans corps : requête invalide.
        assertEquals(400, f.req("POST", "/api/machine/start", good).status)
    }

    @Test fun refusesDomainNamesInTheHostHeader() {
        val f = HubFixture()
        val t = f.pair()
        val ok = listOf("192.168.0.12", "192.168.0.12:8088", "localhost:8088", "[::1]:8088", "127.0.0.1")
        val bad = listOf("evil.example.com", "evil.example.com:8088", "192.168.0.12.evil.com", "", "[::1]x", "192.168.0.12:")
        for (h in ok) assertTrue(h, app.toque.core.companion.CompanionApi.hostAllowed(h))
        for (h in bad) assertFalse(h, app.toque.core.companion.CompanionApi.hostAllowed(h))
        val r = f.api.handle(app.toque.core.companion.HttpRequest("GET", "/api/state", emptyMap(),
            mapOf("host" to "rebind.example.com", "authorization" to "Bearer $t"), ByteArray(0), "192.168.0.20"))
        assertEquals(421, r.status)
    }

    @Test fun pairingOverHttpThenForgetting() {
        val f = HubFixture()
        val o = f.hub.pairing.open()
        assertTrue(Hello.fromMap(f.json(f.req("GET", "/api/hello"))).pairing)
        assertEquals(403, f.req("POST", "/api/pair", body = mapOf("code" to "abcdef")).status)
        assertEquals(400, f.req("POST", "/api/pair", body = mapOf("code" to o.code, "secret" to o.secret)).status)
        val r = f.req("POST", "/api/pair", body = mapOf("secret" to o.secret))
        assertEquals(200, r.status)
        val reply = PairReply.fromMap(f.json(r))
        assertEquals(f.hub.pairing.robotId, reply.robot.id)
        assertEquals("qr", reply.phone.method)
        val who = WhoAmI.fromMap(f.json(f.req("GET", "/api/robot", reply.token)))
        assertEquals(reply.phone.id, who.phone.id)
        assertEquals(200, f.req("DELETE", "/api/phones/me", reply.token).status)
        assertEquals(401, f.req("GET", "/api/state", reply.token).status)
        // Corps refusés.
        f.hub.pairing.open()
        val raw = f.api.handle(app.toque.core.companion.HttpRequest("POST", "/api/pair", emptyMap(),
            mapOf("content-type" to "text/plain"), "{}".toByteArray(), "x"))
        assertEquals(415, raw.status)
        val bad = f.api.handle(app.toque.core.companion.HttpRequest("POST", "/api/pair", emptyMap(),
            mapOf("content-type" to "application/json"), "{".toByteArray(), "x"))
        assertEquals(400, bad.status)
    }

    @Test fun resumeIsAllowedOnlyAfterAUserPauseWithTheLidKeptClosed() {
        val f = HubFixture()
        val t = f.pair()
        f.hub.updateMachine(Snaps.running())
        assertTrue(state(f, t).can.pause.allowed)
        assertEquals(200, f.req("POST", "/api/machine/pause", t).status)
        f.hub.updateMachine(Snaps.paused("USER"))
        val st = state(f, t)
        assertTrue(st.can.resume.allowed)
        assertTrue(st.job!!.pause!!.origin.self)
        assertEquals(Origin.PHONE, st.job!!.pause!!.origin.kind)
        assertEquals(st.job!!.pause!!.since + 600_000, st.job!!.pause!!.autoCancelAt)
        assertEquals(200, f.req("POST", "/api/machine/resume", t).status)
        assertEquals(true, f.host.lastStillAllowed)

        // Couvercle ouvert puis refermé pendant une pause demandée : reprise sur le robot seulement.
        f.hub.updateMachine(Snaps.running())
        f.hub.updateMachine(Snaps.paused("USER"))
        f.hub.updateMachine(Snaps.paused("USER").copy(lidOpen = true))
        f.hub.updateMachine(Snaps.paused("USER"))
        val calls = f.host.calls.size
        val r = f.req("POST", "/api/machine/resume", t)
        assertEquals(409, r.status)
        assertEquals("lid_opened_during_pause", error(f, r).code)
        assertEquals(calls, f.host.calls.size)
        assertTrue(state(f, t).job!!.pause!!.lidOpenedDuringPause)

        // Une ouverture trop brève pour un Snapshot est vue par l'évènement.
        f.hub.updateMachine(Snaps.running())
        f.hub.updateMachine(Snaps.paused("USER"))
        f.hub.onMachineEvent(MachineEvent(EventType.LID_OPENED, "Couvercle ouvert"))
        assertEquals("lid_opened_during_pause", state(f, t).can.resume.code)

        // Pauses LID, bol retiré et SAFETY : jamais depuis le téléphone.
        for ((snap, code) in listOf(Snaps.paused("LID") to "lid_opened",
            Snaps.paused("LID").copy(lidOpen = true, bowlRemoved = true) to "bowl_removed",
            Snaps.paused("SAFETY") to "safety")) {
            f.hub.updateMachine(Snaps.running())
            f.hub.updateMachine(snap)
            if (code != "bowl_removed") f.hub.updateMachine(snap.copy(lidOpen = false))
            val rr = f.req("POST", "/api/machine/resume", t)
            assertEquals(code, 409, rr.status)
            assertEquals(code, error(f, rr).code)
        }

        // Pause commencée avant que le compagnon ne la voie : refusée.
        val g = HubFixture()
        val t2 = g.pair()
        g.hub.updateMachine(Snaps.paused("USER"))
        assertEquals("pause_unknown", state(g, t2).can.resume.code)

        // Contenu chaud à vitesse ≥ 4 sans confirmation : reprise sur le robot.
        f.hub.updateMachine(Snaps.running(speed = 5, tempC = 70))
        f.hub.updateMachine(Snaps.running(speed = 5, tempC = 70).copy(jobState = "PAUSED", pauseReason = "USER"))
        assertEquals("hot_blend", state(f, t).can.resume.code)
    }

    @Test fun theResumeRuleIsCheckedAgainOnTheHostThread() {
        val base = FakeHost()
        lateinit var hub: CompanionHub
        var checked: Boolean? = null
        val host = object : CompanionHost by base {
            override fun resume(stillAllowed: () -> Boolean): CommandResult {
                // Entre la vérification du serveur et l'exécution sur le fil de l'appli, le couvercle s'ouvre.
                hub.updateMachine(Snaps.paused("USER").copy(lidOpen = true))
                checked = stillAllowed()
                return if (checked == true) CommandResult.OK else CommandResult.refuse("changed", "changé")
            }
        }
        hub = CompanionHub(host, FakeCompanionClock(), app.toque.core.companion.MemoryStore(), Random(1))
        val api = app.toque.core.companion.CompanionApi(hub)
        val token = hub.pairing.pairWithCode(hub.pairing.open().code).token!!
        hub.updateMachine(Snaps.running())
        hub.updateMachine(Snaps.paused("USER"))
        val r = api.handle(app.toque.core.companion.HttpRequest("POST", "/api/machine/resume", emptyMap(),
            mapOf("authorization" to "Bearer $token"), ByteArray(0), "x"))
        assertEquals(409, r.status)
        assertEquals(false, checked)
    }

    @Test fun adjustStaysWithinTheRulesAndNeverConfirmsAHotBlend() {
        val b = Bench().ready()
        val f = HubFixture(FakeHost(b.c))
        val t = f.pair()
        assertTrue(b.c.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 300_000)).ok)
        b.step(500)
        f.hub.updateMachine(b.snap)
        assertTrue(state(f, t).can.adjust.allowed)
        assertEquals(200, f.req("POST", "/api/machine/adjust", t, mapOf("addMs" to 60_000)).status)
        b.step(200)
        assertEquals(360_000L, b.snap.totalMs)
        assertEquals(200, f.req("POST", "/api/machine/adjust", t, mapOf("speed" to 3, "tempC" to 90)).status)
        b.step(200)
        assertEquals(3, b.snap.jobSpeed)
        assertEquals(11, b.snap.jobTempLevel)
        // Le contrôleur garde ses règles : vitesse 5 en chauffe refusée.
        f.hub.updateMachine(b.snap)
        val r = f.req("POST", "/api/machine/adjust", t, mapOf("speed" to 5))
        assertEquals(409, r.status)
        assertEquals("heated_speed", error(f, r).code)
        // Entrées invalides.
        for (body in listOf(emptyMap<String, Any>(), mapOf("speed" to 11), mapOf("tempC" to 91),
            mapOf("totalMs" to 1, "addMs" to 1), mapOf("totalMs" to 500)))
            assertTrue(body.toString(), f.req("POST", "/api/machine/adjust", t, body).status in listOf(400, 409))
        // Contenu chaud : un téléphone ne passe jamais la vitesse à 4 ou plus.
        f.hub.updateMachine(b.snap.copy(actualTempC = 75, jobTempLevel = 0))
        val before = f.host.calls.size
        val hot = f.req("POST", "/api/machine/adjust", t, mapOf("speed" to 6, "tempC" to 0))
        assertEquals("hot_blend", error(f, hot).code)
        assertEquals(before, f.host.calls.size)
        assertFalse(b.snap.hotBlendConfirmed)
        // L'interface de l'hôte n'a aucun paramètre de confirmation à chaud.
        val adjust = CompanionHost::class.java.methods.first { it.name == "adjust" }
        assertEquals(1, adjust.parameterCount)
        assertTrue(app.toque.core.companion.AdjustCommand::class.java.declaredFields.none { it.name.lowercase().contains("hot") })
        // Les programmes à phases fixes ne se règlent pas.
        f.hub.updateMachine(Snaps.running(kind = "KNEAD", phases = 3))
        assertEquals("fixed", state(f, t).can.adjust.code)
    }

    /** Seul « start » (cuisson manuelle, coupée tant que le robot ne l'a pas permise) peut lancer quelque chose. */
    /** Un yaourt est une cuisson MANUAL pour la base ; le téléphone doit savoir que c'est une fermentation. */
    @Test fun aFermentationIsToldToPhones() {
        val b = Bench().ready()
        val f = HubFixture(FakeHost(b.c))
        val t = f.pair()
        assertTrue(b.c.startJob(Programs.yogurt(8 * 3600_000L)).ok)
        b.step(500)
        f.hub.updateMachine(b.snap)
        val job = state(f, t).job!!
        assertEquals("MANUAL", job.kind)
        assertEquals("Yaourt", job.label)
        assertEquals(1, job.phaseCount)
        assertEquals(0, job.speed)
        assertEquals(8 * 3600_000L, job.totalMs)
        assertTrue(job.ferment)
        @Suppress("UNCHECKED_CAST")
        val raw = f.json(f.req("GET", "/api/state", t))["job"] as Map<String, Any?>
        assertEquals(true, raw["ferment"])
        assertEquals(job, JobWire.fromMap(raw))

        // Une cuisson manuelle n'en dit rien : son JSON reste celui d'avant.
        b.c.stop(); b.step(3_000); b.c.clearFinished()
        assertTrue(b.c.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 300_000)).ok)
        b.step(500)
        f.hub.updateMachine(b.snap)
        assertFalse(state(f, t).job!!.ferment)
        @Suppress("UNCHECKED_CAST")
        assertFalse((f.json(f.req("GET", "/api/state", t))["job"] as Map<String, Any?>).containsKey("ferment"))
    }

    @Test fun noRouteCanStartAnything() {
        val names = CompanionHost::class.java.methods.map { it.name.lowercase() }
        assertEquals(listOf("start"), names.filter { it.contains("start") })
        for (bad in listOf("turbo", "acknowledge", "confirm", "launch", "setting"))
            assertTrue(bad, names.none { it.contains(bad) })
        val b = Bench().ready()
        val f = HubFixture(FakeHost(b.c))
        val t = f.pair()
        f.hub.updateMachine(b.snap)
        for ((method, path, body) in routes) {
            f.req(method, path, t, body)
            b.step(300)
            f.hub.updateMachine(b.snap)
            assertFalse("$method $path", b.c.busy)
            assertFalse("$method $path", b.snap.jobActive)
            assertFalse(b.snap.motorOn)
            assertFalse(b.snap.heaterOn)
            // « Oublier » retire ce téléphone : les routes suivantes ne passeraient plus.
            if (method == "DELETE" && path == "/api/phones/me") break
        }
        // L'interrupteur du robot est coupé tant que l'appli ne l'a pas donné : même un corps valide ne lance rien.
        assertEquals(409, f.req("POST", "/api/machine/start", f.pair(), mapOf("seconds" to 60, "tempC" to 0, "speed" to 2,
            "reverse" to false, "accessory" to "BLADE")).status)
        b.step(300)
        f.hub.updateMachine(b.snap)
        assertFalse(b.snap.jobActive)
        for (cmd in listOf("turbo", "acknowledge", "hot", "confirm", "launch", "program", "step"))
            assertEquals(404, f.req("POST", "/api/machine/$cmd", f.pair()).status)
    }

    @Test fun revisionMovesOnRealChangesNotOnTheTicking() {
        val f = HubFixture()
        val t = f.pair()
        var s = Snaps.running()
        f.hub.updateMachine(s)
        val r0 = f.hub.currentRevision
        // Le temps qui s'écoule normalement ne change pas la révision (le téléphone extrapole).
        repeat(20) {
            f.clock.advance(100)
            s = s.copy(remainingMs = s.remainingMs - 100, elapsedMs = s.elapsedMs + 100)
            f.hub.updateMachine(s)
        }
        assertEquals(r0, f.hub.currentRevision)
        // Une durée ajoutée (sans changement de totalMs vu ici) : l'écart suffit.
        s = s.copy(remainingMs = s.remainingMs + 60_000)
        f.hub.updateMachine(s)
        assertEquals(r0 + 1, f.hub.currentRevision)
        // Mesures : une révision au plus toutes les 300 ms.
        f.clock.advance(400)
        f.hub.updateMachine(s.copy(weightG = 10))
        val r1 = f.hub.currentRevision
        f.clock.advance(100); f.hub.updateMachine(s.copy(weightG = 20))
        f.clock.advance(100); f.hub.updateMachine(s.copy(weightG = 30))
        assertEquals(r1, f.hub.currentRevision)
        f.clock.advance(150); f.hub.updateMachine(s.copy(weightG = 30))
        assertEquals(r1 + 1, f.hub.currentRevision)
        // Un changement d'état passe tout de suite.
        f.hub.updateMachine(s.copy(weightG = 30, jobState = "PAUSED", pauseReason = "USER"))
        assertEquals(r1 + 2, f.hub.currentRevision)
        assertEquals(r1 + 2, state(f, t).revision)
    }

    @Test fun longPollWaitsForAChangeAndIsBounded() {
        val f = HubFixture(clock = FakeCompanionClock())
        val hub = CompanionHub(f.host, SystemCompanionClock, f.store, Random(2))
        val api = app.toque.core.companion.CompanionApi(hub)
        val token = hub.pairing.pairWithCode(hub.pairing.open().code).token!!
        fun get(q: Map<String, String>) = api.handle(app.toque.core.companion.HttpRequest("GET", "/api/state", q,
            mapOf("authorization" to "Bearer $token"), ByteArray(0), "x"))
        val rev = hub.currentRevision
        // Révision différente : réponse immédiate.
        var t0 = System.nanoTime()
        get(mapOf("since" to (rev - 1).toString(), "wait" to "10"))
        assertTrue((System.nanoTime() - t0) < 500_000_000L)
        // Rien ne change : attente bornée par « wait ».
        t0 = System.nanoTime()
        val same = StateWire.fromMap(Json.parse(String(get(mapOf("since" to rev.toString(), "wait" to "1")).body)) as Map<String, Any?>)
        val waited = (System.nanoTime() - t0) / 1_000_000
        assertTrue("$waited ms", waited in 900..2_500)
        assertEquals(rev, same.revision)
        // Un changement réveille l'attente en moins d'une seconde.
        Thread {
            Thread.sleep(200)
            hub.updateSession(CookSession.of(CompanionData.veloute, 0, waitingFor = WaitingFor.HAND))
        }.start()
        t0 = System.nanoTime()
        val changed = StateWire.fromMap(Json.parse(String(get(mapOf("since" to rev.toString(), "wait" to "20")).body)) as Map<String, Any?>)
        assertTrue((System.nanoTime() - t0) < 1_500_000_000L)
        assertTrue(changed.revision > rev)
        assertEquals("hand", changed.session!!.waitingFor)
        assertEquals(400, get(mapOf("since" to "abc")).status)
    }

    @Test fun journalTracksJobsSessionsAndAlertsWithSequenceNumbers() {
        val b = Bench().ready()
        val f = HubFixture(FakeHost(b.c))
        val t = f.pair()
        val other = f.pair()
        fun sync(ms: Long = 200) {
            b.step(ms)
            f.clock.advance(ms)
            f.hub.updateMachine(b.snap)
        }
        sync()
        // Recette guidée : attente, étape lancée, finie.
        val r = CompanionData.veloute
        f.hub.updateSession(CookSession.of(r, 0, waitingFor = WaitingFor.HAND))
        f.hub.updateSession(CookSession.of(r, 1, setOf(0), waitingFor = WaitingFor.WEIGH))
        f.hub.updateSession(CookSession.of(r, 1, setOf(0), waitingFor = WaitingFor.WEIGH))
        f.hub.updateSession(CookSession.of(r, 2, setOf(0, 1), waitingFor = WaitingFor.LAUNCH, weighed = mapOf(1 to 21)))
        val spec = Programs.manual(1, 0, Direction.CLOCKWISE, 2_000, label = r.title)
        assertTrue(b.c.startJob(spec).ok)
        f.hub.updateSession(CookSession.of(r, 2, setOf(0, 1), listOf(2), WaitingFor.NOTHING, mapOf(1 to 21)))
        sync()
        // Pause depuis un téléphone, reprise sur le robot.
        assertEquals(200, f.req("POST", "/api/machine/pause", t).status)
        sync()
        assertTrue(b.c.resume().ok)
        sync()
        sync(3_500)
        assertEquals(JobState.FINISHED.name, b.snap.jobState)
        b.c.clearFinished()
        sync()
        f.hub.endSession(true)
        // Cuisson manuelle hors recette, arrêtée par l'autre téléphone.
        assertTrue(b.c.startJob(Programs.manual(2, 0, Direction.CLOCKWISE, 60_000)).ok)
        sync(1_000)
        assertEquals(200, f.req("POST", "/api/machine/stop", other).status)
        sync()

        val page = JournalPage.fromMap(f.json(f.req("GET", "/api/journal", t)))
        val kinds = page.entries.reversed().map { it.kind }
        assertEquals(listOf("recipe_started", "waiting", "waiting", "waiting", "step_started", "paused", "resumed",
            "step_finished", "recipe_finished", "job_started", "job_stopped"), kinds)
        // Numéros croissants sans trou, la page en tête.
        val seqs = page.entries.map { it.seq }
        assertEquals((1L..seqs.size).reversed().toList(), seqs)
        assertEquals(page.lastSeq, seqs.first())
        assertEquals(page.lastSeq, state(f, t).journalSeq)
        val alerts = page.entries.reversed().map { it.alert }
        assertEquals(listOf("", "waiting", "waiting", "waiting", "", "pause", "", "step_end", "job_end", "", "job_end"), alerts)
        val pause = page.entries.first { it.kind == "paused" }
        assertTrue(pause.origin.self)
        assertEquals("USER", pause.detail)
        assertEquals(Origin.ROBOT, page.entries.first { it.kind == "resumed" }.origin.kind)
        val stop = page.entries.first { it.kind == "job_stopped" }
        assertEquals(Origin.PHONE, stop.origin.kind)
        assertFalse(stop.origin.self)
        assertEquals("Téléphone 2", stop.origin.name)
        // Activités : la recette (terminée), la cuisson (arrêtée depuis un téléphone).
        assertEquals(listOf("manual", "recipe"), page.activities.map { it.kind })
        assertEquals("stopped", page.activities[0].outcome)
        assertEquals("finished", page.activities[1].outcome)
        assertEquals(2, page.activities[1].handSteps)
        assertEquals("manual", state(f, t).last!!.kind)
        // Suite : seulement les nouvelles lignes.
        f.req("POST", "/api/waiting", t, mapOf("recipeId" to "r1"))
        val next = JournalPage.fromMap(f.json(f.req("GET", "/api/journal", t, query = mapOf("after" to page.lastSeq.toString()))))
        assertEquals(listOf("recipe_sent"), next.entries.map { it.kind })
        assertEquals(Alerts.WAITING, next.entries[0].alert)
    }

    @Test fun journalIsBounded() {
        val j = Journal(maxEntries = 5, maxActivities = 2)
        repeat(12) { j.add(it.toLong(), "k", "t$it") }
        val a1 = j.start(1, "manual", "a")
        j.start(2, "manual", "b")
        j.start(3, "manual", "c")
        val p = j.page(0, 100, "")
        assertEquals(12L, p.lastSeq)
        assertEquals(listOf(12L, 11L, 10L, 9L, 8L), p.entries.map { it.seq })
        assertEquals(listOf("c", "b"), p.activities.map { it.title })
        assertNull(j.activity(a1.id))
        assertEquals(listOf(12L, 11L), j.page(10, 100, "").entries.map { it.seq })
        assertEquals(listOf(12L), j.page(0, 1, "").entries.map { it.seq })
    }

    @Test fun waitingRecipeIsReplacedRemovedAndNotified() {
        val f = HubFixture()
        val t = f.pair()
        var events = 0
        f.hub.onWaitingChanged { events++ }
        f.hub.updateMachine(Snaps.running())
        assertEquals(409, f.req("DELETE", "/api/waiting", t).status)
        assertEquals(404, f.req("POST", "/api/waiting", t, mapOf("recipeId" to "inconnue")).status)
        assertEquals(400, f.req("POST", "/api/waiting", t, mapOf("x" to 1)).status)
        assertEquals(200, f.req("POST", "/api/waiting", t, mapOf("recipeId" to "r1")).status)
        assertEquals(200, f.req("POST", "/api/waiting", t, mapOf("recipeId" to "perso-veloute")).status)
        val st = state(f, t)
        assertEquals("perso-veloute", st.waiting!!.recipeId)
        assertEquals(4, st.waiting!!.stepCount)
        assertTrue(st.waiting!!.origin.self)
        // Arriver pendant une cuisson n'interrompt rien.
        assertTrue(f.host.calls.isEmpty())
        assertEquals("RUNNING", st.job!!.state)
        assertTrue(st.can.removeWaiting.allowed)
        assertEquals(200, f.req("DELETE", "/api/waiting", t).status)
        assertNull(state(f, t).waiting)
        f.req("POST", "/api/waiting", t, mapOf("recipeId" to "r2"))
        f.hub.clearWaiting(WaitingEnd.LAUNCHED)
        assertNull(f.hub.waitingRecipe)
        assertEquals(5, events)
    }

    @Test fun catalogueSearchesFiltersAndPages() {
        val f = HubFixture()
        val t = f.pair()
        fun page(q: Map<String, String>) = CatalogPageWire.fromMap(f.json(f.req("GET", "/api/recipes", t, query = q)))
        val all = page(mapOf("limit" to "10"))
        assertEquals(25, all.total)
        assertEquals(10, all.items.size)
        val second = page(mapOf("limit" to "10", "offset" to "20"))
        assertEquals(5, second.items.size)
        assertEquals(20, second.offset)
        assertTrue((all.items + page(mapOf("limit" to "10", "offset" to "10")).items + second.items).map { it.id }.toSet().size == 25)
        assertEquals(listOf("r3", "perso-veloute", "r1"), page(mapOf("filter" to "recent")).items.map { it.id })
        assertEquals(listOf("r2", "r3"), page(mapOf("filter" to "frequent")).items.map { it.id })
        assertTrue(page(mapOf("filter" to "favorites")).items.all { it.favorite })
        assertEquals("perso-veloute", page(mapOf("q" to "potimaron")).items.first().id)
        assertEquals(listOf("perso-veloute"), page(mapOf("q" to "velouté", "filter" to "recent")).items.map { it.id })
        assertEquals(25, page(mapOf("limit" to "500")).items.size)
        assertEquals(20, page(emptyMap()).items.size)
        assertEquals(400, f.req("GET", "/api/recipes", t, query = mapOf("filter" to "nope")).status)
        assertEquals(400, f.req("GET", "/api/recipes", t, query = mapOf("q" to "x".repeat(101))).status)
        val detail = RecipeDetail.fromMap(f.json(f.req("GET", "/api/recipes/perso-veloute", t)))
        assertEquals("Velouté de potimarron", detail.title)
        assertEquals(4, detail.steps.size)
        assertEquals("3 min · 100 °C · vitesse 1", detail.steps[2].machine!!.summary)
        assertEquals(20, detail.steps[1].weigh!!.grams)
        assertEquals("20 g Beurre", detail.ingredients[0].items[0].display)
        assertEquals(404, f.req("GET", "/api/recipes/inconnue", t).status)
        val img = f.req("GET", "/api/recipes/perso-veloute/image", t)
        assertEquals("image/png", img.contentType)
        assertEquals(3, img.body.size)
        assertEquals(404, f.req("GET", "/api/recipes/r1/image", t).status)
        // Directement, sans HTTP.
        val p = CatalogSearch.page(CompanionData.index, CatalogQuery("", CatalogFilter.ALL, 24, 10))
        assertEquals(1, p.items.size)
    }

    @Test fun everyWireModelRoundTrips() {
        val origin = Origin(Origin.PHONE, "a1b2c3d4", "Téléphone 1", true)
        val step = Wires.step(1, CompanionData.veloute.steps[1], weighedG = 21, hotBlendOnRobot = true)
        val state = StateWire(
            revision = 42, serverTime = 1_795_000_000_000L,
            robot = RobotWire("a1b2c3d4e5f6", "Monsieur Cuisine Connect", "0.7.0", "fr"),
            me = PhoneWire("a1b2c3d4", 1, "Téléphone 1", 1L, 2L, "qr"),
            machine = MachineWire("READY", "Base prête", true, "", "", true, 96, 100, true, 372, true, false, true, false, true, true, false),
            job = JobWire("MANUAL", "Cuisson manuelle", "Velouté", "PAUSED", false, "", 0, 1, 1000, 1_200_000, 760_000, 1, 13, 100,
                false, "BLADE", false, 5L, false, PauseWire("USER", "En pause", 6L, 7L, 8L, origin, false, false)),
            can = Permissions(Permission.YES, Permission.no("lid_opened", "Couvercle ouvert"), Permission.YES, Permission.YES,
                Permission.YES, Permission.YES, Permission.no("no_waiting", "Aucune")),
            session = SessionWire("perso-veloute", "Velouté", 4, 6, 3, listOf(0, 1, 2), listOf(3), "weigh", 9L,
                listOf(step, Wires.step(2, CompanionData.veloute.steps[2]))),
            waiting = WaitingWire("r1", "Recette", 3, 20, 4, "Soupes", true, true, 10L, origin),
            last = ActivityWire(3, "recipe", "Velouté", "perso-veloute", 6, 3, 11L, 12L, "finished", "36 min", 0, origin),
            journalSeq = 17, limits = CompanionHub.LIMITS,
        )
        fun <T> rt(v: T, toMap: (T) -> Map<String, Any?>, fromMap: (Map<String, Any?>) -> T) {
            @Suppress("UNCHECKED_CAST")
            val back = fromMap(Json.parse(Json.write(toMap(v))) as Map<String, Any?>)
            assertEquals(v, back)
        }
        rt(state, { it.toMap() }, { StateWire.fromMap(it) })
        rt(state.copy(job = null, session = null, waiting = null, last = null), { it.toMap() }, { StateWire.fromMap(it) })
        rt(JournalPage(5, listOf(JournalEntry(5, 1L, "paused", "En pause", Alerts.PAUSE, origin, 3, 2, 1000, "USER")),
            listOf(state.last!!)), { it.toMap() }, { JournalPage.fromMap(it) })
        rt(Wires.recipe(CompanionData.veloute), { it.toMap() }, { RecipeDetail.fromMap(it) })
        rt(CatalogPageWire(25, 10, listOf(RecipeSummary("r1", "R", "Soupes", 12, true, false, true, 2, 4.5))), { it.toMap() }, { CatalogPageWire.fromMap(it) })
        rt(PairReply("ab".repeat(32), state.me, state.robot), { it.toMap() }, { PairReply.fromMap(it) })
        rt(WhoAmI(state.robot, state.me), { it.toMap() }, { WhoAmI.fromMap(it) })
        rt(ApiError("wrong_code", "Code incorrect"), { it.toMap() }, { ApiError.fromMap(it) })
        rt(Hello(pairing = true), { it.toMap() }, { Hello.fromMap(it) })
        rt(CommandReply(true, 9), { it.toMap() }, { CommandReply.fromMap(it) })
        rt(AdjustRequest(3, 90, null, 60_000), { it.toMap() }, { AdjustRequest.fromMap(it) })
        rt(app.toque.core.companion.PairRequest(code = "123456"), { it.toMap() }, { app.toque.core.companion.PairRequest.fromMap(it) })
        // Lecture tolérante : champs inconnus ignorés, champs absents par défaut.
        val lenient = StateWire.fromMap(mapOf("revision" to 3L, "futur" to "x"))
        assertEquals(3L, lenient.revision)
        assertNull(lenient.job)
        assertNotNull(lenient.can)
    }
}
