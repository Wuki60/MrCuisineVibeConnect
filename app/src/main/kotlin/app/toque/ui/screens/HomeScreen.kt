package app.toque.ui.screens

import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.Prefs
import app.toque.core.i18n.tr
import app.toque.core.machine.Snapshot
import app.toque.core.recipes.SearchIndex
import app.toque.ui.Icon
import app.toque.ui.ImageLoader
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Accueil : une phrase d'accueil tirée au hasard, une recette mise en avant (nouvelle à chaque
 * démarrage puis toutes les heures), la recherche, et les six grandes entrées à droite.
 * Pas d'heure ici : elle reste dans la barre du haut, et seulement quand le Wi-Fi l'a réglée.
 */
class HomeScreen(a: MainActivity) : Screen(a) {
    override val title = "Toque"
    private lateinit var greeting: TextView
    private lateinit var featured: LinearLayout
    private lateinit var featuredBox: FrameLayout
    private lateinit var featuredImage: ImageView
    private lateinit var featuredInitials: TextView
    private lateinit var featuredTitle: TextView
    private lateinit var featuredMeta: TextView
    private lateinit var featuredTag: LinearLayout
    private lateinit var recipesSub: TextView
    private lateinit var favSub: TextView
    private lateinit var settingsSub: TextView
    private lateinit var resumeCard: LinearLayout
    private lateinit var resumeText: TextView
    private var unsub: (() -> Unit)? = null
    /** Revient régulièrement voir s'il est temps de changer de recette mise en avant. */
    private val tick = object : Runnable {
        override fun run() {
            refreshFeatured()
            view.postDelayed(this, 60_000)
        }
    }

