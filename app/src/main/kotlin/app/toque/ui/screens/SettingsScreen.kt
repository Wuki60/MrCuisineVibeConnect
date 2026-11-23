package app.toque.ui.screens

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import app.toque.Prefs
import app.toque.TimeSync
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.machine.Snapshot
import app.toque.core.net.StatusServer
import app.toque.core.time.ZoneLookup
import app.toque.core.update.Release
import app.toque.recipes.LidlSync
import app.toque.core.recipes.McOnline
import app.toque.recipes.OnlineSync
import app.toque.recipes.SyncProgress
import app.toque.recipes.RecipeFiles
import app.toque.core.recipes.CommunityRecipes
import app.toque.core.recipes.UserRecipes
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Sounds
import app.toque.ui.Ui
import app.toque.ui.UpdateFlow
import app.toque.update.Updater

class SettingsScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Réglages")
    private lateinit var modeRow: LinearLayout
    private lateinit var twinBox: LinearLayout
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var syncText: TextView
    private lateinit var syncBtn: View
    private lateinit var stopSyncBtn: View
    /** Absent sur un robot dont la base de l'appli d'origine n'est pas encore lue (Smart). */
    private var importBtn: View? = null
    private lateinit var bowlText: TextView
    private lateinit var linkText: TextView
    private lateinit var timeText: TextView
    private lateinit var homeText: TextView
    private var settingTime = false
    private var mode = a.prefs.mode

    private enum class Upd { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, ERROR }
    private val updater by lazy { Updater(a) }
    private lateinit var updText: TextView
    private lateinit var updButtons: LinearLayout
    private var upd = Upd.IDLE
    private var release: Release? = null
    private var updMessage = ""
    private lateinit var accountText: TextView
    private lateinit var accountButtons: LinearLayout
    private lateinit var siteText: TextView
    private lateinit var siteBtn: View

    override fun build(): View {
        val col = Ui.vertical(c)
        col.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(24))
        val columns = Ui.horizontal(c, Gravity.TOP)
        val left = Ui.vertical(c)
        val right = Ui.vertical(c)
        columns.addView(left, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(18) })
        columns.addView(right, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        col.addView(columns)

        // ---- Robot (fiche : protocole, programmes, recettes montrées)
        val robot = section(left, tr("Robot"), Icon.Kind.POT)
        robotText = Ui.text(c, "", 18f, Ui.TEXT).apply { setLineSpacing(0f, 1.15f) }
        robot.addView(robotText, Ui.lp(t = 4))
        robot.addView(Ui.button(c, tr("Changer de robot"), Ui.Style.SECONDARY, height = 52) { a.askRobot() }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        renderRobot()

        // ---- Machine
        val machine = section(left, tr("Machine"), Icon.Kind.POT)
        modeRow = Ui.horizontal(c)
        machine.addView(modeRow, Ui.lp(t = 4))
        twinBox = Ui.horizontal(c)
        host = field(a.prefs.twinHost, tr("Hôte"))
        port = field(a.prefs.twinPort.toString(), "Port").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        twinBox.addView(host, LinearLayout.LayoutParams(0, Ui.u(58), 1f).apply { rightMargin = Ui.u(10) })
        twinBox.addView(port, LinearLayout.LayoutParams(Ui.u(120), Ui.u(58)))
        machine.addView(twinBox, Ui.lp(t = 12))
        linkText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        machine.addView(linkText, Ui.lp(t = 12))
        machine.addView(Ui.button(c, tr("Appliquer"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 58) { applyMode() }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 14))
        renderModes()

        // ---- Sécurité
        val safety = section(left, tr("Sécurité"), Icon.Kind.WARNING)
        safety.addView(toggle(tr("Exiger la détection du bol"), a.prefs.requireBowl) { on ->
            a.prefs.requireBowl = on
            a.machine.reconfigure { a.refusal(it) }
        })
        bowlText = Ui.text(c, "", 15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }
        safety.addView(bowlText, Ui.lp(t = 6))
        safety.addView(toggle(tr("Inverser le capteur du bol"), a.prefs.invertBowl) { on ->
            a.prefs.invertBowl = on
            a.machine.reconfigure { a.refusal(it) }
        }, Ui.lp(t = 10))
        safety.addView(Ui.text(c, tr("À activer si le capteur indique « bol détecté » quand le bol est retiré, et l'inverse quand il est en place."),
            15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 6))
        safety.addView(toggle(tr("Montée progressive de la vitesse si le contenu est chaud"), a.prefs.hotRamp) { on ->
            a.prefs.hotRamp = on
            a.machine.reconfigure { a.refusal(it) }
        }, Ui.lp(t = 10))
        safety.addView(Ui.text(c, tr("À partir de 60 °C, la lame atteint sa vitesse en 2 à 3 secondes pour éviter les projections. À froid, elle y va directement."),
            15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 6))
        safety.addView(toggle(tr("Enchaîner les étapes machine des recettes"), a.prefs.chainSteps) { a.prefs.chainSteps = it }, Ui.lp(t = 10))
        safety.addView(Ui.text(c, tr("Les réglages qui se suivent sans geste à faire entre eux partent ensemble d'un seul appui sur Lancer : la lame passe directement à la vitesse et à la température suivantes."),
            15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 6))
        safety.addView(toggle(tr("Sons"), a.prefs.sound) { a.prefs.sound = it }, Ui.lp(t = 10))
        safety.addView(Ui.text(c, tr("Son quand la quantité à peser est atteinte"), 18f, Ui.TEXT), Ui.lp(t = 10))
        safety.addView(dropdown(Sounds.WEIGH_SOUNDS.map { it.first to tr(it.second) }, a.prefs.weighSound) { k ->
            a.prefs.weighSound = k
            Sounds.weighReached(k)
        }, Ui.lp(Ui.MATCH, Ui.u(58), t = 8))

        // ---- Langue (titre bilingue : trouvable quelle que soit la langue affichée)
        val language = section(right, "Langue · Language", Icon.Kind.INFO)
        val options = listOf(I18n.AUTO to tr("Automatique (langue du système)")) + I18n.languages.map { it.code to it.name }
        language.addView(dropdown(options, a.prefs.language) { code ->
            a.prefs.language = code
            a.applyLanguage()
        }, Ui.lp(Ui.MATCH, Ui.u(58), t = 4))

        // ---- Apparence : thème sombre/clair + couleur d'accent
        val look = section(right, tr("Apparence"), Icon.Kind.STAR)
        look.addView(Ui.text(c, tr("Thème"), 18f, Ui.TEXT))
        val themes = listOf("dark" to tr("Mode sombre"), "light" to tr("Mode clair"))
        look.addView(dropdown(themes, if (a.prefs.lightTheme) "light" else "dark") { code ->
            val light = code == "light"
            if (light != a.prefs.lightTheme) a.applyTheme(light)
        }, Ui.lp(Ui.MATCH, Ui.u(58), t = 8))
        look.addView(Ui.text(c, tr("Couleur des boutons, des icônes et des repères."), 15f, Ui.TEXT3)
            .apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 12))
        for (rowIndex in 0 until 2) {
            val row = Ui.horizontal(c, Gravity.CENTER)
            for (i in rowIndex * 5 until rowIndex * 5 + 5) {
                row.addView(swatch(Ui.ACCENTS[i]), LinearLayout.LayoutParams(0, Ui.u(60), 1f))
            }
            look.addView(row, Ui.lp(t = if (rowIndex == 0) 12 else 6))
        }

        // ---- Écran
        val screen = section(right, tr("Écran"), Icon.Kind.CLOCK)
        val delays = listOf("0" to tr("Jamais")) + listOf(2, 5, 10, 15, 30).map { it.toString() to tr("Après %d min", it) }
        screen.addView(Ui.text(c, tr("Mise en veille"), 18f, Ui.TEXT))
        screen.addView(dropdown(delays, a.prefs.standbyMinutes.toString()) { m -> a.prefs.standbyMinutes = m.toInt() },
            Ui.lp(Ui.MATCH, Ui.u(58), t = 8))
        screen.addView(Ui.text(c, tr("Sans geste ni programme en cours, l'écran s'assombrit et n'affiche plus que l'heure. Touchez-le ou tournez le bouton pour reprendre ; le couvercle et la balance le réveillent aussi."),
            15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 8))
        val knob = listOf("normal" to tr("Normale"), "dim" to tr("Plus légère"), "off" to tr("Éteinte"))
        screen.addView(Ui.text(c, tr("Molette pendant la veille"), 18f, Ui.TEXT), Ui.lp(t = 12))
        screen.addView(dropdown(knob, a.prefs.standbyKnob) { a.prefs.standbyKnob = it },
            Ui.lp(Ui.MATCH, Ui.u(58), t = 8))

        // ---- Date et heure
        val time = section(right, tr("Date et heure"), Icon.Kind.CLOCK)
        val zones = listOf(ZoneLookup.AUTO to tr("Automatique (d'après la connexion Internet)")) +
            (ZoneLookup.COMMON + listOf(TimeSync.current(), a.prefs.timeZone))
                .filter { it != ZoneLookup.AUTO }.distinct().map { it to it.replace('_', ' ') }
        time.addView(dropdown(zones, a.prefs.timeZone) { z ->
            a.prefs.timeZone = z
            setClock()
        }, Ui.lp(Ui.MATCH, Ui.u(58), t = 4))
        timeText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        time.addView(timeText, Ui.lp(t = 10))
        time.addView(Ui.button(c, tr("Mettre à l'heure"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 52) { setClock() },
            Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        refreshTime()

        // ---- Recettes Lidl
        val lidl = section(right, tr("Recettes Lidl"), Icon.Kind.SYNC)
        val chosen = a.prefs.robot
        val lidlIntro = if (chosen.lidlDevice == "MC2.0") tr("Toque télécharge lui-même les recettes officielles du Monsieur Cuisine connect sur monsieur-cuisine.com (sans compte).")
            else tr("Toque télécharge lui-même les recettes officielles du %s sur monsieur-cuisine.com (sans compte).", chosen.displayName)
        lidl.addView(Ui.text(c, lidlIntro, 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) })
        syncText = Ui.text(c, "", 16f, Ui.TEXT)
        lidl.addView(syncText, Ui.lp(t = 10))
        val b1 = Ui.horizontal(c)
        syncBtn = Ui.button(c, tr("Synchroniser"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 58) { sync(online = true) }
        b1.addView(syncBtn, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        // Visible seulement pendant une synchronisation ; ce qui est déjà fait est gardé.
        stopSyncBtn = Ui.button(c, tr("Arrêter"), Ui.Style.SECONDARY, Icon.Kind.STOP, height = 58) { SyncProgress.requestStop() }
        b1.addView(stopSyncBtn)
        lidl.addView(b1, Ui.lp(t = 12))
        // Import depuis l'appli d'origine : seulement là où Toque sait lire sa base (pas encore le Smart).
        if (chosen.stockImport) {
            lidl.addView(Ui.text(c, tr("Autre source : les recettes déjà présentes dans l'appli d'origine du robot (remplacent celles du site)."),
                14f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 12))
            // Un bouton par ligne : côte à côte, « Ouvrir l'appli d'origine » n'avait plus la place et
            // son texte se coupait au milieu d'un mot.
            val btn = Ui.button(c, tr("Importer de l'appli d'origine"), Ui.Style.SECONDARY, height = 52) { sync(online = false) }
            lidl.addView(btn, Ui.lp(Ui.WRAP, Ui.WRAP, t = 6))
            importBtn = btn
        }
        lidl.addView(Ui.button(c, tr("Ouvrir l'appli d'origine"), Ui.Style.GHOST, Icon.Kind.LINK, height = 52) { a.openVendorApp() },
            Ui.lp(Ui.WRAP, Ui.WRAP, t = if (chosen.stockImport) 8 else 12))
        lidl.addView(Ui.text(c, tr("Langue des recettes"), 18f, Ui.TEXT), Ui.lp(t = 12))
        val recipeLangs = McOnline.RECIPE_LANGUAGES.map { code ->
            code to when (code) {
                "auto" -> tr("Automatique (selon l'interface)")
                "es-ES" -> "Español"
                "fr-FR" -> "Français"
                "en-US" -> "English"
                "de-DE" -> "Deutsch"
                "it-IT" -> "Italiano"
                "pt-PT" -> "Português"
                "nl-NL" -> "Nederlands"
                "pl-PL" -> "Polski"
                else -> code
            }
        }
        lidl.addView(dropdown(recipeLangs, a.prefs.recipeLanguage) { code ->
            a.prefs.recipeLanguage = code
            a.toast(tr("Pris en compte à la prochaine synchronisation"))
        }, Ui.lp(Ui.MATCH, Ui.u(58), t = 8))
        lidl.addView(toggle(tr("Synchro automatique chaque nuit"), a.prefs.autoSync) { a.prefs.autoSync = it }, Ui.lp(t = 10))
        lidl.addView(toggle(tr("Recettes des autres langues"), a.prefs.foreignRecipes) {
            a.prefs.foreignRecipes = it
            a.toast(tr("Pris en compte à la prochaine synchronisation"))
        }, Ui.lp(t = 10))
        lidl.addView(Ui.text(c, tr("Ajoute les recettes qui n'existent pas dans votre langue, prises en anglais, français, allemand, espagnol… Chacune n'apparaît qu'une fois, avec sa langue sur la vignette."),
            14f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 6))

        // ---- Mes recettes
        val mine = section(right, tr("Mes recettes"), Icon.Kind.EDIT)
        mine.addView(Ui.text(c, tr("Sauvegardez vos recettes, ou passez-les à un autre robot, avec le fichier %s du dossier Download.", UserRecipes.FILE_NAME),
            16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) })
        val files = Ui.horizontal(c)
        files.addView(Ui.button(c, tr("Exporter"), Ui.Style.SECONDARY, Icon.Kind.UPLOAD, height = 52) { recipeFiles(export = true) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        files.addView(Ui.button(c, tr("Importer"), Ui.Style.SECONDARY, Icon.Kind.DOWNLOAD, height = 52) { recipeFiles(export = false) })
        mine.addView(files, Ui.lp(t = 12))

        // ---- Domotique : état en lecture seule pour Home Assistant (aucune commande à distance)
        val home = section(right, tr("Domotique"), Icon.Kind.LINK)
        home.addView(toggle(tr("Publier l'état sur le réseau local"), a.prefs.homeEnabled) { on ->
            if (on && a.prefs.homeToken.length < StatusServer.MIN_TOKEN) a.prefs.homeToken = StatusServer.newToken()
            a.prefs.homeEnabled = on
            a.machine.reconfigure { a.refusal(it) }
            renderHome()
        })
        home.addView(Ui.text(c, tr("Lecture seule : température, vitesse, poids, couvercle, bol, programme et temps restant. Personne ne peut lancer, arrêter ni régler le robot par le réseau. À n'activer que sur un Wi-Fi de confiance."),
            15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 6))
        homeText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f); setTextIsSelectable(true) }
        home.addView(homeText, Ui.lp(t = 10))
        home.addView(Ui.button(c, tr("Nouveau jeton"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 52) {
            a.prefs.homeToken = StatusServer.newToken()
            if (a.prefs.homeEnabled) a.machine.reconfigure { a.refusal(it) }
            renderHome()
        }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        renderHome()
        // ---- Compte Toque (toqueapp.org), facultatif
        val account = section(left, tr("Compte Toque"), Icon.Kind.PEOPLE)
        accountText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        account.addView(accountText)
        accountButtons = Ui.horizontal(c)
        account.addView(accountButtons, Ui.lp(t = 12))

        // ---- Recettes de la communauté (toqueapp.org), sans compte
        val community = section(left, tr("Recettes de la communauté"), Icon.Kind.GLOBE)
        community.addView(Ui.text(c, tr("Les recettes partagées sur toqueapp.org, reçues sans compte. Celles que la communauté a traduites arrivent dans votre langue."),
            16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) })
        community.addView(toggle(tr("Recevoir les recettes de la communauté"), a.prefs.siteCommunity) { on ->
            a.app.site.setCommunity(on)
            refreshSite()
        }, Ui.lp(t = 10))
        siteText = Ui.text(c, "", 16f, Ui.TEXT)
        community.addView(siteText, Ui.lp(t = 10))
        siteBtn = Ui.button(c, tr("Synchroniser"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 52) {
            a.app.site.sync { r -> r?.let { a.toast(it.message, if (it.ok) Ui.OK else Ui.WARN) } }
        }
        community.addView(siteBtn, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))

        // ---- Mises à jour
        val updates = section(left, tr("Mises à jour"), Icon.Kind.INFO)
        updText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        updates.addView(updText)
        updButtons = Ui.horizontal(c)
        updates.addView(updButtons, Ui.lp(t = 12))
        updates.addView(toggle(tr("Prévenir quand une version sort"), a.prefs.updateAlerts) { a.prefs.updateAlerts = it }, Ui.lp(t = 10))
        a.updates.available?.let { release = it; upd = Upd.AVAILABLE }
        renderUpdate()

        // ---- Outils
        val tools = section(right, tr("Outils"), Icon.Kind.SETTINGS)
        tools.addView(link(tr("Diagnostic de la base moteur")) { a.push(DiagnosticsScreen(a)) })
        tools.addView(link(tr("Bouton rotatif")) { a.push(KnobScreen(a)) })
        tools.addView(link(tr("Journal")) { a.push(LogScreen(a)) })
        tools.addView(link(tr("Paramètres Android")) { a.openAndroidSettings() })
        tools.addView(link(tr("Revenir à l'appli d'origine")) { restoreVendor() })
        val version = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (_: Exception) { "?" }
        tools.addView(Ui.text(c, tr("Toque %s · protocole d'après le Monsieur Cuisine Developer SDK (licence MIT). " +
            "Projet indépendant, non affilié à Lidl ni à SilverCrest.", version), 14f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 12))
        a.prefs.robot.let { p -> p.credit?.let { who ->
            tools.addView(Ui.text(c, tr("%s : protocole d'après %s (licence MIT).", p.displayName, who), 14f, Ui.TEXT3)
                .apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 4))
        } }

        return Ui.scroll(c, col)
    }

    /** Adresse et jeton à recopier dans Home Assistant. */
    private fun renderHome() {
        homeText.text = if (!a.prefs.homeEnabled) tr("Désactivé.")
        else tr("Adresse : %s · port %d\nJeton : %s", localAddress() ?: "?", a.prefs.homePort, a.prefs.homeToken)
    }

    /** Adresse IPv4 du robot sur le réseau local (sans permission Wi-Fi). */
    private fun localAddress(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
    } catch (_: Exception) { null }

    private fun section(parent: LinearLayout, title: String, icon: Icon.Kind): LinearLayout {
        val card = Ui.card(c, 24f)
        val head = Ui.horizontal(c)
        head.addView(Icon(c, icon, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(10) })
        head.addView(Ui.text(c, title, 21f, Ui.TEXT, Ui.MEDIUM))
        card.addView(head, Ui.lp(b = 10))
        parent.addView(card, Ui.lp(b = 16))
        return card
    }

    /** Pastille de couleur : un disque, entouré et coché quand c'est la couleur en cours. */
    private fun swatch(color: Int): View {
        val box = android.widget.FrameLayout(c)
        val selected = color == Ui.ACCENT
        val dot = android.widget.FrameLayout(c).apply {
            background = Ui.pressable(Ui.round(color, 26f, if (selected) Ui.TEXT else 0, if (selected) 3f else 0f), 26f)
            if (selected) addView(Icon(c, Icon.Kind.CHECK, Ui.ON_ACCENT), Ui.flp(Ui.u(24), Ui.u(24), Gravity.CENTER))
            isClickable = true
            setOnClickListener { if (!selected) a.applyAccent(color) }
        }
        box.addView(dot, Ui.flp(Ui.u(52), Ui.u(52), Gravity.CENTER))
        return box
    }

    private fun field(value: String, hint: String) = EditText(c).apply {
        setText(value)
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(19f))
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.TEXT3)
        setSingleLine()
        background = Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f)
        setPadding(Ui.u(14), 0, Ui.u(14), 0)
    }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = Ui.horizontal(c)
        row.addView(Ui.text(c, label, 18f, Ui.TEXT), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        var state = initial
        // Interrupteur : piste arrondie, pastille à gauche (non) ou à droite (oui).
        val sw = android.widget.FrameLayout(c)
        val knob = View(c)
        sw.addView(knob, Ui.flp(Ui.u(34), Ui.u(34), Gravity.CENTER_VERTICAL))
        sw.setPadding(Ui.u(5), 0, Ui.u(5), 0)
        fun paint() {
            sw.background = Ui.round(if (state) Ui.ACCENT else Ui.SURFACE3, 22f, if (state) Ui.ACCENT else Ui.STROKE, 1f)
            knob.background = Ui.round(if (state) Ui.ON_ACCENT else Ui.TEXT2, 17f)
            (knob.layoutParams as android.widget.FrameLayout.LayoutParams).gravity =
                Gravity.CENTER_VERTICAL or (if (state) Gravity.END else Gravity.START)
            knob.requestLayout()
            sw.contentDescription = if (state) tr("Oui") else tr("Non")
        }
        paint()
        val flip = { _: View -> state = !state; paint(); onChange(state) }
        sw.setOnClickListener(flip)
        row.setOnClickListener(flip)   // toute la ligne est touchable, pas seulement l'interrupteur
        row.minimumHeight = Ui.u(52)
        row.addView(sw, LinearLayout.LayoutParams(Ui.u(76), Ui.u(44)).apply { leftMargin = Ui.u(14) })
        return row
    }

    private fun link(label: String, onClick: () -> Unit): LinearLayout {
        val row = Ui.horizontal(c)
        row.setPadding(0, Ui.u(14), 0, Ui.u(14))
        row.addView(Ui.text(c, label, 18f, Ui.TEXT), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT3), LinearLayout.LayoutParams(Ui.u(20), Ui.u(20)))
        row.background = Ui.pressable(Ui.round(0, 8f), 8f)
        row.isClickable = true
        row.setOnClickListener { onClick() }
        return row
    }

    /** Menu déroulant : la valeur choisie, et la liste qui s'ouvre en dessous. */
    private fun dropdown(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): View {
        val box = Ui.horizontal(c)
        box.setPadding(Ui.u(16), 0, Ui.u(14), 0)
        box.background = Ui.pressable(Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f), 16f)
        var current = selected
        fun labelOf(code: String) = options.firstOrNull { it.first == code }?.second ?: options.first().second
        val label = Ui.text(c, labelOf(current), 19f, Ui.TEXT)
        box.addView(label, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        box.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT2).apply { rotation = 90f }, LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)))
        box.isClickable = true
        // Poser le doigt hors de la liste la ferme, y compris sur la case elle-même, qui reçoit aussi cet
        // appui : il faut s'en souvenir, sinon le même appui la rouvre aussitôt.
        var shown: PopupWindow? = null
        var boxDownAt = 0L
        var closedByDownAt = -1L
        box.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) boxDownAt = e.downTime
            false
        }
        box.setOnClickListener {
            shown?.let {
                if (it.isShowing) {
                    it.dismiss()
                    return@setOnClickListener
                }
            }
            if (closedByDownAt == boxDownAt) {
                closedByDownAt = -1L
                return@setOnClickListener
            }
            val list = Ui.vertical(c)
            list.setPadding(0, Ui.u(6), 0, Ui.u(6))
            val scroll = ScrollView(c).apply {
                addView(list)
                background = Ui.round(Ui.SURFACE3, 16f, Ui.STROKE, 1f)
            }
            val height = if (options.size > 7) Ui.u(400) else ViewGroup.LayoutParams.WRAP_CONTENT
            val popup = PopupWindow(scroll, box.width, height, false)
            for ((code, name) in options) {
                val row = Ui.text(c, name, 19f, if (code == current) Ui.ACCENT else Ui.TEXT)
                row.setPadding(Ui.u(16), Ui.u(14), Ui.u(16), Ui.u(14))
                row.background = Ui.pressable(Ui.round(0, 0f), 0f)
                row.isClickable = true
                row.setOnClickListener {
                    popup.dismiss()
                    if (code != current) {
                        // Le choix s'affiche tout de suite (avant, il fallait rouvrir les réglages).
                        current = code
                        label.text = labelOf(code)
                        onPick(code)
                    }
                }
                list.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
            }
            popup.setBackgroundDrawable(ColorDrawable(0))
            popup.isOutsideTouchable = true
            popup.setTouchInterceptor { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) closedByDownAt = e.downTime
                false
            }
            popup.setOnDismissListener { if (shown === popup) shown = null }
            popup.elevation = Ui.uf(8f)
            popup.showAsDropDown(box, 0, Ui.u(6))
            shown = popup
        }
        return box
    }

    private lateinit var robotText: TextView

    private fun renderRobot() {
        val p = a.prefs.robot
        robotText.text = if (!a.prefs.robotConfirmed) tr("Pas encore choisi : la liaison avec la base reste fermée en « Machine réelle ».")
            else p.displayName + " (" + p.models.joinToString(", ") + ")" + if (p.tested) "" else
                "\n" + tr("En test : le projet ne l'a pas essayé lui-même, vos retours sont attendus sur GitHub.")
    }

    private fun renderModes() {
        modeRow.removeAllViews()
        for (m in Prefs.Mode.values()) {
            modeRow.addView(Ui.toggleChip(c, when (m) {
                Prefs.Mode.DEMO -> tr("Démo")
                Prefs.Mode.TWIN -> tr("Jumeau (PC)")
                Prefs.Mode.HARDWARE -> tr("Machine réelle")
            }, mode == m) { mode = m; renderModes() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        }
        twinBox.visibility = if (mode == Prefs.Mode.TWIN) View.VISIBLE else View.GONE
        linkText.text = when (mode) {
            Prefs.Mode.DEMO -> tr("Base moteur simulée : pour essayer l'appli sur n'importe quel appareil Android.")
            Prefs.Mode.TWIN -> tr("Jumeau numérique du SDK lancé sur un PC (tools/twin.py). Avec « adb reverse tcp:8765 tcp:8765 », gardez 127.0.0.1.")
            Prefs.Mode.HARDWARE -> tr("Pilote le vrai moteur par le port série. Exige le root. L'appli d'origine est coupée du moteur (réversible).")
        }
    }

    private fun applyMode() {
        if (a.snapshot.busy) {
            a.toast(tr("Arrêtez d'abord le programme en cours"), Ui.WARN)
            return
        }
        if (mode == Prefs.Mode.HARDWARE && !a.prefs.robotConfirmed) {
            // Jamais de trame vers la base sans savoir quel robot c'est.
            a.toast(tr("Choisissez d'abord votre robot"), Ui.WARN)
            a.askRobot()
            return
        }
        if (mode == Prefs.Mode.HARDWARE && a.prefs.mode != Prefs.Mode.HARDWARE) {
            a.confirm(
                tr("Piloter la vraie machine"),
                tr("Toque va prendre le contrôle du moteur et de la chauffe à la place de l'appli d'origine.\n\n" +
                    "Premier essai : bol vide, couvercle fermé, à portée de l'interrupteur principal. " +
                    "Si quoi que ce soit semble anormal, coupez l'interrupteur."),
                tr("Continuer"), danger = true,
            ) { prepareHardware() }
            return
        }
        save()
    }

    private fun save() {
        a.prefs.mode = mode
        a.prefs.bootLaunch = true
        a.prefs.twinHost = host.text.toString().trim().ifEmpty { "127.0.0.1" }
        a.prefs.twinPort = port.text.toString().trim().toIntOrNull() ?: 8765
        a.machine.reconfigure { v -> if (v.ok) a.toast(tr("Mode : %s", mode.label), Ui.OK) else a.refusal(v) }
        a.refreshTitle()
    }

    private fun prepareHardware() {
        a.toast(tr("Vérifications…"))
        Thread {
            val problems = ArrayList<String>()
            if (!RootShell.available()) problems += tr("accès root refusé")
            val sync = LidlSync(a, a.prefs, a.app.recipes, a.app.log)
            if (problems.isEmpty() && sync.installed()) {
                if (sync.components().hardwareService != null && !sync.setVendorHardware(false))
                    problems += tr("impossible de couper l'appli d'origine du moteur")
                sync.forceStop()
            }
            view.post {
                if (problems.isNotEmpty()) {
                    a.confirm(tr("Machine réelle indisponible"), problems.joinToString("\n") { "• $it" }, "OK", tr("Fermer")) {}
                } else save()
            }
        }.start()
    }

    private fun restoreVendor() {
        a.confirm(
            tr("Revenir à l'appli d'origine"),
            tr("Toque libère le moteur, rend l'accès à l'appli Monsieur Cuisine d'origine et passe en mode Démo. " +
                "Toque ne s'ouvrira plus au démarrage du robot."),
            tr("Revenir"),
        ) {
            a.prefs.mode = Prefs.Mode.DEMO
            a.prefs.bootLaunch = false
            a.machine.reconfigure {
                Thread {
                    val sync = LidlSync(a, a.prefs, a.app.recipes, a.app.log)
                    sync.setVendorHardware(true)
                    val launch = sync.components().launch
                    view.post {
                        if (launch != null) a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else a.toast(tr("Appli d'origine introuvable"), Ui.WARN)
                    }
                }.start()
            }
        }
    }

    private fun sync(online: Boolean) {
        if (SyncProgress.running) return
        if (!online && a.prefs.mode != Prefs.Mode.HARDWARE) {
            a.toast(tr("Disponible une fois Toque installé sur le robot (mode Machine réelle)"), Ui.WARN)
            return
        }
        if (!online && !a.prefs.robot.stockImport) {
            // Base de l'appli d'origine du Smart : format pas encore relevé.
            a.toast(tr("Pas encore possible sur ce robot : utilisez « Synchroniser »"), Ui.WARN)
            return
        }
        // L'avancement vit dans SyncProgress, pas dans cet écran : on peut le quitter et y revenir.
        SyncProgress.start(tr("Synchronisation en cours… (quelques minutes)"), stoppable = online)
        val app = a.app
        Thread {
            val r = if (online) OnlineSync(app, app.prefs, app.recipes, app.log).sync()
            else try {
                LidlSync(app, app.prefs, app.recipes, app.log).importNow { SyncProgress.report(it) }
            } finally {
                SyncProgress.finished()
            }
            a.runOnUiThread { a.toast(r.message, if (r.ok) Ui.OK else Ui.WARN) }
        }.start()
    }

    private fun recipeFiles(export: Boolean) {
        a.withStorage { ok ->
            if (!ok) { a.toast(tr("Accès au dossier Download refusé"), Ui.WARN); return@withStorage }
            Thread {
                val files = RecipeFiles(a.app.recipes, a.app.log)
                val r = if (export) files.export() else files.import()
                view.post { a.toast(r.message, if (r.ok) Ui.OK else Ui.WARN) }
            }.start()
        }
    }

    private fun refreshTime(message: String? = null) {
        val zone = TimeSync.current()
        val mode = if (a.prefs.timeZone == ZoneLookup.AUTO) tr("automatique") else tr("choisi à la main")
        timeText.text = tr("Il est %s · fuseau %s (%s)", TimeSync.clock(), zone.replace('_', ' '), mode) +
            (message?.let { "\n" + it } ?: "")
    }

    private fun setClock() {
        if (settingTime) return
        settingTime = true
        timeText.text = tr("Réglage de l'heure…")
        Thread {
            val r = try {
                TimeSync(a, a.prefs, a.app.log).run()
            } catch (e: Exception) {
                TimeSync.Result(false, TimeSync.current(), e.message ?: e.toString())
            }
            a.runOnUiThread {
                settingTime = false
                refreshTime(if (r.ok) null else r.message)
                if (r.ok) a.toast(r.message, Ui.OK)
            }
        }.start()
    }

    private fun refreshSync() {
        val progress = SyncProgress.message
        val running = progress != null
        // Pendant une synchronisation : rien à relancer, et un bouton pour l'arrêter.
        Ui.setButtonEnabled(syncBtn, !running)
        importBtn?.let { Ui.setButtonEnabled(it, !running) }
        stopSyncBtn.visibility = if (running && SyncProgress.stoppable) View.VISIBLE else View.GONE
        Ui.setButtonEnabled(stopSyncBtn, !SyncProgress.stopRequested)
        val last = a.prefs.lastSyncResult
        val first = when {
            progress == null -> if (last.isEmpty()) tr("Jamais synchronisé") else tr("Dernière synchro le %s", lastLine(last, a.prefs.lastSyncLang, a.prefs.lastSyncMs))
            SyncProgress.stopRequested -> tr("Arrêt en cours…")
            else -> progress
        }
        // Le nombre de recettes reste affiché sous l'avancement : « Recettes : 343 / 1667 » compte les fiches relues, pas le catalogue.
        val repo = a.app.recipes
        syncText.text = first + "\n" + if (repo.loaded) tr("%d recettes dans Toque", repo.count) +
            // Recettes enregistrées mais pas faisables sur ce robot (autre robot, programme absent).
            (if (repo.hiddenCount > 0) "\n" + tr("%d recettes masquées : pas faites pour ce robot", repo.hiddenCount) else "")
            else tr("Chargement des recettes…")
    }

    /**
     * Ligne « date : résultat » d'une synchro. Écrite dans une autre langue que celle de l'appli
     * (langue changée depuis) : seulement la date, dans la langue d'aujourd'hui, plutôt qu'un texte
     * dans l'ancienne langue. Le résultat complet revient à la synchro suivante.
     */
    private fun lastLine(text: String, lang: String, ms: Long): String =
        if (lang.isEmpty() || lang == I18n.code || ms <= 0) text
        else java.text.SimpleDateFormat(tr("dd/MM à HH:mm"), I18n.locale).format(java.util.Date(ms))

    // ------------------------------------------------------------------ toqueapp.org

    private fun renderAccount() {
        accountButtons.removeAllViews()
        val prefs = a.prefs
        if (prefs.siteLinked) {
            accountText.text = tr("Relié au compte « %s ». Vos favoris du site arrivent sur le robot, et vous pouvez publier vos recettes.", prefs.siteUser)
            accountButtons.addView(Ui.button(c, tr("Délier"), Ui.Style.GHOST, Icon.Kind.CLOSE, height = 52) {
                a.confirm(tr("Délier le robot ?"), tr("Le robot ne recevra plus vos favoris du site. Les recettes déjà reçues restent."), tr("Délier")) {
                    a.app.site.unlink()
                    a.toast(tr("Robot délié du compte"))
                }
            })
        } else {
            accountText.text = tr("Aucun compte relié. Le compte est facultatif : relié, le robot reçoit vos favoris du site et vous pouvez publier vos recettes depuis le robot.")
            accountButtons.addView(Ui.button(c, tr("Relier mon compte"), Ui.Style.PRIMARY, Icon.Kind.LINK, height = 52) { a.push(SiteLinkScreen(a)) })
        }
    }

    private fun refreshSite() {
        val site = a.app.site
        val prefs = a.prefs
        val on = prefs.siteCommunity || prefs.siteLinked
        Ui.setButtonEnabled(siteBtn, on && !site.busy)
        val last = prefs.siteLastResult
        val first = when {
            site.busy -> tr("Synchronisation en cours…")
            last.isEmpty() -> tr("Jamais synchronisé")
            else -> tr("Dernière synchro le %s", lastLine(last, prefs.siteLastLang, prefs.siteLastMs))
        }
        val repo = a.app.recipes
        val n = if (repo.loaded) repo.index.entries.count { CommunityRecipes.isCommunity(it.id) } else 0
        siteText.text = first + if (repo.loaded) "\n" + tr("%d recettes de la communauté dans Toque", n) else ""
    }

    // ------------------------------------------------------------------ mises à jour

    private fun renderUpdate() {
        val r = release
        val installed = tr("Version installée : %s", updater.installedVersion)
        updText.setTextColor(if (upd == Upd.ERROR) Ui.WARN else if (upd == Upd.AVAILABLE) Ui.TEXT else Ui.TEXT2)
        updText.text = when (upd) {
            Upd.IDLE -> installed + "\n" + tr("Les nouvelles versions sont publiées sur GitHub.")
            Upd.CHECKING -> installed + "\n" + tr("Recherche sur GitHub…")
            Upd.UP_TO_DATE -> installed + "\n" + tr("Toque est à jour.")
            Upd.AVAILABLE -> installed + "\n" + tr("Nouvelle version disponible : %s", r?.version) +
                UpdateFlow.notes(r).let { if (it.isEmpty()) "" else "\n\n" + it }
            Upd.ERROR -> installed + "\n" + updMessage
        }
        updButtons.removeAllViews()
        when (upd) {
            Upd.IDLE, Upd.UP_TO_DATE, Upd.ERROR ->
                updButtons.addView(Ui.button(c, tr("Rechercher une mise à jour"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 58) { checkUpdate() })
            Upd.AVAILABLE -> {
                updButtons.addView(Ui.button(c, tr("Installer la version %s", r?.version), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 58) { installUpdate() },
                    Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
                updButtons.addView(Ui.button(c, tr("Plus tard"), Ui.Style.GHOST, height = 58) { upd = Upd.IDLE; renderUpdate() })
            }
            Upd.CHECKING -> {}
        }
    }

    private fun checkUpdate() {
        if (upd == Upd.CHECKING) return
        upd = Upd.CHECKING
        renderUpdate()
        Thread {
            try {
                val r = updater.latest()
                view.post {
                    release = r
                    val newer = updater.isNewer(r)
                    a.updates.known(r, newer)
                    upd = if (newer) Upd.AVAILABLE else Upd.UP_TO_DATE
                    renderUpdate()
                    if (upd == Upd.AVAILABLE) installUpdate()
                }
            } catch (e: Updater.Failure) {
                view.post { upd = Upd.ERROR; updMessage = e.message ?: tr("Erreur"); renderUpdate() }
            } catch (e: Exception) {
                a.app.log.crash("maj", e)
                view.post { upd = Upd.ERROR; updMessage = tr("Erreur inattendue : %s", e.message); renderUpdate() }
            }
        }.start()
    }

    /** Propose l'installation dans une fenêtre (toujours visible, même si la carte est hors écran). */
    private fun installUpdate() {
        val r = release ?: return
        UpdateFlow.offer(a, r) { msg -> upd = Upd.ERROR; updMessage = msg; renderUpdate() }
    }

    /** Désabonnement du suivi des recettes (le nombre s'affiche une fois la base chargée). */
    private var unsubRecipes: (() -> Unit)? = null
    private var unsubSync: (() -> Unit)? = null
    private var unsubSite: (() -> Unit)? = null

    override fun onShow() {
        mode = a.prefs.mode
        renderModes()
        renderRobot()
        refreshSync()
        renderAccount()
        refreshSite()
        // Changement de robot : les recettes sont refiltrées, puis cet écran se met à jour.
        unsubRecipes = a.app.recipes.onChange { renderRobot(); refreshSync(); refreshSite() }
        unsubSync = SyncProgress.onChange { refreshSync() }
        unsubSite = a.app.site.onChange { renderAccount(); refreshSite() }
    }

    override fun onHide() {
        unsubRecipes?.invoke()
        unsubRecipes = null
        unsubSync?.invoke()
        unsubSync = null
        unsubSite?.invoke()
        unsubSite = null
    }

    override fun onSnapshot(s: Snapshot) {
        bowlText.text = if (s.hasSample) tr("Capteur du bol en ce moment : %s. Vérifiez qu'il change quand vous retirez le bol avant d'activer cette option.",
            if (s.bowlDetected) tr("bol détecté") else tr("bol NON détecté")) else tr("Base non connectée")
    }
}
