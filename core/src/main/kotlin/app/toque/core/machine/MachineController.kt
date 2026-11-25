package app.toque.core.machine

import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey
import app.toque.core.protocol.BoardCodec
import app.toque.core.protocol.ConnectCodec
import app.toque.core.protocol.Direction
import app.toque.core.protocol.FrameParser
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.MccProtocolException
import app.toque.core.protocol.Operation
import app.toque.core.protocol.SmartCodec
import app.toque.core.protocol.Telemetry
import kotlin.math.abs

interface Clock {
    fun nowMs(): Long
}

object MonotonicClock : Clock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
}

enum class LinkState(private val fr: String) {
    OFFLINE(trKey("Base non connectée")),
    ARMING(trKey("Connexion…")),
    READY(trKey("Base prête")),
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
    /** Balance remise à zéro d'elle-même (bol vide replacé). */
    SCALE_ZEROED,
    /** Information sur la balance à montrer (bol replacé chargé, conseil). */
    SCALE_INFO,
    /** Poids du bol vide appris : [MachineEvent.value] en grammes, à mémoriser par l'interface. */
    BOWL_LEARNED,
}

data class MachineEvent(val type: EventType, val message: String, val value: Int = 0)

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
    /** Montée progressive de la vitesse : un niveau toutes les… (0 = vitesse demandée directement). */
    val rampStepMs: Long = 0,
    /** … et quand le contenu est chaud (≥ 60 °C), pour limiter les projections. */
    val hotRampStepMs: Long = 250,
    /** Montée progressive à chaud (désactivable dans les réglages). */
    val hotRamp: Boolean = true,
    /** Exiger le capteur du bol (bit NTC) avant tout démarrage. */
    val requireBowl: Boolean = false,
    /** Certaines bases signalent le bol à l'envers (bit NTC à 1 quand le bol est retiré) : inverser la lecture. */
    val invertBowl: Boolean = false,
    /**
     * Remettre la balance à zéro quand le bol est replacé VIDE sur le socle. « Vide » = la masse
     * posée sur le socle correspond, à [BowlScale.TOLERANCE_G] près, à [emptyBowlG] ; un bol replacé
     * plus lourd n'est jamais taré (son contenu resterait sinon invisible).
     */
    val bowlAutoZero: Boolean = false,
    /** Masse du bol vide (bol, lame, couvercle…) apprise sur ce robot, en g ; 0 = pas encore apprise. */
    val emptyBowlG: Int = 0,
    /** Valeur du bit « bol » quand le bol est en place, si elle a déjà été constatée sur ce robot (voir bowlBitSeated). */
    val bowlBitSeated: Boolean? = null,
    /** Couvercle fermé, le bit « bol » doit garder sa valeur ce temps-là pour être retenu comme « bol en place ». */
    val bowlSenseMs: Long = 1_000,
)

