package app.toque.core.api

import app.toque.core.json.Json
import app.toque.core.machine.JobState
import app.toque.core.machine.Snapshot
import app.toque.core.recipes.CookEvent
import app.toque.core.recipes.SessionResult
import java.util.concurrent.CopyOnWriteArrayList

class ApiEvent(val id: Long, val name: String, val data: String)

/** Événements récents (pour `Last-Event-ID`) et abonnés du flux SSE. Sûr entre fils. */
class EventBus(
    private val capacity: Int = 50,
    /** Un abonné qui lève une exception ne prive pas les autres de l'événement. */
    private val onListenerError: (Exception) -> Unit = {},
) {
    private val recent = ArrayDeque<ApiEvent>()
    private val listeners = CopyOnWriteArrayList<(ApiEvent) -> Unit>()
    private var next = 1L

    val lastId: Long get() = synchronized(this) { next - 1 }

    fun publish(name: String, data: Any?): ApiEvent {
        val e = synchronized(this) {
            val ev = ApiEvent(next++, name, Json.write(data))
            recent.addLast(ev)
            while (recent.size > capacity) recent.removeFirst()
            ev
        }
        for (l in listeners) {
            try { l(e) } catch (x: Exception) { onListenerError(x) }
        }
        return e
    }

    /** Abonne [l] ; renvoie la fonction qui désabonne. */
    fun subscribe(l: (ApiEvent) -> Unit): () -> Unit {
        listeners.add(l)
        return { listeners.remove(l) }
    }

    /** Événements d'identifiant strictement supérieur à [lastId], dans l'ordre. */
    fun since(lastId: Long): List<ApiEvent> = synchronized(this) { recent.filter { it.id > lastId } }
}

/**
 * Transforme ce que voit l'application (instantanés de la machine, session de cuisson, écran, catalogue) en
 * événements de l'API, en respectant la cadence décrite dans la spécification (§4) : les changements
 * discrets partent tout de suite, les variations analogiques au plus une fois par seconde, et un signal de vie
 * régulier.
 */
