package app.toque.core.machine

import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.MccProtocolException
import app.toque.core.protocol.MccStreamParser
import app.toque.core.protocol.Operation
import app.toque.core.protocol.Telemetry

interface Clock {
    fun nowMs(): Long
}

object MonotonicClock : Clock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
}

enum class LinkState(private val fr: String) {
    OFFLINE(trKey("Base non connectée")),
    ARMING(trKey("Connexion…")),
    READY(trKey("Prête")),
    LOST(trKey("Connexion perdue"));

    val label: String get() = tr(fr)
}

enum class JobState { STARTING, RUNNING, STOPPING, PAUSED, FINISHED, ABORTED }

enum class PauseReason(private val fr: String) {
    USER(trKey("En pause")),
    LID(trKey("Couvercle ouvert")),
    SAFETY(trKey("Interrompu par sécurité"));

    val label: String get() = tr(fr)
}

enum class FaultCode {
    COMM_LOST, WRITE_FAILED, TRANSPORT_FAILED, BOARD_ERROR, STOP_TIMEOUT, UNEXPECTED_MOTION, UNEXPECTED_HEAT,
}

data class Fault(val code: FaultCode, val message: String, val boardCode: Int = 0)

class Verdict private constructor(val ok: Boolean, val violations: List<Violation>) {
    val message: String get() = violations.joinToString("\n") { it.message }
    fun has(code: String): Boolean = violations.any { it.code == code }

    companion object {
        val OK = Verdict(true, emptyList())
        fun refuse(v: List<Violation>) = if (v.isEmpty()) OK else Verdict(false, v)
        fun refuse(code: String, message: String) = Verdict(false, listOf(Violation(code, message)))
    }
}

enum class EventType {
    JOB_STARTED, JOB_PAUSED, JOB_RESUMED, PHASE_CHANGED, JOB_FINISHED, JOB_ABORTED,
    LID_OPENED, LID_CLOSED, FAULT, FAULT_CLEARED, LINK_CHANGED, TURBO_END,
}

data class MachineEvent(val type: EventType, val message: String)

class ControllerConfig(
    /** Âge maximal d'un échantillon pour autoriser un démarrage. */
    val freshMs: Long = 800,
    /** Au-delà, la liaison est considérée perdue et tout s'arrête. */
    val staleMs: Long = 1_500,
    /** Échantillons valides consécutifs exigés avant d'autoriser une commande. */
    val armingSamples: Int = 3,
    /** Délai maximal pour observer l'arrêt du moteur après un STOP. */
    val stopTimeoutMs: Long = 8_000,
    /** Après ce délai en STOP, un moteur ou une chauffe encore actifs déclenchent une alerte. */
    val unexpectedGraceMs: Long = 8_000,
    /** Une pause plus longue annule le programme. */
    val pauseMaxMs: Long = 10 * 60_000L,
    /** Un retard d'ordonnanceur ne fait jamais avancer le minuteur de plus que ceci. */
    val maxTickGapMs: Long = 500,
    /** Montée progressive : un niveau toutes les… */
    val rampStepMs: Long = 200,
    val hotRampStepMs: Long = 1_000,
    /** Exiger le capteur du bol (bit NTC) avant tout démarrage. */
    val requireBowl: Boolean = false,
)

/**
 * Cœur de pilotage. Un seul fil d'exécution doit l'appeler (voir [MachineLoop]).
 *
 * Corrige les défauts de l'appli d'origine relevés par l'analyse du SDK :
 *  - les préconditions (couvercle, erreurs, fraîcheur) sont vérifiées AVANT chaque démarrage ;
 *  - une liaison muette ou une écriture en échec arrêtent tout (l'original les ignorait) ;
 *  - un STOP est confirmé par un échantillon reçu APRÈS lui avant tout redémarrage ou
 *    changement de sens (l'original pouvait enchaîner STOP puis START sans trame d'arrêt) ;
 *  - les alertes sont verrouillées jusqu'à acquittement ; rien ne redémarre tout seul.
 */
