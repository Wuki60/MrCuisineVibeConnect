package app.toque.ui.screens

import app.toque.core.protocol.SmartProgram
import app.toque.core.robot.RobotProfiles
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.machine.JobKind
import app.toque.core.machine.Limits
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.UserRecipes
import app.toque.core.recipes.Weigh
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/** Petits éléments communs aux deux écrans d'édition. */
private object Edit {
    fun field(c: Context, value: String, hint: String, lines: Int = 1, number: Boolean = false, size: Float = 21f): EditText =
        EditText(c).apply {
            setText(value)
            this.hint = hint
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(size))
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.TEXT3)
            background = Ui.round(Ui.SURFACE, 16f, Ui.STROKE, 1f)
            setPadding(Ui.u(16), Ui.u(12), Ui.u(16), Ui.u(12))
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or (if (lines == 1) EditorInfo.IME_ACTION_DONE else 0)
            if (lines == 1) {
                setSingleLine()
                inputType = if (number) InputType.TYPE_CLASS_NUMBER
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = lines
                maxLines = lines + 2
                gravity = Gravity.TOP
            }
        }

    fun onChange(e: EditText, f: (String) -> Unit) = e.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c1: Int, a1: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c1: Int) {}
        override fun afterTextChanged(s: Editable?) = f(s?.toString() ?: "")
    })

    fun label(c: Context, t: String): TextView =
        Ui.text(c, t.uppercase(), 14f, Ui.TEXT2, Ui.MEDIUM).apply { letterSpacing = 0.08f }

    /** « − valeur + », boutons ronds au doigt. */
    fun stepper(c: Context, value: String, minusOk: Boolean, plusOk: Boolean, onMinus: () -> Unit, onPlus: () -> Unit): LinearLayout {
        val box = Ui.horizontal(c)
        box.background = Ui.round(Ui.SURFACE2, 30f, Ui.STROKE, 1f)
        fun step(icon: Icon.Kind, ok: Boolean, action: () -> Unit): View {
            val b = FrameLayout(c)
            b.addView(Icon(c, icon, Ui.TEXT), Ui.flp(Ui.u(22), Ui.u(22), Gravity.CENTER))
            b.background = Ui.pressable(Ui.round(0, 26f), 26f)
            b.isClickable = true
            b.setOnClickListener { action() }
            Ui.setButtonEnabled(b, ok)
            return b
        }
        box.addView(step(Icon.Kind.MINUS, minusOk, onMinus), LinearLayout.LayoutParams(Ui.u(52), Ui.u(52)))
        box.addView(Ui.text(c, value, 18f, Ui.TEXT, Ui.MEDIUM).apply { gravity = Gravity.CENTER; minWidth = Ui.u(84) }, Ui.lp(Ui.WRAP, Ui.WRAP))
        box.addView(step(Icon.Kind.PLUS, plusOk, onPlus), LinearLayout.LayoutParams(Ui.u(52), Ui.u(52)))
        return box
    }

    fun iconButton(c: Context, icon: Icon.Kind, color: Int, description: String, rotation: Float = 0f, onClick: () -> Unit): View {
        val b = FrameLayout(c)
        val i = Icon(c, icon, color)
        i.rotation = rotation
        b.addView(i, Ui.flp(Ui.u(22), Ui.u(22), Gravity.CENTER))
        b.background = Ui.pressable(Ui.round(0, 24f), 24f)
        b.contentDescription = description
        b.isClickable = true
        b.setOnClickListener { onClick() }
        return b
    }

    fun hideKeyboard(v: View) {
        (v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }
}

/**
 * Écrire ou modifier une recette perso : titre, personnes, durée, difficulté, ingrédients (une
 * ligne chacun, « 500 g de farine ») et étapes, chacune avec son réglage du robot et sa pesée si
 * besoin. Les réglages passent par les mêmes limites de sécurité que la cuisson manuelle.
 */
