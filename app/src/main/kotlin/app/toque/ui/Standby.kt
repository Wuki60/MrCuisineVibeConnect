package app.toque.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.machine.JobState
import app.toque.core.machine.Snapshot
import kotlin.random.Random

/**
 * Mise en veille, comme l'appli d'origine : après quelques minutes sans geste et sans programme
 * en cours, l'écran passe au noir avec l'heure, rétroéclairage au minimum. Le premier geste
 * (écran ou bouton rotatif) réveille sans rien déclencher ; le couvercle, la balance et les
 * évènements de la base réveillent aussi.
 *
 * Pendant qu'un programme tourne (un yaourt dure 8 h), l'écran n'a pas à rester allumé en plein. Au choix
 * (Réglages › Écran › Veille de l'écran), sans geste :
 *  - d'abord l'écran baisse sa luminosité, sans rien cacher : un appui agit tout de suite et la rétablit ;
 *  - puis il passe au noir comme en veille, avec à la place de l'heure l'étape en cours et son décompte.
 * Seul un programme qui tourne sans rien demander laisse faire : un démarrage, une pause, un arrêt, la fin,
 * une alerte, le couvercle, la balance ou Turbo rallument comme avant.
 */
class Standby(private val a: MainActivity, private val root: FrameLayout) {
    private val main = Handler(Looper.getMainLooper())
    private var lastInputMs = SystemClock.uptimeMillis()
    private var lastWeight = Int.MIN_VALUE
    private var lastLid: Boolean? = null
    /** Le programme au dernier état reçu (sorte, nom, état) : son changement rallume. */
    private var lastJob: String? = null
    private var view: FrameLayout? = null
    private var box: LinearLayout? = null
    private var step: TextView? = null
    private var clock: TextView? = null
    private var hot: TextView? = null
    private var running = false
    private var wokeMs = 0L

    val asleep: Boolean get() = view != null

    /** L'écran a baissé sa luminosité pendant un programme, sans passer au noir. */
    var dimmed = false
        private set

    /** Temps sans geste ni signe de vie du robot (programme, couvercle, balance), en ms. */
    val idleMs: Long get() = SystemClock.uptimeMillis() - lastInputMs

    /** Sortie de veille (pas à l'arrêt de l'écran) : l'alerte de mise à jour regarde à ce moment-là. */
    var onWake: (() -> Unit)? = null

    private val check = object : Runnable {
        override fun run() {
            if (shouldSleep()) sleep() else if (shouldDim()) dim()
            main.postDelayed(this, 10_000)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            move()
            main.postDelayed(this, 60_000)
        }
    }

