package app.toque.ui.screens

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import app.toque.Prefs
import app.toque.TimeSync
import app.toque.companion.RobotCompanion
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.machine.Snapshot
import app.toque.core.time.ZoneLookup
import app.toque.core.update.Release
import app.toque.recipes.LidlSync
import app.toque.core.recipes.McOnline
import app.toque.recipes.OnlineSync
import app.toque.recipes.SyncProgress
import app.toque.recipes.RecipeFiles
import app.toque.core.mealie.MealieRecipes
import app.toque.core.qr.QrCode
import app.toque.core.recipes.CommunityRecipes
import app.toque.core.recipes.UserRecipes
import app.toque.ui.AccentWheel
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.QrView
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
    /** Les familles de réglages, dans l'ordre du menu : le quotidien d'abord, le technique ensuite, le soutien en dernier. */
    enum class Family { SCREEN, KNOB_SOUNDS, RECIPES, COOKING, SAFETY, PHONE, LANGUAGE_TIME, UPDATES, ADVANCED, SUPPORT }

    /** D'où viennent les recettes : une pastille chacune, en haut de la famille « Recettes ». Mealie est dans « Autres ». */
    private enum class Source { LIDL, SITE, MEALIE, MINE, STOCK }

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
    private lateinit var applyRow: LinearLayout
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
    private lateinit var standbyText: TextView
    private lateinit var companionText: TextView
    private lateinit var phonesBox: LinearLayout
    /** Point sur « Avancé » et phrase au-dessus de « Machine » : tant que Toque ne pilote pas le vrai moteur. */
    private lateinit var modeDot: View
    private lateinit var modeText: TextView
    private var unsubCompanion: (() -> Unit)? = null
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
    private lateinit var mealieText: TextView
    private lateinit var mealieButtons: LinearLayout
    /** Code QR de la page « Soutien » : calculé à la première ouverture de la famille seulement. */
    private lateinit var supportQr: QrView

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(16), 0, Ui.u(16), Ui.u(16))

        // ---- À gauche, les familles. Elles tiennent toutes à l'écran ; la liste ne défile que si la
        // barre de cuisson prend de la place en bas.
        val list = Ui.vertical(c)
        val families = Family.values()
        for (f in families) list.addView(entry(f), Ui.lp(Ui.MATCH, Ui.u(ENTRY), b = if (f == families.last()) 0 else ENTRY_GAP))
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
                Family.PHONE -> phonePage(page)
                Family.LANGUAGE_TIME -> languageAndTimePage(page)
                Family.UPDATES -> updatesPage(page)
                Family.ADVANCED -> advancedPage(page)
                Family.SUPPORT -> supportPage(page)
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
        Family.PHONE -> tr("Téléphone")
        Family.LANGUAGE_TIME -> tr("Langue et heure")
        Family.UPDATES -> tr("Mises à jour")
        Family.ADVANCED -> tr("Avancé")
        Family.SUPPORT -> tr("Soutien")
    }

    private fun icon(f: Family): Icon.Kind = when (f) {
        Family.SCREEN -> Icon.Kind.SCREEN
        Family.KNOB_SOUNDS -> Icon.Kind.KNOB
        Family.RECIPES -> Icon.Kind.BOOK
        Family.COOKING -> Icon.Kind.POT
        Family.SAFETY -> Icon.Kind.WARNING
        Family.PHONE -> Icon.Kind.PHONE
        Family.LANGUAGE_TIME -> Icon.Kind.GLOBE   // le globe se reconnaît quelle que soit la langue affichée
        Family.UPDATES -> Icon.Kind.DOWNLOAD
        Family.ADVANCED -> Icon.Kind.SETTINGS
        Family.SUPPORT -> Icon.Kind.COFFEE
    }

    /** Une ligne du menu : l'icône de la famille et son nom (rapetissé plutôt que coupé dans une langue aux mots longs). */
    private fun entry(f: Family): LinearLayout {
        val row = Ui.horizontal(c)
        row.setPadding(Ui.u(14), 0, Ui.u(12), 0)
        row.addView(Icon(c, icon(f), Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(12) })
        row.addView(Ui.fitText(c, 18f, 14f, Ui.TEXT, Ui.MEDIUM).apply { text = name(f) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        if (f == Family.ADVANCED) {
            modeDot = View(c).apply { background = Ui.round(Ui.ACCENT_INK, 5f) }
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
        if (f == Family.SUPPORT && supportQr.code == null) {
            supportQr.code = try { QrCode.encode(DONATE_URL) } catch (t: Throwable) { null }
        }
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
        // Couleur d'accent : les dix d'origine sur une ligne ; toute autre couleur par la roue. Une couleur
        // prise sur la roue est montrée cochée, devant le bouton.
        val colour = Ui.vertical(c)
        colour.addView(Ui.text(c, tr("Couleur"), 18f, Ui.TEXT))
        colour.addView(hint(tr("Couleur des boutons, des icônes et des repères.")), Ui.lp(t = 4))
        val wheel = Ui.horizontal(c)
        if (Ui.ACCENT !in Ui.ACCENTS)
            wheel.addView(swatch(Ui.ACCENT) { openWheel() }, LinearLayout.LayoutParams(Ui.u(52), Ui.u(52)).apply { rightMargin = Ui.u(12) })
        wheel.addView(Ui.button(c, tr("Roue des couleurs"), Ui.Style.SECONDARY, height = 52, textSize = 18f) { openWheel() })
        p.addView(setting(colour, wheel), Ui.lp(t = 8))
        val ten = Ui.horizontal(c)
        for (color in Ui.ACCENTS) ten.addView(swatch(color), LinearLayout.LayoutParams(Ui.u(52), Ui.u(52)).apply { rightMargin = Ui.u(12) })
        p.addView(ten, Ui.lp(t = 10, b = 10))
        // L'image de l'anneau se prépare déjà (une fois par processus) : la roue s'ouvre complète.
        AccentWheel.Ring.get(Ui.u(AccentWheelScreen.RING)) {}

        group(p, tr("Veille"))
        // Le délai au repos, et ce que fait l'écran pendant un programme : sur leur écran.
        p.addView(link(tr("Veille de l'écran")) { a.push(StandbyScreen(a)) })
        standbyText = hint("")
        p.addView(standbyText, Ui.lp(b = 8))
        refreshStandby()
    }

    /**
     * Ce qui est réglé pour la veille, sous son lien, dans les mots de son écran et une situation par ligne :
     * « Au repos : après 10 min », « Pendant un programme : jamais ». Pendant un programme, la mise en veille si
     * elle est réglée, sinon la luminosité qui baisse.
     */
    private fun refreshStandby() {
        val p = a.prefs
        fun after(minutes: Int) = if (minutes <= 0) tr("jamais") else tr("après %d min", minutes)
        val job = if (p.jobStandbyMinutes <= 0 && p.jobDimMinutes > 0) tr("luminosité réduite") else after(p.jobStandbyMinutes)
        standbyText.text = listOf(tr("Au repos : %s", after(p.standbyMinutes)), tr("Pendant un programme : %s", job)).joinToString("\n")
    }

    private fun knobAndSoundsPage(p: LinearLayout) {
        group(p, tr("Molette"))
        // L'anneau lumineux : code couleur, luminosité et couleurs, sur leur écran.
        p.addView(link(tr("Lumière de la molette")) { a.push(KnobLightScreen(a)) })
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
        // Cinq pastilles : dans une langue aux mots longs, la rangée défile plutôt que de couper la dernière.
        p.addView(android.widget.HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(sourceRow)
        }, Ui.lp(t = 10, b = 10))
        line(p)
        for (s in Source.values()) {
            val page = Ui.vertical(c)
            sourcePages += page
            p.addView(page, Ui.lp())
            when (s) {
                Source.LIDL -> lidlPage(page)
                Source.SITE -> communityPage(page)
                Source.MEALIE -> mealiePage(page)
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
                Source.SITE -> "toqueapp.org"
                Source.MEALIE -> "Mealie"
                Source.MINE -> tr("Mes recettes")
                Source.STOCK -> tr("Appli d'origine")
            }, source == s) { source = s; renderSources() }.apply { setPadding(Ui.u(15), Ui.u(10), Ui.u(15), Ui.u(10)) },
                Ui.lp(Ui.WRAP, Ui.WRAP, r = if (s == Source.values().last()) 0 else 8))
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

    /**
     * Famille « Soutien » : de quoi offrir un café au projet. Un code QR à scanner avec son téléphone
     * (le robot n'a pas de navigateur) et l'adresse écrite en clair. Rien n'est envoyé ni ouvert depuis le
     * robot ; le code n'est calculé qu'à la première ouverture de la famille (voir [open]).
     */
    private fun supportPage(p: LinearLayout) {
        group(p, tr("Soutenir Toque"))
        p.addView(hint(tr("Toque est gratuit et sans publicité. Si l'appli vous rend service, vous pouvez soutenir son développement en offrant un café : scannez le code avec l'appareil photo de votre téléphone.")),
            Ui.lp(t = 6, b = 14))
        val row = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
        supportQr = QrView(c)
        row.addView(supportQr, LinearLayout.LayoutParams(Ui.u(SUPPORT_QR), Ui.u(SUPPORT_QR)).apply { rightMargin = Ui.u(24) })
        val col = Ui.vertical(c)
        col.addView(Icon(c, Icon.Kind.COFFEE, Ui.ACCENT_INK), LinearLayout.LayoutParams(Ui.u(44), Ui.u(44)))
        col.addView(Ui.text(c, tr("Offrir un café"), 20f, Ui.TEXT, Ui.MEDIUM), Ui.lp(t = 10))
        col.addView(Ui.text(c, DONATE_URL.removePrefix("https://"), 17f, Ui.TEXT2), Ui.lp(t = 4))
        col.addView(Ui.text(c, tr("Merci pour votre soutien !"), 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }, Ui.lp(t = 14))
        row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        p.addView(row, Ui.lp(b = 12))
    }

    /** Un serveur Mealie auto-hébergé : ses recettes arrivent en lecture seule, réglages proposés à vérifier. */
    private fun mealiePage(p: LinearLayout) {
        p.addView(hint(tr("Vous hébergez vos recettes avec Mealie ? Reliez le robot à votre serveur : toutes ses recettes arrivent dans Toque, chaque nuit et à la demande. Les réglages écrits dans les étapes (« 5 min/100 °C/vitesse 1 ») sont proposés, à vérifier avant de lancer.")),
            Ui.lp(t = 6, b = 8))
        mealieText = Ui.text(c, "", 16f, Ui.TEXT).apply { setLineSpacing(0f, 1.15f) }
        mealieButtons = Ui.horizontal(c)
        p.addView(setting(mealieText, mealieButtons), Ui.lp(t = 8, b = 8))
    }

    private fun refreshMealie() {
        if (!::mealieText.isInitialized) return
        val m = a.app.mealie
        val prefs = a.prefs
        mealieButtons.removeAllViews()
        if (!prefs.mealieLinked) {
            mealieText.text = tr("Aucun serveur Mealie relié.")
            mealieButtons.addView(Ui.button(c, tr("Relier mon serveur"), Ui.Style.PRIMARY, Icon.Kind.LINK, height = 52, textSize = 18f) { a.push(MealieLinkScreen(a)) })
            return
        }
        val last = prefs.mealieLastResult
        val first = when {
            m.busy -> tr("Synchronisation en cours…")
            last.isEmpty() -> tr("Jamais synchronisé")
            else -> tr("Dernière synchro le %s", lastLine(last, prefs.mealieLastLang, prefs.mealieLastMs))
        }
        val repo = a.app.recipes
        val n = if (repo.loaded) repo.index.entries.count { MealieRecipes.isMealie(it.id) } else 0
        mealieText.text = tr("Relié à %s", prefs.mealieUrl) + "\n" + first + if (repo.loaded) "\n" + tr("%d recettes Mealie dans Toque", n) else ""
        val sync = Ui.button(c, tr("Synchroniser"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 52, textSize = 18f) {
            m.sync { r -> r?.let { a.toast(it.message, if (it.ok) Ui.OK else Ui.WARN) } }
        }
        Ui.setButtonEnabled(sync, !m.busy)
        mealieButtons.addView(sync, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        mealieButtons.addView(Ui.button(c, tr("Délier"), Ui.Style.GHOST, Icon.Kind.CLOSE, height = 52, textSize = 18f) {
            a.confirm(tr("Délier le serveur Mealie ?"), tr("Les recettes Mealie partent du robot ; elles restent sur votre serveur. Vos versions dans Mes recettes restent."), tr("Délier")) {
                m.unlink()
                a.toast(tr("Serveur Mealie délié"))
            }
        })
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

        // Programmes affichés (#130, partie « masquer ») : au moins un reste visible.
        group(p, tr("Programmes"))
        p.addView(hint(tr("Programmes affichés sur l'écran Programmes :")), Ui.lp(t = 4, b = 4))
        val features = ProgramsScreen.features()
        val switches = ArrayList<LinearLayout>()
        for (f in features) {
            switches += toggle(f.label, f.name !in a.prefs.hiddenPrograms) { on ->
                val hidden = a.prefs.hiddenPrograms.toMutableSet()
                if (on) hidden.remove(f.name) else hidden.add(f.name)
                if (features.all { it.name in hidden }) {
                    a.toast(tr("Gardez au moins un programme affiché"), Ui.WARN)
                    // Remet l'interrupteur sur « affiché » (son clic le rallume et enregistre l'état d'avant).
                    switches[features.indexOf(f)].let { row -> row.post { row.performClick() } }
                } else {
                    a.prefs.hiddenPrograms = hidden
                }
            }
            p.addView(switches.last(), Ui.lp(t = 6))
        }
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
            Ui.lp(t = 4))
        // Affiché seulement quand le réglage contredit ce que le robot montre (voir onSnapshot).
        invertHint = hint("").apply { setTextColor(Ui.WARN); visibility = View.GONE }
        p.addView(invertHint, Ui.lp(t = 6))
        p.addView(Ui.spacer(c, h = 8))
        line(p)
        p.addView(toggle(tr("Montée progressive de la vitesse si le contenu est chaud"), a.prefs.hotRamp) { on ->
            a.prefs.hotRamp = on
            a.machine.reconfigure { a.refusal(it) }
        }, Ui.lp(t = 8))
        p.addView(hint(tr("À partir de 60 °C, la lame atteint sa vitesse en 2 à 3 secondes pour éviter les projections. À froid, elle y va directement.")),
            Ui.lp(t = 4, b = 8))
    }

    /** Les téléphones du Wi-Fi de la maison (application Toque Compagnon). */
    private fun phonePage(p: LinearLayout) {
        companionText = Ui.text(c, "", 18f, Ui.TEXT).apply { setLineSpacing(0f, 1.15f) }
        p.addView(setting(companionText, Ui.button(c, tr("Appairer un téléphone"), Ui.Style.SECONDARY, Icon.Kind.PHONE, height = 52, textSize = 18f) {
            if (a.app.companion != null) a.push(PairingScreen(a)) else a.toast(tr("Compagnon indisponible : voyez le journal"), Ui.WARN)
        }), Ui.lp(t = 10, b = 8))
        // Les téléphones appairés, ici même : les retirer ne demande plus d'ouvrir l'écran d'appairage.
        phonesBox = Ui.vertical(c)
        p.addView(phonesBox, Ui.lp(b = 12))
        line(p)
        p.addView(toggle(tr("Lancement depuis le téléphone"), a.prefs.remoteStart) { on ->
            val comp = a.app.companion
            if (comp != null) comp.setRemoteStart(on) else a.prefs.remoteStart = on
        }, Ui.lp(t = 8))
        p.addView(hint(tr("Un téléphone appairé peut lancer une cuisson manuelle, couvercle fermé et bol en place.")), Ui.lp(t = 4, b = 8))
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
        host = field(a.prefs.twinHost, tr("Hôte")).apply {
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NEXT
            setOnFocusChangeListener { _, has -> if (has && a.keyboardHeight > 0) Ui.revealInScroll(pane, twinBox, applyRow, 10) }
        }
        port = field(a.prefs.twinPort.toString(), "Port").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> a.hideKeyboard(); true }
            setOnFocusChangeListener { _, has -> if (has && a.keyboardHeight > 0) Ui.revealInScroll(pane, twinBox, applyRow, 10) }
        }
        twinBox.addView(host, LinearLayout.LayoutParams(0, Ui.u(52), 1f).apply { rightMargin = Ui.u(10) })
        twinBox.addView(port, LinearLayout.LayoutParams(Ui.u(120), Ui.u(52)))
        p.addView(twinBox, Ui.lp(t = 8))
        linkText = hint("")
        applyRow = setting(linkText, Ui.button(c, tr("Appliquer"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 52, textSize = 18f) { applyMode() })
        p.addView(applyRow, Ui.lp(t = 8, b = 8))
        renderModes()
        line(p)

        // Les outils, deux par ligne ; le dernier, seul sur la sienne, en prend toute la largeur.
        val tools = listOf<Pair<String, () -> Unit>>(
            tr("Diagnostic de la base moteur") to { a.push(DiagnosticsScreen(a)) },
            tr("Journal") to { a.push(LogScreen(a)) },
            tr("Paramètres Android") to { a.openAndroidSettings() },
            // Régler Magisk (ses notifications pendant la cuisine, par exemple) sans PC ; un nom propre, pas traduit.
            "Magisk" to { a.openMagisk() },
            tr("Revenir à l'appli d'origine") to { restoreVendor() },
        )
        for (pair in tools.chunked(2)) {
            val two = Ui.horizontal(c)
            for ((i, tool) in pair.withIndex())
                two.addView(tile(tool.first, tool.second), LinearLayout.LayoutParams(0, Ui.u(52), 1f).apply { if (i == 0 && pair.size > 1) rightMargin = Ui.u(8) })
            p.addView(two, Ui.lp(t = 8))
        }
        p.addView(Ui.spacer(c, h = 8))
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

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout = Ui.switchRow(c, label, initial, onChange)

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

    /**
     * Menu déroulant : la valeur choisie, et la liste qui s'ouvre sous la case, ou au-dessus quand la place
     * manque en dessous (voir [listPlace]). Android 6 l'ouvrait toujours en dessous : une case en bas de page
     * ne montrait qu'un choix, le reste hors de l'écran.
     */
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
            val popup = PopupWindow(scroll, box.width, ViewGroup.LayoutParams.WRAP_CONTENT, false)
            var chosen: View? = null
            for ((code, name) in options) {
                val row = Ui.text(c, name, 19f, if (code == current) Ui.ACCENT_INK else Ui.TEXT)
                if (code == current) chosen = row
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
            // La liste reste dans la zone de l'écran des réglages : ni sur la barre du haut, ni hors de l'écran.
            scroll.measure(View.MeasureSpec.makeMeasureSpec(box.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val at = IntArray(2)
            box.getLocationInWindow(at)
            val area = IntArray(2)
            view.getLocationInWindow(area)
            val (y, height) = listPlace(at[1], at[1] + box.height, minOf(scroll.measuredHeight, Ui.u(LIST_MAX)),
                area[1] + Ui.u(LIST_MARGIN), area[1] + view.height - Ui.u(LIST_MARGIN), Ui.u(6))
            popup.height = height
            popup.showAtLocation(box, Gravity.TOP or Gravity.START, at[0], y)
            // Une liste plus longue que sa fenêtre s'ouvre sur le choix en cours, au milieu.
            val row = chosen
            if (row != null && scroll.measuredHeight > height)
                scroll.post { scroll.scrollTo(0, maxOf(0, row.top + row.height / 2 - height / 2)) }
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
        a.hideKeyboard()
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

    /** Désabonnement du suivi des recettes (le nombre s'affiche une fois la base chargée). */
    private var unsubRecipes: (() -> Unit)? = null
    private var unsubSync: (() -> Unit)? = null
    private var unsubSite: (() -> Unit)? = null
    private var unsubMealie: (() -> Unit)? = null

    override fun onShow() {
        mode = a.prefs.mode
        renderModes()
        renderMode()
        renderRobot()
        refreshSync()
        renderAccount()
        refreshSite()
        refreshMealie()
        // Changement de robot : les recettes sont refiltrées, puis cet écran se met à jour.
        unsubRecipes = a.app.recipes.onChange { renderRobot(); refreshSync(); refreshSite(); refreshMealie() }
        unsubSync = SyncProgress.onChange { refreshSync() }
        unsubSite = a.app.site.onChange { renderAccount(); refreshSite() }
        unsubMealie = a.app.mealie.onChange { refreshMealie() }
        refreshStandby()
        refreshCompanion()
        unsubCompanion = a.app.companion?.onChange { ch ->
            if (ch == RobotCompanion.Change.PHONES || ch == RobotCompanion.Change.SERVER) refreshCompanion()
        }
    }

    private fun refreshCompanion() {
        val comp = a.app.companion
        val n = comp?.hub?.pairing?.list?.size ?: 0
        PairedPhones.fill(a, phonesBox)
        phonesBox.visibility = if (n > 0) View.VISIBLE else View.GONE
        companionText.text = when (n) {
            0 -> tr("Aucun téléphone appairé")
            1 -> tr("1 téléphone appairé")
            else -> tr("%d téléphones appairés", n)
        }
        val error = comp?.serverError.orEmpty()
        companionText.setTextColor(if (error.isNotEmpty() && n > 0) Ui.WARN else Ui.TEXT)
        if (comp == null) companionText.text = tr("Compagnon indisponible : voyez le journal")
        else if (error.isNotEmpty() && n > 0) companionText.text = companionText.text.toString() + "\n" + tr("Les téléphones ne joignent pas le robot pour l'instant : nouvel essai toutes les 30 s.")
    }

    override fun onHide() {
        openList?.dismiss()
        unsubRecipes?.invoke()
        unsubRecipes = null
        unsubSync?.invoke()
        unsubSync = null
        unsubCompanion?.invoke()
        unsubCompanion = null
        unsubSite?.invoke()
        unsubSite = null
        unsubMealie?.invoke()
        unsubMealie = null
    }

    override fun onKeyboard(visible: Boolean) {
        if (visible && family == Family.ADVANCED && (host.hasFocus() || port.hasFocus())) Ui.revealInScroll(pane, twinBox, applyRow, 10)
    }

    override fun onSnapshot(s: Snapshot) {
        if (family != Family.SAFETY) return
        Ui.setText(bowlText, if (s.hasSample) tr("Capteur du bol en ce moment : %s. Vérifiez qu'il change quand vous retirez le bol avant d'activer cette option.",
            if (s.bowlDetected) tr("bol détecté") else tr("bol NON détecté")) else tr("Base non connectée"))
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

        /**
         * Où ouvrir une liste déroulante haute de [wanted], pour une case allant de [anchorTop] à
         * [anchorBottom], dans la zone de [top] à [bottom] (tout en pixels, dans la fenêtre) : sous la case
         * si elle y tient ; sinon au-dessus si elle y tient ; sinon remontée juste assez pour finir en bas de
         * la zone (elle recouvre alors la case), et pas plus haute que la zone (elle défile).
         * @return le haut de la liste et sa hauteur.
         */
        internal fun listPlace(anchorTop: Int, anchorBottom: Int, wanted: Int, top: Int, bottom: Int, gap: Int): Pair<Int, Int> {
            val height = minOf(wanted, bottom - top).coerceAtLeast(0)
            val below = anchorBottom + gap
            val above = anchorTop - gap - height
            return when {
                below + height <= bottom -> below to height
                above >= top -> above to height
                else -> maxOf(top, bottom - height) to height
            }
        }

        /**
         * Hauteur d'une ligne du menu et espace entre deux lignes, en pixels de référence : les 10 familles
         * tiennent dans la hauteur de l'écran du robot (600 − 68 de barre du haut − 16 de marge = 516 ;
         * 10 × 48 + 9 × 4 = 516).
         */
        private const val ENTRY = 48
        private const val ENTRY_GAP = 4
        /** Page « Soutien » : la page Buy Me a Coffee du projet et le côté de son code QR. */
        private const val DONATE_URL = "https://buymeacoffee.com/toqueapp"
        private const val SUPPORT_QR = 220
        /** Hauteur la plus grande d'une liste déroulante (au-delà elle défile), et sa marge avec le bord de l'écran. */
        private const val LIST_MAX = 400
        private const val LIST_MARGIN = 8
        /** Défilement d'un cran de molette, en pixels de référence. */
        private const val KNOB_STEP = 120
    }
}
