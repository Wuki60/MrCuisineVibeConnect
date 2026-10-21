package app.toque.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.toque.Prefs
import app.toque.core.i18n.tr
import app.toque.core.machine.JobState
import app.toque.core.machine.LinkState
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Mcc

/** Écran de l'appli (vue construite une fois, réutilisée). */
abstract class Screen(val a: MainActivity) {
    abstract val title: String
    val view: View by lazy { build() }
    val c: Context get() = a

    protected abstract fun build(): View
    open fun onShow() {}
    open fun onHide() {}
    open fun onSnapshot(s: Snapshot) {}
    open fun onEvent(e: MachineEvent) {}
    open fun onKnob(delta: Int): Boolean = false
    open fun onKnobPress(): Boolean = false
    open fun canLeave(): Boolean = true
}

/** Barre supérieure : retour, titre, état de la base, heure. */
class TopBar(private val a: MainActivity) : LinearLayout(a) {
    private val back = Icon(a, Icon.Kind.BACK, Ui.TEXT)
    private val titleView = Ui.ellipsize(Ui.text(a, "", 24f, Ui.TEXT, Ui.MEDIUM))
    private val temp = Ui.chip(a, "", Icon.Kind.THERMO, Ui.TEXT2)
    private val lid = Ui.chip(a, tr("Couvercle ouvert"), Icon.Kind.LID, Ui.WARN, 0x33FFC14D)
    private val link = Ui.chip(a, "", Icon.Kind.LINK, Ui.TEXT2)
    private val clock = Ui.text(a, "", 22f, Ui.TEXT, Ui.LIGHT)
    private val home = Icon(a, Icon.Kind.HOME, Ui.TEXT2)
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            clock.text = MainActivity.now()
            handler.postDelayed(this, 10_000)
        }
    }
    private var adminTaps = 0
    private var adminFirst = 0L

    init {
        orientation = HORIZONTAL
        setGravity(Gravity.CENTER_VERTICAL)
        setPadding(Ui.u(10), 0, Ui.u(18), 0)
        setBackgroundColor(Ui.BG)
        back.setPadding(Ui.u(10), Ui.u(10), Ui.u(10), Ui.u(10))
        back.background = Ui.pressable(Ui.round(0, 30f), 30f)
        back.setOnClickListener { a.back() }
        addView(back, LayoutParams(Ui.u(52), Ui.u(52)))
        addView(titleView, LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.u(6) })
        addView(lid, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        addView(temp, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        addView(link, Ui.lp(Ui.WRAP, Ui.WRAP, r = 16))
        addView(clock, Ui.lp(Ui.WRAP, Ui.WRAP, r = 6))
        home.setPadding(Ui.u(10), Ui.u(10), Ui.u(10), Ui.u(10))
        home.background = Ui.pressable(Ui.round(0, 30f), 30f)
        home.setOnClickListener { a.home() }
        addView(home, LayoutParams(Ui.u(52), Ui.u(52)))
        lid.visibility = View.GONE
        temp.visibility = View.GONE
        // Accès caché aux réglages Android : 5 appuis rapides sur l'heure.
        clock.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - adminFirst > 3_000) { adminFirst = now; adminTaps = 0 }
            if (++adminTaps >= 5) {
                adminTaps = 0
                try {
                    a.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                }
            }
        }
    }

    fun startClock() {
        handler.removeCallbacks(tick)
        tick.run()
    }

    fun stopClock() = handler.removeCallbacks(tick)

    fun setTitle(t: String, canGoBack: Boolean) {
        titleView.text = t
        back.visibility = if (canGoBack) View.VISIBLE else View.INVISIBLE
        home.visibility = if (canGoBack) View.VISIBLE else View.GONE
    }

    fun update(s: Snapshot) {
        lid.visibility = if (s.hasSample && s.lidOpen) View.VISIBLE else View.GONE
        if (s.hasSample) {
            temp.visibility = View.VISIBLE
            Ui.chipText(temp).text = "${s.actualTempC} °C"
            val col = if (s.actualTempC >= 40) Ui.tempColor(s.actualTempC) else Ui.TEXT2
            Ui.chipText(temp).setTextColor(col)
            (temp.getChildAt(0) as Icon).color = col
        } else temp.visibility = View.GONE
        val demo = a.prefs.mode == Prefs.Mode.DEMO
        val (label, color) = when {
            s.hasFault -> tr("Alerte") to Ui.DANGER
            s.link == LinkState.READY -> (if (demo) tr("Démo") else tr("Prête")) to (if (demo) Ui.COOL else Ui.OK)
            s.link == LinkState.ARMING -> tr("Connexion…") to Ui.WARN
            s.link == LinkState.LOST -> tr("Liaison perdue") to Ui.DANGER
            else -> tr("Base absente") to Ui.TEXT3
        }
        Ui.chipText(link).text = label
        Ui.chipText(link).setTextColor(color)
        (link.getChildAt(0) as Icon).color = color
    }
}

