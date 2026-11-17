package app.toque.ui.screens

import android.content.Context
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.recipes.McOnline
import app.toque.core.recipes.QuantityLinks
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.Step
import app.toque.core.recipes.Weigh
import app.toque.core.recipes.adjusted
import app.toque.core.recipes.unlistedWeighings
import app.toque.recipes.Reviews
import app.toque.ui.Icon
import app.toque.ui.ImageLoader
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Fiche recette : ingrédients à gauche, étapes à droite, « Cuisiner » pour le pas-à-pas.
 * Une quantité se corrige en la touchant dans la liste d'ingrédients et les étapes qui
 * l'utilisent suivent ; la note et les corrections sont à l'utilisateur et restent en place quand le catalogue est synchronisé.
 */
class RecipeScreen(a: MainActivity, private val recipe: Recipe) : Screen(a) {
    override val title = recipe.title
    private lateinit var favIcon: Icon
    private lateinit var favLabel: TextView
    private var adjustments = a.app.recipes.adjustmentsOf(recipe.id)
    /** La recette telle qu'elle est affichée et cuisinée : celle de la source + les corrections. */
    private var shown = recipe.adjusted(adjustments)
    private val ingredientsBox by lazy { Ui.vertical(c) }
    private val stepsList by lazy { Ui.vertical(c) }

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

        // Auteur et note du site.
        val byline = ArrayList<String>()
        if (recipe.author.isNotBlank()) byline += tr("Par %s", authorName(recipe.author))
        if (recipe.ratingCount > 0) byline += "★ " + tr("%s/5 · %d notes", rating(recipe.rating), recipe.ratingCount)
        // Une ligne chacun : sur une seule, la note était coupée (« 124… ») dès que l'auteur est long.
        byline.forEachIndexed { i, line ->
            left.addView(Ui.ellipsize(Ui.text(c, line, 16f, Ui.TEXT2)), Ui.lp(t = if (i == 0) 10 else 2))
        }

