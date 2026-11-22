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
import android.widget.FrameLayout
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
import app.toque.core.time.ZoneLookup
import app.toque.core.update.Release
import app.toque.recipes.LidlSync
import app.toque.core.recipes.McOnline
import app.toque.mqtt.MqttPublisher
import app.toque.recipes.OnlineSync
import app.toque.recipes.SyncProgress
import app.toque.recipes.RecipeFiles
import app.toque.core.recipes.CommunityRecipes
import app.toque.core.recipes.UserRecipes
import app.toque.ui.AccentWheel
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Sounds
import app.toque.ui.Ui
import app.toque.ui.UpdateFlow
import app.toque.update.Updater

/**
 * Réglages : à gauche les familles, toujours visibles ; à droite la page de la famille ouverte.
 * [startOn] : la famille à ouvrir (sinon la première). Voir [reopened] quand l'interface est refaite.
 */
class SettingsScreen(a: MainActivity, private val startOn: Family? = null) : Screen(a) {
    /** Les familles de réglages, dans l'ordre du menu : le quotidien d'abord, le technique à la fin. */
    enum class Family { SCREEN, KNOB_SOUNDS, RECIPES, COOKING, SAFETY, LANGUAGE_TIME, UPDATES, ADVANCED }

    /** D'où viennent les recettes : une pastille chacune, en haut de la famille « Recettes ». */
    private enum class Source { LIDL, COMMUNITY, MINE, STOCK }

    override val title = tr("Réglages")
    /** La famille ouverte. */
    var family = startOn ?: Family.SCREEN
        private set
    /** La page de droite. Elle ne défile que si la famille ne tient pas à l'écran (autre langue, texte long). */
    lateinit var pane: ScrollView
        private set
    private lateinit var menu: ScrollView
    private val entries = ArrayList<LinearLayout>()
    private val pages = ArrayList<LinearLayout>()
    private var source = Source.LIDL
    private lateinit var sourceRow: LinearLayout
    private val sourcePages = ArrayList<LinearLayout>()
    /** La liste déroulante ouverte, s'il y en a une : refermée quand on change de famille. */
    private var openList: PopupWindow? = null
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
    private lateinit var invertHint: TextView
    private lateinit var linkText: TextView
    private lateinit var timeText: TextView
    /** Point sur « Avancé » et phrase au-dessus de « Machine » : tant que Toque ne pilote pas le vrai moteur. */
    private lateinit var modeDot: View
    private lateinit var modeText: TextView
    private lateinit var mqttHost: EditText
    private lateinit var mqttPort: EditText
    private lateinit var mqttUser: EditText
    private lateinit var mqttPassword: EditText
    private lateinit var mqttPrefix: EditText
    private lateinit var mqttStatus: TextView
    private var settingTime = false
    private var mode = a.prefs.mode

