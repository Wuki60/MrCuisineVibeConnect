package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.Step
import app.toque.ui.Icon
import app.toque.ui.ImageLoader
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/** Fiche recette : ingrédients à gauche, étapes à droite, « Cuisiner » pour le pas-à-pas. */
class RecipeScreen(a: MainActivity, private val recipe: Recipe) : Screen(a) {
    override val title = recipe.title
    private lateinit var favIcon: Icon
    private lateinit var favLabel: TextView

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(18))

        // ---- colonne gauche
        val left = Ui.vertical(c)
        val header = FrameLayout(c)
        header.background = Ui.gradient(Ui.placeholderColors(recipe.title), 24f)
        header.addView(Ui.text(c, Ui.initials(recipe.title), 70f, 0x44FFFFFF, Ui.THIN), Ui.flp(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
        val img = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        header.addView(img, Ui.flp())
        header.clipToOutline = true
        header.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, Ui.uf(24f))
        }
        ImageLoader.load(img, a.app.recipes.imageFile(recipe.image), Ui.u(420))
        left.addView(header, LinearLayout.LayoutParams(Ui.MATCH, Ui.u(170)))

        val meta = Ui.horizontal(c)
        if (recipe.totalMinutes > 0) meta.addView(Ui.chip(c, "${recipe.totalMinutes} min", Icon.Kind.CLOCK), Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        if (recipe.servings > 0) meta.addView(Ui.chip(c, tr("%d pers.", recipe.servings), Icon.Kind.POT), Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        meta.addView(Ui.chip(c, when (recipe.difficulty) { 1 -> tr("Facile"); 2 -> tr("Moyen"); else -> tr("Difficile") }, Icon.Kind.STAR))
        left.addView(meta, Ui.lp(t = 12))

        val actions = Ui.horizontal(c)
        actions.addView(Ui.button(c, tr("Cuisiner"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 66, textSize = 22f) {
            a.push(CookScreen(a, recipe, 0))
        }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(12) })
        val fav = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.STAR, height = 66) { toggleFavorite() }
        favIcon = fav.getChildAt(0) as Icon
        favLabel = Ui.text(c, "", 18f, Ui.TEXT, Ui.MEDIUM)
        fav.addView(favLabel, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.u(8) })
        actions.addView(fav)
        left.addView(actions, Ui.lp(t = 14))

        val ing = Ui.vertical(c)
        ing.addView(section(tr("Ingrédients")))
        for (g in recipe.ingredients) {
            if (g.title.isNotBlank()) ing.addView(Ui.text(c, g.title, 17f, Ui.ACCENT, Ui.MEDIUM), Ui.lp(t = 12, b = 2))
            for (i in g.items) {
                val row = Ui.horizontal(c, Gravity.TOP)
                row.addView(Ui.text(c, "•", 18f, Ui.TEXT3), Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
                row.addView(Ui.text(c, i.display, 18f, Ui.TEXT).apply { setLineSpacing(0f, 1.1f) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                ing.addView(row, Ui.lp(t = 8))
            }
        }
        if (recipe.ingredients.isEmpty()) ing.addView(Ui.text(c, tr("Pas de liste d'ingrédients."), 17f, Ui.TEXT3), Ui.lp(t = 8))
        left.addView(Ui.scroll(c, ing), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(10) })
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.42f).apply { rightMargin = Ui.u(24) })

        // ---- colonne droite
        val steps = Ui.vertical(c)
        steps.addView(section(tr("Étapes")))
        for ((i, s) in recipe.steps.withIndex()) steps.addView(stepRow(i, s), Ui.lp(t = 12))
        val stepsBox = Ui.vertical(c)
        stepsBox.background = Ui.round(Ui.SURFACE, 24f)
        stepsBox.setPadding(Ui.u(22), Ui.u(10), Ui.u(22), Ui.u(18))
        stepsBox.addView(Ui.scroll(c, steps), Ui.lp(Ui.MATCH, Ui.MATCH))
        root.addView(stepsBox, LinearLayout.LayoutParams(0, Ui.MATCH, 0.58f))
        refreshFavorite()
        return root
    }

    private fun section(t: String): TextView =
        Ui.text(c, t.uppercase(), 15f, Ui.TEXT2, Ui.MEDIUM).apply { letterSpacing = 0.08f; setPadding(0, Ui.u(10), 0, 0) }

    private fun stepRow(i: Int, s: Step): View {
        val row = Ui.horizontal(c, Gravity.TOP)
        val num = Ui.text(c, "${i + 1}", 17f, Ui.ACCENT, Ui.MEDIUM).apply { gravity = Gravity.CENTER }
        num.background = Ui.round(Ui.ACCENT_DIM, 18f)
        row.addView(num, LinearLayout.LayoutParams(Ui.u(36), Ui.u(36)).apply { rightMargin = Ui.u(14) })
        val col = Ui.vertical(c)
        if (s.text.isNotBlank()) col.addView(Ui.text(c, s.text, 18f, Ui.TEXT).apply { setLineSpacing(0f, 1.15f) })
        val chips = Ui.horizontal(c)
        s.weigh?.let { chips.addView(Ui.chip(c, tr("Peser %d g", it.grams), Icon.Kind.SCALE, Ui.TEXT2, size = 15f), Ui.lp(Ui.WRAP, Ui.WRAP, r = 8)) }
        s.machine?.let { m ->
            val ok = m.valid
            chips.addView(Ui.chip(c, m.summary, if (ok) Icon.Kind.POT else Icon.Kind.WARNING,
                if (ok) Ui.ACCENT else Ui.WARN, if (ok) Ui.ACCENT_DIM else 0x33FFC14D, 15f))
        }
        if (chips.childCount > 0) col.addView(chips, Ui.lp(t = 8))
        row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        return row
    }

    private fun toggleFavorite() {
        val now = !a.app.recipes.isFavorite(recipe.id)
        a.app.recipes.setFavorite(recipe.id, now)
        refreshFavorite()
        a.toast(if (now) tr("Ajoutée aux favoris") else tr("Retirée des favoris"))
    }

    private fun refreshFavorite() {
        val f = a.app.recipes.isFavorite(recipe.id)
        favIcon.set(if (f) Icon.Kind.STAR_FILLED else Icon.Kind.STAR)
        favIcon.color = if (f) Ui.ACCENT else Ui.TEXT
        favLabel.text = tr("Favori")
    }

    override fun onKnobPress(): Boolean {
        a.push(CookScreen(a, recipe, 0))
        return true
    }
}
