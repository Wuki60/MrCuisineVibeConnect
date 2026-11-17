package app.toque.core.recipes

import app.toque.core.json.Json
import app.toque.core.json.int
import app.toque.core.json.obj
import app.toque.core.json.str
import java.text.Normalizer
import kotlin.math.roundToInt

/**
 * Adaptations personnelles d'une recette importée : une note et des quantités corrigées. Elles sont
 * rangées à part de la recette (comme les favoris) pour survivre aux synchronisations, et
 * appliquées par-dessus à l'affichage avec [adjusted].
 *
 * L'utilisateur corrige un ingrédient ; les pesées et les « 500 g de … » des étapes qui s'y
 * rapportent suivent, en proportion (voir [QuantityLinks]). Les quantités qui ne figurent que dans
 * les étapes (l'eau, souvent absente de la liste) se corrigent à part, par étape ([weigh]).
 *
 * Chaque correction est rattachée à la valeur d'origine : si la recette change à la source, elle ne
 * s'applique plus et la valeur de la recette réapparaît plutôt qu'une correction mal placée.
 * Les réglages machine (temps, vitesse, température) ne sont pas modifiables.
 */
data class RecipeAdjustments(
    val note: String = "",
    /** Clé [qtyKey] → quantité voulue. */
    val qty: Map<String, String> = emptyMap(),
    /** Pesées absentes de la liste d'ingrédients : clé [weighKey] → poids voulu, en grammes. */
    val weigh: Map<String, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = note.isBlank() && qty.isEmpty() && weigh.isEmpty()

    fun withNote(text: String): RecipeAdjustments = copy(note = text.trim())

    /** [value] vide ou égal à la quantité de la recette : la correction est retirée. */
    fun withQty(ingredient: Ingredient, value: String): RecipeAdjustments {
        val key = qtyKey(ingredient)
        val v = value.trim()
        val original = ingredient.originalQty.ifBlank { ingredient.qty }.trim()
        return copy(qty = if (v.isEmpty() || v == original) qty - key else qty + (key to v))
    }

    /** [grams] nul ou égal au poids de la recette : la correction est retirée. */
    fun withWeigh(stepIndex: Int, weigh: Weigh, grams: Int): RecipeAdjustments {
        val original = if (weigh.originalGrams > 0) weigh.originalGrams else weigh.grams
        val key = weighKey(stepIndex, original)
        return copy(weigh = if (grams <= 0 || grams == original) this.weigh - key
        else this.weigh + (key to grams.coerceIn(1, MAX_GRAMS)))
    }

    fun encode(): String = Json.write(linkedMapOf("note" to note, "qty" to qty, "weigh" to weigh))

    /**
     * Report sur [target], la même recette venue de l'autre source (même titre), quand celle-ci est
     * retirée du catalogue. La note suit si [target] n'en a pas ; une quantité seulement si l'ingrédient
     * y est écrit de la même façon ; les pesées d'étape jamais (les étapes ne se correspondent pas).
     * [existing] : ce que [target] a déjà, qui reste prioritaire.
     */
    fun carriedTo(target: Recipe, existing: RecipeAdjustments = RecipeAdjustments()): RecipeAdjustments {
        val keys = target.withSplitQuantities().ingredients.flatMap { it.items }.map { qtyKey(it) }.toHashSet()
        val q = LinkedHashMap(existing.qty)
        for ((k, v) in qty) if (k in keys && k !in q) q[k] = v
        return existing.copy(note = existing.note.ifBlank { note }, qty = q)
    }

    companion object {
        /** Portée de la balance du robot. */
        const val MAX_GRAMS = 5000

        /** Le poids entre parenthèses (« (60 g) ») n'en fait pas partie : il suit la correction. */
        fun qtyKey(i: Ingredient): String =
            listOf(i.originalQty.ifBlank { i.qty }, i.unit, QuantityLinks.keyName(i.name)).joinToString("|") { it.trim() }

        fun weighKey(stepIndex: Int, originalGrams: Int): String = "$stepIndex:$originalGrams"

        /** Titre comparable d'une source à l'autre (casse, accents, apostrophes, ponctuation). */
        fun titleKey(title: String): String = QuantityLinks.norm(title)

        /** Texte illisible ou vide : aucune adaptation. */
        fun decode(text: String): RecipeAdjustments {
            val m = try {
                Json.parse(text).obj()
            } catch (_: Exception) {
                return RecipeAdjustments()
            }
            val qty = LinkedHashMap<String, String>()
            for ((k, v) in m["qty"].obj()) (v as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { qty[k] = it }
            val weighMap = m["weigh"].obj()
            val weigh = LinkedHashMap<String, Int>()
            for (k in weighMap.keys) weighMap.int(k)?.takeIf { it in 1..MAX_GRAMS }?.let { weigh[k] = it }
            return RecipeAdjustments(m.str("note")?.trim().orEmpty(), qty, weigh)
        }
    }
}

/**
 * Relie les quantités des étapes (« Ajouter 500 g de farine », pesées) aux ingrédients de la liste,
 * par le premier mot significatif du nom de l'ingrédient. Une étape n'est reliée que si un seul
 * ingrédient convient et que son poids n'en dépasse pas la quantité (une étape peut n'en prendre
 * qu'une partie) : dans le doute, rien n'est relié et rien n'est modifié.
 * Sur les recettes d'un robot (2308 recettes, novembre 2026), 84 à 87 % des pesées sont reliées.
 */
object QuantityLinks {
    private val STOP = setOf(
        "de", "du", "des", "la", "le", "les", "un", "une", "et", "au", "aux", "en", "ou", "pour", "avec", "sans",
        "frais", "fraiche", "fraiches", "fraich", "froide", "froid", "chaud", "chaude", "bien", "type", "non",
        "traite", "traites", "nature",
    )
    private const val NUMBER = "(\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+|\\d+)"
    private const val END = "(?=\\s+(?:dans|sur|au|aux|à|a|et|puis|ou|en|avec|pour)(?:\\s|$)|[,.;:(]|$)"
    /** « 500 g de farine », « 1 000 g d'eau » : nombre (groupe 1) et ce qui est pesé (groupe 2). */
    private val PHRASE = Regex("$NUMBER\\s*g\\s+(?:de\\s+|d['’]\\s*)(.+?)$END", RegexOption.IGNORE_CASE)
    /** Un nombre suivi de « g » (pas de « gousse », « gros »…). */
    private val GRAMS = Regex("$NUMBER(?=\\s*g(?![\\p{L}]))")

    fun norm(s: String): String {
        val d = Normalizer.normalize(s.lowercase().replace('’', '\'').replace("œ", "oe"), Normalizer.Form.NFD)
        return d.replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    private fun stem(w: String): String = if (w.length > 3 && (w.endsWith('s') || w.endsWith('x'))) w.dropLast(1) else w

    private fun words(text: String): Set<String> = norm(text).split(' ').filter { it.isNotEmpty() }.map(::stem).toSet()

    /** Mots significatifs du nom d'un ingrédient, dans l'ordre (« crème liquide (30 %) » → crème, liquide). */
    fun keywords(name: String): List<String> =
        norm(name.substringBefore(',').substringBefore('(')).split(' ')
            .filter { it.length > 1 && it !in STOP && !it.all(Char::isDigit) }.map(::stem)

    private fun number(s: String): Int = s.filter(Char::isDigit).toInt()

    private val FRACTIONS = mapOf('½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1 / 3.0, '⅔' to 2 / 3.0)
    private val THOUSANDS = Regex("(?<=\\d)[ \\u00A0\\u202F](?=\\d{3}(?!\\d))")

    /** Quantité d'un ingrédient en nombre : « 1,5 », « 1 000 », « 1/2 », « ½ », « 1 ½ ». Autre chose : null. */
    fun parse(q: String): Double? {
        var t = q.trim().replace(THOUSANDS, "").replace(',', '.')
        var frac = 0.0
        FRACTIONS[t.lastOrNull()]?.let { f ->
            frac = f
            t = t.dropLast(1).trim()
            if (t.isEmpty()) return f
        }
        val base = Regex("(\\d+)\\s*/\\s*(\\d+)").matchEntire(t)?.let { m ->
            val b = m.groupValues[2].toDouble()
            if (b == 0.0) return null else m.groupValues[1].toDouble() / b
        } ?: t.toDoubleOrNull() ?: return null
        return (base + frac).takeIf { it >= 0 && !it.isNaN() }
    }

    private const val QTY = "(\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)?(?:\\s*/\\s*\\d+)?(?:\\s+[½¼¾⅓⅔])?|[½¼¾⅓⅔])"
    private const val UNIT = "(kg|g|mg|l|cl|ml|c\\.\\s?s\\.|c\\.\\s?c\\.)"
    private val SPLIT_UNIT = Regex("$QTY\\s*$UNIT\\s+(.+)", RegexOption.IGNORE_CASE)
    private val SPLIT_COUNT = Regex("$QTY\\s+(.+)")
    /** « (60 g) » : poids total ; « (de 100 g) » : poids de chacun. */
    private val PAREN_GRAMS = Regex("\\(\\s*(de\\s+)?$NUMBER\\s*g\\s*\\)", RegexOption.IGNORE_CASE)

    /**
     * Sépare la quantité d'un ingrédient écrit d'un bloc, comme dans les recettes de l'appli
     * d'origine : « 100 g d’emmental » → 100 | g | d’emmental. Le texte affiché ne change pas.
     */
    fun split(i: Ingredient): Ingredient {
        if (i.qty.isNotBlank()) return i
        val n = i.name.trim()
        SPLIT_UNIT.matchEntire(n)?.let { m ->
            if (parse(m.groupValues[1]) != null) return i.copy(qty = m.groupValues[1], unit = m.groupValues[2], name = m.groupValues[3])
        }
        SPLIT_COUNT.matchEntire(n)?.let { m ->
            if (parse(m.groupValues[1]) != null) return i.copy(qty = m.groupValues[1], name = m.groupValues[2])
        }
        return i
    }

    /** Poids de l'ingrédient en grammes (ml comptés comme g), d'après son unité ou « (60 g) » ; null si inconnu. */
    fun grams(i: Ingredient): Double? {
        val q = parse(i.originalQty.ifBlank { i.qty }) ?: return null
        return when (i.unit.trim().lowercase()) {
            "g", "ml" -> q
            "kg", "l" -> q * 1000
            "cl" -> q * 10
            "mg" -> q / 1000
            "" -> PAREN_GRAMS.find(i.name)?.let { m ->
                val g = number(m.groupValues[2]).toDouble()
                if (m.groupValues[1].isNotEmpty()) g * q else g
            } ?: q
            else -> null
        }
    }

    fun keyName(name: String): String =
        PAREN_GRAMS.replace(name) { m -> if (m.groupValues[1].isNotEmpty()) m.value else "" }.replace(Regex("\\s+"), " ").trim()

    /** « échalotes (60 g) » avec 3 au lieu de 2 → « échalotes (90 g) » ; un poids « de chacun » ne change pas. */
    fun scaleName(name: String, factor: Double): String =
        PAREN_GRAMS.replace(name) { m ->
            if (m.groupValues[1].isNotEmpty()) m.value
            else m.value.replace(m.groupValues[2], scaled(number(m.groupValues[2]), factor).toString())
        }

    /** Ce qui est pesé dans « … [grams] g de X … », s'il est écrit ainsi. */
    fun phrase(text: String, grams: Int): String? =
        PHRASE.findAll(text).firstOrNull { number(it.groupValues[1]) == grams }?.groupValues?.get(2)?.trim()

    /** Indice (dans [Recipe.allIngredients]) de l'ingrédient dont [grams] g sont pris d'après [text]. */
    fun link(ingredients: List<Ingredient>, text: String, grams: Int): Int? =
        linkWords(ingredients, words(phrase(text, grams) ?: text), grams)

    private fun linkWords(ingredients: List<Ingredient>, tw: Set<String>, grams: Int): Int? {
        fun qty(k: Int) = grams(ingredients[k]) ?: 0.0
        var cands = ingredients.indices.filter { k ->
            val kw = keywords(ingredients[k].name)
            qty(k) > 0 && kw.isNotEmpty() && kw[0] in tw
        }
        if (cands.size > 1) cands.filter { kotlin.math.abs(qty(it) - grams) < 0.5 }.takeIf { it.isNotEmpty() }?.let { cands = it }
        if (cands.size > 1) {
            val overlap = cands.associateWith { keywords(ingredients[it].name).toSet().intersect(tw).size }
            val best = overlap.values.max()
            cands = cands.filter { overlap[it] == best }
        }
        val k = cands.singleOrNull() ?: return null
        return if (grams <= qty(k) * 1.02) k else null
    }

    /** Pesée par étape : indice d'étape → indice d'ingrédient. Les pesées absentes n'ont pas d'ingrédient. */
    fun weighings(r: Recipe): Map<Int, Int> {
        val ings = r.allIngredients
        val out = HashMap<Int, Int>()
        for ((i, s) in r.steps.withIndex()) {
            val w = s.weigh ?: continue
            link(ings, s.text.ifBlank { w.label }, w.grams)?.let { out[i] = it }
        }
        return out
    }

    /** Remplace les « [old] g » de [text] par « [new] g ». */
    fun replaceGrams(text: String, old: Int, new: Int): String =
        GRAMS.replace(text) { m -> if (number(m.value) == old) new.toString() else m.value }

    /** Met à l'échelle les « N g de X » de [text] dont X est un ingrédient corrigé ([ratio] : indice → facteur). */
    fun scaleText(text: String, ingredients: List<Ingredient>, ratio: Map<Int, Double>): String {
        if (ratio.isEmpty()) return text
        return PHRASE.replace(text) { m ->
            val n = number(m.groupValues[1])
            val k = linkWords(ingredients, words(m.groupValues[2]), n)
            val f = if (k == null) null else ratio[k]
            if (f == null) m.value else scaled(n, f).toString() + m.value.substring(m.groupValues[1].length)
        }
    }

    fun scaled(grams: Int, factor: Double): Int = (grams * factor).roundToInt().coerceAtLeast(if (factor > 0) 1 else 0)
}

/** Les ingrédients écrits d'un bloc (« 100 g de farine ») avec leur quantité à part, pour pouvoir la corriger. */
fun Recipe.withSplitQuantities(): Recipe {
    if (ingredients.none { g -> g.items.any { it.qty.isBlank() } }) return this
    val groups = ingredients.map { g -> g.copy(items = g.items.map(QuantityLinks::split)) }
    return if (groups == ingredients) this else copy(ingredients = groups)
}

/** Pesées des étapes qui ne correspondent à aucun ingrédient de la liste : indice d'étape → ce qui est pesé. */
fun Recipe.unlistedWeighings(): List<Pair<Int, String>> {
    val linked = QuantityLinks.weighings(withSplitQuantities())
    return steps.withIndex().mapNotNull { (i, s) ->
        val w = s.weigh ?: return@mapNotNull null
        if (i in linked) return@mapNotNull null
        val name = QuantityLinks.phrase(s.text, w.grams) ?: w.label.ifBlank { s.text }.take(40)
        i to name.trim()
    }
}

/** La recette telle que l'utilisateur la veut ; les valeurs d'origine restent dans `originalQty` / `originalGrams`. */
fun Recipe.adjusted(a: RecipeAdjustments): Recipe {
    val base = withSplitQuantities()
    if (a.qty.isEmpty() && a.weigh.isEmpty()) return base
    val flat = base.allIngredients
    val ratio = HashMap<Int, Double>()
    var k = 0
    val groups = base.ingredients.map { g ->
        g.copy(items = g.items.map { i ->
            val index = k++
            val v = if (i.qty.isBlank()) null else a.qty[RecipeAdjustments.qtyKey(i)]
            if (v == null || v == i.qty.trim()) i
            else {
                val old = QuantityLinks.parse(i.qty)
                val new = QuantityLinks.parse(v)
                val f = if (old != null && new != null && old > 0) new / old else null
                if (f != null) ratio[index] = f
                i.copy(qty = v, originalQty = i.qty, name = if (f == null) i.name else QuantityLinks.scaleName(i.name, f))
            }
        })
    }
    val links = QuantityLinks.weighings(base)
    val newSteps = base.steps.mapIndexed { si, s ->
        var text = QuantityLinks.scaleText(s.text, flat, ratio)
        val w = s.weigh
        var weigh = w
        if (w != null) {
            val linked = links[si]
            val g = if (linked != null) ratio[linked]?.let { QuantityLinks.scaled(w.grams, it).coerceAtMost(RecipeAdjustments.MAX_GRAMS) }
            else a.weigh[RecipeAdjustments.weighKey(si, w.grams)]
            if (g != null && g != w.grams) {
                weigh = w.copy(grams = g, label = QuantityLinks.replaceGrams(w.label, w.grams, g), originalGrams = w.grams)
                if (text == s.text) text = QuantityLinks.replaceGrams(text, w.grams, g)
            }
        }
        if (text == s.text && weigh === w) s else s.copy(text = text, weigh = weigh)
    }
    return base.copy(ingredients = groups, steps = newSteps)
}