    private enum class Upd { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, ERROR }
    private val updater by lazy { Updater(a) }
    private lateinit var updBox: LinearLayout
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
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(16), 0, Ui.u(16), Ui.u(16))

        // ---- À gauche, les familles. Elles tiennent toutes à l'écran ; la liste ne défile que si la
        // barre de cuisson prend de la place en bas.
        val list = Ui.vertical(c)
        val families = Family.values()
        for (f in families) list.addView(entry(f), Ui.lp(Ui.MATCH, Ui.u(ENTRY), b = if (f == families.last()) 0 else 4))
        menu = Ui.scroll(c, list)
        root.addView(menu, LinearLayout.LayoutParams(Ui.u(256), Ui.MATCH).apply { rightMargin = Ui.u(16) })

        // ---- À droite, une page par famille dans une même carte : seule celle de la famille ouverte se voit.
        val box = Ui.vertical(c)
        box.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(10))
        for (f in families) {
            val page = Ui.vertical(c)
            pages += page
            box.addView(page, Ui.lp())
            when (f) {
                Family.SCREEN -> screenPage(page)
                Family.KNOB_SOUNDS -> knobAndSoundsPage(page)
                Family.RECIPES -> recipesPage(page)
                Family.COOKING -> cookingPage(page)
                Family.SAFETY -> safetyPage(page)
                Family.LANGUAGE_TIME -> languageAndTimePage(page)
                Family.UPDATES -> updatesPage(page)
                Family.ADVANCED -> advancedPage(page)
            }
        }
        pane = Ui.scroll(c, box)
        val card = FrameLayout(c)
        card.background = Ui.round(Ui.SURFACE, 24f)
        card.clipToOutline = true   // la page défile sous les coins arrondis, sans les dépasser
        card.addView(pane, Ui.flp())
        root.addView(card, LinearLayout.LayoutParams(0, Ui.MATCH, 1f))

        renderMode()
        open(family)
        return root
    }

    // ------------------------------------------------------------------ le menu

    private fun name(f: Family): String = when (f) {
        Family.SCREEN -> tr("Écran")
        Family.KNOB_SOUNDS -> tr("Molette et sons")
        Family.RECIPES -> tr("Recettes")
        Family.COOKING -> tr("Cuisson et balance")
        Family.SAFETY -> tr("Sécurité")
        Family.LANGUAGE_TIME -> tr("Langue et heure")
        Family.UPDATES -> tr("Mises à jour")
        Family.ADVANCED -> tr("Avancé")
    }

    private fun icon(f: Family): Icon.Kind = when (f) {
        Family.SCREEN -> Icon.Kind.SCREEN
        Family.KNOB_SOUNDS -> Icon.Kind.KNOB
        Family.RECIPES -> Icon.Kind.BOOK
        Family.COOKING -> Icon.Kind.POT
        Family.SAFETY -> Icon.Kind.WARNING
        Family.LANGUAGE_TIME -> Icon.Kind.GLOBE   // le globe se reconnaît quelle que soit la langue affichée
        Family.UPDATES -> Icon.Kind.DOWNLOAD
        Family.ADVANCED -> Icon.Kind.SETTINGS
    }

    /** Une ligne du menu : l'icône de la famille et son nom (rapetissé plutôt que coupé dans une langue aux mots longs). */
    private fun entry(f: Family): LinearLayout {
        val row = Ui.horizontal(c)
        row.setPadding(Ui.u(14), 0, Ui.u(12), 0)
        row.addView(Icon(c, icon(f), Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(12) })
        row.addView(Ui.fitText(c, 18f, 14f, Ui.TEXT, Ui.MEDIUM).apply { text = name(f) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        if (f == Family.ADVANCED) {
            modeDot = View(c).apply { background = Ui.round(Ui.ACCENT, 5f) }
            row.addView(modeDot, LinearLayout.LayoutParams(Ui.u(10), Ui.u(10)).apply { leftMargin = Ui.u(8) })
        }
        row.isClickable = true
        row.setOnClickListener { open(f) }
        entries += row
        return row
    }

    /** Ouvre la famille [f]. [atEnd] : sa page est montrée par la fin (on y arrive en remontant à la molette). */
    private fun open(f: Family, atEnd: Boolean = false) {
        openList?.dismiss()
        a.hideKeyboard()
        family = f
        last = f
        for (x in Family.values()) {
            val on = x == f
            pages[x.ordinal].visibility = if (on) View.VISIBLE else View.GONE
            val row = entries[x.ordinal]
            row.background = if (on) Ui.round(Ui.SURFACE3, 16f, Ui.STROKE, 1f) else Ui.pressable(Ui.round(0, 16f), 16f)
            row.isSelected = on
            (row.getChildAt(0) as Icon).color = if (on) Ui.ACCENT_INK else Ui.TEXT2
            (row.getChildAt(1) as TextView).typeface = if (on) Ui.BOLD else Ui.MEDIUM
        }
        pane.scrollTo(0, 0)
        if (atEnd) pane.post { pane.scrollTo(0, pane.getChildAt(0).height) }
        // La ligne de la famille reste visible quand le menu lui-même défile.
        val row = entries[f.ordinal]
        menu.post {
            if (row.top < menu.scrollY) menu.scrollTo(0, row.top)
            else if (row.bottom > menu.scrollY + menu.height) menu.scrollTo(0, row.bottom - menu.height)
        }
    }

    /**
     * La molette parcourt les réglages comme elle parcourait la page unique : elle fait défiler la page
     * ouverte si elle dépasse de l'écran, puis passe à la famille suivante (ou précédente).
     */
    override fun onKnob(delta: Int): Boolean {
        if (delta == 0) return true
        val dir = if (delta > 0) 1 else -1
        if (pane.canScrollVertically(dir)) {
            pane.smoothScrollTo(0, pane.scrollY + delta * Ui.u(KNOB_STEP))
            return true
        }
        val next = family.ordinal + dir
        if (next in Family.values().indices) open(Family.values()[next], atEnd = dir < 0)
        return true
    }

    // ------------------------------------------------------------------ les pages

    private fun screenPage(p: LinearLayout) {
        group(p, tr("Apparence"))
        val themes = listOf("dark" to tr("Mode sombre"), "light" to tr("Mode clair"))
        p.addView(choice(tr("Thème"), dropdown(themes, if (a.prefs.lightTheme) "light" else "dark") { code ->
            val light = code == "light"
            if (light != a.prefs.lightTheme) a.applyTheme(light)
        }), Ui.lp(t = 8, b = 8))
        line(p)
        // Couleur d'accent : les dix couleurs sur une ligne.
        p.addView(Ui.text(c, tr("Couleur"), 18f, Ui.TEXT), Ui.lp(t = 12))
        p.addView(hint(tr("Couleur des boutons, des icônes et des repères.")), Ui.lp(t = 4))
        val ten = Ui.horizontal(c)
        for (color in Ui.ACCENTS) ten.addView(swatch(color), LinearLayout.LayoutParams(Ui.u(52), Ui.u(52)).apply { rightMargin = Ui.u(12) })
        p.addView(ten, Ui.lp(t = 10, b = 4))
        // Toute autre couleur : la roue. Une couleur prise sur la roue est montrée cochée, devant le bouton.
        val wheelRow = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        if (Ui.ACCENT !in Ui.ACCENTS)
            wheelRow.addView(swatch(Ui.ACCENT) { openWheel() }, LinearLayout.LayoutParams(Ui.u(52), Ui.u(52)).apply { rightMargin = Ui.u(12) })
        wheelRow.addView(Ui.button(c, tr("Roue des couleurs"), Ui.Style.SECONDARY, height = 52, textSize = 18f) { openWheel() }, Ui.lp(Ui.WRAP, Ui.WRAP))
        p.addView(wheelRow, Ui.lp(t = 4, b = 10))
        // L'image de l'anneau se prépare déjà (une fois par processus) : la roue s'ouvre complète.
        AccentWheel.Ring.get(Ui.u(AccentWheelScreen.RING)) {}

        group(p, tr("Veille"))
        val delays = listOf("0" to tr("Jamais")) + listOf(2, 5, 10, 15, 30).map { it.toString() to tr("Après %d min", it) }
        p.addView(choice(tr("Mise en veille"), dropdown(delays, a.prefs.standbyMinutes.toString()) { m -> a.prefs.standbyMinutes = m.toInt() }),
            Ui.lp(t = 8))
        p.addView(hint(tr("Sans geste ni programme en cours, l'écran s'assombrit et n'affiche plus que l'heure. Touchez-le ou tournez le bouton pour reprendre ; le couvercle et la balance le réveillent aussi.")),
            Ui.lp(t = 4, b = 8))
    }

    private fun knobAndSoundsPage(p: LinearLayout) {
        group(p, tr("Molette"))
        val knob = listOf("normal" to tr("Normale"), "dim" to tr("Plus légère"), "off" to tr("Éteinte"))
        p.addView(choice(tr("Molette pendant la veille"), dropdown(knob, a.prefs.standbyKnob) { a.prefs.standbyKnob = it }), Ui.lp(t = 8, b = 8))
        line(p)
        p.addView(toggle(tr("Couleurs sur la molette"), a.prefs.knobColours) { a.prefs.knobColours = it }, Ui.lp(t = 8))
        p.addView(hint(tr("L'anneau de la molette montre l'état du robot : rouge pour un problème, orange pendant une pause, vert quand c'est à vous, cyan pendant un programme, blanc sinon.")),
            Ui.lp(t = 4, b = 8))
        line(p)
        // Les touches que la molette envoie : à côté de ses autres réglages (c'était dans « Avancé »).
        p.addView(link(tr("Bouton rotatif")) { a.push(KnobScreen(a)) })

        group(p, tr("Sons"))
        p.addView(toggle(tr("Activer les sons"), a.prefs.sound) { a.prefs.sound = it }, Ui.lp(t = 8, b = 8))
        line(p)
        p.addView(choice(tr("Son quand la quantité à peser est atteinte"), dropdown(Sounds.WEIGH_SOUNDS.map { it.first to tr(it.second) }, a.prefs.weighSound) { k ->
            a.prefs.weighSound = k
            Sounds.weighReached(k)
        }), Ui.lp(t = 8, b = 8))
    }

    private fun recipesPage(p: LinearLayout) {
        sourceRow = Ui.horizontal(c)
        p.addView(sourceRow, Ui.lp(t = 10, b = 10))
        line(p)
        for (s in Source.values()) {
            val page = Ui.vertical(c)
            sourcePages += page
            p.addView(page, Ui.lp())
            when (s) {
                Source.LIDL -> lidlPage(page)
                Source.COMMUNITY -> communityPage(page)
                Source.MINE -> myRecipesPage(page)
                Source.STOCK -> stockAppPage(page)
            }
        }
        renderSources()
    }

    /** Les pastilles des sources de recettes, et la page de celle qui est choisie. */
    private fun renderSources() {
        openList?.dismiss()
        sourceRow.removeAllViews()
        for (s in Source.values()) {
            sourceRow.addView(Ui.toggleChip(c, when (s) {
                Source.LIDL -> "Lidl"
                Source.COMMUNITY -> tr("Communauté")
                Source.MINE -> tr("Mes recettes")
                Source.STOCK -> tr("Appli d'origine")
            }, source == s) { source = s; renderSources() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
            sourcePages[s.ordinal].visibility = if (source == s) View.VISIBLE else View.GONE
        }
    }

    /** Recettes Lidl, téléchargées de monsieur-cuisine.com. */
    private fun lidlPage(p: LinearLayout) {
        val chosen = a.prefs.robot
        val texts = Ui.vertical(c)
        syncText = Ui.text(c, "", 16f, Ui.TEXT).apply { setLineSpacing(0f, 1.15f) }
        texts.addView(syncText)
        texts.addView(hint(if (chosen.lidlDevice == "MC2.0") tr("Toque télécharge lui-même les recettes officielles du Monsieur Cuisine connect sur monsieur-cuisine.com (sans compte).")
            else tr("Toque télécharge lui-même les recettes officielles du %s sur monsieur-cuisine.com (sans compte).", chosen.displayName)), Ui.lp(t = 6))
        val buttons = Ui.horizontal(c)
        syncBtn = Ui.button(c, tr("Synchroniser"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 52, textSize = 18f) { sync(online = true) }
        buttons.addView(syncBtn)
        // Visible seulement pendant une synchronisation ; ce qui est déjà fait est gardé.
        stopSyncBtn = Ui.button(c, tr("Arrêter"), Ui.Style.SECONDARY, Icon.Kind.STOP, height = 52, textSize = 18f) { SyncProgress.requestStop() }
        buttons.addView(stopSyncBtn, Ui.lp(Ui.WRAP, Ui.WRAP, l = 10))
        p.addView(setting(texts, buttons), Ui.lp(t = 8, b = 8))
        line(p)
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
        p.addView(choice(tr("Langue des recettes"), dropdown(recipeLangs, a.prefs.recipeLanguage) { code ->
            a.prefs.recipeLanguage = code
            a.toast(tr("Pris en compte à la prochaine synchronisation"))
        }), Ui.lp(t = 8, b = 8))
        line(p)
        p.addView(toggle(tr("Synchro automatique chaque nuit"), a.prefs.autoSync) { a.prefs.autoSync = it }, Ui.lp(t = 8, b = 8))
        line(p)
        p.addView(toggle(tr("Recettes des autres langues"), a.prefs.foreignRecipes) {
            a.prefs.foreignRecipes = it
            a.toast(tr("Pris en compte à la prochaine synchronisation"))
        }, Ui.lp(t = 8))
        p.addView(hint(tr("Ajoute les recettes qui n'existent pas dans votre langue, prises en anglais, français, allemand, espagnol… Chacune n'apparaît qu'une fois, avec sa langue sur la vignette.")),
            Ui.lp(t = 4, b = 8))
    }

    /** Recettes de la communauté (toqueapp.org), reçues sans compte ; et le compte Toque, facultatif. */
    private fun communityPage(p: LinearLayout) {
        p.addView(toggle(tr("Recevoir les recettes de la communauté"), a.prefs.siteCommunity) { on ->
            a.app.site.setCommunity(on)
            refreshSite()
        }, Ui.lp(t = 8))
        p.addView(hint(tr("Les recettes partagées sur toqueapp.org, reçues sans compte. Celles que la communauté a traduites arrivent dans votre langue.")),
            Ui.lp(t = 4, b = 8))
        line(p)
        siteText = Ui.text(c, "", 16f, Ui.TEXT).apply { setLineSpacing(0f, 1.15f) }
        siteBtn = Ui.button(c, tr("Synchroniser"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 52, textSize = 18f) {
            a.app.site.sync { r -> r?.let { a.toast(it.message, if (it.ok) Ui.OK else Ui.WARN) } }
        }
        p.addView(setting(siteText, siteBtn), Ui.lp(t = 8, b = 8))

        group(p, tr("Compte Toque"))
        accountText = hint("")
        accountButtons = Ui.horizontal(c)
        p.addView(setting(accountText, accountButtons), Ui.lp(t = 8, b = 8))
    }

    /** Les recettes écrites sur ce robot : un fichier pour les sauvegarder ou les passer à un autre robot. */
    private fun myRecipesPage(p: LinearLayout) {
        p.addView(hint(tr("Sauvegardez vos recettes, ou passez-les à un autre robot, avec le fichier %s du dossier Download.", UserRecipes.FILE_NAME)), Ui.lp(t = 12))
        val files = Ui.horizontal(c)
        files.addView(Ui.button(c, tr("Exporter"), Ui.Style.SECONDARY, Icon.Kind.UPLOAD, height = 52, textSize = 18f) { recipeFiles(export = true) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        files.addView(Ui.button(c, tr("Importer"), Ui.Style.SECONDARY, Icon.Kind.DOWNLOAD, height = 52, textSize = 18f) { recipeFiles(export = false) })
        p.addView(files, Ui.lp(t = 12, b = 8))
    }

    /** L'appli d'origine du robot : y reprendre ses recettes (là où Toque sait lire sa base), ou l'ouvrir. */
    private fun stockAppPage(p: LinearLayout) {
        val buttons = Ui.horizontal(c)
        if (a.prefs.robot.stockImport) {
            p.addView(hint(tr("Autre source : les recettes déjà présentes dans l'appli d'origine du robot (remplacent celles du site).")), Ui.lp(t = 12))
            val btn = Ui.button(c, tr("Importer de l'appli d'origine"), Ui.Style.SECONDARY, height = 52, textSize = 18f) { sync(online = false) }
            buttons.addView(btn, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
            importBtn = btn
        }
        buttons.addView(Ui.button(c, tr("Ouvrir l'appli d'origine"), Ui.Style.GHOST, Icon.Kind.LINK, height = 52, textSize = 18f) { a.openVendorApp() })
        p.addView(buttons, Ui.lp(t = 12, b = 8))
    }

    private fun cookingPage(p: LinearLayout) {
        group(p, tr("Pendant une recette"))
        p.addView(toggle(tr("Enchaîner les étapes machine des recettes"), a.prefs.chainSteps) { a.prefs.chainSteps = it }, Ui.lp(t = 8))
        p.addView(hint(tr("Les réglages qui se suivent sans geste à faire entre eux partent ensemble d'un seul appui sur Lancer : la lame passe directement à la vitesse et à la température suivantes.")),
            Ui.lp(t = 4, b = 8))
        line(p)
        p.addView(toggle(tr("Tare automatique aux étapes de pesée des recettes"), a.prefs.autoTareSteps) { a.prefs.autoTareSteps = it }, Ui.lp(t = 8))
        p.addView(hint(tr("En passant à une étape « Pesez… », la balance repart de zéro : seul ce que vous ajoutez est compté. Jamais pendant un programme.")),
            Ui.lp(t = 4, b = 8))

        // Balance (0.8.2) : zéro au retour du bol vide.
        group(p, tr("Balance"))
        p.addView(toggle(tr("Remettre la balance à zéro quand le bol vide est replacé"), a.prefs.bowlAutoZero) { on ->
            a.prefs.bowlAutoZero = on
            a.machine.reconfigure { a.refusal(it) }
        }, Ui.lp(t = 8))
        p.addView(hint(tr("Toque compare la masse posée au bol vide mémorisé : un bol replacé avec du contenu n'est jamais remis à zéro.")), Ui.lp(t = 4))
        // Le bol vide mémorisé et, à sa droite, le bouton pour l'oublier : montré seulement s'il y en a un.
        val bowlState = hint("")
        var forget: View? = null
        fun showBowl() {
            val g = a.prefs.emptyBowlG
            bowlState.text = Ui.guillemets(if (g > 0) tr("Bol vide mémorisé : %d g.", g)
                else tr("Bol vide pas encore mémorisé : retirez le bol, replacez-le vide (avec son couvercle si vous cuisinez avec), puis appuyez sur Tare."))
            forget?.visibility = if (g > 0) View.VISIBLE else View.GONE
        }
        val button = Ui.button(c, tr("Oublier le bol vide mémorisé"), Ui.Style.SECONDARY, height = 52, textSize = 18f) {
            a.prefs.emptyBowlG = 0
            a.machine.forgetEmptyBowl()
            showBowl()
        }
        forget = button
        showBowl()
        val bowl = Ui.horizontal(c)
        bowl.addView(bowlState, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        bowl.addView(button, Ui.lp(Ui.WRAP, Ui.WRAP, l = 16))
        p.addView(bowl, Ui.lp(t = 2, b = 8))
    }

    private fun safetyPage(p: LinearLayout) {
        p.addView(toggle(tr("Exiger la détection du bol"), a.prefs.requireBowl) { on ->
            a.prefs.requireBowl = on
            a.machine.reconfigure { a.refusal(it) }
        }, Ui.lp(t = 10))
        bowlText = hint("")
        p.addView(bowlText, Ui.lp(t = 4, b = 8))
        line(p)
        p.addView(toggle(tr("Inverser le capteur du bol"), a.prefs.invertBowl) { on ->
            a.prefs.invertBowl = on
            a.machine.reconfigure { a.refusal(it) }
            showInvertHint(a.snapshot)
        }, Ui.lp(t = 8))
        p.addView(hint(tr("À activer si le capteur indique « bol détecté » quand le bol est retiré, et l'inverse quand il est en place.")),
            Ui.lp(t = 4, b = 8))
        // Affiché seulement quand le réglage contredit ce que le robot montre (voir onSnapshot).
        invertHint = Ui.text(c, "", 16f, Ui.WARN).apply { setLineSpacing(0f, 1.15f); visibility = View.GONE }
        p.addView(invertHint, Ui.lp(t = 0, b = 8))
        line(p)
        p.addView(toggle(tr("Montée progressive de la vitesse si le contenu est chaud"), a.prefs.hotRamp) { on ->
            a.prefs.hotRamp = on
            a.machine.reconfigure { a.refusal(it) }
        }, Ui.lp(t = 8))
        p.addView(hint(tr("À partir de 60 °C, la lame atteint sa vitesse en 2 à 3 secondes pour éviter les projections. À froid, elle y va directement.")),
            Ui.lp(t = 4, b = 8))
    }

    private fun languageAndTimePage(p: LinearLayout) {
        // Nom bilingue, jamais traduit : la langue se retrouve quelle que soit celle qui est affichée.
        val options = listOf(I18n.AUTO to tr("Automatique (langue du système)")) + I18n.languages.map { it.code to it.name }
        p.addView(choice("Langue · Language", dropdown(options, a.prefs.language) { code ->
            a.prefs.language = code
            // L'activité est recréée : les réglages rouverts reviennent sur cette famille (voir [reopened]).
            a.applyLanguage()
        }, 380), Ui.lp(t = 10, b = 8))

        group(p, tr("Date et heure"))
        val zones = listOf(ZoneLookup.AUTO to tr("Automatique (d'après la connexion Internet)")) +
            (ZoneLookup.COMMON + listOf(TimeSync.current(), a.prefs.timeZone))
                .filter { it != ZoneLookup.AUTO }.distinct().map { it to it.replace('_', ' ') }
        p.addView(choice(tr("Fuseau horaire"), dropdown(zones, a.prefs.timeZone) { z ->
            a.prefs.timeZone = z
            setClock()
        }, 440), Ui.lp(t = 8, b = 8))
        line(p)
        timeText = Ui.text(c, "", 16f, Ui.TEXT).apply { setLineSpacing(0f, 1.15f) }
        p.addView(setting(timeText, Ui.button(c, tr("Mettre à l'heure"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 52, textSize = 18f) { setClock() }),
            Ui.lp(t = 8, b = 8))
        refreshTime()
    }

    private fun updatesPage(p: LinearLayout) {
        updText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        updButtons = Ui.horizontal(c)
        updBox = LinearLayout(c)
        updBox.minimumHeight = Ui.u(52)
        p.addView(updBox, Ui.lp(t = 10, b = 8))
        line(p)
        p.addView(toggle(tr("Prévenir quand une version sort"), a.prefs.updateAlerts) { a.prefs.updateAlerts = it }, Ui.lp(t = 8, b = 8))
        a.updates.available?.let { release = it; upd = Upd.AVAILABLE }
        renderUpdate()
    }

    /** Ce qu'on règle à l'installation ou pour un dépannage. */
    private fun advancedPage(p: LinearLayout) {
        // Robot (fiche : protocole, programmes, recettes montrées), choisi au premier démarrage.
        val robot = Ui.vertical(c)
        robot.addView(Ui.text(c, tr("Robot"), 18f, Ui.TEXT))
        robotText = hint("")
        robot.addView(robotText, Ui.lp(t = 4))
        p.addView(setting(robot, Ui.button(c, tr("Changer de robot"), Ui.Style.SECONDARY, height = 52, textSize = 18f) { a.askRobot() }), Ui.lp(t = 10, b = 8))
        renderRobot()
        line(p)

        // Machine : la base simulée, le jumeau sur un PC, ou le vrai moteur. Rien ne change avant « Appliquer ».
        modeText = hint("").apply { setTextColor(Ui.ACCENT_INK) }
        p.addView(modeText, Ui.lp(t = 8))
        modeRow = Ui.horizontal(c)
        p.addView(setting(Ui.text(c, tr("Machine"), 18f, Ui.TEXT), modeRow), Ui.lp(t = 4))
        twinBox = Ui.horizontal(c)
        host = field(a.prefs.twinHost, tr("Hôte"))
        port = field(a.prefs.twinPort.toString(), "Port").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        twinBox.addView(host, LinearLayout.LayoutParams(0, Ui.u(52), 1f).apply { rightMargin = Ui.u(10) })
        twinBox.addView(port, LinearLayout.LayoutParams(Ui.u(120), Ui.u(52)))
        p.addView(twinBox, Ui.lp(t = 8))
        linkText = hint("")
        p.addView(setting(linkText, Ui.button(c, tr("Appliquer"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 52, textSize = 18f) { applyMode() }),
            Ui.lp(t = 8, b = 8))
        renderModes()
        line(p)

        // Les outils, deux par ligne.
        val tools = listOf<Pair<String, () -> Unit>>(
            tr("Diagnostic de la base moteur") to { a.push(DiagnosticsScreen(a)) },
            tr("Journal") to { a.push(LogScreen(a)) },
            tr("Paramètres Android") to { a.openAndroidSettings() },
            tr("Revenir à l'appli d'origine") to { restoreVendor() },
        )
        for (pair in tools.chunked(2)) {
            val two = Ui.horizontal(c)
            for ((i, tool) in pair.withIndex())
                two.addView(tile(tool.first, tool.second), LinearLayout.LayoutParams(0, Ui.u(52), 1f).apply { if (i == 0) rightMargin = Ui.u(8) })
            p.addView(two, Ui.lp(t = 8))
        }
        p.addView(Ui.spacer(c, h = 8))
        line(p)

        // MQTT (publication seule) — sur cette page Avancé, sous les outils.
        group(p, tr("MQTT"))
        p.addView(hint(tr("Publie le programme en cours, le temps restant, la température et les alertes vers un broker. Lecture seule : aucune commande n'est reçue. N'envoie un message que lorsque l'état change. Avec Home Assistant, les entités apparaissent toutes seules (MQTT Discovery).")), Ui.lp(t = 8))
        p.addView(toggle(tr("Activer MQTT"), a.prefs.mqttEnabled) { on ->
            saveMqttFields()
            a.prefs.mqttEnabled = on
            a.app.mqtt.reconfigure()
        }, Ui.lp(t = 10))
        mqttHost = field(a.prefs.mqttHost, tr("Hôte"))
        mqttPort = field(a.prefs.mqttPort.toString(), "Port").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val mqttHostRow = Ui.horizontal(c)
        mqttHostRow.addView(mqttHost, LinearLayout.LayoutParams(0, Ui.u(52), 1f).apply { rightMargin = Ui.u(10) })
        mqttHostRow.addView(mqttPort, LinearLayout.LayoutParams(Ui.u(120), Ui.u(52)))
        p.addView(mqttHostRow, Ui.lp(t = 10))
        mqttUser = field(a.prefs.mqttUser, tr("Utilisateur"))
        mqttPassword = field(a.prefs.mqttPassword, tr("Mot de passe")).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        p.addView(mqttUser, Ui.lp(Ui.MATCH, Ui.u(52), t = 8))
        p.addView(mqttPassword, Ui.lp(Ui.MATCH, Ui.u(52), t = 8))
        mqttPrefix = field(a.prefs.mqttTopicPrefix, tr("Préfixe des topics (vide = toque/<id>)"))
        p.addView(mqttPrefix, Ui.lp(Ui.MATCH, Ui.u(52), t = 8))
        p.addView(toggle(tr("TLS (MQTT sécurisé)"), a.prefs.mqttTls) { on ->
            a.prefs.mqttTls = on
        }, Ui.lp(t = 8))
        mqttStatus = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        p.addView(mqttStatus, Ui.lp(t = 8))
        p.addView(Ui.button(c, tr("Appliquer"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 52, textSize = 18f) {
            saveMqttFields()
            a.app.mqtt.reconfigure()
            a.toast(tr("Réglages MQTT enregistrés"), Ui.OK)
        }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10, b = 8))
        paintMqttStatus(a.app.mqtt.status(), a.app.mqtt.statusDetail())
        line(p)

        val version = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (_: Exception) { "?" }
        p.addView(hint(tr("Toque %s · protocole d'après le Monsieur Cuisine Developer SDK (licence MIT). " +
            "Projet indépendant, non affilié à Lidl ni à SilverCrest.", version)), Ui.lp(t = 10))
        a.prefs.robot.let { r -> r.credit?.let { who ->
            p.addView(hint(tr("%s : protocole d'après %s (licence MIT).", r.displayName, who)), Ui.lp(t = 4))
        } }
    }

    // ------------------------------------------------------------------ les briques d'une page

    /** Texte d'explication : gris clair, 16 px au moins, lisible à bout de bras. */
    private fun hint(s: String): TextView = Ui.text(c, Ui.guillemets(s), 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }

    /** Titre d'un groupe de réglages dans une page, séparé du groupe d'avant par un trait. */
    private fun group(page: LinearLayout, title: String) {
        if (page.childCount > 0) line(page)
        page.addView(Ui.text(c, title, 14f, Ui.TEXT2, Ui.BOLD).apply {
            isAllCaps = true
            letterSpacing = 0.08f
        }, Ui.lp(t = 14, b = 2))
    }

    /** Trait fin entre deux réglages. */
    private fun line(page: LinearLayout) {
        page.addView(Ui.divider(c), LinearLayout.LayoutParams(Ui.MATCH, maxOf(1, Ui.u(1))))
    }

    /** Une ligne de réglage : ce qu'on règle à gauche, la commande à droite. */
    private fun setting(left: View, right: View, width: Int = Ui.WRAP, height: Int = Ui.WRAP): LinearLayout {
        val row = Ui.horizontal(c)
        row.minimumHeight = Ui.u(52)
        row.addView(left, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(16) })
        row.addView(right, LinearLayout.LayoutParams(width, height))
        return row
    }

    /** Un choix dans une liste : son nom à gauche, la liste à droite ([width] en pixels de référence). */
    private fun choice(label: String, list: View, width: Int = 340): LinearLayout =
        setting(Ui.text(c, label, 18f, Ui.TEXT), list, Ui.u(width), Ui.u(52))

    /** Lien vers un autre écran, en case (deux par ligne dans « Avancé »). */
    private fun tile(label: String, onClick: () -> Unit): LinearLayout {
        val row = Ui.horizontal(c)
        row.setPadding(Ui.u(16), 0, Ui.u(12), 0)
        row.addView(Ui.fitText(c, 18f, 14f, Ui.TEXT).apply { text = label }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(8) })
        row.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(20), Ui.u(20)))
        row.background = Ui.pressable(Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f), 16f)
        row.isClickable = true
        row.setOnClickListener { onClick() }
        return row
    }

    /** Tant que le mode appliqué n'est pas la machine réelle : un point sur « Avancé », une phrase au-dessus de « Machine ». */
    private fun renderMode() {
        val m = a.prefs.mode
        val real = m == Prefs.Mode.HARDWARE
        val text = tr("Mode %s : le moteur du robot n'est pas piloté.", m.label)
        modeDot.visibility = if (real) View.GONE else View.VISIBLE
        modeDot.contentDescription = if (real) null else text
        modeText.visibility = if (real) View.GONE else View.VISIBLE
        modeText.text = text
    }

    private fun openWheel() = a.push(AccentWheelScreen(a))

    /** Pastille de couleur : un disque, entouré et coché quand c'est la couleur en cours. */
    private fun swatch(color: Int, onPick: (() -> Unit)? = null): View {
        val box = android.widget.FrameLayout(c)
        val selected = color == Ui.ACCENT
        val dot = android.widget.FrameLayout(c).apply {
            background = Ui.pressable(Ui.round(color, 26f, if (selected) Ui.TEXT else 0, if (selected) 3f else 0f), 26f)
            if (selected) addView(Icon(c, Icon.Kind.CHECK, Ui.ON_ACCENT), Ui.flp(Ui.u(24), Ui.u(24), Gravity.CENTER))
            isClickable = true
            setOnClickListener { if (onPick != null) onPick() else if (!selected) a.applyAccent(color) }
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
                val row = Ui.text(c, name, 19f, if (code == current) Ui.ACCENT_INK else Ui.TEXT)
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
            popup.setOnDismissListener {
                if (shown === popup) shown = null
                if (openList === popup) openList = null
            }
            popup.elevation = Ui.uf(8f)
            popup.showAsDropDown(box, 0, Ui.u(6))
            shown = popup
            openList = popup
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
            }, mode == m) { mode = m; renderModes() }, Ui.lp(Ui.WRAP, Ui.WRAP, l = 8))
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
        renderMode()
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
            mode = Prefs.Mode.DEMO
            renderModes()
            renderMode()
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
            accountButtons.addView(Ui.button(c, tr("Délier"), Ui.Style.GHOST, Icon.Kind.CLOSE, height = 52, textSize = 18f) {
                a.confirm(tr("Délier le robot ?"), tr("Le robot ne recevra plus vos favoris du site. Les recettes déjà reçues restent."), tr("Délier")) {
                    a.app.site.unlink()
                    a.toast(tr("Robot délié du compte"))
                }
            })
        } else {
            accountText.text = tr("Aucun compte relié. Le compte est facultatif : relié, le robot reçoit vos favoris du site et vous pouvez publier vos recettes depuis le robot.")
            accountButtons.addView(Ui.button(c, tr("Relier mon compte"), Ui.Style.PRIMARY, Icon.Kind.LINK, height = 52, textSize = 18f) { a.push(SiteLinkScreen(a)) })
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
        // Une version à installer : ses notes prennent la largeur, les boutons dessous. Sinon le bouton à droite du texte.
        val stacked = upd == Upd.AVAILABLE
        updBox.removeAllViews()
        updBox.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        updBox.gravity = if (stacked) Gravity.START else Gravity.CENTER_VERTICAL
        if (stacked) {
            updBox.addView(updText, Ui.lp())
            updBox.addView(updButtons, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))
        } else {
            updBox.addView(updText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(16) })
            updBox.addView(updButtons, Ui.lp(Ui.WRAP, Ui.WRAP))
        }
        updButtons.removeAllViews()
        when (upd) {
            Upd.IDLE, Upd.UP_TO_DATE, Upd.ERROR ->
                updButtons.addView(Ui.button(c, tr("Rechercher une mise à jour"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 52, textSize = 18f) { checkUpdate() })
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

    /** Propose l'installation dans une fenêtre (toujours visible, quelle que soit la famille ouverte). */
    private fun installUpdate() {
        val r = release ?: return
        UpdateFlow.offer(a, r) { msg -> upd = Upd.ERROR; updMessage = msg; renderUpdate() }
    }


    private fun saveMqttFields() {
        a.prefs.mqttHost = mqttHost.text.toString().trim()
        a.prefs.mqttPort = mqttPort.text.toString().trim().toIntOrNull() ?: 1883
        a.prefs.mqttUser = mqttUser.text.toString().trim()
        a.prefs.mqttPassword = mqttPassword.text.toString()
        a.prefs.mqttTopicPrefix = mqttPrefix.text.toString().trim()
    }

    private fun paintMqttStatus(status: MqttPublisher.Status, detail: String) {
        if (!::mqttStatus.isInitialized) return
        val label = when (status) {
            MqttPublisher.Status.OFF -> tr("MQTT désactivé")
            MqttPublisher.Status.CONNECTING -> tr("Connexion…")
            MqttPublisher.Status.CONNECTED -> tr("Connecté")
            MqttPublisher.Status.ERROR -> tr("Erreur")
        }
        mqttStatus.setTextColor(when (status) {
            MqttPublisher.Status.CONNECTED -> Ui.OK
            MqttPublisher.Status.ERROR -> Ui.WARN
            else -> Ui.TEXT2
        })
        mqttStatus.text = if (detail.isEmpty()) label else "$label\n$detail"
    }

    /** Désabonnement du suivi des recettes (le nombre s'affiche une fois la base chargée). */
    private var unsubRecipes: (() -> Unit)? = null
    private var unsubSync: (() -> Unit)? = null
    private var unsubSite: (() -> Unit)? = null

    override fun onShow() {
        mode = a.prefs.mode
        renderModes()
        renderMode()
        renderRobot()
        refreshSync()
        renderAccount()
        refreshSite()
        // Changement de robot : les recettes sont refiltrées, puis cet écran se met à jour.
        unsubRecipes = a.app.recipes.onChange { renderRobot(); refreshSync(); refreshSite() }
        unsubSync = SyncProgress.onChange { refreshSync() }
        unsubSite = a.app.site.onChange { renderAccount(); refreshSite() }
        a.app.mqtt.onStatus { status, detail ->
            view.post { paintMqttStatus(status, detail) }
        }
    }

    override fun onHide() {
        openList?.dismiss()
        unsubRecipes?.invoke()
        unsubRecipes = null
        unsubSync?.invoke()
        unsubSync = null
        unsubSite?.invoke()
        unsubSite = null
        a.app.mqtt.onStatus(null)
    }

    override fun onSnapshot(s: Snapshot) {
        bowlText.text = if (s.hasSample) tr("Capteur du bol en ce moment : %s. Vérifiez qu'il change quand vous retirez le bol avant d'activer cette option.",
            if (s.bowlDetected) tr("bol détecté") else tr("bol NON détecté")) else tr("Base non connectée")
        showInvertHint(s)
    }

    /**
     * Le sens du capteur a été constaté couvercle fermé (le bol y est forcément) : signale un réglage
     * contraire. Seulement avec la vraie base : le réglage est fait pour elle, pas pour la démo.
     */
    private fun showInvertHint(s: Snapshot) {
        val wrong = a.prefs.mode == Prefs.Mode.HARDWARE && s.bowlSenseKnown && s.bowlSenseInverted != a.prefs.invertBowl
        invertHint.visibility = if (wrong) View.VISIBLE else View.GONE
        if (wrong) invertHint.text =
            if (s.bowlSenseInverted) tr("Sur ce robot, le capteur du bol se lit à l'envers : activez « Inverser le capteur du bol ».")
            else tr("Sur ce robot, le capteur du bol se lit à l'endroit : désactivez « Inverser le capteur du bol ».")
    }

    companion object {
        /** La dernière famille ouverte, gardée le temps que l'interface soit refaite. */
        private var last = Family.SCREEN

        /**
         * Les réglages rouverts sur la famille qu'on venait de régler, quand l'interface est refaite par
         * un de ses réglages (thème, couleur, langue, robot). Ouverts depuis l'accueil, ils commencent
         * toujours par la première famille.
         */
        fun reopened(a: MainActivity) = SettingsScreen(a, last)

        /** Hauteur d'une ligne du menu, en pixels de référence. */
        private const val ENTRY = 52
        /** Défilement d'un cran de molette, en pixels de référence. */
        private const val KNOB_STEP = 120
    }
}