        val actions = Ui.horizontal(c)
        actions.addView(Ui.button(c, tr("Cuisiner"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 66, textSize = 22f) {
            a.push(CookScreen(a, shown, 0))
        }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(12) })
        val fav = Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.STAR, height = 66) { toggleFavorite() }
        favIcon = fav.getChildAt(0) as Icon
        favLabel = Ui.text(c, "", 18f, Ui.TEXT, Ui.MEDIUM)
        fav.addView(favLabel, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.u(8) })
        actions.addView(fav)
        left.addView(actions, Ui.lp(t = 14))

        renderIngredients()
        left.addView(Ui.scroll(c, ingredientsBox), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(10) })
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.42f).apply { rightMargin = Ui.u(24) })

        // ---- colonne droite
        val steps = Ui.vertical(c)
        steps.addView(section(tr("Étapes")))
        steps.addView(stepsList)
        renderSteps()
        McOnline.apiIdOf(recipe.id)?.let { apiId ->
            steps.addView(reviewsBox, Ui.lp(t = 22))
            reviewsBox.addView(section(if (recipe.ratingCount > 0)
                tr("Avis · %s/5 (%d notes)", rating(recipe.rating), recipe.ratingCount) else tr("Avis")))
            reviewsBox.addView(reviewsState, Ui.lp(t = 10))
            loadReviews(apiId, 1)
        }
        val stepsBox = Ui.vertical(c)
        stepsBox.background = Ui.round(Ui.SURFACE, 24f)
        stepsBox.setPadding(Ui.u(22), Ui.u(10), Ui.u(22), Ui.u(18))
        stepsBox.addView(Ui.scroll(c, steps), Ui.lp(Ui.MATCH, Ui.MATCH))
        root.addView(stepsBox, LinearLayout.LayoutParams(0, Ui.MATCH, 0.58f))
        refreshFavorite()
        return root
    }

    // ------------------------------------------------------------------ note et quantités adaptées

    private fun renderIngredients() {
        val ing = ingredientsBox
        ing.removeAllViews()
        ing.addView(section(tr("Ma note")))
        if (adjustments.note.isBlank()) {
            ing.addView(Ui.button(c, tr("Ajouter une note"), Ui.Style.GHOST, Icon.Kind.PLUS, height = 46, textSize = 16f) { editNote() },
                Ui.lp(Ui.WRAP, Ui.WRAP, t = 8))
        } else {
            val note = Ui.text(c, adjustments.note, 17f, Ui.TEXT).apply { setLineSpacing(0f, 1.15f) }
            note.background = Ui.pressable(Ui.round(Ui.ACCENT_DIM, 16f), 16f)
            note.setPadding(Ui.u(14), Ui.u(10), Ui.u(14), Ui.u(10))
            note.setOnClickListener { editNote() }
            ing.addView(note, Ui.lp(t = 8))
        }
        ing.addView(section(tr("Ingrédients")), Ui.lp(t = 8))
        if (shown.allIngredients.any { it.qty.isNotBlank() } || shown.steps.any { it.weigh != null }) {
            ing.addView(Ui.text(c, tr("Touchez une quantité pour l'adapter : les étapes suivent."), 14f, Ui.TEXT3), Ui.lp(t = 4))
        }
        for (g in shown.ingredients) {
            if (g.title.isNotBlank()) ing.addView(Ui.text(c, g.title, 17f, Ui.ACCENT, Ui.MEDIUM), Ui.lp(t = 12, b = 2))
            for (i in g.items) {
                val original = if (i.originalQty.isNotBlank()) quantity(i.originalQty, i.unit) else null
                ing.addView(ingredientRow(i.display, original, if (i.qty.isNotBlank()) ({ editQuantity(i) }) else null), Ui.lp(t = 4))
            }
        }
        if (shown.ingredients.isEmpty()) ing.addView(Ui.text(c, tr("Pas de liste d'ingrédients."), 17f, Ui.TEXT3), Ui.lp(t = 8))
        // Quantités pesées dans les étapes mais absentes de la liste (souvent l'eau).
        val unlisted = recipe.unlistedWeighings().mapNotNull { (si, name) -> shown.steps.getOrNull(si)?.weigh?.let { Triple(si, name, it) } }
        if (unlisted.isNotEmpty()) {
            ing.addView(Ui.text(c, tr("Aussi dans les étapes"), 17f, Ui.ACCENT, Ui.MEDIUM), Ui.lp(t = 14, b = 2))
            for ((si, name, w) in unlisted) {
                val original = if (w.originalGrams > 0) "${w.originalGrams} g" else null
                ing.addView(ingredientRow("${w.grams} g $name", original) { editWeigh(si, name, w) }, Ui.lp(t = 4))
            }
        }
    }

    /** Ligne d'ingrédient ; [original] : valeur de la recette quand elle a été adaptée. */
    private fun ingredientRow(label: String, original: String?, onClick: (() -> Unit)?): View {
        val row = Ui.horizontal(c, Gravity.TOP)
        val changed = original != null
        row.addView(Ui.text(c, "•", 18f, if (changed) Ui.ACCENT else Ui.TEXT3), Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        val col = Ui.vertical(c)
        col.addView(Ui.text(c, label, 18f, if (changed) Ui.ACCENT else Ui.TEXT).apply { setLineSpacing(0f, 1.1f) })
        if (original != null) col.addView(Ui.text(c, tr("Recette : %s", original), 14f, Ui.TEXT3), Ui.lp(t = 2))
        row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        if (onClick != null) {
            row.background = Ui.pressable(Ui.round(0, 12f), 12f)
            row.setOnClickListener { onClick() }
        }
        row.setPadding(0, Ui.u(4), 0, Ui.u(4))
        return row
    }

    private fun renderSteps() {
        stepsList.removeAllViews()
        for ((i, s) in shown.steps.withIndex()) stepsList.addView(stepRow(i, s), Ui.lp(t = 12))
    }

    private fun quantity(qty: String, unit: String): String = listOf(qty.trim(), unit.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    private fun save(next: RecipeAdjustments) {
        adjustments = next
        shown = recipe.adjusted(next)
        a.app.recipes.setAdjustments(recipe.id, next)
        renderIngredients()
        renderSteps()
    }

    private fun editNote() = editor(
        title = tr("Ma note sur cette recette"),
        detail = tr("Elle reste sur ce robot, même après une synchronisation des recettes."),
        value = adjustments.note,
        hint = tr("Ex. : moins d'eau la prochaine fois"),
        lines = 2,
        reset = if (adjustments.note.isBlank()) null else tr("Effacer"),
    ) { text -> save(adjustments.withNote(text.orEmpty())); true }

    private fun editQuantity(i: Ingredient) {
        val original = i.originalQty.ifBlank { i.qty }
        // Pavé numérique dès que la quantité de la recette est un nombre, fractions comprises
        // (« 1/2 », « ½ », « 1 000 ») : la valeur proposée est alors écrite en décimal (« 0,5 »),
        // tapable sur le pavé.
        val number = QuantityLinks.parse(original) != null
        val current = if (number) QuantityLinks.parse(i.qty)?.let(::decimal) ?: i.qty else i.qty
        editor(
            title = tr("Adapter la quantité"),
            detail = listOf(i.unit.trim(), i.name.trim()).filter { it.isNotEmpty() }.joinToString(" ") + "\n" +
                tr("Recette : %s", quantity(original, i.unit)),
            value = current,
            hint = original,
            number = number,
            reset = if (i.originalQty.isBlank()) null else tr("Valeur de la recette"),
        ) { text -> save(adjustments.withQty(i, text.orEmpty())); true }
    }

    /** 0.5 → « 0,5 », 2.0 → « 2 », 0.333… → « 0,33 ». */
    private fun decimal(v: Double): String {
        val r = Math.round(v * 100) / 100.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString().replace('.', ',')
    }

    private fun editWeigh(index: Int, name: String, w: Weigh) {
        val original = if (w.originalGrams > 0) w.originalGrams else w.grams
        editor(
            title = tr("Adapter la quantité"),
            detail = tr("%s · étape %d", name, index + 1) + "\n" + tr("Recette : %s", "$original g"),
            value = w.grams.toString(),
            hint = original.toString(),
            number = true,
            reset = if (w.originalGrams > 0) tr("Valeur de la recette") else null,
        ) { text ->
            val grams = if (text == null) 0 else text.trim().toIntOrNull() ?: -1
            if (grams < 0 || grams > RecipeAdjustments.MAX_GRAMS || (text != null && grams == 0)) {
                a.toast(tr("Entrez un poids entre 1 et %d g", RecipeAdjustments.MAX_GRAMS), Ui.WARN)
                false
            } else {
                save(adjustments.withWeigh(index, w, grams))
                true
            }
        }
    }

    /**
     * Carte de saisie. [onDone] reçoit le texte saisi, ou null pour « revenir à la valeur de la
     * recette » ; il renvoie false pour garder la carte ouverte (saisie refusée).
     */
    private fun editor(title: String, detail: String, value: String, hint: String, lines: Int = 1, number: Boolean = false,
                       reset: String?, onDone: (String?) -> Boolean) {
        val card = Ui.card(c, 28f, Ui.SURFACE2)
        card.setPadding(Ui.u(32), Ui.u(26), Ui.u(32), Ui.u(24))
        card.addView(Ui.text(c, title, 24f, Ui.TEXT, Ui.MEDIUM))
        if (detail.isNotBlank()) card.addView(Ui.text(c, detail, 17f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }, Ui.lp(t = 8))
        val input = EditText(c).apply {
            setText(value)
            setSelection(text.length)
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(22f))
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT3)
            background = Ui.round(Ui.SURFACE, 16f, Ui.STROKE, 1f)
            setPadding(Ui.u(16), Ui.u(12), Ui.u(16), Ui.u(12))
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or (if (lines == 1) EditorInfo.IME_ACTION_DONE else 0)
            if (lines == 1) {
                setSingleLine()
                inputType = if (number) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                // Pavé numérique, mais la virgule décimale française reste acceptée.
                // (setRawInputType après coup : le clavier reste le pavé décimal sans remplacer ce filtre.)
                if (number) {
                    keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.,")
                    setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
                }
            } else {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = lines
                maxLines = lines
                gravity = Gravity.TOP
            }
        }
        card.addView(input, Ui.lp(t = 16, b = 20))
        val imm = c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        fun finish(text: String?) {
            if (!onDone(text)) return
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            a.closeOverlay()
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { finish(input.text.toString()); true } else false
        }
        val row = Ui.horizontal(c, Gravity.END or Gravity.CENTER_VERTICAL)
        if (reset != null) {
            row.addView(Ui.button(c, reset, Ui.Style.GHOST, height = 56, textSize = 17f) { finish(null) })
            row.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(0, 1, 1f))
        }
        row.addView(Ui.button(c, tr("Annuler"), Ui.Style.GHOST, height = 56, textSize = 17f) {
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            a.closeOverlay()
        })
        row.addView(Ui.spacer(c, w = 12))
        row.addView(Ui.button(c, tr("Enregistrer"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 56, textSize = 17f) { finish(input.text.toString()) })
        card.addView(row)
        a.showOverlay(card, 680, dismissOnScrim = false)
        // En haut de l'écran : le clavier occupe la moitié basse.
        (card.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = Ui.u(14)
        }
        card.requestLayout()
        input.requestFocus()
        input.post { imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }

    // ------------------------------------------------------------------ avis

    private val reviewsBox by lazy { Ui.vertical(c) }
    private val reviewsState by lazy { Ui.text(c, "", 16f, Ui.TEXT3) }
    private var moreButton: View? = null
    private var loading = false

    private fun loadReviews(apiId: Long, page: Int) {
        if (loading) return
        loading = true
        reviewsState.visibility = View.VISIBLE
        reviewsState.text = tr("Chargement des avis…")
        Thread {
            val result = try {
                Reviews.load(apiId, page)
            } catch (e: Exception) {
                null
            }
            reviewsBox.post {
                loading = false
                if (result == null) {
                    reviewsState.text = tr("Avis indisponibles (pas de connexion ?)")
                    return@post
                }
                showReviews(apiId, result)
            }
        }.start()
    }

    private fun showReviews(apiId: Long, p: McOnline.ReviewPage) {
        moreButton?.let { reviewsBox.removeView(it) }
        moreButton = null
        if (p.page == 1 && p.reviews.isEmpty()) {
            reviewsState.text = tr("Pas encore de commentaire sur cette recette.")
            return
        }
        reviewsState.visibility = View.GONE
        for (r in p.reviews) reviewsBox.addView(reviewRow(r), Ui.lp(t = 14))
        if (p.page < p.totalPages) {
            val shown = reviewsBox.childCount - 2
            val more = Ui.button(c, tr("Voir plus d'avis (%d sur %d)", shown, p.total), Ui.Style.GHOST, height = 50, textSize = 16f) {
                loadReviews(apiId, p.page + 1)
            }
            moreButton = more
            reviewsBox.addView(more, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))
        }
    }

    private fun reviewRow(r: McOnline.Review): View {
        val box = Ui.vertical(c)
        box.background = Ui.round(Ui.SURFACE2, 16f)
        box.setPadding(Ui.u(16), Ui.u(12), Ui.u(16), Ui.u(12))
        val head = Ui.horizontal(c)
        head.addView(Ui.text(c, "★".repeat(r.rating) + "☆".repeat(5 - r.rating), 17f, Ui.ACCENT), Ui.lp(Ui.WRAP, Ui.WRAP, r = 12))
        val who = listOf(r.author.ifBlank { tr("Anonyme") }, r.date).filter { it.isNotBlank() }.joinToString(" · ")
        head.addView(Ui.ellipsize(Ui.text(c, who, 15f, Ui.TEXT3)), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        box.addView(head)
        if (r.message.isNotBlank()) {
            box.addView(Ui.text(c, r.message, 17f, Ui.TEXT).apply { setLineSpacing(0f, 1.12f) }, Ui.lp(t = 6))
        }
        if (r.reply.isNotBlank()) {
            box.addView(Ui.text(c, tr("Réponse de Monsieur Cuisine : %s", r.reply), 15f, Ui.TEXT2)
                .apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 8))
        }
        return box
    }

    private fun rating(v: Double): String = String.format(I18n.locale, "%.1f", v)

    private fun authorName(name: String): String =
        if (name.equals("Monsieur Cuisine Official", ignoreCase = true)) tr("Monsieur Cuisine (recette officielle)") else name

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
        s.weigh?.let { w ->
            val changed = w.originalGrams > 0
            val chip = Ui.chip(c, if (changed) tr("Peser %d g (recette : %d g)", w.grams, w.originalGrams) else tr("Peser %d g", w.grams),
                Icon.Kind.SCALE, if (changed) Ui.ACCENT else Ui.TEXT2, if (changed) Ui.ACCENT_DIM else Ui.SURFACE2, 15f)
            chips.addView(chip, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        }
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
        a.push(CookScreen(a, shown, 0))
        return true
    }
}