/** Fine barre de progression arrondie. */
class ProgressLine(c: Context) : View(c) {
    var progress = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var color = Ui.ACCENT
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        paint.color = Ui.SURFACE3
        r.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(r, h / 2, h / 2, paint)
        paint.color = color
        r.set(0f, 0f, maxOf(h, width * progress), h)
        if (progress > 0f) canvas.drawRoundRect(r, h / 2, h / 2, paint)
    }
}

/**
 * Barre machine persistante : visible dès qu'un programme tourne, est en pause, vient de
 * finir, ou qu'une alerte est active — sur tous les écrans.
 */
class MachineBar(private val a: MainActivity) : FrameLayout(a) {
    private val jobRow = Ui.horizontal(a)
    private val faultRow = Ui.horizontal(a)
    private val label = Ui.ellipsize(Ui.text(a, "", 20f, Ui.TEXT, Ui.MEDIUM))
    private val state = Ui.ellipsize(Ui.text(a, "", 16f, Ui.TEXT2))
    private val time = Ui.text(a, "", 40f, Ui.TEXT, Ui.LIGHT)
    private val progress = ProgressLine(a)
    private val chips = Ui.horizontal(a)
    private val speedChip = Ui.chip(a, "", Icon.Kind.SPEED, Ui.TEXT)
    private val tempChip = Ui.chip(a, "", Icon.Kind.THERMO, Ui.TEXT)
    private val dirChip = Ui.chip(a, tr("Inverse"), Icon.Kind.REVERSE, Ui.COOL)
    private val pauseBtn: LinearLayout
    private val stopBtn: LinearLayout
    private val okBtn: LinearLayout
    private val faultText = Ui.text(a, "", 19f, Ui.TEXT, Ui.MEDIUM)
    private var last: Snapshot? = null

