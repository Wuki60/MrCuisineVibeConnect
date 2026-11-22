package app.toque.core.recipes

import app.toque.core.i18n.I18n
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Recette pour un autre nombre de personnes. Pour qu'elle reste bonne :
 *
 * - tout ce qui se pèse ou se mesure suit la même proportion (liste d'ingrédients, pesées des
 *   étapes, « 500 g d'eau » dans le texte), arrondi comme on l'écrirait à la main (2,5 g ; 65 g ;
 *   340 g ; 1 250 g), avec le même arrondi partout pour que la liste et les étapes concordent ;
 * - ce qui se compte (œufs, gousses, cuillères) s'arrondit au ½, ou au ¼ pour les petites
 *   cuillerées ; jamais moins d'un demi ;
 * - temps, vitesses et températures ne changent pas : la machine reste réglée comme la recette
 *   testée, et l'écran rappelle de surveiller la cuisson ;
 * - de moitié au double de la recette seulement : au-delà, la cuisson n'a plus grand-chose à voir.
 */
object Portions {
    /** Liquide au maximum dans le bol (notice du SKMC 1200, à vitesse 10). */
    const val BOWL_MAX_G = 2500

    /** Nombres de personnes proposés pour une recette prévue pour [base] personnes. */
    fun range(base: Int): IntRange = if (base <= 0) IntRange.EMPTY else maxOf(1, ceil(base / 2.0).toInt())..base * 2

    private val METRIC = mapOf("g" to 1.0, "mg" to 0.001, "kg" to 1000.0, "ml" to 1.0, "cl" to 10.0, "dl" to 100.0, "l" to 1000.0)
    private val GLYPHS = listOf(0.25 to "¼", 0.5 to "½", 0.75 to "¾")

    /** Poids ou volume (g ou ml) arrondi comme dans une recette écrite à la main. */
    fun roundMass(v: Double): Double {
        val step = when {
            v < 5 -> 0.5
            v < 20 -> 1.0
            v < 250 -> 5.0
            v < 1000 -> 10.0
            else -> 25.0
        }
        return maxOf(step, Math.round(v / step) * step)
    }

    /** Pesée de la balance (grammes entiers). */
    fun roundGrams(g: Double): Int = roundMass(g).roundToLong().toInt().coerceIn(1, RecipeAdjustments.MAX_GRAMS)

    /** Œufs, gousses, cuillerées… : au ½ (au ¼ pour moins d'une cuillerée), au moins ½. */
    fun roundCount(v: Double, pieces: Boolean): Double {
        val step = when {
            v >= 3 -> if (pieces) 1.0 else 0.5
            v < 1 && !pieces -> 0.25
            else -> 0.5
        }
        val min = if (pieces) 0.5 else 0.25
        return maxOf(min, Math.round(v / step) * step)
    }

    /** « (60 g) » dans le nom d'un ingrédient ; un poids « de chacun » (« (de 100 g) ») ne change pas. */
    fun scaleName(name: String, f: Double): String =
        PAREN.replace(name) { m ->
            if (m.groupValues[1].isNotEmpty()) m.value
            else m.value.replace(m.groupValues[2], roundGrams(m.groupValues[2].filter(Char::isDigit).toInt() * f).toString())
        }

    private val PAREN = Regex("\\(\\s*(de\\s+)?(\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+|\\d+)\\s*g\\s*\\)", RegexOption.IGNORE_CASE)

    /** La quantité [qty] de [unit] multipliée par [f], écrite comme la recette l'écrivait. Null : pas un nombre. */
    fun scaleQty(qty: String, unit: String, f: Double, language: String = ""): String? {
        val q = QuantityLinks.parse(qty) ?: return null
        val comma = separator(qty, language)
        val u = METRIC[unit.trim().lowercase()]
        if (u != null) {
            val scaled = roundMass(q * u * f) / u
            return decimal(scaled, comma)
        }
        return glyphs(roundCount(q * f, pieces = unit.isBlank()), comma)
    }

    /** Toutes les quantités pesées ou mesurées d'un texte d'étape, multipliées par [f]. */
    fun scaleText(text: String, f: Double, language: String = ""): String =
        AMOUNT.replace(text) { m ->
            val n = QuantityLinks.parse(m.groupValues[1]) ?: return@replace m.value
            val u = METRIC[m.groupValues[2].lowercase()] ?: return@replace m.value
            val comma = separator(m.groupValues[1], language)
            val scaled = roundMass(n * u * f) / u
            val number = if (u == 1.0 && scaled >= 1000 && THOUSANDS.containsMatchIn(m.groupValues[1])) {
                val s = decimal(scaled, comma)
                s.reversed().chunked(3).joinToString(m.groupValues[1].first { !it.isDigit() }.toString()).reversed()
            } else decimal(scaled, comma)
            number + m.value.substring(m.groupValues[1].length)
        }

    private val THOUSANDS = Regex("\\d[ \\u00A0\\u202F]\\d{3}")
    /** « 500 g », « 1,5 l », « 1 000 g » ; pas « 24 cm », « 2 gousses » ni « 3 l'oignon ». */
    private val AMOUNT = Regex("(\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+|\\d+(?:[.,]\\d+)?)\\s?(kg|mg|ml|cl|dl|g|l)(?![\\p{L}'’])", RegexOption.IGNORE_CASE)

    private fun separator(written: String, language: String): Boolean = when {
        written.contains(',') -> true
        written.contains('.') -> false
        else -> !(language.ifBlank { I18n.code }).lowercase().startsWith("en")
    }

    private fun decimal(v: Double, comma: Boolean): String {
        val r = Math.round(v * 100) / 100.0
        val s = if (r == floor(r)) r.toLong().toString() else r.toString()
        return if (comma) s.replace('.', ',') else s
    }

    private fun glyphs(v: Double, comma: Boolean): String {
        val whole = floor(v).toLong()
        val frac = v - whole
        if (frac < 1e-9) return whole.toString()
        val g = GLYPHS.firstOrNull { abs(it.first - frac) < 1e-6 }?.second ?: return decimal(v, comma)
        return if (whole == 0L) g else "$whole $g"
    }

    /**
     * Accord en nombre quand une quantité comptée passe le cap de 2 (en français, « 1,5 » reste au
     * singulier) : « 1 oignon » → « 2 oignons », « 2 gousses d'ail » → « 1 gousse d'ail ».
     * Seulement les mots du début, jusqu'au premier « de », « à », « en »… ou signe de ponctuation :
     * « 1 petit oignon rouge, émincé » → « 2 petits oignons rouges, émincé ». Français seulement.
     */
    fun agree(phrase: String, from: Double, to: Double, language: String = ""): String {
        if (!(language.ifBlank { I18n.code }).lowercase().startsWith("fr")) return phrase
        if (phrase.contains("(s)") || phrase.contains("(x)")) return phrase // « oignon(s) » : déjà neutre
        val plural = when {
            from < 2 && to >= 2 -> true
            from >= 2 && to < 2 -> false
            else -> return phrase
        }
        val cut = phrase.indexOfFirst { it in ",;:(" }.let { if (it < 0) phrase.length else it }
        var stop = false
        val head = phrase.substring(0, cut).split(' ').joinToString(" ") { w ->
            if (stop || w.isEmpty()) return@joinToString w
            if (w.lowercase() in STOP || w.any { it == '\'' || it == '’' || it == '.' || it.isDigit() }) {
                stop = true
                return@joinToString w
            }
            w.split('-').joinToString("-") { if (plural) pluralFr(it) else singularFr(it) }
        }
        return head + phrase.substring(cut)
    }

    /** Quantité comptée (pas un poids ni un volume) : l'accord s'applique. */
    fun isCount(unit: String): Boolean = METRIC[unit.trim().lowercase()] == null

    private val STOP = setOf("de", "du", "des", "à", "a", "au", "aux", "en", "pour", "ou", "et", "avec", "sans", "sur",
        "dans", "la", "le", "les", "un", "une", "par", "environ", "soit", "bien", "très")
    private val INVARIABLE = setOf("noix", "pois", "radis", "anchois", "ananas", "maïs", "riz", "jus", "os", "cassis",
        "brebis", "panais", "frais", "gros", "gras", "épais", "doux", "faux", "bio", "salsifis", "hachis", "couscous",
        "chinois", "tournedos", "lys")
    private val PLURAL_X = setOf("chou", "genou", "caillou", "bijou", "hibou", "joujou", "pou")

    private fun pluralFr(w: String): String {
        val l = w.lowercase()
        if (l.isEmpty() || !l.all { it.isLetter() } || l in INVARIABLE) return w
        return when {
            l.endsWith("s") || l.endsWith("x") || l.endsWith("z") -> w
            l in PLURAL_X || l.endsWith("au") || (l.endsWith("eu") && l != "bleu" && l != "pneu") -> w + "x"
            l.endsWith("al") -> w.dropLast(2) + "aux"
            else -> w + "s"
        }
    }

    private fun singularFr(w: String): String {
        val l = w.lowercase()
        if (l.isEmpty() || !l.all { it.isLetter() } || l in INVARIABLE) return w
        return when {
            l.endsWith("eux") -> w // crémeux, juteux : même forme au singulier
            l.endsWith("aux") && !l.endsWith("eaux") && !l.endsWith("yaux") -> w.dropLast(3) + "al"
            l.endsWith("aux") || l.endsWith("oux") -> w.dropLast(1)
            l.endsWith("s") && !l.endsWith("ss") && l.length > 2 -> w.dropLast(1)
            else -> w
        }
    }

    /** Poids estimé de tous les ingrédients pesés ou mesurés (ml comptés comme g), pesées hors liste comprises. */
    fun totalGrams(r: Recipe): Int {
        val listed = r.allIngredients.sumOf { i ->
            val q = QuantityLinks.parse(i.qty) ?: return@sumOf 0.0
            METRIC[i.unit.trim().lowercase()]?.let { q * it } ?: 0.0
        }
        val unlisted = r.unlistedWeighings().sumOf { (si, _) -> r.steps.getOrNull(si)?.weigh?.grams ?: 0 }
        return (listed + unlisted).roundToLong().toInt()
    }
}

/**
 * La recette (déjà adaptée par l'utilisateur) pour [people] personnes. Les valeurs « de la recette »
 * (`originalQty`, `originalGrams`) suivent aussi, pour comparer à nombre de personnes égal.
 */
fun Recipe.forServings(people: Int): Recipe {
    if (servings <= 0 || people <= 0 || people == servings) return this
    val f = people.toDouble() / servings
    val groups = ingredients.map { g ->
        g.copy(items = g.items.map { i ->
            val q = Portions.scaleQty(i.qty, i.unit, f, language) ?: return@map i
            // « 1 oignon » → « 2 oignons » : sur l'unité si c'est un mot (« pincée »), sinon sur le nom.
            val from = QuantityLinks.parse(i.qty)
            val to = QuantityLinks.parse(q)
            val count = from != null && to != null && Portions.isCount(i.unit)
            val wordUnit = i.unit.isNotBlank() && i.unit.trim().all { it.isLetter() }
            i.copy(
                qty = q,
                originalQty = if (i.originalQty.isBlank()) "" else Portions.scaleQty(i.originalQty, i.unit, f, language) ?: i.originalQty,
                unit = if (count && wordUnit) Portions.agree(i.unit, from!!, to!!, language) else i.unit,
                name = Portions.scaleName(if (count && i.unit.isBlank()) Portions.agree(i.name, from!!, to!!, language) else i.name, f),
            )
        })
    }
    val newSteps = steps.map { s ->
        val w = s.weigh?.let { w ->
            val grams = Portions.roundGrams(w.grams * f)
            w.copy(
                grams = grams,
                label = Portions.scaleText(w.label, f, language),
                originalGrams = if (w.originalGrams > 0) Portions.roundGrams(w.originalGrams * f) else 0,
            )
        }
        s.copy(text = Portions.scaleText(s.text, f, language), weigh = w)
    }
    return copy(servings = people, ingredients = groups, steps = newSteps)
}
