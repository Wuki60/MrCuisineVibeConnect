package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.machine.Accessory
import app.toque.core.machine.EventType
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobSpec
import app.toque.core.machine.JobState
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.SafetyPolicy
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Mcc
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.StepProgram
import app.toque.ui.HoldButton
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.ProgressLine
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Cuisine pas à pas. Chaque étape « machine » se lance d'un appui ; à la fin du programme,
 * l'étape suivante s'affiche toute seule. Les réglages qui se suivent sans geste à faire entre
 * eux partent ensemble, en un seul programme : la machine passe de l'un à l'autre sans arrêter
 * la lame (sauf changement de sens). La progression est mémorisée (reprise après coupure).
 */
class CookScreen(a: MainActivity, private val recipe: Recipe, startStep: Int) : Screen(a) {
    override val title = recipe.title
    private var index = startStep.coerceIn(0, maxOf(0, recipe.steps.size - 1))
    private val reviewed = HashSet<Int>()
    private val done = HashSet<Int>()
    private var launchedStep = -1
    /** Étapes couvertes par le programme lancé (plusieurs quand elles sont enchaînées). */
    private var launchedCount = 1
    /** Dernière phase de la série vue à l'écran (on ne suit que les changements de phase). */
    private var seenPhase = -1
    /** « Mixer quand même » à chaud déjà accepté pendant cette recette : pas de nouvelle question. */
    private var hotBlendOk = false
    /** Message fixe de l'étape (vérification, refus), réaffiché hors cuisson. */
    private var panelInfo = ""