    init {
        setBackgroundColor(Ui.SURFACE)
        // Programme
        jobRow.setPadding(Ui.u(22), Ui.u(12), Ui.u(18), Ui.u(12))
        val left = Ui.vertical(a)
        left.addView(label)
        left.addView(state, Ui.lp(t = 4))
        jobRow.addView(left, LinearLayout.LayoutParams(Ui.u(230), Ui.WRAP))
        val mid = Ui.vertical(a, Gravity.CENTER_HORIZONTAL)
        mid.addView(time, Ui.lp(Ui.WRAP, Ui.WRAP))
        mid.addView(progress, Ui.lp(Ui.MATCH, Ui.u(6), t = 6))
        jobRow.addView(mid, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.u(10); rightMargin = Ui.u(16) })
        chips.addView(speedChip, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        chips.addView(tempChip, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        chips.addView(dirChip, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        jobRow.addView(chips)
        pauseBtn = Ui.button(a, "", Ui.Style.SECONDARY, Icon.Kind.PAUSE, height = 64) { onPause() }
        stopBtn = Ui.button(a, "", Ui.Style.DANGER, Icon.Kind.STOP, height = 64) {
            a.machine.stop()
        }
        okBtn = Ui.button(a, "OK", Ui.Style.SECONDARY, Icon.Kind.CHECK, height = 64) { a.machine.clearFinished() }
        jobRow.addView(pauseBtn, Ui.lp(Ui.u(84), Ui.WRAP, l = 6))
        jobRow.addView(stopBtn, Ui.lp(Ui.u(84), Ui.WRAP, l = 10))
        jobRow.addView(okBtn, Ui.lp(Ui.WRAP, Ui.WRAP, l = 10))
        addView(jobRow, Ui.flp(Ui.MATCH, Ui.WRAP))
        // Alerte
        faultRow.setPadding(Ui.u(22), Ui.u(14), Ui.u(18), Ui.u(14))
        faultRow.setBackgroundColor(0xFF3A1416.toInt())
        faultRow.addView(Icon(a, Icon.Kind.WARNING, Ui.DANGER), LinearLayout.LayoutParams(Ui.u(34), Ui.u(34)).apply { rightMargin = Ui.u(14) })
        faultRow.addView(faultText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        faultRow.addView(Ui.button(a, tr("Acquitter"), Ui.Style.SECONDARY, Icon.Kind.CHECK, height = 60) {
            a.machine.acknowledge { v -> a.refusal(v) }
        })
        addView(faultRow, Ui.flp(Ui.MATCH, Ui.WRAP))
        visibility = View.GONE
    }

    private fun onPause() {
        val s = last ?: return
        if (s.state == JobState.PAUSED) {
            a.machine.resume(s.hotBlendConfirmed) { v ->
                if (!v.ok && v.has(app.toque.core.machine.SafetyPolicy.HOT_BLEND)) {
                    a.confirm(tr("Contenu chaud"), tr("Le bol est à %d °C. Reprendre le mixage ?", s.actualTempC), tr("Reprendre"), icon = Icon.Kind.THERMO) {
                        a.machine.resume(true) { a.refusal(it) }
                    }
                } else a.refusal(v)
            }
        } else a.machine.pause { a.refusal(it) }
    }

    fun update(s: Snapshot) {
        last = s
        val st = s.state
        val showJob = s.jobActive || s.turboActive
        visibility = if (showJob || s.hasFault) View.VISIBLE else View.GONE
        faultRow.visibility = if (s.hasFault) View.VISIBLE else View.GONE
        jobRow.visibility = if (!s.hasFault && showJob) View.VISIBLE else View.GONE
        if (s.hasFault) {
            faultText.text = s.faultMessage
            return
        }
        if (!showJob) return
        if (s.turboActive) {
            label.text = "Turbo"
            state.text = tr("Relâchez pour arrêter")
            time.text = "●"
            progress.progress = 0f
            setButtons(running = false, ended = false, turbo = true)
            return
        }
        label.text = s.jobLabel
        state.text = when (st) {
            JobState.STARTING -> if (s.elapsedMs == 0L) tr("Démarrage…") else tr("Reprise…")
            JobState.RUNNING -> if (s.waitingStop) tr("Changement de sens…") else if (s.phaseCount > 1) tr("En cours · phase %d/%d", s.phaseIndex + 1, s.phaseCount) else tr("En cours")
            JobState.STOPPING -> tr("Arrêt du moteur…")
            JobState.PAUSED -> when (s.pauseReason) {
                "LID" -> tr("Pause : couvercle ouvert")
                "SAFETY" -> tr("Interrompu par sécurité")
                else -> tr("En pause")
            }
            JobState.FINISHED -> tr("Terminé")
            JobState.ABORTED -> s.endReason
            null -> ""
        }
        state.setTextColor(when (st) {
            JobState.PAUSED -> Ui.WARN
            JobState.FINISHED -> Ui.OK
            JobState.ABORTED -> Ui.DANGER
            else -> Ui.TEXT2
        })
        time.text = if (st == JobState.FINISHED) "00:00" else Ui.clock(s.remainingMs)
        progress.progress = if (s.totalMs > 0) s.elapsedMs.toFloat() / s.totalMs else 0f
        progress.color = if (s.jobTempLevel > 0) Ui.ACCENT else Ui.COOL
        Ui.chipText(speedChip).text = if (s.jobKind == "STEAM") tr("Vapeur") else tr("Vitesse %d", s.jobSpeed)
        tempChip.visibility = if (s.jobTempLevel > 0) View.VISIBLE else View.GONE
        Ui.chipText(tempChip).text = "${Mcc.celsiusForLevel(s.jobTempLevel)} °C"
        dirChip.visibility = if (s.jobReverse && s.jobKind == "MANUAL") View.VISIBLE else View.GONE
        val ended = st == JobState.FINISHED || st == JobState.ABORTED
        setButtons(running = st != JobState.PAUSED, ended = ended, turbo = false)
    }

    private fun setButtons(running: Boolean, ended: Boolean, turbo: Boolean) {
        pauseBtn.visibility = if (ended || turbo) View.GONE else View.VISIBLE
        stopBtn.visibility = if (ended) View.GONE else View.VISIBLE
        okBtn.visibility = if (ended) View.VISIBLE else View.GONE
        (pauseBtn.getChildAt(0) as Icon).set(if (running) Icon.Kind.PAUSE else Icon.Kind.PLAY)
        chips.visibility = if (turbo) View.GONE else View.VISIBLE
    }

}
