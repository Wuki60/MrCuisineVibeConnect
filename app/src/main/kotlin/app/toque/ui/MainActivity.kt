package app.toque.ui

import app.toque.core.companion.CommandResult
import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.Prefs
import app.toque.TimeSync
import app.toque.ToqueApp
import app.toque.companion.RobotCompanion
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.Accessory
import app.toque.core.machine.EventType
import app.toque.core.machine.Limits
import app.toque.core.machine.JobSpec
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.PauseReason
import app.toque.core.machine.SafetyPolicy
import app.toque.core.machine.Snapshot
import app.toque.core.machine.Verdict
import app.toque.core.ring.RingColour
import app.toque.update.Updater
import app.toque.core.robot.DeviceFacts
import app.toque.core.robot.RobotDetector
import app.toque.core.robot.RobotProfile
import app.toque.core.robot.RobotProfiles
import app.toque.recipes.LidlSync
import app.toque.recipes.SyncService
import app.toque.ui.screens.CookScreen
import app.toque.ui.screens.HomeScreen
import app.toque.ui.screens.KnobSetupScreen
import app.toque.ui.screens.ManualScreen
import app.toque.ui.screens.SettingsScreen
import app.toque.ui.screens.SiteLinkScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Écran d'accueil du robot (lanceur) : une seule activité, des écrans empilés. */
class MainActivity : Activity() {
    lateinit var app: ToqueApp
        private set
    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var topBar: TopBar
    private lateinit var machineBar: MachineBar
    private val visibleFrame = Rect()
    private val rootLoc = IntArray(2)
    private val imm by lazy { getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager }
    /** Hauteur du clavier virtuel à l'écran (0 quand il est fermé). */
    var keyboardHeight = 0
        private set
    /** Marge basse à appliquer dans [root] au-dessus du clavier (0 si [adjustResize] a déjà réduit [root]). */
    var keyboardMargin = 0
        private set
    /** Appelé quand le clavier s'ouvre ou se ferme pendant qu'une fenêtre modale est ouverte. */
    var overlayOnKeyboard: ((Boolean) -> Unit)? = null
    /** Bas de la partie de la fenêtre que le clavier laisse visible (les essais, sans clavier, le remplacent). */
    var visibleBottom: () -> Int = {
        window.decorView.getWindowVisibleDisplayFrame(visibleFrame)
        visibleFrame.bottom
    }
    private val stack = ArrayList<Screen>()
    private val main = Handler(Looper.getMainLooper())
    private var unsubscribe: (() -> Unit)? = null
    private var unsubscribeEvents: (() -> Unit)? = null
    private var unsubscribeCompanion: (() -> Unit)? = null
    private var unsubscribeRemoteStart: (() -> Unit)? = null
    /** Recette reçue déjà annoncée (heure d'envoi), pour ne la signaler qu'une fois. */
    private var announcedSentAt = 0L
    private var tone: ToneGenerator? = null
    private var toastView: TextView? = null
    private var lastKnobMs = 0L
    private lateinit var standby: Standby
    lateinit var splash: Splash
        private set
    lateinit var updates: UpdateAlert
        private set
    private var swallowTouch = false
    var knobListener: ((KeyEvent) -> Unit)? = null
    private var knobLongHandled = false
    private var lastKnobPressTime = 0L
    private val knobLongPressRunnable = Runnable {
        knobLongHandled = true
        (current as? app.toque.ui.screens.ProgramsScreen)?.onKnobLongPress()
    }
    private lateinit var timerAlarm: TimerAlarm
    private var unsubscribeTimers: (() -> Unit)? = null
    private var activityStopped = false
    /** La fenêtre ouverte laisse la barre machine (et son bouton Stop) hors du voile. */
    private var overlaySparesBar = false

    /** Molette d'une fenêtre ouverte : tourner ([turn]) et appuyer ([press]). */
    class OverlayKnob(val turn: (Int) -> Unit, val press: () -> Unit)

    /** Ce que fait la molette dans la fenêtre ouverte ; effacé quand elle se ferme. Sans lui, la molette n'y fait rien. */
    var overlayKnob: OverlayKnob? = null

    val prefs: Prefs get() = app.prefs
    val machine get() = app.machine
    val snapshot: Snapshot get() = app.machine.snapshot
    val asleep: Boolean get() = standby.asleep
    val overlayShown: Boolean get() = overlay.visibility == View.VISIBLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = ToqueApp.of(this)
        // Le service machine d'abord : son processus démarre et ouvre la liaison avec la base pendant
        // que l'écran se construit (les barres et le son prennent près d'une demi-seconde sur le robot).
        app.machine.connect()
        val dm = resources.displayMetrics
        Ui.init(dm.widthPixels, dm.heightPixels)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(Ui.background())