class MachineController(
    private val clock: Clock,
    var config: ControllerConfig = ControllerConfig(),
) {
    var listener: ((MachineEvent) -> Unit)? = null

    private val parser = MccStreamParser()
    private val frameBuffer = ArrayList<ByteArray>(4)

    var latest: Telemetry? = null
        private set
    private var latestAtMs = Long.MIN_VALUE / 4
    private var seq = 0L

    var link = LinkState.OFFLINE
        private set
    private var armingCount = 0
    var fault: Fault? = null
        private set
    var transportName = ""
    var transportUp = false
        private set

    private var job: ActiveJob? = null
    private var turboStartedAt: Long? = null
    private var tarePending = false

    private var lastWire: Setting = Setting.STOP
    private var stopSinceMs: Long? = null
    private var rampLevel = 0
    private var rampAtMs = 0L
    private var lastLidOpen = false

    var txFrames = 0L
        private set
    var rxFrames = 0L
        private set
    var lastTxHex = ""
        private set

    private class Barrier(val afterSeq: Long, val sinceMs: Long)

    private inner class ActiveJob(var spec: JobSpec, now: Long) {
        var state = JobState.STARTING
        var phaseIndex = 0
        var phaseElapsed = 0L
        var totalElapsed = 0L
        var lastUpdate = now
        var barrier: Barrier? = Barrier(seq, now)
        var waitingStop: Barrier? = null
        var pauseReason: PauseReason? = null
        var pausedAt = 0L
        var endReason = ""
        var everStarted = false

        val phase: Phase get() = spec.phases[phaseIndex]
        val active: Boolean get() = state == JobState.STARTING || state == JobState.RUNNING || state == JobState.PAUSED
        val ended: Boolean get() = state == JobState.FINISHED || state == JobState.ABORTED
    }

    /** Un programme ou un Turbo est en cours (y compris en pause ou en arrêt). */
    val busy: Boolean
        get() {
            val j = job
            return turboStartedAt != null || (j != null && !j.ended)
        }

    // ------------------------------------------------------------------ réception

    fun onBytes(data: ByteArray, length: Int = data.size) {
        frameBuffer.clear()
        parser.feed(data, length, frameBuffer)
        for (frame in frameBuffer) onFrame(frame)
    }

    val invalidFrames: Long get() = parser.invalidFrames
    val discardedBytes: Long get() = parser.discardedBytes

    private fun onFrame(raw: ByteArray) {
        val t = try {
            Mcc.decodeRx(raw)
        } catch (e: MccProtocolException) {
            return
        }
        val now = clock.nowMs()
        seq++
        rxFrames++
        latest = t
        latestAtMs = now

        when (link) {
            LinkState.OFFLINE, LinkState.LOST -> {
                armingCount = 1
                setLink(LinkState.ARMING)
            }
            LinkState.ARMING -> {
                armingCount++
                if (armingCount >= config.armingSamples && t.motorStopped && !t.heaterOn) setLink(LinkState.READY)
            }
            LinkState.READY -> Unit
        }

        if (t.lidOpen != lastLidOpen) {
            lastLidOpen = t.lidOpen
            if (t.lidOpen) {
                emit(EventType.LID_OPENED, tr("Couvercle ouvert"))
                onLidOpened(now)
            } else {
                emit(EventType.LID_CLOSED, tr("Couvercle fermé"))
            }
        }

        if (t.errorDetected && fault?.code != FaultCode.BOARD_ERROR) {
            raiseFault(FaultCode.BOARD_ERROR, Mcc.boardErrorMessage(t.errorCode), t.errorCode)
        }

        val since = stopSinceMs
        if (since != null && now - since > config.unexpectedGraceMs) {
            if (t.motorOn || t.actualRpm > 0) {
                if (fault?.code != FaultCode.UNEXPECTED_MOTION)
                    raiseFault(FaultCode.UNEXPECTED_MOTION, tr("Le moteur tourne alors qu'il devrait être arrêté. Coupez l'interrupteur principal."))
            } else if (t.heaterOn) {
                if (fault?.code != FaultCode.UNEXPECTED_HEAT)
                    raiseFault(FaultCode.UNEXPECTED_HEAT, tr("La chauffe reste active alors qu'elle devrait être coupée. Coupez l'interrupteur principal."))
            }
        }
    }

    private fun onLidOpened(now: Long) {
        turboStartedAt = null
        val j = job ?: return
        if (j.state == JobState.RUNNING || j.state == JobState.STARTING) pauseJob(j, PauseReason.LID, now)
    }

    // ------------------------------------------------------------------ transport

    fun onTransportUp(name: String) {
        transportName = name
        transportUp = true
        parser.reset()
    }

    fun onTransportDown(reason: String) {
        val wasUp = transportUp
        transportUp = false
        parser.reset()
        if (link != LinkState.OFFLINE) setLink(LinkState.OFFLINE)
        if (wasUp && (job?.active == true || turboStartedAt != null))
            raiseFault(FaultCode.TRANSPORT_FAILED, tr("Liaison interrompue : %s", reason))
    }

    fun onWriteFailed(reason: String) {
        raiseFault(FaultCode.WRITE_FAILED, tr("Impossible d'envoyer les commandes : %s", reason))
    }

    // ------------------------------------------------------------------ émission

    /** À appeler toutes les ~100 ms : met à jour l'état et renvoie la trame à émettre. */
    fun tick(): ByteArray {
        val now = clock.nowMs()
        if ((link == LinkState.READY || link == LinkState.ARMING) && now - latestAtMs > config.staleMs) {
            setLink(LinkState.LOST)
            if (job?.active == true || turboStartedAt != null)
                raiseFault(FaultCode.COMM_LOST, tr("Plus de réponse de la base moteur : tout est arrêté"))
        }
        updateTurbo(now)
        updateJob(now)

        val wire = applyRamp(desiredSetting(), now)
        if (wire.isRest) {
            if (stopSinceMs == null) stopSinceMs = now
        } else {
            stopSinceMs = null
        }
        val frame = Mcc.encodeTx(wire.operation, wire.speed, wire.temperatureLevel, wire.direction, tarePending)
        tarePending = false
        lastWire = wire
        txFrames++
        lastTxHex = Mcc.hex(frame)
        return frame
    }

    /** Trame d'arrêt immédiate (fermeture, plantage). */
    fun stopFrame(): ByteArray = Mcc.encodeTx(Operation.STOP, 0, 0, lastWire.direction, false)

    private fun stopSetting() = Setting(Operation.STOP, 0, 0, lastWire.direction)

    private fun desiredSetting(): Setting {
        if (fault != null || link != LinkState.READY) return stopSetting()
        if (turboStartedAt != null) return Programs.turboSetting()
        val j = job ?: return stopSetting()
        if (j.state != JobState.RUNNING || j.waitingStop != null) return stopSetting()
        val s = j.phase.setting
        return if (s.isRest) Setting(Operation.STOP, 0, 0, s.direction) else s
    }

    private fun applyRamp(target: Setting, now: Long): Setting {
        if (target.isRest || target.operation == Operation.TURBO || target.speed == 0) {
            rampLevel = target.speed
            rampAtMs = now
            return target
        }
        val sameMotion = lastWire.operation == target.operation && lastWire.direction == target.direction && lastWire.speed > 0
        if (!sameMotion) rampLevel = 0
        if (target.speed <= rampLevel) {
            rampLevel = target.speed
            rampAtMs = now
            return target
        }
        val hot = (latest?.actualTemperature ?: 0) >= Limits.HOT_C
        val step = if (hot) config.hotRampStepMs else config.rampStepMs
        if (rampLevel == 0 || now - rampAtMs >= step) {
            rampLevel++
            rampAtMs = now
        }
        return target.copy(speed = rampLevel)
    }

    // ------------------------------------------------------------------ commandes

    private fun fresh(now: Long): Boolean = latest != null && now - latestAtMs <= config.freshMs

    private fun baseChecks(now: Long): List<Violation> {
        val v = ArrayList<Violation>(2)
        val t = latest
        when {
            link == LinkState.OFFLINE -> v += Violation("offline", tr("Base moteur non connectée"))
            link == LinkState.LOST -> v += Violation("lost", tr("Connexion à la base perdue"))
            link == LinkState.ARMING -> v += Violation("arming", tr("Connexion en cours, patientez"))
        }
        if (fault != null) v += Violation("fault", tr("Acquittez d'abord l'alerte : %s", fault!!.message))
        if (t == null || !fresh(now)) {
            if (v.isEmpty()) v += Violation("stale", tr("Pas de mesure récente de la base"))
            return v
        }
        if (t.lidOpen) v += Violation("lid", tr("Fermez et verrouillez le couvercle"))
        if (t.errorDetected) v += Violation("board_error", Mcc.boardErrorMessage(t.errorCode))
        if (config.requireBowl && !t.bowlNtcDetected) v += Violation("bowl", tr("Bol non détecté"))
        return v
    }

    private fun actualTemp(): Int? = latest?.actualTemperature

    fun startJob(spec: JobSpec): Verdict {
        val now = clock.nowMs()
        val j = job
        if (j != null && !j.ended) return Verdict.refuse("busy", tr("Un programme est déjà en cours"))
        if (turboStartedAt != null) return Verdict.refuse("busy", tr("Turbo en cours"))
        val v = ArrayList(baseChecks(now))
        v += SafetyPolicy.checkJob(spec, actualTemp())
        if (v.isNotEmpty()) return Verdict.refuse(v)
        job = ActiveJob(spec, now)
        return Verdict.OK
    }

    fun pause(): Verdict {
        val j = job ?: return Verdict.refuse("idle", tr("Aucun programme"))
        if (j.state != JobState.RUNNING && j.state != JobState.STARTING) return Verdict.refuse("state", tr("Rien à mettre en pause"))
        pauseJob(j, PauseReason.USER, clock.nowMs())
        return Verdict.OK
    }

    fun resume(hotBlendConfirmed: Boolean = false): Verdict {
        val now = clock.nowMs()
        val j = job ?: return Verdict.refuse("idle", tr("Aucun programme"))
        if (j.state != JobState.PAUSED) return Verdict.refuse("state", tr("Le programme n'est pas en pause"))
        if (hotBlendConfirmed && !j.spec.hotBlendConfirmed) j.spec = j.spec.copy(hotBlendConfirmed = true)
        val v = ArrayList(baseChecks(now))
        if (!j.phase.setting.isRest)
            v += SafetyPolicy.checkSetting(j.phase.setting, j.spec.kind, j.spec.accessory, actualTemp(), j.spec.hotBlendConfirmed)
        if (v.isNotEmpty()) return Verdict.refuse(v)
        j.state = JobState.STARTING
        j.barrier = Barrier(seq, now)
        j.waitingStop = null
        j.pauseReason = null
        j.lastUpdate = now
        return Verdict.OK
    }

    /** Arrêt demandé par l'utilisateur. Toujours accepté. */
    fun stop(): Verdict {
        turboStartedAt = null
        val j = job ?: return Verdict.OK
        if (!j.ended && j.state != JobState.STOPPING) {
            j.state = JobState.ABORTED
            j.endReason = tr("Arrêté")
            emit(EventType.JOB_ABORTED, tr("Arrêté"))
        } else if (j.state == JobState.STOPPING) {
            j.state = JobState.FINISHED
        }
        return Verdict.OK
    }

    /** Efface un programme terminé de l'affichage. */
    fun clearFinished() {
        val j = job ?: return
        if (j.ended) job = null
    }

    /**
     * Modifie un travail manuel en cours (vitesse, température, sens, durée totale).
     * Un changement de sens passe par un arrêt confirmé.
     */
    fun adjust(speed: Int? = null, temperatureLevel: Int? = null, direction: Direction? = null,
               totalMs: Long? = null, hotBlendConfirmed: Boolean = false): Verdict {
        val now = clock.nowMs()
        val j = job ?: return Verdict.refuse("idle", tr("Aucun programme"))
        if (j.ended || j.state == JobState.STOPPING) return Verdict.refuse("state", tr("Programme terminé"))
        if (j.spec.kind != JobKind.MANUAL || j.spec.phases.size != 1) return Verdict.refuse("kind", tr("Réglage fixe pour ce programme"))
        val old = j.phase.setting
        val s = old.copy(
            speed = speed ?: old.speed,
            temperatureLevel = temperatureLevel ?: old.temperatureLevel,
            direction = direction ?: old.direction,
        )
        val newTotal = totalMs ?: j.spec.totalMs
        val spec = j.spec.copy(
            phases = listOf(Phase(s, newTotal)),
            totalMs = newTotal,
            hotBlendConfirmed = j.spec.hotBlendConfirmed || hotBlendConfirmed,
        )
        val v = ArrayList(SafetyPolicy.checkJob(spec, actualTemp()))
        if (newTotal < j.totalElapsed + 1_000) v += Violation("past", tr("Durée déjà écoulée"))
        if (v.isNotEmpty()) return Verdict.refuse(v)
        j.spec = spec
        if (s.direction != old.direction && j.state == JobState.RUNNING) j.waitingStop = Barrier(seq, now)
        return Verdict.OK
    }

    fun turboPress(accessory: Accessory = Accessory.BLADE): Verdict {
        val now = clock.nowMs()
        val j = job
        if (j != null && !j.ended) return Verdict.refuse("busy", tr("Arrêtez d'abord le programme en cours"))
        val v = ArrayList(baseChecks(now))
        val t = latest
        if (v.isEmpty() && t != null && !t.motorStopped) v += Violation("moving", tr("Attendez l'arrêt complet du moteur"))
        v += SafetyPolicy.checkSetting(Programs.turboSetting(), JobKind.TURBO, accessory, actualTemp(), false)
        if (v.isNotEmpty()) return Verdict.refuse(v)
        turboStartedAt = now
        return Verdict.OK
    }

    fun turboRelease() {
        turboStartedAt = null
    }

    fun tare() {
        tarePending = true
    }

    fun acknowledgeFault(): Verdict {
        val f = fault ?: return Verdict.OK
        val now = clock.nowMs()
        val t = latest
        val ok = when (f.code) {
            FaultCode.BOARD_ERROR -> t != null && fresh(now) && !t.errorDetected
            FaultCode.COMM_LOST, FaultCode.TRANSPORT_FAILED, FaultCode.WRITE_FAILED -> link == LinkState.READY && transportUp
            FaultCode.UNEXPECTED_MOTION, FaultCode.STOP_TIMEOUT -> t != null && fresh(now) && t.motorStopped
            FaultCode.UNEXPECTED_HEAT -> t != null && fresh(now) && !t.heaterOn
        }
        if (!ok) return Verdict.refuse("fault_active", tr("La cause est toujours présente : %s", f.message))
        fault = null
        emit(EventType.FAULT_CLEARED, tr("Alerte acquittée"))
        return Verdict.OK
    }

    // ------------------------------------------------------------------ déroulement

    private fun updateTurbo(now: Long) {
        val since = turboStartedAt ?: return
        if (now - since >= Limits.MAX_MS_TURBO_PRESS) {
            turboStartedAt = null
            emit(EventType.TURBO_END, tr("Turbo limité à %d s par appui", Limits.MAX_MS_TURBO_PRESS / 1000))
        }
    }

    private fun barrierMet(b: Barrier, now: Long): Boolean {
        val t = latest ?: return false
        return seq > b.afterSeq && fresh(now) && t.motorStopped
    }

    private fun updateJob(now: Long) {
        val j = job ?: return
        val dt = (now - j.lastUpdate).coerceIn(0, config.maxTickGapMs)
        j.lastUpdate = now
        when (j.state) {
            JobState.STARTING -> {
                val b = j.barrier ?: Barrier(seq, now).also { j.barrier = it }
                if (barrierMet(b, now)) {
                    j.barrier = null
                    if (!enterPhase(j, now)) return
                    j.state = JobState.RUNNING
                    if (j.everStarted) emit(EventType.JOB_RESUMED, j.spec.label)
                    else emit(EventType.JOB_STARTED, j.spec.label)
                    j.everStarted = true
                } else if (now - b.sinceMs > config.stopTimeoutMs) {
                    raiseFault(FaultCode.STOP_TIMEOUT, tr("Le moteur ne s'arrête pas : démarrage annulé"))
                }
            }
            JobState.RUNNING -> {
                j.totalElapsed += dt
                val ws = j.waitingStop
                if (ws != null) {
                    if (barrierMet(ws, now)) {
                        j.waitingStop = null
                        if (!enterPhase(j, now)) return
                    } else if (now - ws.sinceMs > config.stopTimeoutMs) {
                        raiseFault(FaultCode.STOP_TIMEOUT, tr("Le moteur ne s'arrête pas entre deux phases"))
                        return
                    }
                } else {
                    j.phaseElapsed += dt
                }
                if (j.totalElapsed >= j.spec.totalMs) {
                    j.state = JobState.STOPPING
                    j.barrier = Barrier(seq, now)
                    return
                }
                if (ws == null && j.phaseElapsed >= j.phase.durationMs) advancePhase(j, now)
            }
            JobState.STOPPING -> {
                val b = j.barrier ?: Barrier(seq, now).also { j.barrier = it }
                if (barrierMet(b, now)) {
                    j.state = JobState.FINISHED
                    j.endReason = tr("Terminé")
                    emit(EventType.JOB_FINISHED, j.spec.label)
                } else if (now - b.sinceMs > config.stopTimeoutMs) {
                    j.state = JobState.FINISHED
                    j.endReason = tr("Terminé")
                    raiseFault(FaultCode.STOP_TIMEOUT, tr("Le moteur ne s'est pas arrêté à la fin du programme"))
                }
            }
            JobState.PAUSED -> {
                if (now - j.pausedAt > config.pauseMaxMs) {
                    j.state = JobState.ABORTED
                    j.endReason = tr("Pause trop longue : programme annulé")
                    emit(EventType.JOB_ABORTED, j.endReason)
                }
            }
            JobState.FINISHED, JobState.ABORTED -> Unit
        }
    }

    /** Vérifie qu'on peut (re)lancer la phase courante ; sinon met en pause. */
    private fun enterPhase(j: ActiveJob, now: Long): Boolean {
        val s = j.phase.setting
        if (s.isRest) return true
        val v = ArrayList(baseChecks(now))
        v += SafetyPolicy.checkSetting(s, j.spec.kind, j.spec.accessory, actualTemp(), j.spec.hotBlendConfirmed)
        if (v.isEmpty()) return true
        val reason = if (latest?.lidOpen == true) PauseReason.LID else PauseReason.SAFETY
        pauseJob(j, reason, now, v.first().message)
        return false
    }

    private fun advancePhase(j: ActiveJob, now: Long) {
        val phases = j.spec.phases
        var next = j.phaseIndex + 1
        if (next >= phases.size) {
            if (!j.spec.cyclic) {
                j.state = JobState.STOPPING
                j.barrier = Barrier(seq, now)
                return
            }
            next = 0
        }
        val cur = j.phase.setting
        val nxt = phases[next].setting
        j.phaseIndex = next
        j.phaseElapsed = 0
        val directionChange = !cur.isRest && !nxt.isRest && cur.direction != nxt.direction && nxt.speed > 0
        when {
            nxt.isRest && !cur.isRest -> j.waitingStop = Barrier(seq, now)
            directionChange -> j.waitingStop = Barrier(seq, now)
            cur.isRest && !nxt.isRest -> if (!enterPhase(j, now)) return
        }
        emit(EventType.PHASE_CHANGED, j.spec.label)
    }

    private fun pauseJob(j: ActiveJob, reason: PauseReason, now: Long, detail: String? = null) {
        j.state = JobState.PAUSED
        j.pauseReason = reason
        j.pausedAt = now
        j.waitingStop = null
        j.barrier = null
        emit(EventType.JOB_PAUSED, detail ?: reason.label)
    }

    private fun raiseFault(code: FaultCode, message: String, boardCode: Int = 0) {
        fault = Fault(code, message, boardCode)
        turboStartedAt = null
        val j = job
        if (j != null && !j.ended) {
            j.state = JobState.ABORTED
            j.endReason = message
            emit(EventType.JOB_ABORTED, message)
        }
        emit(EventType.FAULT, message)
    }

    private fun setLink(state: LinkState) {
        if (link == state) return
        link = state
        if (state != LinkState.ARMING) armingCount = 0
        emit(EventType.LINK_CHANGED, state.label)
    }

    private fun emit(type: EventType, message: String) {
        val l = listener ?: return
        try {
            l(MachineEvent(type, message))
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ état

    fun snapshot(): Snapshot {
        val now = clock.nowMs()
        val t = latest
        val j = job
        val remaining = if (j == null) 0L else (j.spec.totalMs - j.totalElapsed).coerceAtLeast(0)
        val s = j?.phase?.setting
        return Snapshot(
            link = link,
            transportName = transportName,
            transportUp = transportUp,
            faultCode = fault?.code?.name ?: "",
            faultMessage = fault?.message ?: "",
            hasSample = t != null,
            sampleAgeMs = if (t == null) -1 else now - latestAtMs,
            actualTempC = t?.actualTemperature ?: 0,
            targetTempC = t?.targetTemperature ?: 0,
            rpm = t?.actualRpm ?: 0,
            speedLevel = t?.actualSpeedLevel ?: 0,
            weightG = t?.weight ?: 0,
            scaleStable = t?.scaleStable ?: false,
            scaleState = t?.androidScaleState ?: "",
            lidOpen = t?.lidOpen ?: false,
            bowlDetected = t?.bowlNtcDetected ?: false,
            motorOn = t?.motorOn ?: false,
            heaterOn = t?.heaterOn ?: false,
            boardErrorCode = if (t?.errorDetected == true) t.errorCode else 0,
            firmware = t?.firmwareVersion ?: 0,
            rxHex = t?.hex ?: "",
            jobActive = j != null,
            jobKind = j?.spec?.kind?.name ?: "",
            jobLabel = j?.spec?.label ?: "",
            jobState = j?.state?.name ?: "",
            pauseReason = j?.pauseReason?.name ?: "",
            endReason = j?.endReason ?: "",
            phaseIndex = j?.phaseIndex ?: 0,
            phaseCount = j?.spec?.phases?.size ?: 0,
            elapsedMs = j?.totalElapsed ?: 0,
            totalMs = j?.spec?.totalMs ?: 0,
            remainingMs = remaining,
            waitingStop = j != null && (j.waitingStop != null || j.state == JobState.STARTING || j.state == JobState.STOPPING),
            jobSpeed = s?.speed ?: 0,
            jobTempLevel = s?.temperatureLevel ?: 0,
            jobReverse = s?.direction == Direction.COUNTER_CLOCKWISE,
            accessory = j?.spec?.accessory?.name ?: Accessory.BLADE.name,
            hotBlendConfirmed = j?.spec?.hotBlendConfirmed ?: false,
            turboActive = turboStartedAt != null,
            wireOperation = lastWire.operation.name,
            wireSpeed = lastWire.speed,
            wireTempLevel = lastWire.temperatureLevel,
            txHex = lastTxHex,
            txFrames = txFrames,
            rxFrames = rxFrames,
            invalidFrames = parser.invalidFrames,
        )
    }
}
