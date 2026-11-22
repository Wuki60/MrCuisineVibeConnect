package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.recipes.UserRecipes
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
import java.text.SimpleDateFormat
import java.util.Date

class HomeScreen(a: MainActivity) : Screen(a) {
    // Pas de titre sur l'accueil : la barre du haut ne garde que l'état de la machine et l'heure.
    override val title = ""
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var greeting: TextView
    private lateinit var recipesSub: TextView
    private lateinit var favSub: TextView
    private lateinit var settingsSub: TextView
    private lateinit var resumeCard: LinearLayout
    private lateinit var resumeText: TextView
    private lateinit var knobChip: LinearLayout
    private var unsub: (() -> Unit)? = null
    private val tick = object : Runnable {
        override fun run() {
            refreshClock()
            view.postDelayed(this, 10_000)
        }
    }

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(28), Ui.u(10), Ui.u(24), Ui.u(24))

        val left = Ui.vertical(c)
        greeting = Ui.text(c, "", 24f, Ui.TEXT2, Ui.LIGHT)
        left.addView(greeting, Ui.lp(t = 8))
        clock = Ui.text(c, "", 132f, Ui.TEXT, Ui.THIN)
        left.addView(clock, Ui.lp(t = 2))
        date = Ui.text(c, "", 22f, Ui.TEXT2, Ui.LIGHT)
        left.addView(date, Ui.lp(t = 2))

        // Rappel tant que le bouton rotatif n'est ni réglé ni vu en marche.
        knobChip = Ui.chip(c, tr("Bouton rotatif à régler"), Icon.Kind.WARNING, Ui.WARN, 0x33FFC14D, 18f)
        knobChip.isClickable = true
        knobChip.setOnClickListener { a.push(KnobSetupScreen(a)) }
        left.addView(knobChip, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))

        val search = Ui.horizontal(c)
        search.setPadding(Ui.u(24), 0, Ui.u(24), 0)
        search.background = Ui.pressable(Ui.round(Ui.SURFACE, 36f, Ui.STROKE, 1f), 36f)
        search.addView(Icon(c, Icon.Kind.SEARCH, Ui.TEXT2), LinearLayout.LayoutParams(Ui.u(28), Ui.u(28)).apply { rightMargin = Ui.u(14) })
        search.addView(Ui.text(c, tr("Rechercher une recette…"), 21f, Ui.TEXT3))
        search.isClickable = true
        search.setOnClickListener { a.push(RecipesScreen(a, focusSearch = true)) }
        left.addView(search, Ui.lp(Ui.MATCH, Ui.u(72), t = 34))

        // Raccourcis sous la recherche : dernières recettes cuisinées, et les plus cuisinées.
        val shortcuts = Ui.horizontal(c)
        shortcuts.addView(Ui.button(c, tr("Récentes"), Ui.Style.SECONDARY, Icon.Kind.CLOCK, height = 56, textSize = 18f) {
            a.push(RecipesScreen(a, initialUsage = RecipesScreen.Usage.RECENT))
        }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(12) })
        shortcuts.addView(Ui.button(c, tr("Fréquentes"), Ui.Style.SECONDARY, Icon.Kind.FLAME, height = 56, textSize = 18f) {
            a.push(RecipesScreen(a, initialUsage = RecipesScreen.Usage.MOST))
        }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        left.addView(shortcuts, Ui.lp(Ui.MATCH, Ui.WRAP, t = 14))

        resumeCard = Ui.horizontal(c)
        resumeCard.setPadding(Ui.u(22), Ui.u(16), Ui.u(18), Ui.u(16))
        resumeCard.background = Ui.pressable(Ui.round(Ui.ACCENT_DIM, 24f), 24f)
        resumeCard.addView(Icon(c, Icon.Kind.PLAY, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(14) })
        resumeText = Ui.ellipsize(Ui.text(c, "", 19f, Ui.TEXT, Ui.MEDIUM), 2)
        resumeCard.addView(resumeText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        // Zone de toucher plus grande que l'icône (au doigt, sur la carte).
        val cancel = android.widget.FrameLayout(c)
        cancel.addView(Icon(c, Icon.Kind.CLOSE, Ui.TEXT2), Ui.flp(Ui.u(24), Ui.u(24), Gravity.CENTER))
        cancel.background = Ui.pressable(Ui.round(0, 28f), 28f)
        cancel.isClickable = true
        cancel.contentDescription = tr("Abandonner la recette")
        cancel.setOnClickListener { abandonGuided() }
        resumeCard.addView(cancel, LinearLayout.LayoutParams(Ui.u(56), Ui.u(56)).apply { leftMargin = Ui.u(8) })
        resumeCard.isClickable = true
        resumeCard.setOnClickListener {
            val r = a.app.recipes.getAdjusted(a.prefs.guidedRecipe)
            if (r != null) a.push(CookScreen(a, r, a.prefs.guidedStep)) else { a.prefs.guidedRecipe = ""; refresh() }
        }
        left.addView(resumeCard, Ui.lp(Ui.MATCH, Ui.WRAP, t = 16))
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.42f).apply { rightMargin = Ui.u(28) })

        val grid = Ui.vertical(c)
        val row1 = Ui.horizontal(c)
        val row2 = Ui.horizontal(c)
        val (t1, s1) = tile(Icon.Kind.BOOK, tr("Recettes")) { a.push(RecipesScreen(a)) }
        recipesSub = s1
        row1.addView(t1, tileLp(true))
        row1.addView(tile(Icon.Kind.POT, tr("Cuisson\nmanuelle"), hasSub = false) { a.push(ManualScreen(a)) }.first, tileLp(true))
        row1.addView(tile(Icon.Kind.KNEAD, tr("Programmes"), tr("Pétrir, vapeur, rissoler, nettoyage")) { a.push(ProgramsScreen(a)) }.first, tileLp(false))
        row2.addView(tile(Icon.Kind.SCALE, tr("Balance"), tr("Peser au gramme")) { a.push(ScaleScreen(a)) }.first, tileLp(true))
        val (t5, s5) = tile(Icon.Kind.STAR, tr("Favoris")) { a.push(RecipesScreen(a, favorites = true)) }
        favSub = s5
        row2.addView(t5, tileLp(true))
        // Sous-titre seulement quand une nouvelle version de Toque attend d'être installée.
        val (t6, s6) = tile(Icon.Kind.SETTINGS, tr("Réglages")) { a.push(SettingsScreen(a)) }
        settingsSub = s6
        settingsSub.setTextColor(Ui.ACCENT)
        row2.addView(t6, tileLp(false))
        grid.addView(row1, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { bottomMargin = Ui.u(16) })
        grid.addView(row2, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        root.addView(grid, LinearLayout.LayoutParams(0, Ui.MATCH, 0.58f))
        return root
    }

    private fun tileLp(gap: Boolean) = LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (gap) rightMargin = Ui.u(16) }

    private fun tile(icon: Icon.Kind, title: String, sub: String = "", hasSub: Boolean = true, onClick: () -> Unit): Pair<LinearLayout, TextView> {
        // Tuile centrée : grande pastille d'icône, titre et sous-titre dessous. La pastille est à la
        // même hauteur dans toutes les tuiles, quel que soit le texte (une ou deux lignes, avec ou
        // sans sous-titre) : espace fixe au-dessus, texte accroché sous la pastille.
        val t = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
        t.setPadding(Ui.u(14), Ui.u(14), Ui.u(14), Ui.u(14))
        t.background = Ui.pressable(Ui.round(Ui.SURFACE, 26f, Ui.STROKE, 1f), 26f)
        t.addView(View(c), LinearLayout.LayoutParams(1, 0, 2f))
        val badge = android.widget.FrameLayout(c)
        badge.background = Ui.round(Ui.ACCENT_DIM, 44f)
        badge.addView(Icon(c, icon, Ui.ACCENT), Ui.flp(Ui.u(46), Ui.u(46), Gravity.CENTER))
        t.addView(badge, LinearLayout.LayoutParams(Ui.u(84), Ui.u(84)))
        val text = Ui.vertical(c, Gravity.CENTER_HORIZONTAL or Gravity.TOP)
        text.addView(Ui.text(c, title, 22f, Ui.TEXT, Ui.MEDIUM).apply { gravity = Gravity.CENTER }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))
        val s = Ui.ellipsize(Ui.text(c, sub, 15f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, 2)
        if (hasSub) text.addView(s, Ui.lp(Ui.WRAP, Ui.WRAP, t = 4))
        t.addView(text, LinearLayout.LayoutParams(Ui.MATCH, 0, 3f))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t to s
    }

    private fun refreshClock() {
        clock.text = MainActivity.now()
        date.text = SimpleDateFormat(tr("EEEE d MMMM"), I18n.locale).format(Date()).replaceFirstChar { it.uppercaseChar() }
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        greeting.text = when (h) {
            in 5..11 -> tr("Bonjour")
            in 12..17 -> tr("Bon après-midi")
            else -> tr("Bonsoir")
        }
    }

    private fun refresh() {
        val repo = a.app.recipes
        val n = repo.count
        val perso = repo.index.entries.count { UserRecipes.isUser(it.id) }
        val lidl = n - perso
        recipesSub.text = when {
            !repo.loaded -> tr("Chargement…")
            n == 0 -> tr("Aucune recette Lidl pour l'instant")
            else -> listOfNotNull(
                when (lidl) { 0 -> null; 1 -> tr("1 recette Lidl"); else -> tr("%d recettes Lidl", lidl) },
                when (perso) { 0 -> null; 1 -> tr("1 recette perso"); else -> tr("%d recettes perso", perso) },
            ).joinToString("\n")
        }
        val favs = repo.index.entries.count { it.favorite }
        favSub.text = if (favs == 0) tr("Aucun favori") else if (favs == 1) tr("1 favori") else tr("%d favoris", favs)
        knobChip.visibility = if (a.prefs.knobReady) View.GONE else View.VISIBLE
        val update = a.updates.available
        settingsSub.text = if (update != null) tr("Version %s disponible", update.version) else ""
        settingsSub.visibility = if (update != null) View.VISIBLE else View.GONE
        val guided = a.prefs.guidedRecipe
        val title = if (guided.isEmpty()) null else repo.index.entries.firstOrNull { it.id == guided }?.title
        resumeCard.visibility = if (title != null) View.VISIBLE else View.GONE
        if (title != null) resumeText.text = tr("Reprendre « %s » — étape %d", title, a.prefs.guidedStep + 1)
    }

    override fun onShow() {
        refreshClock()
        refresh()
        unsub = a.app.recipes.onChange { refresh() }
        a.updates.onChange = { refresh() }
        view.removeCallbacks(tick)
        view.postDelayed(tick, 10_000)
    }

    override fun onHide() {
        unsub?.invoke()
        a.updates.onChange = null
        view.removeCallbacks(tick)
    }

    /** Retire la recette en cours de l'accueil (sans toucher à un programme qui tourne). */
    private fun abandonGuided() {
        if (a.snapshot.busy) {
            a.toast(tr("Arrêtez d'abord le programme en cours"), Ui.WARN)
            return
        }
        a.confirm(tr("Abandonner la recette"), tr("Elle ne sera plus proposée sur l'accueil. Vous pourrez la recommencer depuis les recettes."),
            tr("Abandonner"), icon = Icon.Kind.CLOSE) {
            a.prefs.guidedRecipe = ""
            a.prefs.guidedStep = 0
            refresh()
        }
    }
}