        root = FrameLayout(this)
        root.background = Ui.background()
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= 26) FocusHighlightApi26.off(root)
        column = Ui.vertical(this)
        topBar = TopBar(this)
        column.addView(topBar, Ui.lp(Ui.MATCH, Ui.u(TopBar.HEIGHT)))
        content = FrameLayout(this)
        column.addView(content, Ui.lp(Ui.MATCH, 0, 1f))
        machineBar = MachineBar(this)
        machineBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) machineBar.post { spareMachineBar() }
        }
        column.addView(machineBar, Ui.lp(Ui.MATCH, Ui.WRAP))
        root.addView(column, Ui.flp())
        overlay = FrameLayout(this)
        overlay.visibility = View.GONE
        root.addView(overlay, Ui.flp())
        setContentView(root)
        root.viewTreeObserver.addOnGlobalLayoutListener { updateKeyboardInset() }
        timerAlarm = TimerAlarm(this, root)
        standby = Standby(this, root)
        splash = Splash(this, root)
        updates = UpdateAlert(this)
        standby.onWake = { updates.onWake() }
        KnobLight.log = { m -> app.log.write("molette", m) }
        KnobLight.idle(prefs.ring.on)

        try {
            tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (_: RuntimeException) {
        }

        app.recipes.load()
        // L'heure ne s'affiche qu'avec le Wi-Fi connecté (voir WallClock).
        WallClock.online = { TimeSync.online(applicationContext) }
        // Langue de l'interface, vue par les téléphones (l'activité est recréée quand elle change).
        app.companion?.refreshRobot()
        SyncService.schedule(this)
        push(HomeScreen(this))
        if (reopenSettings) {
            reopenSettings = false
            push(SettingsScreen.reopened(this))
        } else if (!prefs.knobReady && !prefs.knobSetupShown) {
            // Premier lancement : le bouton du robot n'envoie pas les mêmes touches partout.
            prefs.knobSetupShown = true
            push(KnobSetupScreen(this))
        }
        // Robot pas encore confirmé : on le reconnaît sans rien lui envoyer, la personne confirme.
        // Pas d'écran de démarrage alors : la base reste fermée jusqu'à la réponse, il cacherait la question.
        // Sinon, par-dessus l'accueil, le temps que la base soit prête.
        if (!prefs.robotConfirmed) {
            Splash.done = true
            root.post { askRobot() }
        } else {
            splash.start()
            // Ensuite (une seule fois) : proposer de relier un compte Toque.
            if (!prefs.siteOffered) root.post { offerSite() }
        }
        // Recettes de la communauté (sans compte) : une première fois sans attendre la nuit, puis une
        // fois par jour au plus. Un peu après le démarrage, pour ne pas le ralentir.
        if (prefs.siteCommunity && System.currentTimeMillis() - prefs.siteLastMs > 24 * 3600_000L)
            main.postDelayed({ app.site.sync() }, 20_000)
    }

    /**
     * Une seule fois : relier un compte toqueapp.org (facultatif). « Non merci » : plus jamais
     * demandé (Réglages › Recettes › toqueapp.org reste là). Sans compte, la communauté arrive quand même.
     */
    fun offerSite() {
        if (prefs.siteOffered || prefs.siteLinked || overlayShown) return
        val card = Ui.card(this, 28f, Ui.SURFACE2)
        card.setPadding(Ui.u(32), Ui.u(28), Ui.u(32), Ui.u(26))
        val head = Ui.horizontal(this)
        head.addView(Icon(this, Icon.Kind.PEOPLE, Ui.ACCENT_INK), LinearLayout.LayoutParams(Ui.u(34), Ui.u(34)).apply { rightMargin = Ui.u(14) })
        head.addView(Ui.text(this, tr("Compte Toque"), 26f, Ui.TEXT, Ui.MEDIUM))
        card.addView(head)
        card.addView(Ui.text(this, tr("Vous avez un compte sur toqueapp.org ? Reliez-le pour retrouver ici vos favoris du site et publier vos recettes depuis le robot.") +
            "\n\n" + tr("Sans compte, les recettes de la communauté arrivent quand même. Vous pourrez relier un compte plus tard dans Réglages › Recettes › toqueapp.org."),
            19f, Ui.TEXT2).apply { setLineSpacing(0f, 1.2f) }, Ui.lp(t = 16, b = 26))
        val row = Ui.horizontal(this, Gravity.END or Gravity.CENTER_VERTICAL)
        row.addView(Ui.button(this, tr("Non merci"), Ui.Style.GHOST, height = 60) {
            prefs.siteOffered = true
            closeOverlay()
        })
        row.addView(Ui.spacer(this, w = 14))
        row.addView(Ui.button(this, tr("Me connecter"), Ui.Style.PRIMARY, height = 60) {
            prefs.siteOffered = true
            closeOverlay()
            push(SiteLinkScreen(this))
        })
        card.addView(row)
        showOverlay(card, 680, dismissOnScrim = false)
    }

    /** Ce que la tablette laisse voir (applis d'origine installées, Android, ports), sans parler à la base. */
    private fun deviceFacts(): DeviceFacts {
        val pm = packageManager
        val installed = RobotProfiles.ALL.map { it.vendorPackage }.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }.toSet()
        val ports = RobotProfiles.ALL.map { it.serialDevice }.filter { java.io.File(it).exists() }.toSet()
        return DeviceFacts(installed, android.os.Build.VERSION.SDK_INT, ports)
    }

    /**
     * « Quel est votre robot ? » : le robot reconnu est proposé en premier ; la personne choisit.
     * Tant qu'elle n'a pas choisi, Toque n'ouvre pas la liaison avec la base en « Machine réelle ».
     */
    fun askRobot() {
        val d = RobotDetector.detect(deviceFacts())
        val card = Ui.card(this, 28f, Ui.SURFACE2)
        card.setPadding(Ui.u(32), Ui.u(28), Ui.u(32), Ui.u(26))
        card.addView(Ui.text(this, tr("Quel est votre robot ?"), 26f, Ui.TEXT, Ui.MEDIUM))
        val found = d.profile
        val intro = if (found != null) tr("Robot reconnu : %s (%s). Confirmez-le.", found.displayName, d.reason)
            else tr("Robot pas reconnu (%s). Choisissez-le d'après l'étiquette sous le robot.", d.reason)
        card.addView(Ui.text(this, intro + "\n" + tr("Toque ne parle à la base qu'une fois le robot confirmé."), 18f, Ui.TEXT2)
            .apply { setLineSpacing(0f, 1.2f) }, Ui.lp(t = 12, b = 18))
        val order = if (found != null) listOf(found) + RobotProfiles.ALL.filter { it !== found } else RobotProfiles.ALL
        for (p in order) {
            val label = p.displayName + " (" + p.models.joinToString(", ") + ")" + if (p.tested) "" else " · " + tr("en test")
            card.addView(Ui.button(this, label, if (p === found) Ui.Style.PRIMARY else Ui.Style.SECONDARY, height = 60, textSize = 18f) {
                closeOverlay()
                setRobot(p)
            }, Ui.lp(Ui.MATCH, Ui.WRAP, b = 10))
        }
        card.addView(Ui.button(this, tr("Plus tard"), Ui.Style.GHOST, height = 56) { closeOverlay() }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 4))
        showOverlay(card, 720, dismissOnScrim = false)
    }

    /** Change de robot : relance la liaison avec la base et refiltre les recettes. Refusé pendant un programme. */
    fun setRobot(p: RobotProfile) {
        if (snapshot.busy) {
            toast(tr("Impossible pendant un programme"), Ui.WARN)
            return
        }
        val before = RobotProfiles.byId(prefs.robotId)
        prefs.robotId = p.id
        RobotProfiles.active = p
        machine.reconfigure { v -> if (!v.ok) refusal(v) }
        // Catalogue déjà chargé : relu avec le filtre du nouveau robot (fiches courtes, sans relire chaque recette).
        app.recipes.refreshLater()
        refreshTitle()
        // Les Réglages ouverts montrent des choses propres au robot (recettes Lidl, import) : on les refait.
        if (current is SettingsScreen && before !== p) replace(SettingsScreen.reopened(this))
        val msg = if (!p.tested) tr("%s : en test. Vos retours sont attendus sur GitHub.", p.displayName) else tr("Robot : %s", p.displayName)
        toast(msg, if (p.tested) Ui.OK else Ui.WARN)
        if (before != null && before.lidlDevice != p.lidlDevice && app.recipes.count > 0)
            toast(tr("Synchronisez les recettes Lidl pour avoir celles de ce robot."), Ui.WARN)
        if (!prefs.siteOffered) root.post { offerSite() }
    }

    /**
     * Appui sur Turbo. Sur le Smart, la température n'est mesurée qu'après une chauffe : si elle est
     * inconnue, on demande d'abord de confirmer que le contenu est froid, puis de rappuyer.
     */
    fun turboDown(accessory: Accessory) {
        machine.turboDown(accessory) { v ->
            if (!v.ok && v.has(SafetyPolicy.COLD_CONFIRM) && v.violations.size == 1) {
                machine.turboUp()
                confirm(
                    tr("Contenu froid ?"),
                    tr("Ce robot ne mesure la température qu'après une chauffe. Le Turbo est interdit au-dessus de %d °C.\n\n" +
                        "Confirmez que le contenu est froid, puis appuyez de nouveau sur Turbo. La confirmation est oubliée à l'ouverture du couvercle.",
                        Limits.HOT_C),
                    tr("Le contenu est froid"), icon = Icon.Kind.THERMO,
                ) { machine.confirmCold { refusal(it) } }
            } else {
                refusal(v)
            }
        }
    }

    /** Applique la couleur d'accent choisie : comme pour la langue, l'interface est reconstruite. */
    fun applyAccent(color: Int) {
        prefs.accentColor = color
        Ui.setAccent(color)
        reopenSettings = true
        recreate()
    }

    /** Applique le thème clair ou sombre : l'interface est reconstruite, on revient aux réglages. */
    fun applyTheme(light: Boolean) {
        prefs.lightTheme = light
        Ui.setTheme(light)
        Ui.setAccent(prefs.accentColor)
        reopenSettings = true
        recreate()
    }

    /**
     * Couleur et/ou thème demandés par un téléphone (fil principal ; null : inchangé). Appliqués comme
     * dans les Réglages (l'interface est reconstruite), mais on reste sur l'accueil, sans ouvrir les
     * Réglages. Refusé quand la reconstruction ferait perdre quelque chose : un programme (même en pause
     * ou terminé, encore affiché), ou tout autre écran que l'accueil seul (recette guidée, éditeur,
     * appairage, réglages…), une fenêtre ou l'alarme du minuteur ouvertes. La veille sur l'accueil accepte.
     */
    fun appearanceFromPhone(accent: Int?, light: Boolean?): CommandResult {
        val s = snapshot
        if (s.jobActive || s.busy) return CommandResult.refuse("busy",
            tr("Un programme est en cours sur le robot : changez l'apparence une fois qu'il est fini"))
        if (stack.size != 1 || current !is HomeScreen || overlayShown || timerAlarm.shown)
            return CommandResult.refuse("busy",
                tr("Le robot n'est pas sur son écran d'accueil : revenez-y sur le robot, puis réessayez"))
        if (light != null) prefs.lightTheme = light
        if (accent != null) prefs.accentColor = accent
        Ui.setTheme(prefs.lightTheme)
        Ui.setAccent(if (prefs.accentColor != 0) prefs.accentColor else Ui.ACCENTS[0])
        // Pas de reopenSettings : l'accueil reste l'écran du robot.
        recreate()
        return CommandResult.OK
    }

    /** Applique la langue choisie dans les réglages : l'interface est reconstruite, on revient aux réglages. */
    fun applyLanguage() {
        I18n.select(I18n.resolve(prefs.language, Locale.getDefault().language).code)
        reopenSettings = true
        recreate()
    }

    override fun onStart() {
        super.onStart()
        unsubscribe = app.machine.onSnapshot { s ->
            topBar.update(s)
            machineBar.update(s)
            stack.lastOrNull()?.onSnapshot(s)
            standby.onSnapshot(s)
            splash.onSnapshot(s, app.machine.heard)
            knobRing(s)
            spareMachineBar()
        }
        unsubscribeEvents = app.machine.onEvent { e -> onMachineEvent(e) }
        announcedSentAt = app.companion?.hub?.waitingRecipe?.sentAt ?: 0L
        unsubscribeCompanion = app.companion?.onChange { c -> if (c == RobotCompanion.Change.WAITING) announceReceived() }
        unsubscribeRemoteStart = app.companion?.onRemoteStart { name -> remoteStarted(name) }
        topBar.startClock()
        standby.start()
        updates.onStart()
        startTimers()
        root.postDelayed(syncCheck, 60_000)
        if (activityStopped) {
            activityStopped = false
            current?.onShow()
        }
    }

    // ------------------------------------------------------------------ minuteurs de cuisine

    /** Pastille de la barre du haut, message de fin ; les minuteurs eux-mêmes tournent sans l'activité ([Timers]). */
    private fun startTimers() {
        val timers = Timers.of(this)
        unsubscribeTimers = timers.listen {
            topBar.showTimers(timers)
            // Tant que la sonnerie joue, l'écran ne se met pas en veille.
            if (timers.alarmActive) standby.poke()
        }
        timers.onRing = {
            standby.poke()
            timerAlarm.refresh()
        }
        topBar.showTimers(timers)
        timerAlarm.refresh()
        if (timers.lost.isNotEmpty()) {
            toast(tr("Minuteur arrêté par le redémarrage du robot : %s", timers.lost.joinToString(", ") { it.label }), Ui.WARN)
            timers.lost = emptyList()
        }
    }

    /** Hauteur de la barre machine quand elle est affichée (à laisser libre), 0 sinon. */
    fun machineBarSpace(): Int = if (machineBar.visibility == View.VISIBLE) machineBar.height.takeIf { it > 0 } ?: Ui.u(MachineBar.HEIGHT) else 0

    private fun setBottomMargin(v: View, want: Int) {
        val lp = v.layoutParams as FrameLayout.LayoutParams
        if (lp.bottomMargin != want) {
            lp.bottomMargin = want
            v.layoutParams = lp
        }
    }

    /**
     * Marge basse de l'écran au-dessus du clavier. Sous une fenêtre ouverte, l'écran du dessous ne bouge pas
     * (c'est la fenêtre qui se place au-dessus du clavier), sauf quand la barre machine est affichée : son
     * bouton Stop reste toujours au-dessus du clavier.
     */
    private fun columnKeyboardMargin(): Int = if (overlayShown && machineBarSpace() == 0) 0 else keyboardMargin

    private fun spareMachineBar() {
        val bottom = machineBarSpace()
        setBottomMargin(column, columnKeyboardMargin())
        timerAlarm.spare(keyboardMargin + bottom)
        if (overlayShown) setBottomMargin(overlay, keyboardMargin + if (overlaySparesBar) bottom else 0)
        toastView?.let { setBottomMargin(it, maxOf(Ui.u(110), keyboardMargin + bottom + Ui.u(16))) }
    }

    /**
     * En mode plein écran immersif, Android ne réduit pas toujours le contenu au-dessus du clavier :
     * on mesure la zone réellement visible ([getWindowVisibleDisplayFrame]) pour remonter l'écran,
     * les fenêtres modales et les messages au-dessus du clavier, et proposer de le masquer.
     */
    private fun updateKeyboardInset() {
        // Appelé à chaque mise en page de l'écran (plusieurs fois par seconde pendant une cuisson) : sans champ de
        // saisie ni clavier ouvert, rien à mesurer, et la zone visible n'est pas demandée au système (un appel
        // à WindowManager à chaque fois).
        if (keyboardHeight == 0 && window.currentFocus !is EditText) return
        val decorH = window.decorView.height
        if (decorH <= 0 || root.height <= 0) return
        val visible = visibleBottom()
        val rawKb = (decorH - visible).coerceAtLeast(0)
        val kb = if (rawKb > Ui.u(90)) rawKb else 0
        val margin = if (kb > 0) {
            root.getLocationInWindow(rootLoc)
            (rootLoc[1] + root.height - visible).coerceAtLeast(0)
        } else 0
        if (kb == keyboardHeight && margin == keyboardMargin) return
        val wasShown = keyboardHeight > 0
        keyboardHeight = kb
        keyboardMargin = margin
        val nowShown = kb > 0
        topBar.setKeyboardVisible(nowShown && !overlayShown)
        spareMachineBar()
        if (wasShown != nowShown) {
            // L'écran sous une fenêtre ouverte ne se resserre pas pour un clavier qui sert à la fenêtre.
            if (!nowShown || !overlayShown) current?.onKeyboard(nowShown)
            overlayOnKeyboard?.invoke(nowShown)
            if (!nowShown) immersive()
        }
        if (nowShown) (currentFocus as? EditText)?.let { f ->
            f.post { f.bringPointIntoView(f.selectionEnd.coerceAtLeast(0)) }
        }
    }

    /** Chaque minute : la synchro automatique des recettes regarde si le robot est inutilisé (voir AutoSync). */
    private val syncCheck = object : Runnable {
        override fun run() {
            SyncService.reportUi(standby.idleMs, snapshot.busy || current is CookScreen || current?.keepAwake == true)
            SyncService.consider(this@MainActivity)
            root.postDelayed(this, 60_000)
        }
    }

    override fun onResume() {
        super.onResume()
        immersive()
        prefs.returnBubble = false
        ReturnBubble.stop(this)
        if (prefs.vendorSession) endVendorSession()
    }

    override fun onPause() {
        main.removeCallbacks(knobLongPressRunnable)
        super.onPause()
    }

    override fun onStop() {
        main.removeCallbacks(knobLongPressRunnable)
        unsubscribeTimers?.invoke()
        unsubscribeTimers = null
        Timers.of(this).onRing = null
        unsubscribe?.invoke()
        unsubscribeEvents?.invoke()
        unsubscribeCompanion?.invoke()
        unsubscribeCompanion = null
        unsubscribeRemoteStart?.invoke()
        unsubscribeRemoteStart = null
        topBar.stopClock()
        root.removeCallbacks(syncCheck)
        SyncService.reportUi(-1, false)
        standby.stop()
        // Toque n'est plus à l'écran (appli d'origine, paramètres Android…) : blanc du robot.
        KnobLight.release()
        updates.onStop()
        activityStopped = true
        current?.onHide()
        super.onStop()
    }

    /** Code couleur de l'anneau de la molette ; KnobLight n'écrit que quand la couleur change. */
    private fun knobRing(s: Snapshot) {
        KnobLight.show(if (prefs.knobColours) RingColour.of(s, current?.cookWaiting == true) else null)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        Updater.onInstallError = null
        tone?.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            immersive()
            // Smart : la rotation du système reste sur le paysage de Toque (#59). Rien sur le Connect.
            RotationLock.apply(this, prefs) { m -> app.log.write("écran", m) }
        }
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

    /** [s] est encore dans la pile (affiché ou recouvert par un autre écran). */
    fun isOnStack(s: Screen): Boolean = s in stack

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
        if (!top.canLeave { back() }) return
        hideKeyboard()
        top.onHide()
        stack.removeAt(stack.size - 1)
        show(stack.last())
    }

    /** Retour à l'accueil (maison) ; un écran aux modifications non enregistrées demande d'abord confirmation. */
    fun home() {
        for (i in stack.size - 1 downTo 1) if (!stack[i].canLeave { home() }) return
        hideKeyboard()
        while (stack.size > 1) stack.removeAt(stack.size - 1).onHide()
        show(stack.first())
    }

    private fun show(s: Screen) {
        lastKnobPressTime = 0L
        main.removeCallbacks(knobLongPressRunnable)
        content.removeAllViews()
        val v = s.view
        (v.parent as? FrameLayout)?.removeView(v)
        content.addView(v, Ui.flp())
        topBar.setTitle(s.title, s.titleCounter, stack.size > 1)
        s.onShow()
        s.onKeyboard(false)
        s.onSnapshot(snapshot)
    }

    fun refreshTitle() {
        current?.let { topBar.setTitle(it.title, it.titleCounter, stack.size > 1) }
    }

    override fun onBackPressed() {
        if (timerAlarm.shown) return
        if (overlay.visibility == View.VISIBLE) {
            closeOverlay()
            return
        }
        back()
    }

    // ------------------------------------------------------------------ bouton rotatif

    // ------------------------------------------------------------------ veille

    /** Le geste qui réveille l'écran ne fait rien d'autre (on ne lance rien en le touchant dans le noir). */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) swallowTouch = standby.onInput()
        if (swallowTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swallowTouch = false
            return true
        }
        noteTap(event)
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) closeKeyboardAfterTapOutside(event)
        return handled
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && standby.onKnob()) return true
        // Écran de démarrage : ni la molette ni Retour n'agissent sur les écrans qu'il couvre.
        if (splash.shown) return true
        // Retour reste Retour (tablettes et émulateur) : jamais un cran du bouton, même appris par erreur.
        val isBack = event.keyCode == KeyEvent.KEYCODE_BACK
        if (!isBack) knobListener?.let { l ->
            if (event.action == KeyEvent.ACTION_DOWN) l(event)
            return true
        }
        // Message de fin de minuteur, ou fenêtre ouverte : la molette agit sur eux, jamais sur l'écran dessous ;
        // une fenêtre sans réglage de molette (confirmation) ne se valide pas d'un appui de molette.
        if (!isBack && (timerAlarm.shown || overlayShown && currentFocus !is EditText)) {
            val k = knobKind(event.keyCode)
            if (k != 0) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (timerAlarm.shown) { if (k == PRESS) timerAlarm.ok() }
                    else overlayKnob?.let { if (k == PRESS) it.press() else it.turn(k) }
                }
                return true
            }
        }
        if (!isBack && !overlayShown) {
            val k = knobKind(event.keyCode)
            if (k != 0 && event.action == KeyEvent.ACTION_DOWN && !prefs.knobSetupDone) prefs.knobSetupDone = true
            val s = current
            // Programmes (#105) : appui long = retour, double appui ; l'appui simple part au relâchement.
            if (k == PRESS && s is app.toque.ui.screens.ProgramsScreen && currentFocus !is EditText) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        knobLongHandled = false
                        main.postDelayed(knobLongPressRunnable, 500L)
                    }
                    return true
                } else if (event.action == KeyEvent.ACTION_UP) {
                    main.removeCallbacks(knobLongPressRunnable)
                    if (!knobLongHandled) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastKnobPressTime < 350L && s.onKnobDoubleClick()) {
                            lastKnobPressTime = 0L
                        } else {
                            lastKnobPressTime = now
                            s.onKnobPress()
                        }
                    }
                    return true
                }
            }
            if (k != 0 && event.action == KeyEvent.ACTION_DOWN && s != null && currentFocus !is EditText) {
                if (k == PRESS && s.onKnobPress()) return true
                if (k != PRESS && s.onKnob(k)) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** -1 / 1 : un cran de la molette, [PRESS] : son appui, 0 : une autre touche. */
    private fun knobKind(k: Int): Int {
        val left = prefs.knobLeft.let { if (it >= 0) it == k else k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_F7 }
        val right = prefs.knobRight.let { if (it >= 0) it == k else k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_F6 }
        val press = prefs.knobPress.let {
            if (it >= 0) it == k else k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_F5
        }
        return when {
            press -> PRESS
            left -> -1
            right -> 1
            else -> 0
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL && standby.onKnob()) return true
        if (splash.shown) return true
        if (event.action == MotionEvent.ACTION_SCROLL) {
            // Encodeur rotatif (AXIS_SCROLL) ou molette (AXIS_VSCROLL)
            val v = event.getAxisValue(AXIS_SCROLL).let { if (it == 0f) -event.getAxisValue(MotionEvent.AXIS_VSCROLL) else it }
            val now = System.currentTimeMillis()
            if (v != 0f && (timerAlarm.shown || overlay.visibility == View.VISIBLE)) {
                if (!timerAlarm.shown && now - lastKnobMs > 40) overlayKnob?.turn(if (v > 0) 1 else -1)
                lastKnobMs = now
                return true
            }
            if (v != 0f && now - lastKnobMs > 40) {
                lastKnobMs = now
                if (!prefs.knobSetupDone) prefs.knobSetupDone = true
                if (current?.onKnob(if (v > 0) 1 else -1) == true) return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    // ------------------------------------------------------------------ évènements machine

    private fun onMachineEvent(e: MachineEvent) {
        val ownSound = current?.ownsFinishSound(e) == true
        current?.onEvent(e)
        if (e.type != EventType.LINK_CHANGED && e.type != EventType.PHASE_CHANGED) standby.poke()
        when (e.type) {
            EventType.JOB_FINISHED -> if (!ownSound) chime(2)
            EventType.FAULT -> { chime(3); toast(e.message, Ui.DANGER) }
            EventType.LID_OPENED -> if (snapshot.busy || snapshot.state != null) toast(tr("Couvercle ouvert : pause"), Ui.WARN)
            EventType.JOB_PAUSED -> if (e.message != PauseReason.USER.label) toast(e.message, Ui.WARN)
            EventType.TURBO_END -> toast(e.message, Ui.TEXT2)
            EventType.SCALE_ZEROED -> toast(e.message, Ui.OK)
            EventType.SCALE_INFO -> toast(e.message, Ui.TEXT2)
            EventType.BOWL_LEARNED -> {
                if (e.value > 0) prefs.emptyBowlG = e.value
                toast(e.message, Ui.OK)
            }
            else -> Unit
        }
    }

    /**
     * Une recette vient d'arriver d'un téléphone : simple message, et seulement s'il ne dérange rien
     * (pas en veille, pas de fenêtre ouverte, pas pendant une recette guidée). Rien ne s'interrompt :
     * la recette attend sur l'accueil.
     */
    private fun announceReceived() {
        val w = app.companion?.hub?.waitingRecipe ?: return
        if (w.sentAt == announcedSentAt) return
        announcedSentAt = w.sentAt
        // Recette retrouvée après une relance de l'interface : déjà annoncée à l'époque.
        if (w.sender.isRobot || System.currentTimeMillis() - w.sentAt > 60_000) return
        if (standby.asleep || overlayShown || current is CookScreen) return
        toast(tr("Recette reçue du téléphone : %s", w.title), Ui.ACCENT_INK)
    }

    /**
     * Cuisson manuelle lancée depuis un téléphone : un message, et l'écran Cuisson manuelle si
     * l'accueil est affiché (ses cartes suivent la cuisson). Ailleurs, rien ne change d'écran ;
     * la veille se lève d'elle-même (programme en cours).
     */
    private fun remoteStarted(name: String) {
        toast(tr("Cuisson lancée depuis %s", name.ifBlank { tr("un téléphone") }), Ui.ACCENT_INK)
        if (depth == 1 && current is HomeScreen && !overlayShown) push(ManualScreen(this))
    }

    /**
     * « Arrêter la recette » demandé par un téléphone (sa confirmation est faite là-bas) : la recette
     * guidée ouverte s'arrête comme avec son bouton. false s'il n'y en a pas.
     */
    fun stopGuidedRecipe(): Boolean {
        val cook = stack.lastOrNull { it is CookScreen } as? CookScreen ?: return false
        cook.stopRecipe()
        return true
    }

    /** Quantité à peser atteinte. */
    fun weighBeep() {
        if (prefs.sound) Sounds.weighReached(prefs.weighSound)
    }

    /** Fin de recette : mélodie forte, comme l'appli d'origine. */
    fun recipeDoneSound() {
        if (prefs.sound) Sounds.recipeDone()
    }

    fun weighRecordedSound() {
        if (prefs.sound) Sounds.weighRecorded()
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
        lp.bottomMargin = maxOf(Ui.u(110), keyboardMargin + machineBarSpace() + Ui.u(16))
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

    /**
     * Affiche une carte modale au centre de l'écran. [spareMachineBar] : la barre machine reste hors du voile
     * (son bouton Stop reste à portée) quand un programme tourne.
     */
    fun showOverlay(card: View, width: Int = 640, dismissOnScrim: Boolean = true, spareMachineBar: Boolean = false) {
        // Avant de refermer le clavier : la fenêtre d'avant, qu'on remplace, n'a plus à suivre son départ.
        overlayOnKeyboard = null
        hideKeyboard()
        overlayKnob = null
        overlaySparesBar = spareMachineBar
        column.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        topBar.setKeyboardVisible(false)
        overlay.removeAllViews()
        overlay.setBackgroundColor(Ui.SCRIM)
        overlay.isClickable = true
        overlay.setOnClickListener { if (dismissOnScrim) closeOverlay() }
        card.isClickable = true
        overlay.addView(card, Ui.flp(Ui.u(width), Ui.WRAP, Gravity.CENTER))
        overlay.visibility = View.VISIBLE
        spareMachineBar()
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(120).start()
    }

    fun closeOverlay() {
        // Avant de refermer le clavier : la fenêtre s'en va, elle n'a plus à suivre son départ.
        overlayOnKeyboard = null
        if (keyboardHeight > 0 || currentFocus is EditText) hideKeyboard()
        overlay.visibility = View.GONE
        overlay.removeAllViews()
        overlayKnob = null
        overlaySparesBar = false
        column.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        topBar.setKeyboardVisible(false)
        spareMachineBar()
    }

    /** Dialogue de confirmation. */
    fun confirm(title: String, message: String, yes: String, no: String = tr("Annuler"),
                danger: Boolean = false, icon: Icon.Kind = Icon.Kind.WARNING, spareMachineBar: Boolean = false, onYes: () -> Unit) {
        val card = Ui.card(this, 28f, Ui.SURFACE2)
        card.setPadding(Ui.u(32), Ui.u(28), Ui.u(32), Ui.u(26))
        val head = Ui.horizontal(this)
        head.addView(Icon(this, icon, if (danger) Ui.DANGER else Ui.ACCENT_INK), LinearLayout.LayoutParams(Ui.u(34), Ui.u(34)).apply { rightMargin = Ui.u(14) })
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
        showOverlay(card, 680, spareMachineBar = spareMachineBar)
    }

    /**
     * Démarre un programme ; si le contenu est chaud, demande d'abord la confirmation
     * de mixage (comme l'appli d'origine, mais avant tout mouvement).
     */
    fun startJob(spec: JobSpec, onHotConfirmed: () -> Unit = {}, onHotAsked: () -> Unit = {}, done: (Verdict) -> Unit = {}) {
        machine.start(spec) { v ->
            if (!v.ok && v.has(SafetyPolicy.HOT_BLEND) && v.violations.size == 1) {
                onHotAsked()
                confirm(
                    tr("Contenu chaud"),
                    // Smart : la base ne mesure la température qu'après une chauffe ; Toque la croit chaude.
                    (if (RobotProfiles.active.capabilities.measuresTemperature)
                        tr("Le bol est à %d °C. Mixer chaud peut provoquer des projections.\n\n" +
                            "Vérifiez que le couvercle est verrouillé, que le gobelet est en place et que le bol n'est pas trop rempli.", snapshot.actualTempC)
                    else tr("Ce robot ne mesure pas toujours la température du bol : Toque considère le contenu comme chaud. Mixer chaud peut provoquer des projections.\n\n" +
                            "Vérifiez que le couvercle est verrouillé, que le gobelet est en place et que le bol n'est pas trop rempli.")) +
                        if (prefs.hotRamp) " " + tr("La vitesse montera progressivement.") else "",
                    tr("Mixer quand même"), icon = Icon.Kind.THERMO,
                ) {
                    onHotConfirmed()
                    machine.start(spec.copy(hotBlendConfirmed = true)) { v2 -> refusal(v2); done(v2) }
                }
            } else {
                refusal(v)
                done(v)
            }
        }
    }

    private var onStorage: ((Boolean) -> Unit)? = null

    /** Accès au dossier Download (import et export de recettes) ; Android le demande une fois. */
    fun withStorage(then: (Boolean) -> Unit) {
        val perms = arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (perms.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) { then(true); return }
        onStorage = then
        requestPermissions(perms, STORAGE_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != STORAGE_REQUEST) return
        val ok = grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        onStorage?.invoke(ok)
        onStorage = null
        immersive()
    }

    fun showKeyboard(field: View, afterPost: (() -> Unit)? = null) {
        field.requestFocus()
        field.post {
            imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            afterPost?.invoke()
        }
    }

    fun hideKeyboard() {
        // Le champ en cours est demandé à la fenêtre : les essais ne le suivent que par elle.
        val focused = window.currentFocus
        val token = focused?.windowToken ?: window.decorView.windowToken
        imm.hideSoftInputFromWindow(token, 0)
        if (focused is EditText) {
            focused.clearFocus()
            root.requestFocus()
        }
        // Une fenêtre ouverte apprend tout de suite que son clavier part, sans attendre qu'Android le
        // constate : elle se replace pendant qu'il se referme, pas après.
        if (keyboardHeight > 0) overlayOnKeyboard?.invoke(false)
    }

    /** Hauteur dont dispose une fenêtre ouverte quand le clavier est fermé (la barre machine épargnée, s'il y a lieu). */
    fun overlayRoom(): Int = root.height - (if (overlaySparesBar) machineBarSpace() else 0)

    // ------------------------------------------------------------------ appui à côté du champ

    private var tapField: EditText? = null
    private var tapArmed = false
    private var tapX = 0f
    private var tapY = 0f

    private fun noteTap(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapField = window.currentFocus as? EditText
                tapArmed = tapField != null || keyboardHeight > 0
                tapX = event.x
                tapY = event.y
            }
            MotionEvent.ACTION_CANCEL -> tapArmed = false
        }
    }

    /**
     * Ce robot n'a pas de bouton Retour, donc rien pour refermer le clavier : un appui bref ailleurs que sur
     * un champ le referme, sur une page comme dans une fenêtre ouverte (sa carte, le voile autour). Un
     * bouton garde son effet et décide lui-même (« Suivant » garde le clavier, « OK » le referme) ; une
     * ligne de liste aussi. Faire défiler la page ne le referme pas.
     */
    private fun closeKeyboardAfterTapOutside(up: MotionEvent) {
        if (!tapArmed) return
        tapArmed = false
        val field = tapField
        tapField = null
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        if (Math.abs(up.x - tapX) > slop || Math.abs(up.y - tapY) > slop) return
        // L'appui a donné la main à un autre champ : le clavier sert encore.
        val now = window.currentFocus
        if (now is EditText && now !== field) return
        var v: View? = viewAt(window.decorView, tapX, tapY)
        while (v != null) {
            if (v is EditText) return
            // Le voile d'une fenêtre ouverte : rien à y presser, même s'il écoute les appuis pour se fermer.
            if (v === overlay) break
            if (v.hasOnClickListeners() || v.parent is AdapterView<*>) return
            v = v.parent as? View
        }
        hideKeyboard()
    }

    /** La vue affichée sous le point ([x], [y] dans le repère du parent de [v]), la plus au-dessus. */
    private fun viewAt(v: View, x: Float, y: Float): View? {
        // Là où la vue est dessinée : sa place, plus son décalage (une carte qui glisse).
        if (v.visibility != View.VISIBLE || x < v.x || x >= v.x + v.width || y < v.y || y >= v.y + v.height) return null
        if (v is ViewGroup) {
            val cx = x - v.x + v.scrollX
            val cy = y - v.y + v.scrollY
            for (i in v.childCount - 1 downTo 0) viewAt(v.getChildAt(i), cx, cy)?.let { return it }
        }
        return v
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
            // Base de l'appli d'origine : copiée seulement quand son format est connu (Connect).
            if (!prefs.robot.stockImport) return@Thread
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
                "Revenez ensuite à Toque avec le bouton « Retour à Toque » affiché par-dessus (vous pouvez le déplacer) : les recettes seront importées automatiquement."),
            tr("Ouvrir"), icon = Icon.Kind.SYNC,
        ) {
            machine.suspendPort { v ->
                if (!v.ok) { refusal(v); return@suspendPort }
                Thread {
                    // Sans bouton de retour, impossible de revenir à Toque : on n'ouvre pas.
                    if (!ReturnBubble.ensurePermission(this)) {
                        main.post {
                            toast(tr("Impossible d'afficher le bouton « Retour à Toque » (root ?) : ouverture annulée"), Ui.WARN)
                            machine.resumePort()
                        }
                        return@Thread
                    }
                    val sync = LidlSync(this, prefs, app.recipes, app.log)
                    val enabled = sync.setVendorHardware(true)
                    val launch = sync.components().launch
                    main.post {
                        if (!enabled || launch == null) {
                            toast(tr("Appli d'origine indisponible (root ?)"), Ui.WARN)
                            machine.resumePort()
                        } else {
                            prefs.vendorSession = true
                            prefs.returnBubble = true
                            ReturnBubble.start(this)
                            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }.start()
            }
        }
    }

    /** Paramètres Android (accès caché), avec le bouton « Retour à Toque » pour en revenir. */
    fun openAndroidSettings() = openOutside(Intent(android.provider.Settings.ACTION_SETTINGS))

    /**
     * L'appli Magisk (ses notifications, les droits accordés), sans passer par un PC. Elle est ouverte par
     * son lanceur : le nom de son écran change d'une version à l'autre. Introuvable (désinstallée, ou masquée
     * sous un autre nom par son propre réglage) : un message. Avant d'ouvrir, un rappel : Magisk propose une
     * réinstallation qui réécrit le démarrage du robot.
     */
    fun openMagisk() {
        val launch = packageManager.getLaunchIntentForPackage(MAGISK_PACKAGE)
        if (launch == null) {
            toast(tr("Magisk est introuvable sur ce robot : désinstallé, ou masqué sous un autre nom."), Ui.WARN)
            return
        }
        confirm(tr("Ouvrir Magisk ?"),
            tr("Magisk donne à Toque l'accès root dont il a besoin. Vous pouvez y régler ses notifications. N'y lancez jamais « Installation directe » et refusez toute réinstallation proposée : elle réécrit le démarrage du robot."),
            tr("Ouvrir")) { openOutside(launch) }
    }

    /** Ouvre [intent] hors de Toque, avec le bouton « Retour à Toque » : le robot n'a pas de touche Retour. */
    private fun openOutside(intent: Intent) {
        Thread {
            val ok = ReturnBubble.ensurePermission(this)
            main.post {
                if (!ok) {
                    toast(tr("Impossible d'afficher le bouton « Retour à Toque » (root ?) : ouverture annulée"), Ui.WARN)
                    return@post
                }
                prefs.returnBubble = true
                ReturnBubble.start(this)
                try {
                    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {
                    prefs.returnBubble = false
                    ReturnBubble.stop(this)
                }
            }
        }.start()
    }

    /**
     * Ouvre [intent] hors de Toque sans le bouton « Retour à Toque » : pour un écran qui a sa propre flèche de
     * retour et se referme sur Toque (les réglages du clavier d'Android 6 et ceux de HeliBoard, essayés sur un
     * SKMC 1100 A1). Sans ce bouton, Android 6 ne refuse pas les demandes de permission de cet écran.
     */
    fun openWithItsOwnWayBack(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            toast(tr("Cet écran ne s'ouvre pas depuis Toque."), Ui.WARN)
        }
    }

    companion object {
        private var reopenSettings = false
        private const val STORAGE_REQUEST = 7
        /** L'appli Magisk sous son nom d'origine (son réglage « Masquer l'appli » lui en donne un autre). */
        const val MAGISK_PACKAGE = "com.topjohnwu.magisk"
        private const val PRESS = 2

        /** MotionEvent.AXIS_SCROLL (API 26), valeur fixe pour Android 6. */
        private const val AXIS_SCROLL = 26
        private val clockFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)

        /** Heure affichée ; le fuseau peut changer en cours de route (réglage automatique). */
        fun now(): String {
            clockFormat.timeZone = java.util.TimeZone.getDefault()
            return clockFormat.format(Date())
        }
    }
}

/**
 * À part de [MainActivity] : une classe qui nomme une méthode absente d'Android 6, même derrière un test de
 * version, n'y est plus vérifiée à l'installation mais à chaque démarrage. Celle-ci n'est chargée qu'à partir
 * d'Android 8.
 */
@android.annotation.TargetApi(26)
private object FocusHighlightApi26 {
    fun off(v: View) {
        v.defaultFocusHighlightEnabled = false
    }
}