class ApiPublisher(
    private val backend: ApiBackend,
    private val bus: EventBus,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private class Key(
        val status: String, val link: String, val faultCode: String, val screenOn: Boolean, val sessionId: String?,
        val lidOpen: Boolean, val bowl: Boolean, val motor: Boolean, val heater: Boolean,
        val jobActive: Boolean, val jobKind: String, val jobState: String, val jobLabel: String,
        val phase: Int, val phaseCount: Int,
        /** Vitesse annoncée par la base et température demandée : ils changent au passage d'une étape à l'autre. */
        val targetSpeed: Int, val commandedTempC: Int,
        val tempC: Int, val rpm: Int, val weightG: Int,
    ) {
        fun sameDiscrete(o: Key) = status == o.status && link == o.link && faultCode == o.faultCode &&
            screenOn == o.screenOn && sessionId == o.sessionId && lidOpen == o.lidOpen && bowl == o.bowl &&
            motor == o.motor && heater == o.heater && jobActive == o.jobActive && jobKind == o.jobKind &&
            jobState == o.jobState && jobLabel == o.jobLabel && phase == o.phase && phaseCount == o.phaseCount &&
            targetSpeed == o.targetSpeed && commandedTempC == o.commandedTempC

        /** Au repos (ni travail ni recette), la balance dérive de quelques grammes : seuil plus large, plus lent. */
        private val quiet get() = !jobActive && sessionId == null

        fun analogMoved(o: Key, now: Long, lastAtMs: Long, lastWeightAtMs: Long): Boolean {
            if (now - lastAtMs < MIN_ANALOG_INTERVAL_MS) return false
            if (kotlin.math.abs(tempC - o.tempC) >= TEMP_DELTA_C || kotlin.math.abs(rpm - o.rpm) >= RPM_DELTA) return true
            val d = kotlin.math.abs(weightG - o.weightG)
            return if (quiet) d >= WEIGHT_DELTA_QUIET_G && now - lastWeightAtMs >= WEIGHT_QUIET_INTERVAL_MS
            else d >= WEIGHT_DELTA_G
        }
    }

    private var last: Key? = null
    private var lastAtMs = 0L
    private var lastWeightAtMs = 0L
    private var lastFault = ""
    private var jobWasOngoing = false
    private var lastJobKind = ""
    private var lastJobLabel = ""

    private fun key(): Key {
        val s = backend.snapshot
        val session = backend.session
        // Ce que l'API montre du travail : rien une fois terminé (l'écran de fin n'est pas un changement d'état).
        val on = s.jobOngoing
        return Key(
            ApiStatus.of(s, session), s.link.name, s.faultCode, backend.screenOn, session?.id,
            s.lidOpen, s.bowlDetected, s.motorOn, s.heating,
            on, if (on) s.jobKind else "", if (on) s.jobState else "", if (on) s.jobLabel else "",
            if (on) s.phaseIndex else 0, if (on) s.phaseCount else 0,
            s.targetSpeedLevel, s.commandedTempC,
            s.actualTempC, s.rpm, s.weightG,
        )
    }

    private fun publishState(k: Key, now: Long) {
        bus.publish("state", ApiModels.state(
            backend.snapshot, backend.session, backend.screenOn, now / 1000, backend.snapshotAgeMs,
        ))
        if (last == null || last!!.weightG != k.weightG) lastWeightAtMs = now
        last = k
        lastAtMs = now
    }

    /** À appeler à chaque instantané de la machine (~10 fois par seconde). */
    @Synchronized
    fun onSnapshot() {
        val s = backend.snapshot
        if (s.faultCode != lastFault) {
            val code = if (s.faultCode.isEmpty()) lastFault else s.faultCode
            bus.publish("fault", linkedMapOf("code" to code, "message" to s.faultMessage, "active" to s.hasFault))
            lastFault = s.faultCode
        }
        publishJobEnd(s)
        val now = clockMs()
        val k = key()
        val prev = last
        val publish = when {
            prev == null -> true
            !k.sameDiscrete(prev) -> true
            k.analogMoved(prev, now, lastAtMs, lastWeightAtMs) -> true
            else -> now - lastAtMs >= (if (k.jobActive) HEARTBEAT_JOB_MS else HEARTBEAT_IDLE_MS)
        }
        if (publish) publishState(k, now)
    }

    /**
     * `job_done` : une fois, quand un travail qu'on a vu en cours se termine ou est arrêté. Un travail déjà terminé au
     * premier instantané (redémarrage de l'application) ne produit rien. Le travail disparaît alors de `/state`.
     */
    private fun publishJobEnd(s: Snapshot) {
        if (s.jobOngoing) {
            jobWasOngoing = true
            lastJobKind = s.jobKind
            lastJobLabel = s.jobLabel
            return
        }
        if (!jobWasOngoing) return
        jobWasOngoing = false
        if (!s.jobEnded) return
        bus.publish("job_done", linkedMapOf(
            "kind" to lastJobKind, "label" to lastJobLabel,
            "result" to if (s.state == JobState.FINISHED) "finished" else "aborted",
            "reason" to s.endReason, "totalS" to s.totalMs / 1000,
        ))
    }

    /** L'écran s'est allumé ou éteint. */
    @Synchronized
    fun onScreenChanged() = publishState(key(), clockMs())

    /** Un changement de la session de cuisson. */
    @Synchronized
    fun onCookEvent(e: CookEvent) {
        when (e) {
            is CookEvent.Changed -> bus.publish("session", ApiModels.session(e.session))
            is CookEvent.StepChanged -> bus.publish("step", ApiModels.stepEvent(e.session))
            is CookEvent.Ended -> {
                bus.publish("done", linkedMapOf(
                    "sessionId" to e.sessionId, "recipeId" to e.recipeId,
                    "result" to if (e.result == SessionResult.FINISHED) "finished" else "aborted",
                ))
                bus.publish("session", ApiModels.session(null))
            }
        }
        // La session change `status` et `sessionId` : l'état part tout de suite.
        publishState(key(), clockMs())
    }

    /** Le catalogue de recettes a changé. */
    @Synchronized
    fun onRecipesChanged() {
        bus.publish("recipes", linkedMapOf("rev" to backend.recipesRev, "total" to backend.recipeCount))
    }

    companion object {
        const val TEMP_DELTA_C = 1
        const val RPM_DELTA = 50
        const val WEIGHT_DELTA_G = 2
        const val WEIGHT_DELTA_QUIET_G = 5
        const val WEIGHT_QUIET_INTERVAL_MS = 10_000L
        const val MIN_ANALOG_INTERVAL_MS = 1_000L
        const val HEARTBEAT_JOB_MS = 5_000L
        const val HEARTBEAT_IDLE_MS = 30_000L
    }
}
