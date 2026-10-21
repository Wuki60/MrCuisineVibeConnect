package app.toque.ui

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.Prefs
import app.toque.ToqueApp
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.EventType
import app.toque.core.machine.JobSpec
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.PauseReason
import app.toque.core.machine.SafetyPolicy
import app.toque.core.machine.Snapshot
import app.toque.core.machine.Verdict
import app.toque.recipes.LidlSync
import app.toque.recipes.SyncService
import app.toque.ui.screens.HomeScreen
import app.toque.ui.screens.SettingsScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Écran d'accueil du robot (lanceur) : une seule activité, des écrans empilés. */
class MainActivity : Activity() {
    lateinit var app: ToqueApp
        private set
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var topBar: TopBar
    private lateinit var machineBar: MachineBar
    private val stack = ArrayList<Screen>()
    private val main = Handler(Looper.getMainLooper())
    private var unsubscribe: (() -> Unit)? = null
    private var unsubscribeEvents: (() -> Unit)? = null
    private var tone: ToneGenerator? = null
    private var toastView: TextView? = null
    private var lastKnobMs = 0L
    var knobListener: ((KeyEvent) -> Unit)? = null

    val prefs: Prefs get() = app.prefs
    val machine get() = app.machine
    val snapshot: Snapshot get() = app.machine.snapshot

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = ToqueApp.of(this)
        val dm = resources.displayMetrics
        Ui.init(dm.widthPixels, dm.heightPixels)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Ui.BG))

        root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        val column = Ui.vertical(this)
        topBar = TopBar(this)
        column.addView(topBar, Ui.lp(Ui.MATCH, Ui.u(60)))
        content = FrameLayout(this)
        column.addView(content, Ui.lp(Ui.MATCH, 0, 1f))
        machineBar = MachineBar(this)
        column.addView(machineBar, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(column, Ui.flp())
        overlay = FrameLayout(this)
        overlay.visibility = View.GONE
        root.addView(overlay, Ui.flp())
        setContentView(root)

        try {
            tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (_: RuntimeException) {
        }

        app.machine.connect()
        app.recipes.load()
        SyncService.schedule(this)
        push(HomeScreen(this))
        if (reopenSettings) {
            reopenSettings = false
            push(SettingsScreen(this))
        }
    }

    /** Applique la langue choisie dans les réglages : l'interface est reconstruite, on revient aux réglages. */
    fun applyLanguage() {
        I18n.lang = I18n.resolve(prefs.language, Locale.getDefault().language)
        reopenSettings = true
        recreate()
    }

    override fun onStart() {
        super.onStart()
        unsubscribe = app.machine.onSnapshot { s ->
            topBar.update(s)
            machineBar.update(s)
            stack.lastOrNull()?.onSnapshot(s)
        }
        unsubscribeEvents = app.machine.onEvent { e -> onMachineEvent(e) }
        topBar.startClock()
    }

    override fun onResume() {
        super.onResume()
        immersive()
        if (prefs.vendorSession) endVendorSession()
    }

    override fun onStop() {
        unsubscribe?.invoke()
        unsubscribeEvents?.invoke()
        topBar.stopClock()
        super.onStop()
    }

    override fun onDestroy() {
        tone?.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    // ------------------------------------------------------------------ navigation

    val current: Screen? get() = stack.lastOrNull()
    val depth: Int get() = stack.size

    fun push(s: Screen) {
        hideKeyboard()
        stack.lastOrNull()?.onHide()
        stack += s
        show(s)
    }

    /** Remplace l'écran courant (pas de retour vers lui). */
    fun replace(s: Screen) {
        hideKeyboard()
        stack.removeLastOrNull()?.onHide()
        stack += s
        show(s)
    }

    fun back() {
        if (stack.size <= 1) return
        val top = stack.last()
        if (!top.canLeave()) return
        hideKeyboard()
        top.onHide()
        stack.removeAt(stack.size - 1)
        show(stack.last())
    }

    fun home() {
        hideKeyboard()
        while (stack.size > 1) stack.removeAt(stack.size - 1).onHide()
        show(stack.first())
    }

    private fun show(s: Screen) {
        content.removeAllViews()
        val v = s.view
        (v.parent as? FrameLayout)?.removeView(v)
        content.addView(v, Ui.flp())
        topBar.setTitle(s.title, stack.size > 1)
        s.onShow()
        s.onSnapshot(snapshot)
    }

    fun refreshTitle() {
        current?.let { topBar.setTitle(it.title, stack.size > 1) }
    }

    override fun onBackPressed() {
        if (overlay.visibility == View.VISIBLE) {
            closeOverlay()
            return
        }
        back()
    }

    // ------------------------------------------------------------------ bouton rotatif

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        knobListener?.let { l ->
            if (event.action == KeyEvent.ACTION_DOWN) l(event)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && overlay.visibility != View.VISIBLE) {
            val k = event.keyCode
            val left = prefs.knobLeft.let { if (it >= 0) it == k else k == KeyEvent.KEYCODE_DPAD_LEFT }
            val right = prefs.knobRight.let { if (it >= 0) it == k else k == KeyEvent.KEYCODE_DPAD_RIGHT }
            val press = prefs.knobPress.let {
                if (it >= 0) it == k else k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
            }
            val s = current
            if (s != null && currentFocus !is android.widget.EditText) {
                if (left && s.onKnob(-1)) return true
                if (right && s.onKnob(1)) return true
                if (press && s.onKnobPress()) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            // Encodeur rotatif (AXIS_SCROLL) ou molette (AXIS_VSCROLL)
            val v = event.getAxisValue(AXIS_SCROLL).let { if (it == 0f) -event.getAxisValue(MotionEvent.AXIS_VSCROLL) else it }
            val now = System.currentTimeMillis()
            if (v != 0f && now - lastKnobMs > 40) {
                lastKnobMs = now
                if (current?.onKnob(if (v > 0) 1 else -1) == true) return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    // ------------------------------------------------------------------ évènements machine

    private fun onMachineEvent(e: MachineEvent) {
        current?.onEvent(e)
        when (e.type) {
            EventType.JOB_FINISHED -> chime(2)
            EventType.FAULT -> { chime(3); toast(e.message, Ui.DANGER) }
            EventType.LID_OPENED -> if (snapshot.busy || snapshot.state != null) toast(tr("Couvercle ouvert : pause"), Ui.WARN)
            EventType.JOB_PAUSED -> if (e.message != PauseReason.USER.label) toast(e.message, Ui.WARN)
            EventType.TURBO_END -> toast(e.message, Ui.TEXT2)
            else -> Unit
        }
    }

    fun chime(times: Int = 1) {
        if (!prefs.sound) return
        val t = tone ?: return
        for (i in 0 until times) main.postDelayed({
            try {
                t.startTone(if (times >= 3) ToneGenerator.TONE_PROP_NACK else ToneGenerator.TONE_PROP_ACK, 180)
            } catch (_: RuntimeException) {
            }
        }, i * 260L)
    }

    // ------------------------------------------------------------------ dialogues et messages

    fun toast(message: String, color: Int = Ui.TEXT) {
        toastView?.let { root.removeView(it) }
        val t = Ui.text(this, message, 19f, color, Ui.MEDIUM)
        t.setPadding(Ui.u(24), Ui.u(14), Ui.u(24), Ui.u(14))
        t.background = Ui.round(Ui.SURFACE3, 40f, Ui.STROKE, 1f)
        t.gravity = Gravity.CENTER
        t.maxWidth = Ui.u(820)
        val lp = Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = Ui.u(110)
        root.addView(t, lp)
        toastView = t
        t.alpha = 0f
        t.animate().alpha(1f).setDuration(150).start()
        main.postDelayed({
            if (toastView === t) {
                t.animate().alpha(0f).setDuration(200).withEndAction { root.removeView(t); if (toastView === t) toastView = null }.start()
            }
        }, 3_200)
    }

    fun refusal(v: Verdict) {
        if (!v.ok) toast(v.message, Ui.WARN)
    }

    /** Affiche une carte modale au centre de l'écran. */
    fun showOverlay(card: View, width: Int = 640, dismissOnScrim: Boolean = true) {
        hideKeyboard()
        overlay.removeAllViews()
        overlay.setBackgroundColor(Ui.SCRIM)
        overlay.isClickable = true
        overlay.setOnClickListener { if (dismissOnScrim) closeOverlay() }
        card.isClickable = true
        overlay.addView(card, Ui.flp(Ui.u(width), Ui.WRAP, Gravity.CENTER))
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(120).start()
    }

    fun closeOverlay() {
        overlay.visibility = View.GONE
        overlay.removeAllViews()
    }

    /** Dialogue de confirmation. */
    fun confirm(title: String, message: String, yes: String, no: String = tr("Annuler"),
                danger: Boolean = false, icon: Icon.Kind = Icon.Kind.WARNING, onYes: () -> Unit) {
        val card = Ui.card(this, 28f, Ui.SURFACE2)
        card.setPadding(Ui.u(32), Ui.u(28), Ui.u(32), Ui.u(26))
        val head = Ui.horizontal(this)
        head.addView(Icon(this, icon, if (danger) Ui.DANGER else Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(34), Ui.u(34)).apply { rightMargin = Ui.u(14) })
        head.addView(Ui.text(this, title, 26f, Ui.TEXT, Ui.MEDIUM))
        card.addView(head)
        card.addView(Ui.text(this, message, 19f, Ui.TEXT2).apply { setLineSpacing(0f, 1.2f) }, Ui.lp(t = 16, b = 26))
        val row = Ui.horizontal(this, Gravity.END or Gravity.CENTER_VERTICAL)
        row.addView(Ui.button(this, no, Ui.Style.GHOST, height = 60) { closeOverlay() })
        row.addView(Ui.spacer(this, w = 14))
        row.addView(Ui.button(this, yes, if (danger) Ui.Style.DANGER else Ui.Style.PRIMARY, height = 60) {
            closeOverlay()
            onYes()
        })
        card.addView(row)
        showOverlay(card, 680)
    }

    /**
     * Démarre un programme ; si le contenu est chaud, demande d'abord la confirmation
     * de mixage (comme l'appli d'origine, mais avant tout mouvement).
     */
    fun startJob(spec: JobSpec, done: (Verdict) -> Unit = {}) {
        machine.start(spec) { v ->
            if (!v.ok && v.has(SafetyPolicy.HOT_BLEND) && v.violations.size == 1) {
                confirm(
                    tr("Contenu chaud"),
                    tr("Le bol est à %d °C. Mixer chaud peut provoquer des projections.\n\n" +
                        "Vérifiez que le couvercle est verrouillé, que le gobelet est en place et que le bol n'est pas trop rempli. " +
                        "La vitesse montera progressivement.", snapshot.actualTempC),
                    tr("Mixer quand même"), icon = Icon.Kind.THERMO,
                ) {
                    machine.start(spec.copy(hotBlendConfirmed = true)) { v2 -> refusal(v2); done(v2) }
                }
            } else {
                refusal(v)
                done(v)
            }
        }
    }

    fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // ------------------------------------------------------------------ appli d'origine

    /** Retour depuis l'appli d'origine : on la coupe du moteur, on reprend la main, on importe. */
    private fun endVendorSession() {
        prefs.vendorSession = false
        toast(tr("Reprise de la main sur le robot…"))
        Thread {
            val sync = LidlSync(this, prefs, app.recipes, app.log)
            sync.forceStop()
            sync.setVendorHardware(false)
            main.post { machine.resumePort() }
            val r = sync.importNow()
            main.post { toast(r.message, if (r.ok) Ui.OK else Ui.WARN) }
        }.start()
    }

    fun openVendorApp() {
        if (snapshot.busy) {
            toast(tr("Arrêtez d'abord le programme en cours"), Ui.WARN)
            return
        }
        confirm(
            tr("Ouvrir l'appli d'origine"),
            tr("Toque libère le moteur et lance l'appli Monsieur Cuisine d'origine, qui se synchronise avec les serveurs Lidl comme d'habitude.\n\n" +
                "Revenez ensuite à Toque avec le bouton Accueil : les recettes seront importées automatiquement."),
            tr("Ouvrir"), icon = Icon.Kind.SYNC,
        ) {
            machine.suspendPort { v ->
                if (!v.ok) { refusal(v); return@suspendPort }
                Thread {
                    val sync = LidlSync(this, prefs, app.recipes, app.log)
                    val enabled = sync.setVendorHardware(true)
                    val launch = sync.components().launch
                    main.post {
                        if (!enabled || launch == null) {
                            toast(tr("Appli d'origine indisponible (root ?)"), Ui.WARN)
                            machine.resumePort()
                        } else {
                            prefs.vendorSession = true
                            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }.start()
            }
        }
    }

    companion object {
        private var reopenSettings = false

        /** MotionEvent.AXIS_SCROLL (API 26), valeur fixe pour Android 6. */
        private const val AXIS_SCROLL = 26
        val clockFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
        fun now(): String = clockFormat.format(Date())
    }
}