    private lateinit var segments: LinearLayout
    private lateinit var counter: TextView
    private lateinit var text: TextView
    private lateinit var weighBox: LinearLayout
    private lateinit var weighLabel: TextView
    private lateinit var weighValue: TextView
    private lateinit var weighBar: ProgressLine
    private lateinit var panel: LinearLayout
    private lateinit var prevBtn: LinearLayout
    private lateinit var nextBtn: LinearLayout
    private lateinit var nextLabel: TextView

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(4), Ui.u(24), Ui.u(18))
        segments = Ui.horizontal(c)
        root.addView(segments, Ui.lp(Ui.MATCH, Ui.u(6), b = 14))

        val body = Ui.horizontal(c, Gravity.TOP)
        val left = Ui.vertical(c)
        counter = Ui.text(c, "", 17f, Ui.TEXT2, Ui.MEDIUM).apply { letterSpacing = 0.06f }
        left.addView(counter)
        text = Ui.text(c, "", 31f, Ui.TEXT, Ui.LIGHT).apply { setLineSpacing(0f, 1.18f) }
        left.addView(Ui.scroll(c, text), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(12) })

        weighBox = Ui.vertical(c)
        weighBox.background = Ui.round(Ui.SURFACE, 22f)
        weighBox.setPadding(Ui.u(20), Ui.u(14), Ui.u(16), Ui.u(14))
        val wRow = Ui.horizontal(c)
        wRow.addView(Icon(c, Icon.Kind.SCALE, Ui.COOL), LinearLayout.LayoutParams(Ui.u(28), Ui.u(28)).apply { rightMargin = Ui.u(12) })
        weighLabel = Ui.ellipsize(Ui.text(c, "", 19f, Ui.TEXT, Ui.MEDIUM))
        wRow.addView(weighLabel, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        weighValue = Ui.text(c, "", 30f, Ui.TEXT, Ui.LIGHT)
        wRow.addView(weighValue, Ui.lp(Ui.WRAP, Ui.WRAP, r = 14))
        wRow.addView(Ui.button(c, tr("Tare"), Ui.Style.SECONDARY, height = 52) { a.machine.tare() })
        weighBox.addView(wRow)
        weighBar = ProgressLine(c).apply { color = Ui.COOL }
        weighBox.addView(weighBar, Ui.lp(Ui.MATCH, Ui.u(8), t = 10))
        left.addView(weighBox, Ui.lp(t = 12))
        body.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { rightMargin = Ui.u(22) })

        panel = Ui.vertical(c)
        panel.background = Ui.round(Ui.SURFACE, 26f)
        panel.setPadding(Ui.u(22), Ui.u(20), Ui.u(22), Ui.u(20))
        body.addView(panel, LinearLayout.LayoutParams(Ui.u(340), Ui.MATCH))
        root.addView(body, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        val nav = Ui.horizontal(c)
        prevBtn = Ui.button(c, tr("Précédent"), Ui.Style.GHOST, Icon.Kind.BACK, height = 64) { go(index - 1) }
        nav.addView(prevBtn)
        nav.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))
        nextBtn = Ui.button(c, tr("Suivant"), Ui.Style.SECONDARY, Icon.Kind.CHEVRON, height = 64) { next() }
        nextLabel = nextBtn.getChildAt(1) as TextView
        nav.addView(nextBtn)
        root.addView(nav, Ui.lp(t = 14))
        render()
        return root
    }

    private fun next() {
        if (index >= recipe.steps.size - 1) {
            a.prefs.guidedRecipe = ""
            a.chime(1)
            a.toast(tr("Bon appétit !"), Ui.OK)
            a.back()
        } else go(index + 1)
    }

    private fun go(i: Int) {
        if (i < 0 || i >= recipe.steps.size) return
        index = i
        a.prefs.guidedRecipe = recipe.id
        a.prefs.guidedStep = index
        render()
    }

    private fun render() {
        val n = recipe.steps.size
        segments.removeAllViews()
        for (i in 0 until n) {
            val v = View(c)
            v.background = Ui.round(if (i < index) Ui.ACCENT else if (i == index) Ui.TEXT else Ui.SURFACE3, 4f)
            segments.addView(v, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (i < n - 1) rightMargin = Ui.u(6) })
        }
        if (n == 0) {
            counter.text = ""
            text.text = tr("Cette recette ne contient pas d'étapes.")
            weighBox.visibility = View.GONE
            panel.removeAllViews()
            return
        }
        val step = recipe.steps[index]
        counter.text = tr("ÉTAPE %d SUR %d", index + 1, n)
        text.text = step.text.ifBlank { step.machine?.summary ?: "" }
        val w = step.weigh
        weighBox.visibility = if (w != null) View.VISIBLE else View.GONE
        if (w != null) weighLabel.text = tr("Pesez %d g", w.grams) + if (w.label.isNotBlank()) " · ${w.label}" else ""
        Ui.setButtonEnabled(prevBtn, index > 0)
        nextLabel.text = if (index == n - 1) tr("Terminer") else tr("Suivant")
        buildPanel()
        onSnapshot(a.snapshot)
    }

    private lateinit var startBtn: View
    private var turboBtn: HoldButton? = null
    private var reviewBtn: View? = null
    private var panelState: TextView? = null

    private fun buildPanel() {
        panel.removeAllViews()
        turboBtn = null
        reviewBtn = null
        val m = recipe.steps[index].machine
        if (m == null) {
            val box = Ui.vertical(c, Gravity.CENTER)
            box.addView(Icon(c, Icon.Kind.CHECK, Ui.TEXT3), LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)))
            box.addView(Ui.text(c, tr("Pas de réglage machine pour cette étape"), 18f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, Ui.lp(t = 12))
            panel.addView(box, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
            startBtn = Ui.button(c, tr("Étape suivante"), Ui.Style.SECONDARY, Icon.Kind.CHEVRON, height = 70) { next() }
            panel.addView(startBtn)
            return
        }
        val head = Ui.horizontal(c)
        val badge = FrameLayout(c)
        badge.background = Ui.round(Ui.ACCENT_DIM, 24f)
        val kindIcon = when (m.program) {
            StepProgram.KNEAD -> Icon.Kind.KNEAD
            StepProgram.STEAM -> Icon.Kind.STEAM
            StepProgram.TOAST -> Icon.Kind.FLAME
            StepProgram.TURBO -> Icon.Kind.BOLT
            StepProgram.MANUAL -> Icon.Kind.POT
        }
        badge.addView(Icon(c, kindIcon, Ui.ACCENT), Ui.flp(Ui.u(28), Ui.u(28), Gravity.CENTER))
        head.addView(badge, LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)).apply { rightMargin = Ui.u(12) })
        head.addView(Ui.text(c, when (m.program) {
            StepProgram.MANUAL -> tr("Réglage machine")
            StepProgram.KNEAD -> JobKind.KNEAD.label
            StepProgram.STEAM -> JobKind.STEAM.label
            StepProgram.TOAST -> JobKind.TOAST.label
            StepProgram.TURBO -> JobKind.TURBO.label
        }, 22f, Ui.TEXT, Ui.MEDIUM))
        panel.addView(head)

        val grid = Ui.vertical(c)
        fun line(icon: Icon.Kind, label: String, value: String, color: Int = Ui.TEXT) {
            val r = Ui.horizontal(c)
            r.addView(Icon(c, icon, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)).apply { rightMargin = Ui.u(10) })
            r.addView(Ui.text(c, label, 17f, Ui.TEXT2), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            r.addView(Ui.text(c, value, 24f, color, Ui.LIGHT))
            grid.addView(r, Ui.lp(t = 10))
        }
        if (m.program == StepProgram.TURBO) {
            line(Icon.Kind.BOLT, tr("Impulsions"), if (m.pulses > 0) "× ${m.pulses}" else tr("au besoin"))
        } else {
            line(Icon.Kind.CLOCK, tr("Durée"), Ui.duration(m.seconds))
            if (m.program == StepProgram.MANUAL) {
                line(Icon.Kind.THERMO, tr("Température"), if (m.tempC > 0) "${Mcc.celsiusForLevel(m.temperatureLevel)} °C" else "—",
                    if (m.tempC > 0) Ui.tempColor(m.tempC) else Ui.TEXT2)
                line(Icon.Kind.SPEED, tr("Vitesse"), "${m.speed}")
                if (m.reverse) line(Icon.Kind.REVERSE, tr("Sens"), tr("Inverse"), Ui.COOL)
                if (m.accessory == Accessory.WHISK) line(Icon.Kind.WHISK, tr("Accessoire"), Accessory.WHISK.label, Ui.WARN)
            }
        }
        panel.addView(grid, Ui.lp(t = 6))
        val chained = if (a.prefs.chainSteps) chainLength(index) else 0
        if (chained > 0) {
            val next = (index + 1..index + chained).map { recipe.steps[it].machine!!.summary }
            val list = next.take(4).joinToString("\n") { "• $it" } +
                if (next.size > 4) "\n" + tr("… et %d autres", next.size - 4) else ""
            panel.addView(Ui.text(c, tr("Puis, enchaînés automatiquement :") + "\n" + list, 15f, Ui.TEXT2)
                .apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 10))
        }
        panelState = Ui.text(c, "", 16f, Ui.WARN).apply { setLineSpacing(0f, 1.1f) }
        panel.addView(panelState, Ui.lp(t = 12))
        panel.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))

        val violations = m.violations()
        val step = recipe.steps[index]
        panelInfo = ""
        if (violations.isNotEmpty()) {
            panelInfo = tr("Réglage refusé par la sécurité : %s. Utilisez la cuisson manuelle.", violations.first().message)
        }
        if (step.needsReview && index !in reviewed && violations.isEmpty()) {
            val rb = Ui.button(c, tr("J'ai vérifié ce réglage"), Ui.Style.GHOST, Icon.Kind.CHECK, height = 56, textSize = 17f) {
                reviewed += index
                buildPanel()
                onSnapshot(a.snapshot)
            }
            reviewBtn = rb
            panel.addView(rb, Ui.lp(b = 10))
            panelInfo = tr("Réglage importé : vérifiez-le avant de lancer.")
        }
        panelState?.text = panelInfo
        if (m.program == StepProgram.TURBO) {
            val t = HoldButton(c, tr("Maintenir pour Turbo"), onDown = {
                a.machine.turboDown(m.accessory) { v -> if (!v.ok) a.refusal(v) }
            }, onUp = { a.machine.turboUp() })
            turboBtn = t
            startBtn = t
            panel.addView(t, Ui.lp(Ui.MATCH, Ui.u(70)))
        } else {
            startBtn = Ui.button(c, tr("Lancer"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 74, textSize = 23f) { launch() }
            panel.addView(startBtn)
        }
        if (m.program == StepProgram.MANUAL) {
            panel.addView(Ui.button(c, tr("Ajuster à la main"), Ui.Style.GHOST, height = 50, textSize = 16f) {
                a.push(ManualScreen(a, m))
            }, Ui.lp(t = 8))
        }
        val blocked = violations.isNotEmpty() || (step.needsReview && index !in reviewed)
        Ui.setButtonEnabled(startBtn, !blocked)
    }

    private fun launch() {
        val first = index
        val m = recipe.steps[first].machine ?: return
        val series = series(first)
        val job = series ?: m.toJob(tr("Étape %d · %s", first + 1, recipe.title), hotBlendConfirmed = hotBlendOk) ?: return
        val count = series?.phases?.size ?: 1
        a.prefs.guidedRecipe = recipe.id
        a.prefs.guidedStep = first
        a.startJob(job, onHotConfirmed = { hotBlendOk = true }) { v ->
            if (v.ok) {
                launchedStep = first
                launchedCount = count
                seenPhase = 0
            }
        }
    }

    /**
     * Réglages enchaînés à partir de [first], réunis en un seul programme (une phase par étape) :
     * la machine passe directement au réglage suivant, sans arrêt ni nouvel appui. Uniquement
     * des réglages manuels avec le même accessoire ; sinon les étapes s'enchaînent une à une.
     */
    private fun series(first: Int): JobSpec? {
        if (!a.prefs.chainSteps) return null
        val n = chainLength(first)
        if (n == 0) return null
        val machines = (first..first + n).map { recipe.steps[it].machine ?: return null }
        if (machines.any { it.program != StepProgram.MANUAL || it.accessory != machines[0].accessory }) return null
        val phases = machines.map { it.toJob("", hotBlendOk)?.phases?.singleOrNull() ?: return null }
        val spec = JobSpec(
            kind = JobKind.MANUAL,
            label = tr("Étapes %d à %d · %s", first + 1, first + n + 1, recipe.title),
            phases = phases,
            totalMs = phases.sumOf { it.durationMs },
            cyclic = false,
            accessory = machines[0].accessory,
            hotBlendConfirmed = hotBlendOk,
        )
        return spec.takeIf { SafetyPolicy.checkJob(it, null).isEmpty() }
    }

    /**
     * L'étape [i] continue directement la précédente : un réglage machine seul (pas de texte
     * à suivre, pas de pesée), après une autre étape machine. Le Turbo reste toujours à l'appui.
     */
    private fun continues(i: Int): Boolean {
        val s = recipe.steps.getOrNull(i) ?: return false
        val m = s.machine ?: return false
        val prev = recipe.steps.getOrNull(i - 1)?.machine ?: return false
        return m.program != StepProgram.TURBO && prev.program != StepProgram.TURBO && s.weigh == null &&
            (s.text.isBlank() || s.text == m.summary) && m.violations().isEmpty()
    }

    /** Nombre d'étapes qui s'enchaîneront après l'étape [i]. */
    private fun chainLength(i: Int): Int {
        var n = 0
        while (continues(i + n + 1)) n++
        return n
    }

    override fun onSnapshot(s: Snapshot) {
        val w = recipe.steps.getOrNull(index)?.weigh
        if (w != null) {
            weighValue.text = if (s.hasSample) "${s.weightG} g" else "—"
            weighBar.progress = if (w.grams > 0) s.weightG.toFloat() / w.grams else 0f
            weighBar.color = if (s.weightG > w.grams * 1.05f) Ui.WARN else Ui.COOL
        }
        val running = launchedStep >= 0 && s.jobActive
        if (running && launchedCount > 1 && s.phaseIndex != seenPhase &&
            s.state != JobState.FINISHED && s.state != JobState.ABORTED) {
            // Série en cours : à chaque nouveau réglage, l'écran passe à l'étape correspondante.
            seenPhase = s.phaseIndex
            val current = launchedStep + s.phaseIndex.coerceIn(0, launchedCount - 1)
            for (k in launchedStep until current) done += k
            reviewed += current
            if (current != index) {
                go(current)
                return
            }
        }
        val m = recipe.steps.getOrNull(index)?.machine ?: return
        val mine = running && index >= launchedStep && index < launchedStep + launchedCount
        val ps = panelState
        if (m.program != StepProgram.TURBO && ::startBtn.isInitialized) {
            startBtn.visibility = if (mine && s.state != JobState.FINISHED && s.state != JobState.ABORTED) View.GONE else View.VISIBLE
            if (s.busy && !mine) Ui.setButtonEnabled(startBtn, false)
            else if (reviewBtn == null && m.violations().isEmpty()) Ui.setButtonEnabled(startBtn, !s.busy)
        }
        if (!mine && ps != null) {
            if (index in done) {
                ps.setTextColor(Ui.OK)
                ps.text = tr("Étape terminée")
            } else {
                ps.setTextColor(Ui.WARN)
                ps.text = panelInfo
            }
        }
        if (mine && ps != null) {
            ps.setTextColor(Ui.TEXT2)
            ps.text = when (s.state) {
                JobState.RUNNING, JobState.STARTING -> tr("En cours… reste %s", Ui.clock(s.remainingMs))
                JobState.STOPPING -> tr("Arrêt du moteur…")
                JobState.PAUSED -> tr("En pause")
                JobState.FINISHED -> tr("Terminé")
                JobState.ABORTED -> s.endReason
                null -> ""
            }
        }
        turboBtn?.let { t ->
            t.isEnabled = !s.busy || s.turboActive
            t.alpha = if (t.isEnabled) 1f else 0.35f
        }
    }

    override fun onEvent(e: MachineEvent) {
        if (e.type == EventType.JOB_FINISHED && launchedStep >= 0 && index >= launchedStep && index < launchedStep + launchedCount) {
            val finished = launchedStep + launchedCount - 1
            for (k in launchedStep..finished) done += k
            launchedStep = -1
            launchedCount = 1
            if (index != finished) go(finished)
            // Étapes non réunissables en un programme (vapeur puis réglage…) : la suivante part seule.
            val chain = a.prefs.chainSteps && continues(finished + 1)
            view.postDelayed({
                if (index == finished) {
                    a.machine.clearFinished()
                    if (index < recipe.steps.size - 1) {
                        // Étape enchaînée : le réglage a été vu avec la première étape de la série.
                        if (chain) reviewed += finished + 1
                        go(finished + 1)
                        if (chain) launch()
                    }
                }
            }, if (chain) 400L else 1_500L)
        }
    }

    override fun onKnob(delta: Int): Boolean {
        go(index + delta)
        return true
    }

    override fun onKnobPress(): Boolean {
        val m = recipe.steps.getOrNull(index)?.machine
        if (m == null) next() else if (m.program != StepProgram.TURBO && startBtn.isEnabled && startBtn.visibility == View.VISIBLE) launch()
        return true
    }
}
