package app.toque.core.companion

import app.toque.core.i18n.tr
import app.toque.core.machine.ControllerConfig
import app.toque.core.machine.EventType
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobState
import app.toque.core.machine.Limits
import app.toque.core.machine.LinkState
import app.toque.core.machine.ManualSettings
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.PauseReason
import app.toque.core.machine.SafetyPolicy
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Mcc
import app.toque.core.robot.Feature
import app.toque.core.robot.RobotProfile
import app.toque.core.robot.RobotProfiles
import app.toque.core.recipes.OwnStepSettings
import app.toque.core.recipes.Portions
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeRules
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.UserRecipes
import java.io.IOException
import java.net.InetAddress
import java.util.Random
import java.security.SecureRandom

/** La recette envoyée par un téléphone, qui attend sur l'accueil du robot. */
data class WaitingRecipe(
    val recipeId: String,
    val title: String,
    val stepCount: Int,
    val totalMinutes: Int,
    val servings: Int,
    val category: String,
    val user: Boolean,
    val hasImage: Boolean,
    val sentAt: Long,
    val sender: Actor,
)

/** Pourquoi la recette en attente a quitté l'accueil. */
enum class WaitingEnd { LAUNCHED, REMOVED }

/**
 * Cœur du compagnon, dans le processus de l'interface. L'appli lui pousse l'état de la machine
 * ([updateMachine], [onMachineEvent]), la recette guidée ([updateSession], [endSession]) et les infos
 * du robot ([updateRobot]) ; le serveur HTTP lui demande l'état propre à chaque téléphone et lui
 * transmet les commandes permises, qu'il passe à [host] hors verrou.
 *
 * Il tient le numéro de révision (attente longue des téléphones), le journal, la recette en attente
 * et le suivi des pauses qui décide si un téléphone peut reprendre. Toutes les méthodes publiques
 * sont sûres entre fils. Les écouteurs sont appelés hors verrou, sur le fil qui a fait le changement
 * (souvent un fil du serveur) : côté Android, repasser sur le fil principal.
 */