    /** Heure pas encore réglée : on regarde souvent, pour la montrer dès qu'elle l'est (le reste attend la minute). */
    private val clockTick = object : Runnable {
        override fun run() {
            refresh()
            if (!WallClock.known && view != null) main.postDelayed(this, WallClock.UNKNOWN_TICK_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        lastInputMs = SystemClock.uptimeMillis()
        main.postDelayed(check, 10_000)
    }

    fun stop() {
        running = false
        main.removeCallbacks(check)
        undim()
        wake()
    }

    /** Un geste de la personne : repousse la veille. Vrai si ce geste a servi à réveiller (ne pas le transmettre). */
    fun onInput(): Boolean {
        lastInputMs = SystemClock.uptimeMillis()
        // Luminosité seulement baissée : tout se voit encore, le geste agit (Stop reste à un appui).
        undim()
        if (!asleep) return false
        wake()
        wokeMs = SystemClock.uptimeMillis()
        return true
    }

    /** Bouton rotatif : un tour envoie plusieurs crans, on ignore aussi ceux qui suivent le réveil. */
    fun onKnob(): Boolean {
        if (onInput()) return true
        return SystemClock.uptimeMillis() - wokeMs < 800
    }

    /** Quelque chose se passe sur le robot : on réveille et on repousse la veille. */
    fun poke() {
        lastInputMs = SystemClock.uptimeMillis()
        undim()
        wake()
    }

    fun onSnapshot(s: Snapshot) {
        val weightMoved = lastWeight != Int.MIN_VALUE && Math.abs(s.weightG - lastWeight) >= 20
        val lidMoved = lastLid != null && lastLid != s.lidOpen
        lastWeight = s.weightG
        lastLid = s.lidOpen
        // Un programme qui démarre, se met en pause, reprend ou finit : toujours un réveil.
        val job = if (s.jobActive) "${s.jobKind}|${s.jobLabel}|${s.jobState}" else ""
        val jobMoved = lastJob != null && lastJob != job
        lastJob = job
        if ((s.busy && !quiet(s)) || weightMoved || lidMoved || jobMoved) poke()
        else if (asleep) refresh()
    }

    /**
     * Un programme tourne sans rien demander, et un des deux réglages « pendant un programme » est choisi : il
     * ne retient pas l'écran. Tout autre état (démarrage, arrêt en cours, pause, alerte, Turbo) le garde allumé.
     */
    private fun quiet(s: Snapshot): Boolean =
        (a.prefs.jobDimMinutes > 0 || a.prefs.jobStandbyMinutes > 0) &&
            s.jobActive && s.state == JobState.RUNNING && !s.hasFault && !s.turboActive

    private fun shouldSleep(): Boolean {
        if (asleep || !running || a.current?.keepAwake == true) return false
        val s = a.snapshot
        // Pendant un programme : son propre délai ; au repos : celui de la veille.
        val minutes = if (s.busy) (if (quiet(s)) a.prefs.jobStandbyMinutes else 0) else a.prefs.standbyMinutes
        return minutes > 0 && idleMs >= minutes * 60_000L
    }

    private fun shouldDim(): Boolean {
        if (asleep || dimmed || !running || a.current?.keepAwake == true) return false
        val minutes = a.prefs.jobDimMinutes
        return minutes > 0 && quiet(a.snapshot) && idleMs >= minutes * 60_000L
    }

    /** Pendant un programme : l'écran reste tel quel, moins lumineux. */
    private fun dim() {
        dimmed = true
        brightness(level(a.prefs.jobDimBrightness))
    }

    private fun undim() {
        if (!dimmed) return
        dimmed = false
        if (!asleep) brightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    private fun sleep() {
        a.hideKeyboard()
        val v = FrameLayout(a)
        v.setBackgroundColor(0xFF000000.toInt())
        v.isClickable = true
        val b = Ui.vertical(a, Gravity.CENTER_HORIZONTAL)
        // Pendant un programme, au-dessus de son décompte : l'étape (« Étape 3 · Velouté ») ou le nom du programme.
        val st = Ui.ellipsize(Ui.text(a, "", 26f, 0xFF8A9099.toInt(), Ui.MEDIUM), 2).apply { gravity = Gravity.CENTER }
        b.addView(st, Ui.lp(Ui.WRAP, Ui.WRAP))
        // L'heure ; pendant un programme, le temps qui lui reste.
        val t = Ui.text(a, "", 150f, 0xFF7C828B.toInt(), Ui.THIN)
        b.addView(t, Ui.lp(Ui.WRAP, Ui.WRAP))
        // La veille est noire dans les deux thèmes : l'avertissement garde sa teinte claire.
        val h = Ui.text(a, "", 22f, Ui.WARN_ON_DARK, Ui.MEDIUM)
        b.addView(h, Ui.lp(Ui.WRAP, Ui.WRAP, t = 6))
        b.addView(Ui.text(a, tr("Touchez l'écran pour reprendre"), 17f, 0xFF3E434A.toInt()), Ui.lp(Ui.WRAP, Ui.WRAP, t = 18))
        v.addView(b, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER).apply {
            leftMargin = Ui.u(60)
            rightMargin = Ui.u(60)
        })
        root.addView(v, Ui.flp())
        view = v
        box = b
        step = st
        clock = t
        hot = h
        refresh()
        v.alpha = 0f
        v.animate().alpha(1f).setDuration(800).start()
        dimmed = false
        brightness(0.02f)
        // Anneau de la molette : ce qui est réglé pour la veille (rien si c'est la même chose qu'allumé).
        a.prefs.ring.standby?.let { KnobLight.start(it) }
        main.postDelayed(tick, 60_000)
        if (!WallClock.known) main.postDelayed(clockTick, WallClock.UNKNOWN_TICK_MS)
    }

    private fun wake() {
        val v = view ?: return
        main.removeCallbacks(tick)
        main.removeCallbacks(clockTick)
        v.animate().cancel()
        root.removeView(v)
        view = null
        box = null
        step = null
        clock = null
        hot = null
        brightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        KnobLight.stop()
        if (running) onWake?.invoke()
    }

    private fun refresh() {
        val s = a.snapshot
        // Un programme en cours : son étape et son décompte prennent la place de l'heure. L'état arrive plusieurs
        // fois par seconde : un texte n'est réécrit que quand il change.
        val job = s.jobActive && s.busy
        step?.let {
            set(it, if (job) s.jobLabel else "")
            it.visibility = if (job && s.jobLabel.isNotEmpty()) View.VISIBLE else View.GONE
        }
        clock?.let { set(it, if (job) Ui.clock(s.remainingMs) else WallClock.text()) }
        val temp = s.actualTempC
        hot?.let {
            set(it, if (temp >= 50) tr("Bol chaud : %d °C", temp) else "")
            it.visibility = if (temp >= 50) View.VISIBLE else View.GONE
        }
    }

    private fun set(t: TextView, text: String) {
        if (t.text.toString() != text) t.text = text
    }

    /** Déplace un peu l'heure chaque minute, pour ne pas marquer l'écran. */
    private fun move() {
        val v = view ?: return
        val b = box ?: return
        val dx = ((v.width - b.width) / 2 - Ui.u(40)).coerceAtLeast(0)
        val dy = ((v.height - b.height) / 2 - Ui.u(40)).coerceAtLeast(0)
        b.translationX = if (dx > 0) Random.nextInt(-dx, dx + 1).toFloat() else 0f
        b.translationY = if (dy > 0) Random.nextInt(-dy, dy + 1).toFloat() else 0f
    }

    private fun brightness(value: Float) {
        val lp = a.window.attributes
        lp.screenBrightness = value
        a.window.attributes = lp
    }

    companion object {
        /** Au repos, minutes sans geste avant l'écran noir avec l'heure ; 0 : jamais. */
        val REST_AFTER = listOf(0, 2, 5, 10, 15, 30)
        /** Pendant un programme, minutes sans geste avant de baisser la luminosité ; 0 : jamais. */
        val DIM_AFTER = listOf(0, 1, 2, 5, 10)
        /** Luminosités réduites proposées, en pour cent ; 0 : la plus faible que l'écran sait tenir. */
        val DIM_LEVELS = listOf(0, 10, 20, 35, 50)
        /** Pendant un programme, minutes sans geste avant l'écran noir avec l'étape et son décompte ; 0 : jamais. */
        val STANDBY_AFTER = listOf(0, 2, 5, 15, 30)

        /** Ce que la fenêtre demande à Android pour [percent] : au plus bas, comme la veille. */
        fun level(percent: Int): Float = if (percent <= 0) 0.02f else percent.coerceAtMost(100) / 100f
    }
}