/** Réglages de la remise à zéro au retour du bol. */
object BowlScale {
    /** Écart toléré entre la masse posée et le bol vide mémorisé. */
    const val TOLERANCE_G = 40
    /** Le capteur du bol doit garder son nouvel état aussi longtemps pour compter. */
    const val DEBOUNCE_MS = 500L
    /** Poids stable depuis au moins… avant toute lecture de référence. */
    const val SETTLE_MS = 800L
    /** Après le retour du bol, le poids est évalué pendant… (le temps de poser le couvercle). */
    const val RETURN_WINDOW_MS = 30_000L
    /** Un appui sur Tare dans ce délai après le retour du bol apprend le bol vide. */
    const val LEARN_WINDOW_MS = 5 * 60_000L
    /** Masses plausibles pour un bol vide. */
    const val MIN_EMPTY_G = 500
    const val MAX_EMPTY_G = 4_000
}

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

    /** Format des trames de la carte : fourni par la liaison à son ouverture ([onTransportUp]). */
    @Volatile var codec: BoardCodec = ConnectCodec
        private set
    private var parser: FrameParser = codec.newRxParser()
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

    /**
     * Smart : l'utilisateur a confirmé que le contenu est froid (la carte ne mesure la température
     * qu'après une chauffe). La confirmation est liée à une « époque » : toute ouverture du
     * couvercle, chauffe envoyée, perte ou changement de liaison passe à l'époque suivante et
     * l'annule. Elle expire aussi après [Limits.COLD_CONFIRM_MS].
     */
    private var coldEpoch = 0
    /** Époque du dernier refus « contenu froid ? » (la fenêtre de confirmation affichée). */
    private var coldAskedEpoch = -1
    private var coldConfirmedEpoch = -1
    private var coldConfirmedAtMs = 0L

    private fun invalidateCold() {
        coldEpoch++
    }

    private fun coldConfirmed(now: Long): Boolean =
        coldConfirmedEpoch == coldEpoch && now - coldConfirmedAtMs in 0..Limits.COLD_CONFIRM_MS
    private var tarePending = false
    var standby = false
        private set

    private val bowl = BowlScaleTracker(
        config = { config },
        isBusyOrFaulted = { busy || fault != null },
        requestTare = { requestTare() },
        emit = { type, msg, value -> emit(type, msg, value) },
    )

    /** Bol mémorisé, appris pendant cette session ou lu dans la configuration. */
    val emptyBowlG: Int get() = bowl.emptyBowlG

    /** Fin du dernier pétrissage (Malaxer) : l'appli d'origine fait attendre 30 min avant le suivant. */
    private var kneadEndedAtMs: Long? = null

    @Volatile private var lastWire: Setting = Setting.STOP
    private var stopSinceMs: Long? = null
    private var rampLevel = 0
    private var rampAtMs = 0L
    private var lastLidOpen = false
    /** Dernier échantillon où la carte indiquait un moteur en marche. */
    private var lastMotionAtMs = Long.MIN_VALUE / 4
    /** La base a déjà mesuré un régime lame non nul : sinon, le bit moteur est le seul signe d'arrêt. */
    private var rpmMeasured = false

    var txFrames = 0L
        private set
    var rxFrames = 0L
        private set
    private var lastTxBytes: ByteArray? = null
    private var lastTxHexCached = ""
    val lastTxHex: String
        get() {
            val b = lastTxBytes ?: return lastTxHexCached
            lastTxHexCached = Mcc.hex(b)
            lastTxBytes = null
            return lastTxHexCached
        }

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
        /** Minuterie envoyée pendant la phase courante (octets 25-27 du Smart), hors [PhaseTimer.Total]. */
        var timerS = 0
        /** Numéro du dernier échantillon reçu au début de la phase courante. */
        var phaseSeq = 0L
        /** Fin de travail déjà prise en compte (attente entre deux pétrissages). */
        var endNoted = false
        /** Sens de la dernière phase où la lame tournait (changement de sens à travers un repos). */
        var lastMovingDir: Direction? = null

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
            codec.decodeRx(raw)
        } catch (e: MccProtocolException) {
            return
        }
        val now = clock.nowMs()
        if (t.motorOn || t.actualRpm > 0) lastMotionAtMs = now
        if (t.actualRpm > 0) rpmMeasured = true
        seq++
        rxFrames++
        latest = t
        latestAtMs = now
        bowl.onSample(t, now)

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
                invalidateCold()
                emit(EventType.LID_OPENED, tr("Couvercle ouvert"))
                onLidOpened(now)
            } else {
                emit(EventType.LID_CLOSED, tr("Couvercle fermé"))
            }
        }

        if (t.errorDetected && fault?.code != FaultCode.BOARD_ERROR) {
            raiseFault(FaultCode.BOARD_ERROR, codec.errorMessage(t.errorCode), t.errorCode)
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

    fun onTransportUp(name: String, codec: BoardCodec = ConnectCodec) {
        transportName = name
        transportUp = true
        if (codec !== this.codec) {
            this.codec = codec
            parser = codec.newRxParser()
        }
        parser.reset()
    }

    fun onTransportDown(reason: String) {
        val wasUp = transportUp
        transportUp = false
        invalidateCold()
        parser.reset()
        if (link != LinkState.OFFLINE) setLink(LinkState.OFFLINE)
        if (wasUp && (job?.active == true || turboStartedAt != null))
            raiseFault(FaultCode.TRANSPORT_FAILED, tr("Liaison interrompue : %s", reason))
    }

    fun onWriteFailed(reason: String) {
        raiseFault(FaultCode.WRITE_FAILED, tr("Impossible d'envoyer les commandes : %s", reason))
    }

    // ------------------------------------------------------------------ émission

    /** À appeler à chaque trame (100 ms Connect, 200 ms Smart) : met à jour l'état et renvoie la trame à émettre. */
    fun tick(): ByteArray {
        val now = clock.nowMs()
        if ((link == LinkState.READY || link == LinkState.ARMING) && now - latestAtMs > config.staleMs) {
            if (!standby || job?.active == true || turboStartedAt != null) {
                setLink(LinkState.LOST)
                if (job?.active == true || turboStartedAt != null)
                    raiseFault(FaultCode.COMM_LOST, tr("Plus de réponse de la base moteur : tout est arrêté"))
            }
        }
        updateTurbo(now)
        updateJob(now)

        val wire = applyRamp(desiredSetting(), now)
        if (wire.isRest) {
            if (stopSinceMs == null) stopSinceMs = now
        } else {
            stopSinceMs = null
        }
        // Smart : la tare part seulement entre deux trames STOP (jamais en premier arrêt après un
        // réglage actif) ; demandée pendant un programme, elle est abandonnée.
        val tare = tarePending && (!codec.tareOnlyAtRest || (wire.isRest && lastWire.isRest))
        if (tare || !wire.isRest || !codec.tareOnlyAtRest) tarePending = false
        if (tare) {
            // Le zéro de la base va passer au poids actuel : la référence socle vide suit.
            bowl.onTareSent(latest?.weight)
        }
        val j = job
        val durationS = if (wire.isRest || j == null) 0
            else if (j.phase.timer == PhaseTimer.Total) ((j.spec.totalMs + 999) / 1000).toInt() else j.timerS
        val frame = try {
            codec.encodeTx(wire.operation, wire.speed, wire.temperatureLevel, wire.direction, tare, durationS, wire.program, wire.smartLevel, wire.programCelsius)
                .also { lastWire = wire; if (wire.heated) invalidateCold() }
        } catch (e: IllegalArgumentException) {
            // Réglage refusé par le codec (ne devrait pas arriver : vérifié au démarrage) : on arrête.
            raiseFault(FaultCode.WRITE_FAILED, tr("Réglage refusé par le protocole : %s", e.message ?: ""))
            lastWire = stopSetting()
            codec.stopFrame(lastWire.direction)
        }
        txFrames++
        lastTxBytes = frame
        return frame
    }

    /** Trame d'arrêt immédiate (fermeture, plantage). */
    fun stopFrame(): ByteArray = codec.stopFrame(lastWire.direction)

    private fun stopSetting() = Setting(Operation.STOP, 0, 0, lastWire.direction)

    private fun desiredSetting(): Setting {
        if (fault != null || link != LinkState.READY) return stopSetting()
        if (turboStartedAt != null) return Programs.turboSetting()
        if (standby && codec.supports(Operation.SLEEP) && job?.active != true && turboStartedAt == null)
            return Setting(Operation.SLEEP, 0, 0, lastWire.direction)
        val j = job ?: return stopSetting()
        if (j.state != JobState.RUNNING || j.waitingStop != null) return stopSetting()
        val s = j.phase.setting
        return if (s.isRest) Setting(Operation.STOP, 0, 0, s.direction) else s
    }

    private fun applyRamp(target: Setting, now: Long): Setting {
        // Pas de montée progressive pour le Turbo, ni quand la carte refuse les vitesses
        // intermédiaires (programmes gérés par la carte du Smart : pétrin à vitesse 4 fixe).
        if (target.isRest || target.operation == Operation.TURBO || target.speed == 0 ||
            !codec.accepts(target.operation, 1, target.temperatureLevel, target.direction, target.program, target.smartLevel, target.programCelsius)
        ) {
            rampLevel = target.speed
            rampAtMs = now
            return target
        }
        val hot = (actualTemp() ?: 0) >= Limits.HOT_C
        val step = if (hot) (if (config.hotRamp) config.hotRampStepMs else 0L) else config.rampStepMs
        if (step <= 0) {
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
        if (rampLevel == 0 || now - rampAtMs >= step) {
            rampLevel++
            rampAtMs = now
        }
        return target.copy(speed = rampLevel)
    }

    // ------------------------------------------------------------------ commandes

    private fun bowlPresent(t: Telemetry): Boolean = bowl.bowlPresent(t)

    private fun bowlRemoved(t: Telemetry): Boolean = bowl.bowlRemoved(t)

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
        if (t.lidOpen) v += Violation("lid", if (bowlRemoved(t)) tr("Remettez le bol et fermez le couvercle") else tr("Fermez et verrouillez le couvercle"))
        if (t.errorDetected) v += Violation("board_error", codec.errorMessage(t.errorCode))
        if (config.requireBowl && !bowlPresent(t)) v += Violation("bowl", tr("Bol non détecté"))
        return v
    }

    /** Température à prendre en compte pour la sécurité (pessimiste si la carte ne la mesure pas toujours). */
    private fun actualTemp(): Int? {
        val t = latest?.actualTemperature ?: return null
        return if (codec.temperatureReliable) t else maxOf(t, Limits.HOT_C)
    }

    fun startJob(requested: JobSpec): Verdict {
        val now = clock.nowMs()
        val j = job
        if (j != null && !j.ended) return Verdict.refuse("busy", tr("Un programme est déjà en cours"))
        if (turboStartedAt != null) return Verdict.refuse("busy", tr("Turbo en cours"))
        // Smart : pétrin, vapeur et rissolage tels que l'appli d'origine les envoie.
        val spec = if (codec.rules is SmartCodec) Programs.forSmart(requested) else requested
        val v = ArrayList(baseChecks(now))
        v += SafetyPolicy.checkJob(spec, actualTemp())
        if (spec.phases.any { p -> p.setting.let { !it.isRest && !codec.accepts(it.operation, it.speed, it.temperatureLevel, it.direction, it.program, it.smartLevel, it.programCelsius) } })
            v += Violation("unsupported", tr("Programme pas encore pris en charge sur ce robot"))
        // Comme l'appli d'origine : 30 min entre deux pétrissages, pour laisser refroidir le moteur.
        val kneadEnd = kneadEndedAtMs
        if (spec.cooldown && codec.rules is SmartCodec && kneadEnd != null && now - kneadEnd < Limits.KNEAD_COOLDOWN_MS) {
            val min = ((Limits.KNEAD_COOLDOWN_MS - (now - kneadEnd) + 59_999) / 60_000).toInt()
            v += Violation(KNEAD_COOLDOWN, tr("Attendez encore %d min avant le prochain pétrissage, pour que l'appareil puisse refroidir.", min))
        }
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
        noteEnd(j)
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
               totalMs: Long? = null, hotBlendConfirmed: Boolean = false,
               /** Smart, avec [temperatureLevel] : niveau de sa table (40 °C) ; null = celui de Toque. */
               smartLevel: Int? = null): Verdict {
        val now = clock.nowMs()
        val j = job ?: return Verdict.refuse("idle", tr("Aucun programme"))
        if (j.ended || j.state == JobState.STOPPING) return Verdict.refuse("state", tr("Programme terminé"))
        if (j.spec.kind != JobKind.MANUAL || j.spec.phases.size != 1) return Verdict.refuse("kind", tr("Réglage fixe pour ce programme"))
        val old = j.phase.setting
        val newSpeed = speed ?: old.speed
        val newLevel = temperatureLevel ?: old.temperatureLevel
        val s = old.copy(
            operation = Programs.manualOperation(newSpeed, newLevel),
            speed = newSpeed,
            temperatureLevel = newLevel,
            direction = Programs.manualDirection(newSpeed, direction ?: old.direction),
            smartLevel = if (temperatureLevel != null) smartLevel else old.smartLevel,
        )
        val newTotal = totalMs ?: j.spec.totalMs
        val spec = j.spec.copy(
            phases = listOf(Phase(s, newTotal)),
            totalMs = newTotal,
            hotBlendConfirmed = j.spec.hotBlendConfirmed || hotBlendConfirmed,
        )
        val v = ArrayList(SafetyPolicy.checkJob(spec, actualTemp()))
        if (!codec.accepts(s.operation, s.speed, s.temperatureLevel, s.direction, s.program, s.smartLevel, s.programCelsius))
            v += Violation("unsupported", tr("Programme pas encore pris en charge sur ce robot"))
        if (newTotal < j.totalElapsed + 1_000) v += Violation("past", tr("Durée déjà écoulée"))
        if (v.isNotEmpty()) return Verdict.refuse(v)
        j.spec = spec
        // Changement de sens, ou passage entre chauffe avec et sans mélange : par un arrêt confirmé.
        if ((s.direction != old.direction || s.operation != old.operation) && j.state == JobState.RUNNING) j.waitingStop = Barrier(seq, now)
        return Verdict.OK
    }

    fun turboPress(accessory: Accessory = Accessory.BLADE): Verdict {
        val now = clock.nowMs()
        val j = job
        if (j != null && !j.ended) return Verdict.refuse("busy", tr("Arrêtez d'abord le programme en cours"))
        val v = ArrayList(baseChecks(now))
        val t = latest
        // Base sans mesure du régime (Smart, SKMC 1100 A1) : on attend aussi stopSettleMs() après le dernier écho
        // « moteur en marche » (ex. arrêt d'un programme en sens inverse puis Turbo).
        if (v.isEmpty() && ((t != null && !t.motorStopped) || now - lastMotionAtMs < stopSettleMs() || !lastWire.isRest))
            v += Violation("moving", tr("Attendez l'arrêt complet du moteur"))
        // Température non mesurée (Smart) : Turbo seulement après confirmation « contenu froid » ;
        // une température réellement mesurée au-dessus de 60 °C l'interdit dans tous les cas.
        val turboTemp = if (codec.temperatureReliable) actualTemp() else latest?.actualTemperature
        v += SafetyPolicy.checkSetting(Programs.turboSetting(), JobKind.TURBO, accessory, turboTemp, false)
        if (!codec.temperatureReliable && !coldConfirmed(now) && v.none { it.code == "turbo_hot" }) {
            v += Violation(SafetyPolicy.COLD_CONFIRM,
                tr("Température du contenu inconnue : confirmez qu'il est froid (moins de %d °C)", Limits.HOT_C))
            coldAskedEpoch = coldEpoch
        }
        if (!codec.supports(Operation.TURBO)) v += Violation("unsupported", tr("Programme pas encore pris en charge sur ce robot"))
        if (v.isNotEmpty()) return Verdict.refuse(v)
        turboStartedAt = now
        return Verdict.OK
    }

    /**
     * Smart : l'utilisateur confirme que le contenu est froid, en réponse à un refus
     * [SafetyPolicy.COLD_CONFIRM]. Refusée si le couvercle a été ouvert ou la liaison a changé
     * depuis ce refus, si la liaison n'est pas prête ou si le couvercle est ouvert.
     */
    fun confirmCold(): Verdict {
        val now = clock.nowMs()
        val t = latest
        if (link != LinkState.READY || !fresh(now) || t == null) return Verdict.refuse("link", tr("Base non connectée"))
        if (t.lidOpen) return Verdict.refuse("lid", tr("Fermez et verrouillez le couvercle"))
        if (t.actualTemperature >= Limits.HOT_C)
            return Verdict.refuse("turbo_hot", tr("Turbo interdit : contenu à %d °C (≥ %d °C)", t.actualTemperature, Limits.HOT_C))
        if (coldAskedEpoch != coldEpoch)
            return Verdict.refuse(SafetyPolicy.COLD_CONFIRM, tr("Le couvercle a été ouvert entre-temps : appuyez de nouveau sur Turbo"))
        coldConfirmedEpoch = coldEpoch
        coldConfirmedAtMs = now
        return Verdict.OK
    }

    fun turboRelease() {
        turboStartedAt = null
    }

    /**
     * Tare demandée par la personne. [learn] : si elle survient peu après le retour du bol, la masse
     * posée devient le bol vide de référence (désactivé pour les tares automatiques des recettes).
     */
    fun tare(learn: Boolean = true) {
        if (learn) bowl.onManualTare(latest, clock.nowMs(), busy)
        requestTare()
    }

    /** Oublie le bol vide appris (réglages). */
    fun forgetEmptyBowl() {
        bowl.forgetEmptyBowl()
    }

    private fun requestTare() {
        tarePending = true
        bowl.onTareRequested()
    }

    fun setStandby(enabled: Boolean) {
        if (enabled && (!codec.supports(Operation.SLEEP) || job?.active == true || turboStartedAt != null)) {
            return
        }
        standby = enabled
        if (!enabled) {
            latestAtMs = clock.nowMs()
        }
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
        return seq > b.afterSeq && fresh(now) && t.motorStopped && now - lastMotionAtMs >= stopSettleMs()
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
                    startPhaseTimer(j)
                    j.state = JobState.RUNNING
                    if (j.everStarted) emit(EventType.JOB_RESUMED, j.spec.label)
                    else emit(EventType.JOB_STARTED, j.spec.label)
                    j.everStarted = true
                } else if (now - b.sinceMs > config.stopTimeoutMs) {
                    raiseFault(FaultCode.STOP_TIMEOUT, tr("Le moteur ne s'arrête pas : démarrage annulé"))
                }
            }
            JobState.RUNNING -> {
                val ws = j.waitingStop
                // Programme à étapes de l'appli d'origine : l'arrêt confirmé avant un changement de
                // sens (ajouté par Toque), ou un repos prolongé jusqu'à l'arrêt de la lame, ne
                // raccourcit pas la dernière étape.
                val prolonge = j.spec.capturedSteps && ws == null && j.phaseElapsed >= j.phase.durationMs
                if (prolonge && j.phaseElapsed - j.phase.durationMs > config.stopTimeoutMs) {
                    raiseFault(FaultCode.STOP_TIMEOUT, tr("Le moteur ne s'arrête pas entre deux phases"))
                    return
                }
                if ((ws == null && !prolonge) || !j.spec.capturedSteps) j.totalElapsed += dt
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
                if (ws == null && (j.phaseElapsed >= j.phase.durationMs || temperatureReached(j, now))) advancePhase(j, now)
            }
            JobState.STOPPING -> {
                val b = j.barrier ?: Barrier(seq, now).also { j.barrier = it }
                if (barrierMet(b, now)) {
                    j.state = JobState.FINISHED
                    j.endReason = tr("Terminé")
                    noteEnd(j)
                    emit(EventType.JOB_FINISHED, j.spec.label)
                } else if (now - b.sinceMs > config.stopTimeoutMs) {
                    j.state = JobState.FINISHED
                    j.endReason = tr("Terminé")
                    noteEnd(j)
                    raiseFault(FaultCode.STOP_TIMEOUT, tr("Le moteur ne s'est pas arrêté à la fin du programme"))
                }
            }
            JobState.PAUSED -> {
                if (now - j.pausedAt > config.pauseMaxMs) {
                    j.state = JobState.ABORTED
                    noteEnd(j)
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

    /** Minuterie de la phase qui commence (temps restant ou valeur fixe), et premier échantillon attendu. */
    private fun startPhaseTimer(j: ActiveJob) {
        j.phaseSeq = seq
        j.phase.setting.let { if (!it.isRest && it.speed > 0) j.lastMovingDir = it.direction }
        j.timerS = when (val t = j.phase.timer) {
            PhaseTimer.Total -> ((j.spec.totalMs + 999) / 1000).toInt()
            PhaseTimer.Remaining -> (((j.spec.totalMs - j.totalElapsed).coerceAtLeast(0) + 999) / 1000).toInt()
            is PhaseTimer.Fixed -> t.seconds
        }
    }

    /**
     * Préchauffe du nettoyage long : la carte a levé le bit 5 (« température atteinte ») dans une
     * réponse à la trame de cette phase (même niveau de température), reçue après son début.
     */
    private fun temperatureReached(j: ActiveJob, now: Long): Boolean {
        val p = j.phase
        if (!p.untilTemperature) return false
        val t = latest ?: return false
        val level = p.setting.program?.level ?: return false
        return seq > j.phaseSeq && fresh(now) && t.aboveTargetTemperature && t.targetTemperatureLevel == level
    }

    /**
     * Délai minimal après le dernier écho « moteur en marche ». Une base qui ne mesure pas le régime
     * (Smart, et certaines bases du Connect : SKMC 1100 A1 / carte spm188a, issue #10) peut faire
     * tomber le bit moteur alors que la lame ralentit encore : on attend alors au moins
     * [NO_RPM_STOP_SETTLE_MS] avant un redémarrage, un changement de sens ou un Turbo.
     */
    private fun stopSettleMs(): Long =
        if (rpmMeasured) codec.stopSettleMs else maxOf(codec.stopSettleMs, NO_RPM_STOP_SETTLE_MS)

    /** Lame arrêtée depuis au moins [stopSettleMs], d'après un échantillon récent. */
    private fun settled(now: Long): Boolean {
        val t = latest ?: return false
        return fresh(now) && t.motorStopped && now - lastMotionAtMs >= stopSettleMs()
    }

    /** Fin d'un travail : un pétrissage de plus de 30 s fait attendre 30 min avant le suivant. */
    private fun noteEnd(j: ActiveJob) {
        if (j.endNoted) return
        j.endNoted = true
        if (j.spec.cooldown && j.totalElapsed >= KNEAD_COUNTS_MS) kneadEndedAtMs = clock.nowMs()
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
        // Après un repos de l'appli d'origine, on compare au sens où la lame tournait avant ce repos
        // (le repos peut déjà avoir l'autre sens : Malaxer pâte ferme).
        val restInProgram = cur.program != null && cur.speed == 0
        val ref = if (restInProgram) j.lastMovingDir ?: cur.direction else cur.direction
        var directionChange = !cur.isRest && !nxt.isRest && ref != nxt.direction && nxt.speed > 0
        if (directionChange && restInProgram) {
            // Repos de l'appli d'origine (vitesse 0, fonction gardée) : comme elle, on repart dans
            // l'autre sens sans STOP, mais seulement une fois la lame arrêtée ; sinon le repos dure
            // un peu plus (nouvel essai au prochain tick).
            if (!settled(now)) return
            directionChange = false
        }
        j.phaseIndex = next
        j.phaseElapsed = 0
        val operationChange = !cur.isRest && !nxt.isRest && cur.operation != nxt.operation
        startPhaseTimer(j)
        when {
            nxt.isRest && !cur.isRest -> j.waitingStop = Barrier(seq, now)
            directionChange || operationChange -> j.waitingStop = Barrier(seq, now)
            // Même sens : on passe directement au réglage suivant, après l'avoir revérifié
            // (le contenu a pu chauffer pendant la phase précédente).
            !nxt.isRest -> if (!enterPhase(j, now)) return
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
            noteEnd(j)
            j.endReason = message
            emit(EventType.JOB_ABORTED, message)
        }
        emit(EventType.FAULT, message)
    }

    private fun setLink(state: LinkState) {
        if (link == state) return
        link = state
        invalidateCold()
        if (state != LinkState.ARMING) armingCount = 0
        if (state != LinkState.READY) bowl.clearStability()
        if (state == LinkState.OFFLINE || state == LinkState.LOST) {
            // La base peut refaire son zéro en revenant : les références de la balance ne valent plus.
            bowl.onLinkDown()
        }
        emit(EventType.LINK_CHANGED, state.label)
    }

    private fun emit(type: EventType, message: String, value: Int = 0) {
        val l = listener ?: return
        try {
            l(MachineEvent(type, message, value))
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ état

    companion object {
        /** Refus « attendez 30 min avant le prochain pétrissage ». */
        const val KNEAD_COOLDOWN = "knead_cooldown"
        /** Un pétrissage compte pour l'attente de 30 min s'il a duré au moins ceci. */
        const val KNEAD_COUNTS_MS = 30_000L
        /** Attente après arrêt quand la base n'a jamais mesuré de régime lame (comme le Smart). */
        const val NO_RPM_STOP_SETTLE_MS = 2_000L
    }

    fun snapshot(): Snapshot {
        val now = clock.nowMs()
        val t = latest
        val j = job
        val remaining = if (j == null) 0L else {
            val r = (j.spec.totalMs - j.totalElapsed).coerceAtLeast(0)
            // Programme à étapes non cyclique : il finit avec sa dernière étape (préchauffe du
            // nettoyage long finie tôt, par exemple).
            if (j.spec.capturedSteps && !j.spec.cyclic) minOf(r,
                (j.phase.durationMs - j.phaseElapsed).coerceAtLeast(0) +
                    j.spec.phases.drop(j.phaseIndex + 1).sumOf { minOf(it.durationMs, j.spec.totalMs) })
            else r
        }
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
            speedLevel = t?.shownSpeedLevel ?: 0,
            weightG = t?.weight ?: 0,
            scaleStable = bowl.scaleStableComputed,
            scaleState = t?.androidScaleState ?: "",
            lidOpen = t?.lidOpen ?: false,
            bowlDetected = t?.let { bowlPresent(it) } ?: false,
            bowlRemoved = t?.let { bowlRemoved(it) } ?: false,
            bowlSenseKnown = bowl.bowlBitSeated != null,
            bowlSenseInverted = bowl.bowlBitSeated == false,
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
            jobTempC = if (s == null || !s.heated) 0 else s.temperatureC,
            jobProgram = s?.program?.name ?: "",
            jobReverse = s?.direction == Direction.COUNTER_CLOCKWISE,
            accessory = j?.spec?.accessory?.name ?: Accessory.BLADE.name,
            hotBlendConfirmed = j?.spec?.hotBlendConfirmed ?: false,
            jobFerment = j?.spec?.ferment ?: false,
            turboActive = turboStartedAt != null,
            turboRemainingMs = turboStartedAt?.let { (Limits.MAX_MS_TURBO_PRESS - (now - it)).coerceIn(0, Limits.MAX_MS_TURBO_PRESS) } ?: 0,
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