class CompanionHub(
    private val host: CompanionHost,
    private val clock: CompanionClock = SystemCompanionClock,
    store: CompanionStore = MemoryStore(),
    random: Random = SecureRandom(),
    private val log: (String) -> Unit = {},
    val limits: HttpLimits = HttpLimits(),
    /** Doit valoir celui du contrôleur (annulation automatique d'une pause). */
    private val pauseMaxMs: Long = ControllerConfig().pauseMaxMs,
) {
    val pairing = PairingManager(clock, random, store, log = log)

    private val lock = Object()
    private var robot = RobotInfo()
    private var snap: Snapshot? = null
    private var revision = 1L

    // Détection des changements (voir bump).
    private var telemetryDirty = false
    private var lastBumpMono = Long.MIN_VALUE / 4
    /** Révision du dernier changement qui n'est pas que de la télémesure (attente « quiet »). */
    private var quietRevision = 1L
    /** Numéro de l'attente longue en cours de chaque téléphone qui n'en veut qu'une ([awaitChange]). */
    private val polls = HashMap<String, Long>()
    private var pollCounter = 0L
    private val temperature = TemperatureWatch()
    private var sentRemaining = 0L
    private var sentRemainingMono = 0L

    // Programme en cours, vu par le compagnon.
    private var jobActivity: Journal.Activity? = null
    private var jobStartedAt = 0L
    private var jobStep = -1

    // Pause en cours.
    private var pauseKnown = false
    private var pauseSinceWall = 0L
    private var pauseSinceMono = 0L
    /**
     * Couvercle ouvert ou bol retiré depuis le début du programme ou sa dernière reprise, vu par un
     * Snapshot ou par l'évènement LID_OPENED, quel que soit l'ordre d'arrivée. Effacé seulement par une
     * reprise observée ou un nouveau programme.
     */
    private var lidTaint = false
    /** Numéro du programme suivi (un réglage préparé pour un programme ne s'applique pas à un autre). */
    private var jobGeneration = 0L
    /** Dernière durée totale envoyée par un téléphone : (programme, total vu avant, total demandé). */
    private class TotalSent(val generation: Long, val before: Long, val after: Long)
    private var totalSent: TotalSent? = null
    /** Adresses authentifiées récemment (heure monotone), pour les places réservées du serveur. */
    private val trustedAddresses = LinkedHashMap<String, Long>()
    private var pauseBowl = false
    private var pauseActor = Actor.ROBOT

    /** Dernière commande de chaque sorte venue d'un téléphone, pour attribuer la transition qui suit. */
    private class Pending(val actor: Actor, val mono: Long)
    private val pending = HashMap<String, Pending>()

    private var session: CookSession? = null
    private var sessionActivity: Journal.Activity? = null
    private var sessionStartedAt = 0L
    /** Recette guidée quittée sans être finie ni arrêtée : « Reprendre » sur l'accueil du robot (`suspendedSession`). */
    private var suspended: CookSession? = null
    private var suspendedStartedAt = 0L
    private var waitingKey = ""

    private var waiting: WaitingRecipe? = null
    private val journal = Journal()

    /** Interrupteur du robot « Lancement depuis le téléphone » (coupé tant que l'appli ne l'a pas donné). */
    private var remoteStart = false
    /** Un lancement depuis un téléphone est en cours de traitement (un seul à la fois). */
    private var startInFlight = false
    /** Attente, après un lancement accepté, de l'instantané qui montre la cuisson (la réponse la contient). */
    internal var startSeenMs = START_SEEN_MS
    /** Attente, après un « OK » accepté, de l'instantané où le programme terminé a disparu. */
    internal var clearSeenMs = START_SEEN_MS

    private val waitingListeners = ArrayList<() -> Unit>()
    @Volatile private var server: HttpServer? = null
    @Volatile private var stopped = false

    // ------------------------------------------------------------------ cycle de vie

    /**
     * Le serveur devrait écouter : au moins un téléphone appairé, ou l'écran d'appairage ouvert.
     * L'appli décide d'appeler [start] ou [stop] (après un appairage, un retrait, l'ouverture de l'écran…).
     */
    val wanted: Boolean get() = pairing.hasPhones || pairing.isOpen

    val running: Boolean get() = server?.running == true

    /** Port ouvert, -1 à l'arrêt. */
    val port: Int get() = server?.boundPort ?: -1

    @Throws(IOException::class)
    fun start(port: Int = DEFAULT_PORT, bind: InetAddress? = null) {
        synchronized(this) {
            if (server?.running == true) return
            val s = HttpServer(CompanionApi(this), port, bind, limits, log) { isTrusted(it) }
            s.start()
            server = s
            stopped = false
        }
    }

    fun stop() {
        val s = synchronized(this) {
            val s = server
            server = null
            s
        }
        stopped = true
        s?.stop()
        pairing.flush()
        synchronized(lock) { lock.notifyAll() }
    }

    /**
     * Texte du QR code pour le code d'appairage en cours ([address] : IPv4 du robot sur le Wi-Fi), ou null.
     * Sans le nom du robot (le téléphone le reçoit dans la réponse d'appairage) : un code plus petit,
     * donc des modules plus gros à l'écran, plus faciles à lire pour l'appareil photo.
     */
    fun pairingPayload(address: String): String? {
        val offer = pairing.current ?: return null
        val p = port
        if (p <= 0) return null
        return PairingPayload(address, p, offer.secret, pairing.robotId, "").format()
    }

    // ------------------------------------------------------------------ écouteurs

    /** Recette en attente envoyée, remplacée ou retirée. */
    fun onWaitingChanged(l: () -> Unit): () -> Unit {
        synchronized(lock) { waitingListeners += l }
        return { synchronized(lock) { waitingListeners -= l } }
    }

    fun onPhonesChanged(l: () -> Unit): () -> Unit = pairing.onPhonesChanged(l)
    fun onPairingChanged(l: () -> Unit): () -> Unit = pairing.onPairingChanged(l)

    /**
     * Donne au téléphone [id] le nom [label] ([PairingManager.rename] : même nettoyage, vide = « Téléphone N »),
     * depuis le robot ou depuis ce téléphone (POST /api/phones/me). Un nom qui change fait une nouvelle
     * révision : les téléphones qui suivent l'état voient leur `me.name` tout de suite. Le téléphone
     * renommé, ou null s'il n'est plus appairé.
     */
    fun renamePhone(id: String, label: String): Phone? {
        val before = pairing.list.firstOrNull { it.id == id } ?: return null
        if (!pairing.rename(id, label)) return null
        val after = pairing.list.firstOrNull { it.id == id } ?: return null
        if (after.label != before.label) synchronized(lock) { bumpLocked() }
        return after
    }

    /**
     * Met le téléphone [id] en lecture seule ([on]) ou lui rend ses commandes ([PairingManager.setReadOnly]).
     * Ses « can » changent tout de suite (nouvelle révision). Le téléphone, ou null s'il n'est plus appairé.
     */
    fun setPhoneReadOnly(id: String, on: Boolean): Phone? {
        val before = pairing.list.firstOrNull { it.id == id } ?: return null
        if (!pairing.setReadOnly(id, on)) return null
        val after = pairing.list.firstOrNull { it.id == id } ?: return null
        if (after.readOnly != before.readOnly) synchronized(lock) { bumpLocked() }
        return after
    }

    private fun fireWaiting() {
        val copy = synchronized(lock) { ArrayList(waitingListeners) }
        for (l in copy) try {
            l()
        } catch (t: Throwable) {
            log("écouteur en échec : $t")
        }
    }

    // ------------------------------------------------------------------ ce que pousse l'appli

    fun updateRobot(info: RobotInfo) = synchronized(lock) {
        if (info != robot) {
            robot = info
            bumpLocked()
        }
    }

    fun robotInfo(): RobotInfo = synchronized(lock) { robot }

    /** Réglage du robot « Lancement depuis le téléphone » : la permission change aussitôt. */
    fun setRemoteStart(on: Boolean) = synchronized(lock) {
        if (on != remoteStart) {
            remoteStart = on
            bumpLocked()
        }
    }

    val remoteStartEnabled: Boolean get() = synchronized(lock) { remoteStart }

    /** À chaque Snapshot (environ 10 fois par seconde), depuis n'importe quel fil. */
    fun updateMachine(s: Snapshot) = synchronized(lock) {
        val prev = snap
        snap = s
        val seq = journal.lastSeq
        observe(prev, s)
        val now = clock.monoMs()
        var bump = false
        // Changement d'état ou nouvelle ligne du journal : réveille aussi les attentes « quiet ».
        val structuralChange = journal.lastSeq != seq || !sameStructure(prev, s)
        if (!sameTelemetry(prev, s)) telemetryDirty = true
        if (telemetryDirty && now - lastBumpMono >= TELEMETRY_MIN_MS) bump = true
        // Le téléphone extrapole le temps restant : ne prévenir que s'il s'écarte de l'extrapolation.
        if (s.state == JobState.RUNNING) {
            val expected = sentRemaining - (now - sentRemainingMono)
            if (kotlin.math.abs(expected - s.remainingMs) > DRIFT_MS) bump = true
        }
        if (structuralChange) bumpLocked()
        else if (bump) bumpLocked(structural = false)
    }

    /** Évènements de la machine : ne rien manquer d'une ouverture du couvercle pendant une pause. */
    fun onMachineEvent(e: MachineEvent) = synchronized(lock) {
        // Quel que soit l'état vu : l'ouverture peut précéder le premier Snapshot « en pause ».
        if (e.type == EventType.LID_OPENED) lidTaint = true
    }

    // ------------------------------------------------------------------ adresses de confiance
    // Verrou dédié : le fil d'acceptation HTTP ne bloque jamais sur le verrou principal du hub.

    /** Une requête authentifiée est arrivée de [remote]. */
    internal fun noteAuthenticated(remote: String) = synchronized(trustedAddresses) {
        trustedAddresses.remove(remote)
        trustedAddresses[remote] = clock.monoMs()
        while (trustedAddresses.size > MAX_TRUSTED) trustedAddresses.remove(trustedAddresses.keys.first())
    }

    /** [remote] s'est authentifiée il y a moins de [TRUSTED_MS] (appelé par le fil d'acceptation). */
    fun isTrusted(remote: String): Boolean = synchronized(trustedAddresses) {
        val at = trustedAddresses[remote] ?: return false
        clock.monoMs() - at <= TRUSTED_MS
    }

    /** Preuve d'identité pour POST /api/prove (voir [PairingManager.prove]). */
    internal fun prove(phoneId: String, nonce: String, local: String): String = pairing.prove(phoneId, nonce, local)

    /** Recette guidée affichée sur le robot, à chaque changement (étape, étapes faites, attente). */
    fun updateSession(s: CookSession) = synchronized(lock) {
        val now = clock.wallMs()
        val old = session
        if (old == null || old.recipeId != s.recipeId) {
            if (old != null) endSessionLocked(false, resumable = false)
            // Reprise de la recette quittée, ou une autre recette : celle qui attendait n'attend plus.
            suspended = null
            sessionStartedAt = now
            val a = journal.start(now, "recipe", s.title, s.recipeId, s.steps.size, s.steps.count { it.step.machine == null })
            sessionActivity = a
            journal.add(now, "recipe_started", tr("Recette lancée sur le robot"), activityId = a.id)
            waitingKey = ""
        }
        session = s
        val key = s.waitingFor.key + ":" + s.current
        if (key != waitingKey) {
            waitingKey = key
            val text = waitingText(s)
            if (text != null) journal.add(now, "waiting", text, Alerts.WAITING, activityId = sessionActivity?.id ?: 0,
                stepIndex = s.current, detail = s.waitingFor.key)
        }
        bumpLocked()
    }

    /**
     * La recette guidée est quittée : [completed] si elle a été menée au bout (« Bon appétit »). [resumable] :
     * elle reste proposée sur l'accueil du robot (« Reprendre ») ; les téléphones la voient alors en
     * `suspendedSession` jusqu'à sa reprise, son abandon ([clearSuspended]) ou une autre recette.
     */
    fun endSession(completed: Boolean, resumable: Boolean = false) = synchronized(lock) {
        if (session != null) {
            endSessionLocked(completed, resumable)
            bumpLocked()
        }
    }

    /**
     * Après un redémarrage de Toque : la recette que l'accueil propose de reprendre, à l'étape où elle en
     * était. Rien si une recette est déjà suivie.
     */
    fun suspendSession(s: CookSession) = synchronized(lock) {
        if (session != null) return@synchronized
        suspended = s
        suspendedStartedAt = 0L
        bumpLocked()
    }

    /** La recette à reprendre ne l'est plus (abandonnée sur l'accueil du robot). */
    fun clearSuspended() = synchronized(lock) {
        if (suspended != null) {
            suspended = null
            bumpLocked()
        }
    }

    private fun endSessionLocked(completed: Boolean, resumable: Boolean) {
        val now = clock.wallMs()
        val a = sessionActivity
        // Arrêtée par un téléphone (POST /api/session/stop) juste avant : à lui.
        val by = if (completed) null else recent("stop_recipe", clock.monoMs())
        if (a != null) {
            journal.end(a, now, if (completed) "finished" else "abandoned", by)
            when {
                completed -> journal.add(now, "recipe_finished", tr("Recette terminée"), Alerts.JOB_END, activityId = a.id)
                by != null -> journal.add(now, "recipe_abandoned", tr("Recette arrêtée depuis %s", by.name), actor = by, activityId = a.id)
                else -> journal.add(now, "recipe_abandoned", tr("Recette quittée sur le robot"), activityId = a.id)
            }
        }
        // Quittée sur le robot (ni finie, ni arrêtée par un téléphone) : elle attend sa reprise.
        val keep = session?.takeIf { resumable && !completed && by == null }
        suspended = keep
        suspendedStartedAt = if (keep != null) sessionStartedAt else 0L
        session = null
        sessionActivity = null
        waitingKey = ""
    }

    private fun waitingText(s: CookSession): String? {
        val n = s.current + 1
        val step = s.steps.getOrNull(s.current)?.step
        return when (s.waitingFor) {
            WaitingFor.WEIGH -> {
                val w = step?.weigh
                if (w != null) tr("Pesée : ajoutez %d g (%s)", w.grams, w.label) else tr("Étape %d : pesée", n)
            }
            WaitingFor.HAND -> tr("Étape %d à faire à la main", n)
            WaitingFor.LAUNCH -> tr("Étape %d prête à lancer sur le robot", n)
            WaitingFor.HOT_BLEND -> tr("Contenu chaud : confirmez le mixage sur le robot")
            WaitingFor.FINISH -> tr("Dernière étape faite : terminez sur le robot")
            WaitingFor.NOTHING, WaitingFor.RESUME -> null
        }
    }

    // ------------------------------------------------------------------ recette en attente

    val waitingRecipe: WaitingRecipe? get() = synchronized(lock) { waiting }

    /** Le robot retire la recette en attente : lancée (« Cuisiner ») ou retirée de l'accueil. */
    fun clearWaiting(why: WaitingEnd) {
        val changed = synchronized(lock) {
            val w = waiting ?: return
            waiting = null
            val now = clock.wallMs()
            if (why == WaitingEnd.LAUNCHED) journal.add(now, "recipe_launched", tr("Recette reçue lancée : %s", w.title))
            else journal.add(now, "recipe_removed", tr("Recette retirée du robot : %s", w.title))
            bumpLocked()
            true
        }
        if (changed) fireWaiting()
    }

    /**
     * Remet la recette en attente que l'appli a gardée (relance de l'interface), sans ligne de journal.
     * Ignorée si une recette attend déjà (elle est plus récente).
     */
    fun restoreWaiting(w: WaitingRecipe) {
        synchronized(lock) {
            if (waiting != null) return
            waiting = w
            bumpLocked()
        }
        fireWaiting()
    }

    internal fun sendRecipe(phone: Phone, recipe: Recipe) {
        synchronized(lock) {
            val now = clock.wallMs()
            waiting = WaitingRecipe(recipe.id, recipe.title, recipe.steps.size, recipe.totalMinutes, recipe.servings,
                recipe.category, recipe.source == app.toque.core.recipes.RecipeSource.USER, recipe.image.isNotBlank(),
                now, Actor.of(phone))
            journal.add(now, "recipe_sent", tr("Recette envoyée au robot : %s", recipe.title), Alerts.WAITING, Actor.of(phone))
            bumpLocked()
        }
        fireWaiting()
    }

    internal fun removeWaiting(phone: Phone): Boolean {
        val changed = synchronized(lock) {
            val w = waiting ?: return false
            waiting = null
            journal.add(clock.wallMs(), "recipe_removed", tr("Recette retirée du robot : %s", w.title), actor = Actor.of(phone))
            bumpLocked()
            true
        }
        if (changed) fireWaiting()
        return changed
    }

    // ------------------------------------------------------------------ nombre de personnes

    /**
     * Nombre de personnes choisi depuis un téléphone : refusé si la recette est en cours sur le robot
     * (revérifié sur le fil de l'appli, juste avant d'enregistrer), sinon enregistré par [host] comme
     * depuis la page de la recette du robot. Journal (sans alerte) et recette en attente à jour.
     */
    internal fun setServings(phone: Phone, recipeId: String, people: Int): ServingsReply {
        if (synchronized(lock) { session?.recipeId == recipeId }) return ServingsReply(refusal = ServingsChange.cooking())
        val r = host.setServings(recipeId, people) { synchronized(lock) { session?.recipeId != recipeId } }
        val recipe = r.recipe
        if (r.refusal != null || recipe == null) return r
        val waitingChanged = synchronized(lock) {
            journal.add(clock.wallMs(), "servings", tr("Nombre de personnes : %s, %s", recipe.title,
                Portions.label(recipe.servings, recipe.servingUnit)), actor = Actor.of(phone), detail = "servings=${recipe.servings}")
            val changed = updateWaitingServingsLocked(recipeId, recipe.servings)
            bumpLocked()
            changed
        }
        if (waitingChanged) fireWaiting()
        return r
    }

    /**
     * L'appli a enregistré un autre nombre de personnes pour [recipeId] (page de la recette du robot,
     * ou téléphone) : la recette en attente, si c'est elle, le reprend.
     */
    fun recipeServingsChanged(recipeId: String, servings: Int) {
        val changed = synchronized(lock) {
            updateWaitingServingsLocked(recipeId, servings).also { if (it) bumpLocked() }
        }
        if (changed) fireWaiting()
    }

    private fun updateWaitingServingsLocked(recipeId: String, servings: Int): Boolean {
        val w = waiting ?: return false
        if (w.recipeId != recipeId || w.servings == servings || servings <= 0) return false
        waiting = w.copy(servings = servings)
        return true
    }

    // ------------------------------------------------------------------ suivi des transitions

    private fun ended(st: JobState?) = st == JobState.FINISHED || st == JobState.ABORTED

    /** Auteur de la commande [kind] envoyée il y a moins de [ATTRIBUTION_MS], consommée au passage. */
    private fun recent(kind: String, now: Long): Actor? {
        val p = pending.remove(kind) ?: return null
        return if (now - p.mono <= ATTRIBUTION_MS) p.actor else null
    }

    private fun observe(p: Snapshot?, s: Snapshot) {
        val wall = clock.wallMs()
        val mono = clock.monoMs()
        val ps = if (p != null && p.jobActive) p.state else null
        val ss = if (s.jobActive) s.state else null

        if (s.hasFault && (p == null || p.faultCode != s.faultCode))
            journal.add(wall, "fault", s.faultMessage, Alerts.FAULT, activityId = jobActivity?.id ?: 0)

        val isNew = ss != null && !ended(ss) && (ps == null || ended(ps) || (p != null && (p.jobLabel != s.jobLabel || p.jobKind != s.jobKind)))
        if (isNew) {
            jobStartedAt = wall
            jobGeneration++
            totalSent = null
            lidTaint = false
            val sess = session
            if (sess != null) {
                val a = sessionActivity
                jobActivity = a
                jobStep = sess.running.firstOrNull() ?: sess.current
                val summary = sess.steps.getOrNull(jobStep)?.step?.machine?.summary ?: jobSummary(s)
                journal.add(wall, "step_started", tr("Étape %d lancée : %s", jobStep + 1, summary),
                    activityId = a?.id ?: 0, stepIndex = jobStep)
            } else {
                val manual = s.kind == JobKind.MANUAL && s.phaseCount == 1
                val a = journal.start(wall, if (manual) "manual" else "program", s.jobLabel, summary = jobSummary(s), totalMs = s.totalMs)
                jobActivity = a
                jobStep = -1
                // Lancée depuis un téléphone (POST /api/machine/start) juste avant : à lui.
                val starter = if (manual) recent("start", mono) else null
                if (starter != null) journal.add(wall, "job_started", tr("Démarrage depuis %s : %s", starter.name, s.jobLabel),
                    actor = starter, activityId = a.id)
                else journal.add(wall, "job_started", tr("Démarrage sur le robot : %s", s.jobLabel), activityId = a.id)
            }
            resetPause()
        }

        val activityId = jobActivity?.id ?: 0
        if (ss == JobState.PAUSED && ps != JobState.PAUSED) {
            // Vue dès son début seulement si le programme était déjà suivi juste avant.
            pauseKnown = ps != null
            pauseSinceWall = wall
            pauseSinceMono = mono
            pauseBowl = s.bowlRemoved
            pauseActor = if (s.pauseReason == PauseReason.USER.name) recent("pause", mono) ?: Actor.ROBOT else Actor.ROBOT
            val detail = if (s.bowlRemoved) "BOWL" else s.pauseReason
            journal.add(wall, "paused", pauseLabel(s), Alerts.PAUSE, pauseActor, activityId, jobStep, s.elapsedMs, detail)
        } else if (ss == JobState.PAUSED) {
            if (s.bowlRemoved && !pauseBowl) {
                pauseBowl = true
                journal.add(wall, "paused", tr("Bol retiré"), Alerts.PAUSE, Actor.ROBOT, activityId, jobStep, s.elapsedMs, "BOWL")
            }
        } else if (ps == JobState.PAUSED && ss != null && !ended(ss)) {
            journal.add(wall, "resumed", tr("Reprise"), actor = recent("resume", mono) ?: Actor.ROBOT,
                activityId = activityId, stepIndex = jobStep, elapsedMs = s.elapsedMs)
            resetPause()
            lidTaint = false
        }

        if (ss != null && ended(ss) && !ended(ps) && ps != null) {
            val a = jobActivity
            val inSession = session != null && a != null && a === sessionActivity
            if (ss == JobState.FINISHED) {
                if (inSession) journal.add(wall, "step_finished", tr("Étape %d terminée", jobStep + 1), Alerts.STEP_END,
                    activityId = activityId, stepIndex = jobStep, elapsedMs = s.elapsedMs)
                else {
                    journal.add(wall, "job_finished", tr("Terminé : %s", s.jobLabel), Alerts.JOB_END, activityId = activityId, elapsedMs = s.elapsedMs)
                    if (a != null) journal.end(a, wall, "finished")
                }
            } else {
                val stopper = recent("stop", mono)
                val outcome: String
                val text: String
                when {
                    stopper != null -> {
                        outcome = "stopped"
                        text = tr("Arrêté depuis %s après %s", stopper.name, SafetyPolicy.formatDuration(s.elapsedMs))
                    }
                    s.hasFault -> {
                        outcome = "fault"
                        text = s.faultMessage
                    }
                    ps == JobState.PAUSED && mono - pauseSinceMono >= pauseMaxMs - 2_000 -> {
                        outcome = "cancelled"
                        text = tr("Pause trop longue : programme annulé")
                    }
                    else -> {
                        outcome = "stopped"
                        text = tr("Arrêté sur le robot après %s", SafetyPolicy.formatDuration(s.elapsedMs))
                    }
                }
                journal.add(wall, "job_$outcome", text, Alerts.JOB_END, stopper ?: Actor.ROBOT, activityId, jobStep, s.elapsedMs)
                if (a != null && !inSession) journal.end(a, wall, outcome, stopper ?: Actor.ROBOT)
            }
            resetPause()
            jobActivity = null
            jobStep = -1
        } else if (ss == null && ps != null && !ended(ps)) {
            // Disparu sans fin observée (service machine relancé) : clore l'activité.
            val a = jobActivity
            if (a != null && a !== sessionActivity) journal.end(a, wall, "stopped")
            jobActivity = null
            resetPause()
        }
        // Température atteinte : cuisson manuelle à une phase, ou étape de la recette guidée.
        val inSession = session != null && jobActivity != null && jobActivity === sessionActivity
        val eligible = ss != null && (inSession || (session == null && s.kind == JobKind.MANUAL && s.phaseCount == 1))
        val reached = temperature.update(jobGeneration, eligible, ss == JobState.RUNNING, s.jobTempLevel,
            s.hasSample && tempKnown(s), s.actualTempC, targetCelsius(s))
        if (reached != null) journal.add(wall, "temp_reached", tr("Température atteinte : %d °C", reached), Alerts.TEMP_REACHED,
            activityId = activityId, stepIndex = jobStep, elapsedMs = s.elapsedMs)

        // Après les effacements (reprise, nouveau programme) : ce Snapshot compte pour la suite.
        if (s.lidOpen || s.bowlRemoved) lidTaint = true
    }

    private fun resetPause() {
        pauseKnown = false
        pauseBowl = false
        pauseSinceWall = 0
        pauseSinceMono = 0
        pauseActor = Actor.ROBOT
    }

    private fun pauseLabel(s: Snapshot): String = when {
        s.bowlRemoved -> tr("Bol retiré")
        else -> runCatching { PauseReason.valueOf(s.pauseReason).label }.getOrElse { s.pauseReason }
    }

    private fun jobSummary(s: Snapshot): String = buildString {
        append(SafetyPolicy.formatDuration(s.totalMs))
        if (s.kind == JobKind.MANUAL && s.phaseCount == 1) {
            if (s.jobTempLevel > 0) append(" · ").append(s.jobTempC).append(" °C")
            append(" · ").append(if (s.jobSpeed == 0) tr("sans mélange") else tr("vitesse %d", s.jobSpeed))
            if (s.jobReverse && s.jobSpeed > 0) append(" · ").append(tr("inverse"))
        }
    }

    // ------------------------------------------------------------------ révision

    /**
     * Ce que le téléphone voit de l'état n'a pas changé depuis [a]. Champ par champ, sans rien allouer :
     * appelé à chaque Snapshot (10 fois par seconde sur le Connect).
     */
    private fun sameStructure(a: Snapshot?, b: Snapshot): Boolean = a != null &&
        a.link == b.link && a.faultCode == b.faultCode && a.lidOpen == b.lidOpen && a.bowlDetected == b.bowlDetected &&
        a.bowlRemoved == b.bowlRemoved && a.jobActive == b.jobActive && a.jobKind == b.jobKind &&
        a.jobLabel == b.jobLabel && a.jobState == b.jobState && a.pauseReason == b.pauseReason &&
        a.endReason == b.endReason && a.phaseIndex == b.phaseIndex && a.totalMs == b.totalMs &&
        a.jobSpeed == b.jobSpeed && a.jobTempLevel == b.jobTempLevel && a.jobReverse == b.jobReverse &&
        a.accessory == b.accessory && a.hotBlendConfirmed == b.hotBlendConfirmed && a.turboActive == b.turboActive &&
        a.motorOn == b.motorOn && a.heaterOn == b.heaterOn && a.hot == b.hot && a.waitingStop == b.waitingStop &&
        a.hasSample == b.hasSample

    /** Température et poids inchangés depuis [a]. */
    private fun sameTelemetry(a: Snapshot?, b: Snapshot): Boolean = a != null &&
        a.actualTempC == b.actualTempC && a.targetTempC == b.targetTempC && a.weightG == b.weightG &&
        a.scaleStable == b.scaleStable

    /** Nouvelle révision ; [structural] faux : télémesure seule (température, poids), sans réveiller une attente « quiet ». */
    private fun bumpLocked(structural: Boolean = true) {
        revision++
        if (structural) quietRevision = revision
        val now = clock.monoMs()
        lastBumpMono = now
        telemetryDirty = false
        sentRemaining = snap?.remainingMs ?: 0
        sentRemainingMono = now
        lock.notifyAll()
    }

    val currentRevision: Long get() = synchronized(lock) { revision }

    /**
     * Attend que la révision diffère de [since] (au plus [maxWaitMs]) ; renvoie la révision. [quiet] :
     * seulement pour un changement qui n'est pas que de la télémesure (téléphone en arrière-plan, robot au
     * repos), ou tout de suite si [since] est plus ancien que le dernier tel changement ou inconnu ici
     * (appli du robot redémarrée).
     *
     * [phoneId] (`replace=1`, téléphone récent) : une nouvelle attente du même téléphone met fin à
     * celle-ci. Le téléphone qui coupe son attente (retour à l'écran) en ouvre aussitôt une autre : sans
     * cela, l'ancienne attendrait ici jusqu'à 120 s, sur une socket que le téléphone a déjà fermée, en
     * gardant une des places de son adresse. Elle répond alors l'état actuel dans le vide et libère sa
     * place. Un téléphone n'a jamais deux attentes longues voulues en même temps (un seul suiveur).
     */
    internal fun awaitChange(since: Long, maxWaitMs: Long, quiet: Boolean = false, phoneId: String? = null): Long = synchronized(lock) {
        val deadline = clock.monoMs() + maxWaitMs.coerceIn(0, limits.maxLongPollMs)
        // Avec [phoneId] : cette attente devient la seule du téléphone, la précédente s'arrête aussitôt.
        val mine = if (phoneId == null) 0L else {
            val n = ++pollCounter
            polls[phoneId] = n
            lock.notifyAll()
            n
        }
        try {
            while ((if (quiet) since <= revision && quietRevision <= since else revision == since) && !stopped &&
                (phoneId == null || polls[phoneId] == mine)) {
                val left = deadline - clock.monoMs()
                if (left <= 0) break
                lock.wait(minOf(left, 1_000L))
            }
        } finally {
            if (phoneId != null && polls[phoneId] == mine) polls.remove(phoneId)
        }
        revision
    }

    /** Attentes longues « une seule par téléphone » en cours (essais). */
    internal val pollCount: Int get() = synchronized(lock) { polls.size }
    /** Attentes « une seule par téléphone » commencées depuis le démarrage (essais). */
    internal val pollsTaken: Long get() = synchronized(lock) { pollCounter }

    // ------------------------------------------------------------------ règles des commandes

    internal fun permissions(): Permissions = synchronized(lock) { permissionsLocked() }

    private fun permissionsLocked(): Permissions {
        val s = snap ?: Snapshot()
        val st = if (s.jobActive) s.state else null
        val pause = if (st == JobState.RUNNING || st == JobState.STARTING) Permission.YES
            else Permission.no("nothing_running", tr("Rien à mettre en pause"))
        val stop = if ((st != null && !ended(st)) || s.turboActive) Permission.YES
            else Permission.no("nothing_running", tr("Rien à arrêter"))
        val adjust = when {
            st == null || ended(st) || st == JobState.STOPPING -> Permission.no("nothing_running", tr("Rien à régler"))
            s.hasFault -> Permission.no("fault", tr("Alerte en cours : voyez le robot"))
            s.kind != JobKind.MANUAL || s.phaseCount != 1 -> Permission.no("fixed", tr("Réglage fixe pour ce programme"))
            else -> Permission.YES
        }
        val tare = if (s.link == LinkState.READY) Permission.YES else Permission.no("link", tr("Base moteur non prête"))
        val remove = if (waiting != null) Permission.YES else Permission.no("no_waiting", tr("Aucune recette en attente"))
        val favorite = if (host.setsFavorites) Permission.YES
            else Permission.no("unsupported", tr("Le robot ne change pas les favoris depuis le téléphone"))
        val edit = if (host.writesRecipes) Permission.YES else Permission.no("unsupported", tr("Le robot n'enregistre pas les recettes du téléphone"))
        val look = if (host.setsAppearance) Permission.YES
            else Permission.no("unsupported", tr("Le robot ne change pas son apparence depuis le téléphone"))
        val own = if (host.takesOwnSteps) Permission.YES else Permission.no("unsupported", tr("Le robot ne prend pas votre réglage depuis le téléphone"))
        return Permissions(pause, resumeLocked(), stop, adjust, tare, Permission.YES, remove, startLocked(), clearLocked(),
            favorite, stopRecipeLocked(), edit, look, Permission.YES, own)
    }

    /** « Arrêter la recette » : une recette guidée est ouverte sur le robot (et l'hôte sait l'arrêter). */
    private fun stopRecipeLocked(): Permission = when {
        !host.stopsRecipes -> Permission.no("unsupported", tr("Le robot n'arrête pas la recette depuis le téléphone"))
        session == null -> Permission.no("no_recipe", tr("Aucune recette en cours sur le robot"))
        else -> Permission.YES
    }

    /**
     * Arrête la recette guidée depuis [phone], comme le bouton « Arrêter la recette » du robot (la
     * confirmation se fait sur le téléphone) : permis à tout téléphone appairé pendant une recette,
     * comme l'arrêt d'un programme. Le programme qu'elle avait lancé s'arrête (ligne « Arrêté depuis… »)
     * et la session se clôt sur la ligne « Recette arrêtée depuis… ». Revérifié sur le fil de l'appli.
     */
    internal fun stopRecipe(phone: Phone): CommandResult {
        val p = synchronized(lock) {
            stopRecipeLocked().also {
                if (it.allowed) {
                    mark("stop_recipe", phone)
                    val s = snap
                    if (s != null && s.jobActive && !ended(s.state)) mark("stop", phone)
                }
            }
        }
        if (!p.allowed) return refused(p)
        val r = host.stopRecipe()
        if (!r.ok) synchronized(lock) {
            pending.remove("stop_recipe")
            pending.remove("stop")
        }
        return r
    }

    /**
     * Favori choisi depuis un téléphone : enregistré par [host] comme l'étoile du robot. Sans ligne de
     * journal ni nouvelle révision (rien de l'état ne change) ; permis à tout téléphone appairé.
     */
    internal fun setFavorite(@Suppress("UNUSED_PARAMETER") phone: Phone, recipeId: String, favorite: Boolean): CommandResult {
        if (!host.setsFavorites) return CommandResult.refuse("unsupported", tr("Le robot ne change pas les favoris depuis le téléphone"))
        return host.setFavorite(recipeId, favorite)
    }

    // ------------------------------------------------------------------ apparence

    /**
     * Couleur et/ou thème du robot choisis depuis [phone] (déjà vérifiés : [AppearanceRequest]). Ce qui
     * est déjà en place ne fait rien (réponse « ok » sans reconstruire l'interface : pas de boucle) ;
     * sinon [host] l'applique sur son fil principal, ou refuse s'il n'est pas libre. Une ligne de journal
     * (sans alerte) ; l'état suivant porte la nouvelle apparence (refreshRobot de l'appli).
     */
    internal fun setAppearance(phone: Phone, req: AppearanceRequest): CommandResult {
        if (!host.setsAppearance) return CommandResult.refuse("unsupported", tr("Le robot ne change pas son apparence depuis le téléphone"))
        // Une couleur de la roue, hors des dix, chez un hôte qui ne prend que les dix : refusée, rien d'appliqué.
        if (req.accent != null && req.accent !in RobotLooks.ACCENTS && !host.setsAnyAccent)
            return CommandResult.refuse("bad_request", tr("Couleur inconnue"))
        val now = synchronized(lock) { robot }
        val accent = req.accent?.takeIf { it != RobotWire.accent(now.accent) }
        val theme = req.theme?.takeIf { it != RobotWire.theme(now.theme) }
        if (accent == null && theme == null) return CommandResult.OK
        val r = host.setAppearance(accent, theme)
        if (r.ok) synchronized(lock) {
            val what = listOfNotNull(accent?.let { tr("couleur %s", it) },
                theme?.let { if (it == RobotWire.THEME_LIGHT) tr("mode clair") else tr("mode sombre") }).joinToString(", ")
            journal.add(clock.wallMs(), "appearance", tr("Apparence changée depuis %s : %s", phone.name, what), actor = Actor.of(phone))
            bumpLocked()
        }
        return r
    }

    // ------------------------------------------------------------------ recettes écrites sur le téléphone

    /** Créations déjà faites, par brouillon du téléphone : une création répétée (réponse perdue) ne crée pas deux recettes. */
    private class Created(val id: String, var version: String)
    private val created = LinkedHashMap<String, Created>()

    private fun unsupportedEdit() = EditError(409, "unsupported", tr("Le robot n'enregistre pas les recettes du téléphone"))
    private fun notMine() = EditError(409, "not_user", tr("Seules vos recettes se modifient : faites « Ma version »"))
    private fun notFound() = EditError(404, "not_found", tr("Recette introuvable"))
    private fun cooking() = EditError(409, "cooking", tr("Recette en cours sur le robot"))

    /** GET /api/recipes/{id}/edit : une recette perso telle qu'enregistrée, avec sa version. */
    internal fun editable(id: String): Pair<EditableRecipe?, EditError?> {
        if (!host.writesRecipes) return null to unsupportedEdit()
        val r = host.storedRecipe(id) ?: return null to notFound()
        if (!UserRecipes.isUser(id)) return null to notMine()
        return editableOf(r) to null
    }

    /** L'hôte prend les réglages d'étape tapés sur le téléphone (lot B). */
    internal val takesSettings: Boolean get() = host.writesRecipes && host.writesMachineSteps

    /** Ce que l'éditeur d'étape du robot choisi propose, chez un hôte qui prend les réglages ; null sinon. */
    private fun settingsWire(): StepSettingsWire? = if (takesSettings) StepSettingsWire.of(RobotProfiles.active) else null

    /** Une recette enregistrée telle que l'éditeur du téléphone la lit, avec ce que l'éditeur d'étape propose. */
    internal fun editableOf(r: Recipe): EditableRecipe = EditableRecipe.of(r, settingsWire())

    /** GET /api/editor : les bornes de l'éditeur pour une recette nouvelle, et ses réglages chez un hôte qui les prend. */
    internal fun editor(): Pair<EditorWire?, EditError?> {
        if (!host.writesRecipes) return null to unsupportedEdit()
        return EditorWire(EditLimitsWire(), settingsWire()) to null
    }

    /**
     * Enregistre la recette perso [id] écrite sur le téléphone. Refusé (409) pendant qu'elle est cuisinée
     * sur le robot, ouverte dans son éditeur, ou si la version envoyée n'est plus la sienne ; puis jugée
     * par les limites de l'éditeur (422). Tout se revérifie sur le fil de l'appli, juste avant d'écrire.
     */
    internal fun saveRecipe(phone: Phone, id: String, req: RecipeEditRequest): RecipeWrite {
        if (!host.writesRecipes) return RecipeWrite(error = unsupportedEdit())
        if (!UserRecipes.isUser(id)) return RecipeWrite(error = if (host.storedRecipe(id) == null) notFound() else notMine())
        if (synchronized(lock) { session?.recipeId == id }) return RecipeWrite(error = cooking())
        val w = host.writeRecipe(id) { stored, inEditor ->
            decideSave(id, stored, inEditor, req.version) { RecipeEdits.apply(stored!!, stored, req).from(phone.name) }
        }
        return saved(w)
    }

    private fun decideSave(id: String, stored: Recipe?, inEditor: Boolean, version: String?, build: () -> RecipeWrite): RecipeWrite = when {
        stored == null -> RecipeWrite(error = notFound())
        synchronized(lock) { session?.recipeId == id } -> RecipeWrite(error = cooking())
        inEditor -> RecipeWrite(error = EditError(409, "editing", tr("Recette ouverte dans l'éditeur du robot")))
        RecipeEdits.version(stored) != version -> RecipeWrite(error = EditError(409, "stale", tr("La recette a changé sur le robot")))
        else -> build()
    }

    /**
     * Nouvelle recette perso écrite sur le téléphone : le robot lui donne son identifiant. Une seconde
     * demande avec le même brouillon (réponse perdue) enregistre sur la recette déjà créée, tant qu'elle
     * n'a pas changé sur le robot depuis.
     */
    internal fun createRecipe(phone: Phone, req: RecipeEditRequest): RecipeWrite {
        if (!host.writesRecipes) return RecipeWrite(error = unsupportedEdit())
        val key = phone.id + "|" + req.draft
        val known = synchronized(lock) { created[key] }
        if (known != null) {
            if (synchronized(lock) { session?.recipeId == known.id }) return RecipeWrite(error = cooking())
            val w = host.writeRecipe(known.id) { stored, inEditor ->
                decideSave(known.id, stored, inEditor, known.version) { RecipeEdits.apply(stored!!, stored, req).from(phone.name) }
            }
            return saved(w).also { r -> r.saved?.let { synchronized(lock) { known.version = RecipeEdits.version(it) } } }
        }
        val now = clock.wallMs()
        var w = RecipeWrite()
        // Identifiant déjà pris (même titre, même milliseconde) : le suivant.
        for (attempt in 0 until 4) {
            val id = UserRecipes.newId(req.title.ifBlank { "recette" }, now + attempt)
            w = host.writeRecipe(id) { stored, _ ->
                if (stored != null) RecipeWrite(error = EditError(409, "busy", tr("Réessayez dans un instant")))
                else RecipeEdits.apply(UserRecipes.blank(now).copy(id = id), null, req).from(phone.name)
            }
            if (w.error?.code != "busy") break
        }
        val r = saved(w)
        r.saved?.let {
            synchronized(lock) {
                created[key] = Created(it.id, RecipeEdits.version(it))
                // Les 32 dernières seulement (sous-classe évitée : ponts absents d'Android 6).
                while (created.size > 32) created.remove(created.keys.first())
            }
        }
        return r
    }

    /**
     * « Ma version » d'une recette Lidl : sa copie à soi, telle que le robot l'affiche (comme le bouton de
     * la page de la recette), enregistrée tout de suite dans « Mes recettes ». La recette Lidl ne change pas.
     */
    internal fun copyRecipe(@Suppress("UNUSED_PARAMETER") phone: Phone, id: String): RecipeWrite {
        if (!host.writesRecipes) return RecipeWrite(error = unsupportedEdit())
        val shown = host.recipe(id) ?: return RecipeWrite(error = notFound())
        if (UserRecipes.isUser(id)) return RecipeWrite(error = EditError(409, "already_user", tr("C'est déjà une de vos recettes")))
        val now = clock.wallMs()
        var w = RecipeWrite()
        for (attempt in 0 until 4) {
            val copy = UserRecipes.copyOf(shown, now + attempt)
            w = host.writeRecipe(copy.id) { stored, _ ->
                when {
                    stored != null -> RecipeWrite(error = EditError(409, "busy", tr("Réessayez dans un instant")))
                    else -> RecipeRules.check(copy, copy, machines = false)?.let { RecipeWrite(error = EditError.of(it)) } ?: RecipeWrite(saved = copy)
                }
            }
            if (w.error?.code != "busy") break
        }
        return saved(w)
    }

    /** Après une écriture : la recette en attente sur le robot, si c'est elle, prend son nouveau titre. */
    private fun saved(w: RecipeWrite): RecipeWrite {
        val r = w.saved ?: return w
        val changed = synchronized(lock) {
            val cur = waiting
            if (cur != null && cur.recipeId == r.id && (cur.title != r.title || cur.stepCount != r.steps.size || cur.totalMinutes != r.totalMinutes)) {
                waiting = cur.copy(title = r.title, stepCount = r.steps.size, totalMinutes = r.totalMinutes)
                bumpLocked()
                true
            } else false
        }
        if (changed) fireWaiting()
        return w
    }

    // ------------------------------------------------------------------ réglage du propriétaire d'une étape (Mealie)

    private fun ownUnsupported() = EditError(409, "unsupported", tr("Le robot ne prend pas votre réglage depuis le téléphone"))

    /**
     * La fiche de [rec] (telle que le robot l'affiche) pour un téléphone : chez un hôte qui prend le réglage du
     * propriétaire depuis un téléphone, une recette qui en prend (Mealie) porte `ownSettings`, l'empreinte et
     * l'auteur du réglage de chaque étape.
     */
    internal fun detail(rec: Recipe, baseServings: Int): RecipeDetail {
        val own = if (host.takesOwnSteps && OwnStepSettings.appliesTo(rec)) host.ownSteps(rec.id)?.takeIf { OwnStepSettings.appliesTo(it.source) } else null
        return Wires.recipe(rec, baseServings, own)
    }

    private fun ownWire(r: OwnStepsRead) = OwnStepsWire.of(r, StepSettingsWire.of(RobotProfiles.active))

    /** GET /api/recipes/{id}/steps : les étapes d'une recette qui prend le réglage du propriétaire, et l'éditeur d'étape du robot. */
    internal fun ownSteps(id: String): Pair<OwnStepsWire?, EditError?> {
        if (!host.takesOwnSteps) return null to ownUnsupported()
        val r = host.ownSteps(id) ?: return null to notFound()
        if (!OwnStepSettings.appliesTo(r.source)) return null to OwnStepEdits.notAllowed()
        return ownWire(r) to null
    }

    /**
     * Le réglage de l'étape [index] de la recette [id] donné depuis [phone] ([clear] : retour au réglage de la
     * recette ; sinon [machine], null pour « sans le robot ») ; [key] : l'empreinte de l'étape que le téléphone a
     * lue. Refusé (409) pendant que la recette est cuisinée sur le robot, ou si l'étape n'est plus celle-là
     * (« stale ») ; jugé ensuite ([OwnStepEdits.decide]). Tout se revérifie sur le fil de l'appli, juste avant
     * d'écrire. Enregistré comme venu d'un téléphone : à vérifier sur le robot avant son lancement.
     */
    internal fun writeOwnStep(phone: Phone, id: String, index: Int, key: String, machine: StepMachine?, clear: Boolean): Pair<OwnStepsWire?, EditError?> {
        if (!host.takesOwnSteps) return null to ownUnsupported()
        if (synchronized(lock) { session?.recipeId == id }) return null to cooking()
        val w = host.writeOwnStep(id) { read ->
            if (synchronized(lock) { session?.recipeId == id }) OwnStepWrite(error = cooking())
            else OwnStepEdits.decide(read, index, key, machine, clear).from(phone.name)
        }
        val after = w.after
        if (w.error != null || after == null) return null to (w.error ?: EditError.of(CommandResult.noAnswer()))
        return ownWire(after) to null
    }

    /** « OK » : un programme terminé ou arrêté est encore affiché sur le robot. */
    private fun clearLocked(): Permission {
        val s = snap ?: Snapshot()
        return if (s.jobActive && ended(s.state)) Permission.YES
            else Permission.no("nothing_ended", tr("Aucun programme terminé à fermer"))
    }

    /**
     * Le bouton OK du robot depuis un téléphone : retire de l'affichage le programme terminé ou
     * arrêté (MachineController.clearFinished, qui n'efface qu'un programme fini). Sans effet sur la
     * machine, sans ligne de journal ; permis à tout téléphone, quel que soit l'auteur du programme.
     * Une fois accepté, attend (au plus [clearSeenMs]) l'instantané sans ce programme : la révision
     * de la réponse le montre déjà.
     */
    internal fun clear(@Suppress("UNUSED_PARAMETER") phone: Phone): CommandResult {
        val generation = synchronized(lock) {
            val p = clearLocked()
            if (!p.allowed) return refused(p)
            jobGeneration
        }
        val r = host.clear()
        if (r.ok) synchronized(lock) {
            val deadline = clock.monoMs() + clearSeenMs
            while (clearLocked().allowed && jobGeneration == generation && !stopped) {
                val left = deadline - clock.monoMs()
                if (left <= 0) break
                lock.wait(minOf(left, 200L))
            }
        }
        return r
    }

    /**
     * Règle de lancement d'une cuisson manuelle depuis un téléphone (conditions 1 à 5 de
     * docs/COMPANION.md §6) : interrupteur du robot, base prête, rien en cours, pas de recette
     * ouverte, couvercle fermé et bol en place. Les réglages sont vérifiés à part ([prepareStartLocked]).
     */
    private fun startLocked(): Permission {
        val s = snap ?: Snapshot()
        val st = if (s.jobActive) s.state else null
        return when {
            !remoteStart -> Permission.no("disabled", tr("Lancement depuis le téléphone coupé sur le robot"))
            s.link != LinkState.READY || !s.hasSample -> Permission.no("link", tr("Base moteur non prête"))
            s.hasFault -> Permission.no("fault", tr("Alerte en cours : voyez le robot"))
            (st != null && !ended(st)) || s.turboActive -> Permission.no("busy", tr("Un programme est déjà en cours sur le robot"))
            session != null -> Permission.no("recipe", tr("Une recette est en cours sur le robot"))
            s.bowlRemoved -> Permission.no("bowl", tr("Remettez le bol sur le robot"))
            s.lidOpen -> Permission.no("lid", tr("Fermez le couvercle sur le robot"))
            !s.bowlDetected -> Permission.no("bowl", tr("Bol non détecté sur le robot"))
            else -> Permission.YES
        }
    }

    /**
     * La cuisson à lancer, ou son refus, d'après l'état présent : la règle ([startLocked]), les bornes
     * de l'écran du robot (rien n'est corrigé) et les règles de sécurité, sans aucune violation
     * (un mixage à chaud se lance sur le robot).
     */
    private fun prepareStartLocked(settings: ManualSettings, phone: Phone): StartCommand {
        fun no(code: String, reason: String) = StartCommand(refusal = CommandResult.refuse(code, reason))
        val p = startLocked()
        if (!p.allowed) return no(p.code, p.reason)
        val out = settings.outOfBounds()
        if (out != null) return no(out.code, out.message)
        val s = snap ?: Snapshot()
        val v = settings.violations(if (s.hasSample) s.actualTempC else null)
        if (v.any { it.code == SafetyPolicy.HOT_BLEND }) return no(SafetyPolicy.HOT_BLEND, tr("Contenu chaud : lancez sur le robot"))
        if (v.isNotEmpty()) return no(v.first().code, v.first().message)
        return StartCommand(settings.spec(hotBlendConfirmed = false), phone.name)
    }

    /**
     * Lance une cuisson manuelle depuis [phone], jamais avec confirmation de mixage à chaud. La règle
     * est vérifiée à l'arrivée, puis de nouveau par [CompanionHost.start] sur le fil de l'appli, juste
     * avant d'agir. Une fois acceptée, attend (au plus [startSeenMs]) l'instantané qui montre la
     * cuisson : la révision de la réponse la contient déjà.
     */
    internal fun start(phone: Phone, req: StartRequest): CommandResult {
        // Bornes du robot choisi : pas de chauffe sans mélange sur un robot qui ne la sait pas faire.
        val settings = req.settings(limitsFor(RobotProfiles.active).manual()) ?: return CommandResult.refuse("temp_range", tr("Température hors table"))
        val generation: Long
        synchronized(lock) {
            val c = prepareStartLocked(settings, phone)
            if (c.refusal != null) return c.refusal
            if (startInFlight) return CommandResult.refuse("busy", tr("Un lancement est déjà en cours"))
            startInFlight = true
            mark("start", phone)
            generation = jobGeneration
        }
        try {
            val r = unmarkOnFailure("start", host.start { synchronized(lock) { prepareStartLocked(settings, phone) } })
            if (r.ok) synchronized(lock) {
                val deadline = clock.monoMs() + startSeenMs
                while (jobGeneration == generation && !stopped) {
                    val left = deadline - clock.monoMs()
                    if (left <= 0) break
                    lock.wait(minOf(left, 200L))
                }
            }
            return r
        } finally {
            synchronized(lock) { startInFlight = false }
        }
    }

    /** Règle de reprise depuis un téléphone : pause demandée (USER), couvercle resté fermé et bol en place tout du long. */
    private fun resumeLocked(): Permission {
        val s = snap ?: return Permission.no("not_paused", tr("Le programme n'est pas en pause"))
        if (!s.jobActive || s.state != JobState.PAUSED) return Permission.no("not_paused", tr("Le programme n'est pas en pause"))
        if (s.hasFault) return Permission.no("fault", tr("Alerte en cours : voyez le robot"))
        return when {
            s.pauseReason == PauseReason.LID.name ->
                if (pauseBowl || s.bowlRemoved) Permission.no("bowl_removed", tr("Bol retiré : reprenez sur le robot"))
                else Permission.no("lid_opened", tr("Couvercle ouvert : reprenez sur le robot"))
            s.pauseReason != PauseReason.USER.name -> Permission.no("safety", tr("Arrêt de sécurité : reprenez sur le robot"))
            !pauseKnown -> Permission.no("pause_unknown", tr("Début de la pause non suivi : reprenez sur le robot"))
            lidTaint || s.lidOpen || s.bowlRemoved ->
                Permission.no("lid_opened_during_pause", tr("Le couvercle a été ouvert pendant la pause : reprenez sur le robot"))
            s.hot && s.jobSpeed >= 4 && !s.hotBlendConfirmed -> Permission.no("hot_blend", tr("Contenu chaud : reprenez sur le robot"))
            s.link != LinkState.READY -> Permission.no("link", tr("Base moteur non prête"))
            else -> Permission.YES
        }
    }

    private fun refused(p: Permission) = CommandResult.refuse(p.code, p.reason)

    internal fun pause(phone: Phone): CommandResult {
        val p = synchronized(lock) { permissionsLocked().pause.also { if (it.allowed) mark("pause", phone) } }
        if (!p.allowed) return refused(p)
        return unmarkOnFailure("pause", host.pause())
    }

    internal fun stop(phone: Phone): CommandResult {
        val p = synchronized(lock) { permissionsLocked().stop.also { if (it.allowed) mark("stop", phone) } }
        if (!p.allowed) return refused(p)
        return unmarkOnFailure("stop", host.stop())
    }

    internal fun resume(phone: Phone): CommandResult {
        val p = synchronized(lock) { resumeLocked().also { if (it.allowed) mark("resume", phone) } }
        if (!p.allowed) return refused(p)
        return unmarkOnFailure("resume", host.resume { synchronized(lock) { resumeLocked().allowed } })
    }

    internal fun tare(phone: Phone): CommandResult {
        val p = synchronized(lock) { permissionsLocked().tare }
        if (!p.allowed) return refused(p)
        val r = host.tare()
        if (r.ok) synchronized(lock) {
            journal.add(clock.wallMs(), "tare", tr("Balance remise à zéro"), actor = Actor.of(phone), activityId = jobActivity?.id ?: 0)
            bumpLocked()
        }
        return r
    }

    /**
     * Ajuste durée, température, vitesse et sens de la cuisson en cours, toujours sans confirmation de
     * mixage à chaud. La règle est revérifiée par [CompanionHost.adjust] sur le fil de l'appli, juste
     * avant d'agir, contre l'état le plus frais et le même programme.
     */
    internal fun adjust(phone: Phone, req: AdjustRequest): CommandResult {
        if (req.speed == null && req.tempC == null && req.totalMs == null && req.addMs == null && req.reverse == null)
            return CommandResult.refuse("bad_request", tr("Aucun réglage demandé"))
        if (req.totalMs != null && req.addMs != null) return CommandResult.refuse("bad_request", tr("Durée totale ou ajout, pas les deux"))
        if (req.speed != null && req.speed !in 0..10) return CommandResult.refuse("speed_range", tr("Vitesse hors plage (0 à 10)"))
        val level = req.tempC?.let { if (it == 0) 0 else Mcc.levelForCelsius(it) }
        if (level != null && level < 0) return CommandResult.refuse("temp_range", tr("Température hors table"))
        val generation = synchronized(lock) {
            val c = prepareAdjustLocked(req, level, jobGeneration, dryRun = true)
            if (c.refusal != null) return c.refusal
            jobGeneration
        }
        var sent: AdjustCommand? = null
        val r = host.adjust {
            synchronized(lock) { prepareAdjustLocked(req, level, generation, dryRun = false).also { sent = it } }
        }
        val cmd = sent
        synchronized(lock) {
            val t = totalSent
            if (!r.ok && cmd?.totalMs != null && t != null && t.after == cmd.totalMs) totalSent = null
            if (r.ok && cmd != null && cmd.refusal == null) {
                val parts = ArrayList<String>()
                if (req.addMs != null) parts += (if (req.addMs >= 0) "+" else "−") + SafetyPolicy.formatDuration(kotlin.math.abs(req.addMs))
                if (req.totalMs != null) parts += SafetyPolicy.formatDuration(req.totalMs)
                if (req.tempC != null) parts += if (req.tempC == 0) tr("sans chauffe") else "${req.tempC} °C"
                if (req.speed != null) parts += if (req.speed == 0) tr("sans mélange") else tr("vitesse %d", req.speed)
                if (req.reverse != null) parts += if (req.reverse) tr("sens inverse") else tr("sens normal")
                val detail = listOfNotNull(req.speed?.let { "speed=$it" }, req.tempC?.let { "tempC=$it" },
                    req.totalMs?.let { "totalMs=$it" }, req.addMs?.let { "addMs=$it" }, req.reverse?.let { "reverse=$it" },
                    cmd.totalMs?.let { "newTotalMs=$it" }).joinToString(";")
                journal.add(clock.wallMs(), "adjusted", tr("Réglage modifié : %s", parts.joinToString(" · ")), actor = Actor.of(phone),
                    activityId = jobActivity?.id ?: 0, stepIndex = jobStep, elapsedMs = snap?.elapsedMs ?: 0, detail = detail)
                bumpLocked()
            }
        }
        return r
    }

    /**
     * Le réglage à envoyer, ou son refus, d'après l'état présent. Une durée ajoutée part du dernier
     * total envoyé tant que le Snapshot ne l'a pas encore reflété (deux « +1 min » font deux minutes).
     */
    private fun prepareAdjustLocked(req: AdjustRequest, level: Int?, generation: Long, dryRun: Boolean): AdjustCommand {
        val s = snap ?: Snapshot()
        fun no(p: Permission) = AdjustCommand(refusal = refused(p))
        if (generation != jobGeneration) return no(Permission.no("changed", tr("Le programme a changé : réglez sur le robot")))
        val p = permissionsLocked().adjust
        if (!p.allowed) return no(p)
        val newSpeed = req.speed ?: s.jobSpeed
        val speedChange = req.speed != null && req.speed != s.jobSpeed
        val directionChange = req.reverse != null && req.reverse != s.jobReverse
        // Le mixage à chaud se confirme sur le robot : un téléphone ne fait pas passer un contenu chaud à
        // une vitesse de 4 ou plus, ni ne relance la lame dans l'autre sens à cette vitesse.
        if (s.hot && newSpeed >= 4 && (speedChange || directionChange))
            return no(Permission.no(SafetyPolicy.HOT_BLEND, tr("Contenu chaud : changez la vitesse sur le robot")))
        val t = totalSent
        val base = if (t != null && t.generation == generation && s.totalMs == t.before) t.after else s.totalMs
        val total = when {
            req.totalMs != null -> req.totalMs
            req.addMs != null -> base + req.addMs
            else -> null
        }
        if (total != null && total !in Limits.MIN_MS..Limits.MAX_MS_DEFAULT)
            return no(Permission.no("too_long", tr("Durée hors limites")))
        if (!dryRun && total != null) totalSent = TotalSent(generation, if (t != null && t.generation == generation && s.totalMs == t.before) t.before else s.totalMs, total)
        return AdjustCommand(req.speed, level, req.reverse, total)
    }

    private fun unmarkOnFailure(kind: String, r: CommandResult): CommandResult {
        if (!r.ok) synchronized(lock) { pending.remove(kind) }
        return r
    }

    private fun mark(kind: String, phone: Phone) {
        pending[kind] = Pending(Actor.of(phone), clock.monoMs())
    }

    // ------------------------------------------------------------------ état et journal

    internal fun journalPage(phone: Phone, after: Long, limit: Int): JournalPage =
        synchronized(lock) { journal.page(after, limit, phone.id) }

    internal fun robotWire(): RobotWire = synchronized(lock) {
        RobotWire(pairing.robotId, robot.name, robot.appVersion, robot.language,
            accent = RobotWire.accent(robot.accent), theme = RobotWire.theme(robot.theme), anyAccent = anyAccent(),
            model = RobotProfiles.active.id)
    }

    /**
     * La base mesure la température en ce moment : toujours sur le Connect ; sur le Smart, seulement en
     * chauffant ou pendant une cuisson chauffée (sa base ne mesure qu'après une chauffe).
     */
    private fun sessionWire(c: CookSession, startedAt: Long) =
        SessionWire(c.recipeId, c.title, c.servings, c.steps.size, c.current, c.done.sorted(), c.running,
            c.waitingFor.key, startedAt, c.steps.mapIndexed { i, st -> Wires.step(i, st.step, st.weighedG, st.hotBlendOnRobot) })

    /** `machine.weightValid` : une vraie pesée (base prête, balance normale, moteur et Turbo arrêtés). */
    private fun weightValid(s: Snapshot): Boolean = s.hasSample && s.link == LinkState.READY && s.scaleState == "normal" &&
        !s.motorOn && !s.turboActive && RobotProfiles.active.capabilities.scale

    /** `machine.heating` : Toque fait chauffer la base (le programme en cours a une température). */
    private fun heating(s: Snapshot): Boolean = s.jobActive && s.state == JobState.RUNNING && s.jobTempC > 0 && !s.hasFault

    /** Ce que peut un téléphone en lecture seule : rien commander (il peut seulement se renommer). */
    private fun readOnlyPermissions(): Permissions {
        val no = Permission.no("read_only", tr("Ce téléphone est en lecture seule : les commandes se font sur le robot"))
        return Permissions(no, no, no, no, no, no, no, no, no, no, no, no, no, Permission.YES, no)
    }

    private fun tempKnown(s: Snapshot): Boolean =
        RobotProfiles.active.capabilities.measuresTemperature || s.heaterOn || (s.jobActive && s.jobTempLevel > 0)

    /**
     * Consigne en °C de la cuisson : celle que la base renvoie quand elle n'est pas dans la table de
     * Toque mais tombe sur le niveau de la cuisson (40 °C du Smart, niveau 37 °C), sinon celle du niveau.
     */
    private fun targetCelsius(s: Snapshot): Int {
        val level = s.jobTempLevel
        val board = s.targetTempC
        return if (level > 0 && board > 0 && Mcc.levelForCelsius(board) <= 0 && Mcc.levelAtMost(board) == level) board
        else Mcc.celsiusForLevel(level)
    }

    /** `robot.anyAccent` : le robot change sa couleur depuis un téléphone, et en prend toute couleur gardée. */
    private fun anyAccent(): Boolean = host.setsAppearance && host.setsAnyAccent

    /** État vu par [phone]. */
    internal fun state(phone: Phone): StateWire = synchronized(lock) {
        val s = snap ?: Snapshot()
        val wall = clock.wallMs()
        val machine = MachineWire(s.link.name, s.link.label, s.ready, s.faultCode, s.faultMessage, s.hasSample,
            s.actualTempC, s.targetTempC, s.hot, s.weightG, s.scaleStable, s.lidOpen, s.bowlDetected, s.bowlRemoved,
            s.motorOn, s.heaterOn, s.turboActive, tempKnown(s), weightValid(s), heating(s))
        val job = if (!s.jobActive) null else {
            val pause = if (s.state != JobState.PAUSED) null else {
                val autoAt = if (pauseSinceWall > 0) pauseSinceWall + pauseMaxMs else 0L
                val autoIn = if (pauseSinceMono > 0) (pauseSinceMono + pauseMaxMs - clock.monoMs()).coerceAtLeast(0) else -1L
                PauseWire(s.pauseReason, pauseLabel(s), pauseSinceWall, autoAt, autoIn, pauseActor.wire(phone.id),
                    lidTaint || !pauseKnown, pauseBowl || s.bowlRemoved)
            }
            JobWire(s.jobKind, runCatching { JobKind.valueOf(s.jobKind).label }.getOrElse { s.jobKind }, s.jobLabel,
                s.jobState, s.state == JobState.RUNNING, s.endReason, s.phaseIndex, s.phaseCount, s.elapsedMs, s.totalMs,
                s.remainingMs, s.jobSpeed, s.jobTempLevel, s.jobTempC, s.jobReverse, s.accessory,
                s.hotBlendConfirmed, jobStartedAt, s.waitingStop, pause, s.jobFerment)
        }
        val sess = session?.let { sessionWire(it, sessionStartedAt) }
        val left = if (session == null) suspended?.let { sessionWire(it, suspendedStartedAt) } else null
        val wait = waiting?.let { w ->
            WaitingWire(w.recipeId, w.title, w.stepCount, w.totalMinutes, w.servings, w.category, w.user, w.hasImage,
                w.sentAt, w.sender.wire(phone.id))
        }
        val can = if (phone.readOnly) readOnlyPermissions() else permissionsLocked()
        StateWire(COMPANION_PROTOCOL, revision, wall, robotWire(), PhoneWire.of(phone), machine, job, can, sess, wait, journal.lastEnded()?.wire(phone.id),
            journal.lastSeq, limitsFor(RobotProfiles.active), left)
    }

    internal fun hostRef(): CompanionHost = host

    companion object {
        const val DEFAULT_PORT = 8088
        /** Délai qu'une méthode de [CompanionHost] ne doit pas dépasser. */
        const val HOST_TIMEOUT_MS = 5_000L
        /** Mesures (température, poids) : une révision au plus toutes les… */
        const val TELEMETRY_MIN_MS = 300L
        /** Écart toléré entre le temps restant réel et celui que le téléphone extrapole. */
        const val DRIFT_MS = 1_500L
        /** Une transition qui suit une commande de téléphone de moins de… lui est attribuée. */
        const val ATTRIBUTION_MS = 3_000L
        /** Une adresse authentifiée garde ses places réservées pendant… */
        const val TRUSTED_MS = 10 * 60_000L
        const val MAX_TRUSTED = 32
        /** Après un lancement accepté, attente au plus de l'instantané qui montre la cuisson. */
        const val START_SEEN_MS = 2_000L

        val LIMITS = LimitsWire(
            temperaturesC = Mcc.TEMPERATURE_LEVELS.filter { it > 0 },
            maxSpeed = 10, maxSpeedHeated = Limits.MAX_SPEED_HEATED, maxSpeedReverse = Limits.MAX_SPEED_REVERSE,
            whiskMaxSpeed = app.toque.core.machine.Accessory.WHISK.maxSpeed,
            maxTempNoStirC = Mcc.celsiusForLevel(Limits.MAX_TEMP_LEVEL_NO_STIR), hotC = Limits.HOT_C,
            minMs = Limits.MIN_MS, maxMs = Limits.MAX_MS_DEFAULT, maxMsHighSpeed = Limits.MAX_MS_HIGH_SPEED,
            pauseMaxMs = ControllerConfig().pauseMaxMs,
            manualMinMs = app.toque.core.machine.ManualLimits.MIN_SECONDS * 1000L,
            highSpeedAbove = app.toque.core.machine.ManualLimits.HIGH_SPEED_ABOVE,
        )

        private val limitsCache = java.util.concurrent.ConcurrentHashMap<String, LimitsWire>()

        /**
         * Bornes du robot choisi : ses températures et la chauffe sans mélange s'il la sait faire. Seulement
         * les températures de la table de Toque : le téléphone envoie un niveau de cette table (pas 40 °C).
         */
        fun limitsFor(p: RobotProfile): LimitsWire = limitsCache.getOrPut(p.id) {
            LIMITS.copy(
                temperaturesC = p.capabilities.temperaturesC.filter { Mcc.levelForCelsius(it) > 0 },
                heatNoStir = p.capabilities.has(Feature.HEAT_NO_STIR),
            )
        }
    }
}