    private val phrases get() = listOf(
        tr("Bonjour ! Quelle recette allons-nous faire aujourd'hui ?"),
        tr("Qu'est-ce qui vous ferait plaisir aujourd'hui ?"),
        tr("On cuisine quoi de bon ?"),
        tr("Une petite envie ? Je m'occupe du reste."),
        tr("Prêt à passer en cuisine ?"),
        tr("Salé, sucré… on se lance ?"),
        tr("Et si on essayait une nouvelle recette ?"),
        tr("Le bol est prêt, et vous ?"),
    )

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(28), Ui.u(12), Ui.u(24), Ui.u(24))

        // ---- Colonne de gauche : accueil, recette du moment, recherche
        val left = Ui.vertical(c)
        greeting = Ui.ellipsize(Ui.text(c, "", 30f, Ui.TEXT, Ui.LIGHT), 2).apply { setLineSpacing(0f, 1.08f) }
        left.addView(greeting, Ui.lp(t = 4))

        featured = Ui.vertical(c)
        featured.background = Ui.pressable(Ui.round(Ui.SURFACE, 26f, Ui.STROKE, 1f), 26f)
        featured.isClickable = true
        featured.setOnClickListener { openFeatured() }
        featuredBox = FrameLayout(c)
        featuredBox.clipToOutline = true
        featuredBox.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // coins arrondis en haut seulement (le bas se prolonge sous le texte)
                outline.setRoundRect(0, 0, view.width, view.height + Ui.u(26), Ui.uf(26f))
            }
        }
        featuredInitials = Ui.text(c, "", 64f, 0x44FFFFFF, Ui.THIN)
        featuredBox.addView(featuredInitials, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
        featuredImage = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        featuredBox.addView(featuredImage, Ui.flp())
        // voile sombre en bas de la photo, pour que l'étiquette reste lisible
        featuredBox.addView(View(c).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0x66000000))
        }, Ui.flp())
        featuredTag = Ui.chip(c, tr("Suggestion du moment"), Icon.Kind.STAR_FILLED, Ui.ON_ACCENT, Ui.ACCENT, 14f)
        featuredBox.addView(featuredTag, Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.START).apply {
            setMargins(Ui.u(14), Ui.u(14), 0, 0)
        })
        featured.addView(featuredBox, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        val text = Ui.horizontal(c)
        text.setPadding(Ui.u(18), Ui.u(12), Ui.u(14), Ui.u(14))
        val texts = Ui.vertical(c)
        featuredTitle = Ui.ellipsize(Ui.text(c, "", 21f, Ui.TEXT, Ui.MEDIUM))
        featuredMeta = Ui.ellipsize(Ui.text(c, "", 15f, Ui.TEXT2))
        texts.addView(featuredTitle)
        texts.addView(featuredMeta, Ui.lp(t = 4))
        text.addView(texts, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        text.addView(Icon(c, Icon.Kind.CHEVRON, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)))
        featured.addView(text)
        left.addView(featured, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(16) })

        val search = Ui.horizontal(c)
        search.setPadding(Ui.u(22), 0, Ui.u(22), 0)
        search.background = Ui.pressable(Ui.round(Ui.SURFACE, 32f, Ui.STROKE, 1f), 32f)
        search.addView(Icon(c, Icon.Kind.SEARCH, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(26), Ui.u(26)).apply { rightMargin = Ui.u(14) })
        search.addView(Ui.text(c, tr("Rechercher une recette…"), 20f, Ui.TEXT3))
        search.isClickable = true
        search.setOnClickListener { a.push(RecipesScreen(a, focusSearch = true)) }
        left.addView(search, Ui.lp(Ui.MATCH, Ui.u(64), t = 14))

        resumeCard = Ui.horizontal(c)
        resumeCard.setPadding(Ui.u(20), Ui.u(14), Ui.u(18), Ui.u(14))
        resumeCard.background = Ui.pressable(Ui.round(Ui.ACCENT_DIM, 22f), 22f)
        resumeCard.addView(Icon(c, Icon.Kind.PLAY, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(12) })
        resumeText = Ui.ellipsize(Ui.text(c, "", 18f, Ui.TEXT, Ui.MEDIUM), 2)
        resumeCard.addView(resumeText, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        resumeCard.isClickable = true
        resumeCard.setOnClickListener {
            val r = a.app.recipes.get(a.prefs.guidedRecipe)
            if (r != null) a.push(CookScreen(a, r, a.prefs.guidedStep)) else { a.prefs.guidedRecipe = ""; refresh() }
        }
        left.addView(resumeCard, Ui.lp(Ui.MATCH, Ui.WRAP, t = 12))
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.42f).apply { rightMargin = Ui.u(24) })

        // ---- Grille de droite
        val grid = Ui.vertical(c)
        val row1 = Ui.horizontal(c)
        val row2 = Ui.horizontal(c)
        val (t1, s1) = tile(Icon.Kind.BOOK, tr("Recettes")) { a.push(RecipesScreen(a)) }
        recipesSub = s1
        row1.addView(t1, tileLp(true))
        row1.addView(tile(Icon.Kind.POT, tr("Cuisson\nmanuelle"), tr("Temps, chaleur, vitesse")) { a.push(ManualScreen(a)) }.first, tileLp(true))
        row1.addView(tile(Icon.Kind.KNEAD, tr("Programmes"), tr("Pétrir, vapeur, rissoler")) { a.push(ProgramsScreen(a)) }.first, tileLp(false))
        row2.addView(tile(Icon.Kind.SCALE, tr("Balance"), tr("Peser au gramme")) { a.push(ScaleScreen(a)) }.first, tileLp(true))
        val (t5, s5) = tile(Icon.Kind.STAR, tr("Favoris")) { a.push(RecipesScreen(a, favorites = true)) }
        favSub = s5
        row2.addView(t5, tileLp(true))
        val (t6, s6) = tile(Icon.Kind.SETTINGS, tr("Réglages")) { a.push(SettingsScreen(a)) }
        settingsSub = s6
        row2.addView(t6, tileLp(false))
        grid.addView(row1, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { bottomMargin = Ui.u(14) })
        grid.addView(row2, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        root.addView(grid, LinearLayout.LayoutParams(0, Ui.MATCH, 0.58f))
        return root
    }

    private fun tileLp(gap: Boolean) = LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (gap) rightMargin = Ui.u(14) }

    private fun tile(icon: Icon.Kind, title: String, sub: String = "", onClick: () -> Unit): Pair<LinearLayout, TextView> {
        val t = Ui.vertical(c)
        t.setPadding(Ui.u(20), Ui.u(20), Ui.u(18), Ui.u(18))
        t.background = Ui.pressable(Ui.round(Ui.SURFACE, 26f, Ui.STROKE, 1f), 26f)
        val badge = FrameLayout(c)
        badge.background = Ui.round(Ui.ACCENT_DIM, 18f)
        badge.addView(Icon(c, icon, Ui.ACCENT), Ui.flp(Ui.u(28), Ui.u(28), Gravity.CENTER))
        t.addView(badge, LinearLayout.LayoutParams(Ui.u(54), Ui.u(54)))
        t.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        t.addView(Ui.text(c, title, 21f, Ui.TEXT, Ui.MEDIUM).apply { setLineSpacing(0f, 1.05f) })
        val s = Ui.ellipsize(Ui.text(c, sub, 15f, Ui.TEXT2), 2)
        t.addView(s, Ui.lp(t = 6))
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t to s
    }

    // ------------------------------------------------------------------ recette mise en avant

    private fun currentFeatured(): SearchIndex.Entry? {
        val repo = a.app.recipes
        if (!repo.loaded || repo.count == 0) return null
        val entries = repo.index.entries
        val now = SystemClock.elapsedRealtime() // l'horloge murale peut être fausse avant le Wi-Fi
        val kept = entries.firstOrNull { it.id == featuredId }
        if (kept != null && now - featuredAt < HOUR_MS) return kept
        // de préférence une recette avec photo
        val withPhoto = entries.filter { it.image.isNotEmpty() && repo.imageFile(it.image)?.exists() == true }
        val pool = (if (withPhoto.isNotEmpty()) withPhoto else entries).filter { it.id != featuredId }.ifEmpty { entries }
        val pick = pool.random()
        featuredId = pick.id
        featuredAt = now
        return pick
    }

    private fun refreshFeatured() {
        val e = currentFeatured()
        if (e == null) {
            featuredTag.visibility = View.GONE
            featuredBox.background = Ui.gradient(intArrayOf(Ui.SURFACE2, Ui.SURFACE), 0f)
            featuredImage.setImageDrawable(null)
            featuredInitials.text = ""
            featuredTitle.text = if (a.app.recipes.loaded) tr("Aucune recette pour l'instant") else tr("Chargement…")
            featuredMeta.text = tr("Réglages › Recettes › Synchroniser")
            return
        }
        if (featuredTitle.tag == e.id) return
        featuredTitle.tag = e.id
        featuredTag.visibility = View.VISIBLE
        featuredBox.background = Ui.gradient(Ui.placeholderColors(e.title), 0f)
        featuredInitials.text = Ui.initials(e.title)
        ImageLoader.load(featuredImage, a.app.recipes.imageFile(e.image), Ui.u(460))
        featuredTitle.text = e.title
        val parts = ArrayList<String>()
        if (e.totalMinutes > 0) parts += "${e.totalMinutes} min"
        if (e.category.isNotBlank()) parts += e.category
        if (e.ratingCount > 0) parts += "★ " + String.format(java.util.Locale.ROOT, "%.1f", e.rating)
        featuredMeta.text = parts.joinToString(" · ")
    }

    private fun openFeatured() {
        val id = featuredId
        val r = if (id != null) a.app.recipes.get(id) else null
        if (r != null) a.push(RecipeScreen(a, r)) else a.push(SettingsScreen(a))
    }

    private fun refresh() {
        val repo = a.app.recipes
        val n = repo.count
        recipesSub.text = when {
            !repo.loaded -> tr("Chargement…")
            n == 0 -> tr("Aucune recette pour l'instant")
            else -> tr("%d recettes", n)
        }
        val favs = repo.index.entries.count { it.favorite }
        favSub.text = if (favs == 0) tr("Aucun favori") else if (favs == 1) tr("1 favori") else tr("%d favoris", favs)
        settingsSub.text = a.prefs.mode.label
        val guided = a.prefs.guidedRecipe
        val title = if (guided.isEmpty()) null else repo.index.entries.firstOrNull { it.id == guided }?.title
        resumeCard.visibility = if (title != null) View.VISIBLE else View.GONE
        if (title != null) resumeText.text = tr("Reprendre « %s » — étape %d", title, a.prefs.guidedStep + 1)
        featuredTitle.tag = null
        refreshFeatured()
    }

    override fun onShow() {
        greeting.text = phrases.random()
        refresh()
        unsub = a.app.recipes.onChange { refresh() }
        view.removeCallbacks(tick)
        view.postDelayed(tick, 60_000)
    }

    override fun onHide() {
        unsub?.invoke()
        view.removeCallbacks(tick)
    }

    override fun onSnapshot(s: Snapshot) {
        if (a.prefs.mode == Prefs.Mode.DEMO) settingsSub.text = a.prefs.mode.label
    }

    companion object {
        private const val HOUR_MS = 60 * 60 * 1000L
        /** Gardés tant que l'appli tourne : la recette ne change pas à chaque retour à l'accueil. */
        private var featuredId: String? = null
        private var featuredAt = 0L
    }
}