class RecipeEditScreen(a: MainActivity, start: Recipe, private val isNew: Boolean) : Screen(a) {
    override val title = if (isNew) tr("Nouvelle recette") else tr("Modifier la recette")
    private var draft = start
    /** Une copie d'une recette Lidl est déjà une nouveauté à enregistrer. */
    private var dirty = isNew && start.title.isNotBlank()
    private val metaBox by lazy { Ui.vertical(c) }
    private val ingredientsBox by lazy { Ui.vertical(c) }
    private val stepsBox by lazy { Ui.vertical(c) }

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(18))

        // ---- gauche : la recette et ses ingrédients
        val left = Ui.vertical(c)
        val form = Ui.vertical(c)
        form.addView(Edit.label(c, tr("Titre")))
        val titleInput = Edit.field(c, draft.title, tr("Ex. : Soupe de potiron"))
        Edit.onChange(titleInput) { t -> if (t != draft.title) { draft = draft.copy(title = t); dirty = true } }
        titleInput.setOnEditorActionListener { v, _, _ -> Edit.hideKeyboard(v); v.clearFocus(); true }
        form.addView(titleInput, Ui.lp(Ui.MATCH, Ui.WRAP, t = 6))
        renderMeta()
        form.addView(metaBox, Ui.lp(t = 14))
        form.addView(Edit.label(c, tr("Ingrédients")), Ui.lp(t = 18))
        form.addView(Ui.text(c, tr("Une ligne chacun, la quantité d'abord : « 500 g de farine ». Les quantités suivent le nombre de personnes."),
            14f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 4))
        renderIngredients()
        form.addView(ingredientsBox, Ui.lp(t = 6))
        form.addView(Ui.button(c, tr("Ajouter un ingrédient"), Ui.Style.GHOST, Icon.Kind.PLUS, height = 50, textSize = 16f) { editIngredient(null) },
            Ui.lp(Ui.WRAP, Ui.WRAP, t = 8, b = 8))
        left.addView(Ui.scroll(c, form), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        val actions = Ui.horizontal(c)
        actions.addView(Ui.button(c, tr("Enregistrer"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 62, textSize = 20f) { save() },
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        if (!isNew) {
            actions.addView(Ui.button(c, "", Ui.Style.GHOST, Icon.Kind.TRASH, height = 62) { delete() }.apply {
                contentDescription = tr("Supprimer la recette")
            }, Ui.lp(Ui.WRAP, Ui.WRAP, l = 12))
        }
        left.addView(actions, Ui.lp(t = 10))
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.42f).apply { rightMargin = Ui.u(24) })

        // ---- droite : les étapes
        val steps = Ui.vertical(c)
        steps.addView(Edit.label(c, tr("Étapes")), Ui.lp(t = 10))
        renderSteps()
        steps.addView(stepsBox)
        steps.addView(Ui.button(c, tr("Ajouter une étape"), Ui.Style.GHOST, Icon.Kind.PLUS, height = 50, textSize = 16f) { editStep(-1) },
            Ui.lp(Ui.WRAP, Ui.WRAP, t = 14))
        val box = Ui.vertical(c)
        box.background = Ui.round(Ui.SURFACE, 24f)
        box.setPadding(Ui.u(22), Ui.u(8), Ui.u(22), Ui.u(18))
        box.addView(Ui.scroll(c, steps), Ui.lp(Ui.MATCH, Ui.MATCH))
        root.addView(box, LinearLayout.LayoutParams(0, Ui.MATCH, 0.58f))
        return root
    }

    // ------------------------------------------------------------------ personnes, durée, difficulté

    private fun renderMeta() {
        metaBox.removeAllViews()
        val row = Ui.horizontal(c, Gravity.TOP)
        val people = Ui.vertical(c)
        people.addView(Edit.label(c, tr("Personnes")))
        val n = draft.servings
        people.addView(Edit.stepper(c, if (n > 0) n.toString() else "—", n > 1, n < 20,
            { setMeta(draft.copy(servings = (n - 1).coerceAtLeast(1))) }, { setMeta(draft.copy(servings = (n + 1).coerceIn(1, 20))) }),
            Ui.lp(Ui.WRAP, Ui.WRAP, t = 6))
        row.addView(people, Ui.lp(Ui.WRAP, Ui.WRAP, r = 18))
        val time = Ui.vertical(c)
        time.addView(Edit.label(c, tr("Durée totale")))
        val m = draft.totalMinutes
        val step = if (m < 60) 5 else 15
        time.addView(Edit.stepper(c, "$m min", m > 0, m < 600,
            { setMeta(draft.copy(totalMinutes = (m - (if (m <= 60) 5 else 15)).coerceAtLeast(0))) },
            { setMeta(draft.copy(totalMinutes = (m + step).coerceAtMost(600))) }), Ui.lp(Ui.WRAP, Ui.WRAP, t = 6))
        row.addView(time)
        metaBox.addView(row)
        val levels = Ui.horizontal(c)
        for ((level, label) in listOf(1 to tr("Facile"), 2 to tr("Moyen"), 3 to tr("Difficile"))) {
            levels.addView(Ui.toggleChip(c, label, draft.difficulty == level) { setMeta(draft.copy(difficulty = level)) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        }
        metaBox.addView(levels, Ui.lp(t = 12))
    }

    private fun setMeta(r: Recipe) {
        a.hideKeyboard()
        draft = r
        dirty = true
        renderMeta()
    }

    // ------------------------------------------------------------------ ingrédients

    /** Ingrédients à plat : (groupe, rang dans le groupe). */
    private fun positions(): List<Pair<Int, Int>> = draft.ingredients.flatMapIndexed { g, group -> group.items.indices.map { g to it } }

    private fun renderIngredients() {
        ingredientsBox.removeAllViews()
        if (draft.allIngredients.isEmpty()) {
            ingredientsBox.addView(Ui.text(c, tr("Aucun ingrédient pour l'instant."), 17f, Ui.TEXT3), Ui.lp(t = 4))
            return
        }
        var k = 0
        for (group in draft.ingredients) {
            if (group.title.isNotBlank()) ingredientsBox.addView(Ui.text(c, group.title, 16f, Ui.ACCENT, Ui.MEDIUM), Ui.lp(t = 10, b = 2))
            for (i in group.items) {
                val index = k++
                val row = Ui.horizontal(c)
                row.addView(Ui.text(c, "•", 18f, Ui.TEXT3), Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
                row.addView(Ui.text(c, i.display, 18f, Ui.TEXT).apply { setLineSpacing(0f, 1.1f) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                row.addView(Edit.iconButton(c, Icon.Kind.CLOSE, Ui.TEXT3, tr("Retirer")) { removeIngredient(index) },
                    LinearLayout.LayoutParams(Ui.u(48), Ui.u(48)))
                row.background = Ui.pressable(Ui.round(0, 12f), 12f)
                row.isClickable = true
                row.setOnClickListener { editIngredient(index) }
                ingredientsBox.addView(row, Ui.lp(t = 2))
            }
        }
    }

    private fun setIngredient(index: Int?, item: Ingredient?) {
        val groups = draft.ingredients.map { it.items.toMutableList() }.toMutableList()
        val titles = draft.ingredients.map { it.title }.toMutableList()
        if (index == null) {
            if (item == null) return
            if (groups.isEmpty()) { groups.add(mutableListOf()); titles.add("") }
            groups.last().add(item)
        } else {
            val (g, k) = positions().getOrNull(index) ?: return
            if (item == null) groups[g].removeAt(k) else groups[g][k] = item
        }
        draft = draft.copy(ingredients = groups.indices.filter { groups[it].isNotEmpty() }.map { IngredientGroup(titles[it], groups[it]) })
        dirty = true
        renderIngredients()
    }

    private fun removeIngredient(index: Int) = setIngredient(index, null)

    /** Carte de saisie d'un ingrédient ; « Suivant » enchaîne sur le suivant sans fermer. */
    private fun editIngredient(index: Int?) {
        val current = index?.let { draft.allIngredients.getOrNull(it) }
        val card = Ui.card(c, 28f, Ui.SURFACE2)
        card.setPadding(Ui.u(32), Ui.u(24), Ui.u(32), Ui.u(22))
        card.addView(Ui.text(c, if (current == null) tr("Ajouter un ingrédient") else tr("Modifier l'ingrédient"), 24f, Ui.TEXT, Ui.MEDIUM))
        val input = Edit.field(c, current?.display.orEmpty(), tr("Ex. : 500 g de farine"))
        input.setSelection(input.text.length)
        if (current == null) input.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NEXT
        card.addView(input, Ui.lp(t = 14, b = 18))
        val imm = c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        fun close() {
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            a.closeOverlay()
        }
        fun commit(): Boolean {
            val item = UserRecipes.ingredient(input.text.toString())
            if (current == null) {
                if (item == null) return false
                setIngredient(null, item)
            } else {
                // Une ligne vidée retire l'ingrédient.
                setIngredient(index, item)
            }
            return true
        }
        input.setOnEditorActionListener { _, action, _ ->
            when (action) {
                EditorInfo.IME_ACTION_NEXT -> { if (commit()) input.setText(""); true }
                EditorInfo.IME_ACTION_DONE -> { commit(); close(); true }
                else -> false
            }
        }
        val row = Ui.horizontal(c, Gravity.END or Gravity.CENTER_VERTICAL)
        row.addView(Ui.button(c, tr("Annuler"), Ui.Style.GHOST, height = 56, textSize = 17f) { close() })
        if (current == null) {
            row.addView(Ui.spacer(c, w = 12))
            row.addView(Ui.button(c, tr("Suivant"), Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 56, textSize = 17f) {
                if (commit()) input.setText("")
                input.requestFocus()
            })
        }
        row.addView(Ui.spacer(c, w = 12))
        row.addView(Ui.button(c, tr("OK"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 56, textSize = 17f) { commit(); close() })
        card.addView(row)
        a.showOverlay(card, 680, dismissOnScrim = false)
        (card.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = Ui.u(14)
        }
        card.requestLayout()
        input.requestFocus()
        input.post { imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }

    // ------------------------------------------------------------------ étapes

    private fun renderSteps() {
        stepsBox.removeAllViews()
        if (draft.steps.isEmpty()) {
            stepsBox.addView(Ui.text(c, tr("Aucune étape pour l'instant."), 17f, Ui.TEXT3), Ui.lp(t = 10))
            return
        }
        val last = draft.steps.size - 1
        for ((i, s) in draft.steps.withIndex()) {
            val row = Ui.horizontal(c, Gravity.TOP)
            val num = Ui.text(c, "${i + 1}", 17f, Ui.ACCENT, Ui.MEDIUM).apply { gravity = Gravity.CENTER }
            num.background = Ui.round(Ui.ACCENT_DIM, 18f)
            row.addView(num, LinearLayout.LayoutParams(Ui.u(36), Ui.u(36)).apply { rightMargin = Ui.u(14); topMargin = Ui.u(4) })
            val col = Ui.vertical(c)
            col.addView(Ui.ellipsize(Ui.text(c, s.text.ifBlank { "—" }, 18f, if (s.text.isBlank()) Ui.TEXT3 else Ui.TEXT), 3)
                .apply { setLineSpacing(0f, 1.12f) })
            val chips = Ui.horizontal(c)
            s.weigh?.let { w -> chips.addView(Ui.chip(c, tr("Peser %d g", w.grams), Icon.Kind.SCALE, Ui.TEXT2, Ui.SURFACE2, 15f), Ui.lp(Ui.WRAP, Ui.WRAP, r = 8)) }
            s.machine?.let { m ->
                val ok = m.valid
                chips.addView(Ui.chip(c, m.summary, if (ok) Icon.Kind.POT else Icon.Kind.WARNING,
                    if (ok) Ui.ACCENT else Ui.WARN, if (ok) Ui.ACCENT_DIM else 0x33FFC14D, 15f))
            }
            if (chips.childCount > 0) col.addView(chips, Ui.lp(t = 6))
            col.background = Ui.pressable(Ui.round(0, 12f), 12f)
            col.isClickable = true
            col.setOnClickListener { editStep(i) }
            row.addView(col, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            // Côte à côte : une étape d'une ligne n'est pas plus haute que les flèches (espacement régulier).
            val move = Ui.horizontal(c)
            move.addView(Edit.iconButton(c, Icon.Kind.CHEVRON, Ui.TEXT2, tr("Monter"), -90f) { moveStep(i, -1) }.also { Ui.setButtonEnabled(it, i > 0) },
                LinearLayout.LayoutParams(Ui.u(44), Ui.u(44)))
            move.addView(Edit.iconButton(c, Icon.Kind.CHEVRON, Ui.TEXT2, tr("Descendre"), 90f) { moveStep(i, 1) }.also { Ui.setButtonEnabled(it, i < last) },
                LinearLayout.LayoutParams(Ui.u(44), Ui.u(44)))
            row.addView(move, Ui.lp(Ui.WRAP, Ui.WRAP, l = 6))
            stepsBox.addView(row, Ui.lp(t = 12))
        }
    }

    private fun moveStep(i: Int, dir: Int) {
        val j = i + dir
        if (j !in draft.steps.indices) return
        val list = draft.steps.toMutableList()
        val s = list.removeAt(i)
        list.add(j, s)
        draft = draft.copy(steps = list)
        dirty = true
        renderSteps()
    }

    /** [index] -1 : nouvelle étape, ajoutée à la fin. */
    private fun editStep(index: Int) {
        a.hideKeyboard()
        val step = draft.steps.getOrNull(index) ?: Step("")
        a.push(StepEditScreen(a, step, if (index < 0) draft.steps.size + 1 else index + 1, isNew = index < 0) { result ->
            val list = draft.steps.toMutableList()
            when {
                result == null -> if (index >= 0) list.removeAt(index)
                index < 0 -> list += result
                else -> list[index] = result
            }
            draft = draft.copy(steps = list)
            dirty = true
            renderSteps()
        })
    }

    // ------------------------------------------------------------------ enregistrer, supprimer, quitter

    private fun save() {
        a.hideKeyboard()
        val r = draft.copy(title = draft.title.trim().replace(Regex("\\s+"), " "))
        UserRecipes.problem(r)?.let { a.toast(it, Ui.WARN); return }
        // Le nombre de personnes vient d'être choisi ici : la fiche s'ouvre à ce nombre.
        val adj = a.app.recipes.adjustmentsOf(r.id)
        if (adj.servings != 0) a.app.recipes.setAdjustments(r.id, adj.copy(servings = 0))
        a.app.recipes.saveUser(r)
        dirty = false
        a.toast(tr("Recette enregistrée dans Mes recettes"), Ui.OK)
        a.back()
        val saved = a.app.recipes.get(r.id) ?: r
        // Retour sur la fiche de cette recette : elle est remplacée par la nouvelle version.
        val page = a.current as? RecipeScreen
        if (page != null && page.recipe.id == r.id) a.replace(RecipeScreen(a, saved)) else a.push(RecipeScreen(a, saved))
        // Compte toqueapp.org relié : mise à jour en ligne, ou proposer de la publier.
        SitePublish.afterSave(a, saved)
    }

    private fun delete() {
        val name = draft.title.ifBlank { tr("Sans titre") }
        a.confirm(tr("Supprimer la recette"), if (a.app.site.isOnline(draft.id))
            tr("« %s » sera supprimée de ce robot, avec sa note et ses corrections. Elle reste sur toqueapp.org : supprimez-la depuis le site si besoin.", name)
            else tr("« %s » sera supprimée de ce robot, avec sa note et ses corrections.", name),
            tr("Supprimer"), danger = true, icon = Icon.Kind.TRASH) {
            a.app.recipes.deleteUser(draft.id)
            dirty = false
            a.toast(tr("Recette supprimée"))
            a.back()
            if ((a.current as? RecipeScreen)?.recipe?.id == draft.id) a.back()
        }
    }

    override fun canLeave(proceed: () -> Unit): Boolean {
        if (!dirty) return true
        a.confirm(tr("Quitter sans enregistrer ?"), tr("Les modifications de cette recette seront perdues."), tr("Quitter"), tr("Rester"), danger = true) {
            dirty = false
            proceed()
        }
        return false
    }
}

/**
 * Une étape : le texte, la quantité à peser et le réglage du robot (aucun, manuel, pétrir,
 * vapeur, rissoler, turbo). [onDone] reçoit l'étape, ou null pour la supprimer.
 */
class StepEditScreen(
    a: MainActivity,
    private val start: Step,
    number: Int,
    private val isNew: Boolean,
    private val onDone: (Step?) -> Unit,
) : Screen(a) {
    override val title = tr("Étape %d", number)
    private var text = start.text
    private var grams = start.weigh?.grams ?: 0
    private var machine: StepMachine? = start.machine
    private var finished = false
    private val settingBox by lazy { Ui.vertical(c) }

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(18))

        val left = Ui.vertical(c)
        left.addView(Edit.label(c, tr("Ce qu'il faut faire")))
        val input = Edit.field(c, text, tr("Ex. : Ajouter les oignons et mixer."), lines = 4, size = 20f)
        Edit.onChange(input) { text = it }
        left.addView(input, Ui.lp(Ui.MATCH, Ui.WRAP, t = 6))
        left.addView(Edit.label(c, tr("À peser (facultatif)")), Ui.lp(t = 18))
        val weighRow = Ui.horizontal(c)
        val weigh = Edit.field(c, if (grams > 0) grams.toString() else "", "0", number = true)
        Edit.onChange(weigh) { grams = it.trim().toIntOrNull() ?: 0 }
        weigh.setOnEditorActionListener { v, _, _ -> Edit.hideKeyboard(v); v.clearFocus(); true }
        weighRow.addView(weigh, LinearLayout.LayoutParams(Ui.u(150), Ui.WRAP))
        weighRow.addView(Ui.text(c, "g", 20f, Ui.TEXT2), Ui.lp(Ui.WRAP, Ui.WRAP, l = 10))
        left.addView(weighRow, Ui.lp(t = 6))
        left.addView(Ui.text(c, tr("La balance s'affiche à cette étape et sonne une fois le poids atteint."), 14f, Ui.TEXT3), Ui.lp(t = 6))
        left.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
        val actions = Ui.horizontal(c)
        if (!isNew) actions.addView(Ui.button(c, tr("Supprimer l'étape"), Ui.Style.GHOST, Icon.Kind.TRASH, height = 58, textSize = 17f) {
            finished = true
            a.back()
            onDone(null)
        }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 12))
        actions.addView(Ui.button(c, tr("OK"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 58, textSize = 19f) { done() },
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        left.addView(actions)
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 0.5f).apply { rightMargin = Ui.u(24) })

        val right = Ui.vertical(c)
        right.background = Ui.round(Ui.SURFACE, 24f)
        right.setPadding(Ui.u(22), Ui.u(16), Ui.u(22), Ui.u(18))
        right.addView(Edit.label(c, tr("Réglage du robot")))
        renderSetting()
        right.addView(Ui.scroll(c, settingBox), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f).apply { topMargin = Ui.u(10) })
        root.addView(right, LinearLayout.LayoutParams(0, Ui.MATCH, 0.5f))
        return root
    }

    private fun program(p: StepProgram?) {
        a.hideKeyboard()
        val m = machine
        machine = when (p) {
            null -> null
            m?.program -> m
            StepProgram.MANUAL -> StepMachine(seconds = 60, speed = 2)
            StepProgram.KNEAD -> StepMachine(StepProgram.KNEAD, seconds = 90, speed = 4)
            StepProgram.STEAM -> StepMachine(StepProgram.STEAM, seconds = 20 * 60)
            StepProgram.TOAST -> StepMachine(StepProgram.TOAST, seconds = 7 * 60)
            StepProgram.TURBO -> StepMachine(StepProgram.TURBO, pulses = 3)
            // Programmes du Smart : durée et température par défaut de l'appli d'origine (raisonnables).
            StepProgram.MIXER -> StepMachine(StepProgram.MIXER, seconds = 30)
            StepProgram.SMOOTHIE -> StepMachine(StepProgram.SMOOTHIE, seconds = 60)
            StepProgram.SOUS_VIDE -> StepMachine(StepProgram.SOUS_VIDE, seconds = 3600, tempC = 60)
            StepProgram.SLOW_COOK -> StepMachine(StepProgram.SLOW_COOK, seconds = 2 * 3600, tempC = 90)
            StepProgram.FERMENT -> StepMachine(StepProgram.FERMENT, seconds = 8 * 3600, tempC = 40)
        }
        renderSetting()
        if (p == StepProgram.MANUAL && m?.program != StepProgram.MANUAL) openManual()
    }

    private fun openManual() {
        a.push(ManualScreen(a, machine) { picked -> machine = picked; renderSetting() })
    }

    private fun renderSetting() {
        val box = settingBox
        box.removeAllViews()
        val m = machine
        // Seulement ce que le robot choisi sait faire (le Smart a ses programmes en plus).
        val caps = RobotProfiles.active.capabilities
        val choices = listOf<Pair<StepProgram?, String>>(null to tr("Aucun")) +
            StepProgram.values().filter { caps.has(it.feature) || it == m?.program }.map { it to it.label }
        for (row in choices.chunked(3)) {
            val line = Ui.horizontal(c)
            for ((p, label) in row) line.addView(Ui.toggleChip(c, label, m?.program == p) { program(p) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
            box.addView(line, Ui.lp(b = 8))
        }
        if (m == null) {
            box.addView(Ui.text(c, tr("Étape sans le robot : couper, verser, réserver…"), 17f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) },
                Ui.lp(t = 10))
        } else when (m.program) {
            StepProgram.MANUAL -> {
                box.addView(Ui.text(c, m.summary, 22f, Ui.ACCENT, Ui.MEDIUM), Ui.lp(t = 10))
                box.addView(Ui.button(c, tr("Régler durée, température et vitesse"), Ui.Style.SECONDARY, Icon.Kind.POT, height = 56, textSize = 17f) {
                    openManual()
                }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 12))
            }
            StepProgram.TURBO -> {
                box.addView(Edit.label(c, tr("Impulsions")), Ui.lp(t = 10))
                box.addView(Edit.stepper(c, "× ${m.pulses}", m.pulses > 1, m.pulses < 10,
                    { set(m.copy(pulses = m.pulses - 1)) }, { set(m.copy(pulses = m.pulses + 1)) }), Ui.lp(Ui.WRAP, Ui.WRAP, t = 6))
            }
            StepProgram.KNEAD, StepProgram.STEAM, StepProgram.TOAST -> {
                val max = when (m.program) {
                    StepProgram.KNEAD -> (Limits.MAX_MS_KNEAD / 1000).toInt()
                    StepProgram.TOAST -> (Limits.MAX_MS_TOAST / 1000).toInt()
                    else -> (Limits.MAX_MS_DEFAULT / 1000).toInt()
                }
                val step = when (m.program) { StepProgram.KNEAD -> 15; StepProgram.TOAST -> 30; else -> 60 }
                box.addView(Edit.label(c, tr("Durée")), Ui.lp(t = 10))
                box.addView(Edit.stepper(c, Ui.clock(m.seconds * 1000L), m.seconds > step, m.seconds < max,
                    { set(m.copy(seconds = (m.seconds - step).coerceAtLeast(step))) },
                    { set(m.copy(seconds = (m.seconds + step).coerceAtMost(max))) }), Ui.lp(Ui.WRAP, Ui.WRAP, t = 6))
                val hint = when (m.program) {
                    StepProgram.STEAM -> tr("Mettez au moins 500 g d'eau dans le bol. 120 °C, la lame reste immobile.")
                    StepProgram.TOAST -> tr("Dorer oignons, lardons, viandes à 130 °C, avec un brassage doux en sens inverse.")
                    else -> tr("Pâtes à pain, pizza, brioche. Alterne sens inverse et normal à vitesse 4.")
                }
                box.addView(Ui.text(c, hint, 15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 10))
            }
            StepProgram.MIXER, StepProgram.SMOOTHIE, StepProgram.SOUS_VIDE, StepProgram.SLOW_COOK, StepProgram.FERMENT -> {
                // Bornes de l'appli d'origine du Smart pour ce programme.
                val sp = when (m.program) {
                    StepProgram.MIXER -> SmartProgram.MIXER
                    StepProgram.SMOOTHIE -> SmartProgram.SMOOTHIE
                    StepProgram.SOUS_VIDE -> SmartProgram.SOUS_VIDE
                    StepProgram.SLOW_COOK -> SmartProgram.MIJOTER_BRASSE
                    else -> SmartProgram.FERMENTER_BRASSE
                }
                val step = if (sp.maxS <= 600) 10 else if (sp.maxS <= 3 * 3600) 5 * 60 else 15 * 60
                box.addView(Edit.label(c, tr("Durée")), Ui.lp(t = 10))
                box.addView(Edit.stepper(c, Ui.clock(m.seconds * 1000L), m.seconds - step >= sp.minS, m.seconds + step <= sp.maxS,
                    { set(m.copy(seconds = (m.seconds - step).coerceAtLeast(sp.minS))) },
                    { set(m.copy(seconds = (m.seconds + step).coerceAtMost(sp.maxS))) }), Ui.lp(Ui.WRAP, Ui.WRAP, t = 6))
                if (m.program.heated) {
                    box.addView(Edit.label(c, tr("Température")), Ui.lp(t = 10))
                    box.addView(Edit.stepper(c, "${m.tempC} °C", m.tempC > sp.minCelsius, m.tempC < sp.maxCelsius,
                        { set(m.copy(tempC = (m.tempC - 1).coerceAtLeast(sp.minCelsius))) },
                        { set(m.copy(tempC = (m.tempC + 1).coerceAtMost(sp.maxCelsius))) }), Ui.lp(Ui.WRAP, Ui.WRAP, t = 6))
                }
                box.addView(Ui.text(c, tr("Programme du Monsieur Cuisine Smart, comme sur son appli d'origine."), 15f, Ui.TEXT3)
                    .apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 10))
            }
        }
        m?.violations()?.firstOrNull()?.let { v -> box.addView(Ui.text(c, v.message, 16f, Ui.WARN), Ui.lp(t = 10)) }
    }

    private fun set(m: StepMachine) {
        machine = m
        renderSetting()
    }

    private fun result(): Step {
        val g = grams.coerceIn(0, RecipeAdjustments.MAX_GRAMS)
        return Step(text.trim(), machine, if (g > 0) Weigh("", g) else null, needsReview = false)
    }

    private fun done() {
        a.hideKeyboard()
        val s = result()
        if (s.text.isEmpty() && s.machine == null && s.weigh == null) { a.toast(tr("Écrivez ce qu'il faut faire"), Ui.WARN); return }
        if (grams > RecipeAdjustments.MAX_GRAMS) { a.toast(tr("Entrez un poids entre 1 et %d g", RecipeAdjustments.MAX_GRAMS), Ui.WARN); return }
        s.machine?.violations()?.firstOrNull()?.let { a.toast(it.message, Ui.WARN); return }
        finished = true
        a.back()
        onDone(s)
    }

    override fun canLeave(proceed: () -> Unit): Boolean {
        if (finished) return true
        val unchanged = text.trim() == start.text.trim() && machine == start.machine && grams == (start.weigh?.grams ?: 0)
        if (unchanged) return true
        a.confirm(tr("Abandonner l'étape ?"), tr("Les modifications de cette étape seront perdues."), tr("Abandonner"), tr("Rester"), danger = true) {
            finished = true
            proceed()
        }
        return false
    }
}
