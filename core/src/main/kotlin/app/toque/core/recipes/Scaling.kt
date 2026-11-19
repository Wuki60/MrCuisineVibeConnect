package app.toque.core.recipes

/** Quantités d'ingrédients recalculées pour un autre nombre de personnes. */
object Scaling {
    private val GLYPHS = mapOf("½" to 0.5, "¼" to 0.25, "¾" to 0.75, "⅓" to 1.0 / 3, "⅔" to 2.0 / 3)
    private val NUMBER = Regex("""^(\d+(?:[.,]\d+)?)?\s*(?:(\d+)/(\d+)|([½¼¾⅓⅔]))?$""")
    /** Milliers séparés par une espace (« 1 000 », y compris insécable). */
    private val THOUSANDS = Regex("""^\d{1,3}(?:[ \u00A0\u202F]\d{3})+$""")
    /** Quantité en tête d'un texte libre (« 400 g de tomates », « ½ concombre », « 2 à 3 œufs »). */
    private val LEADING = Regex(
        """^(\d{1,3}(?:[ \u00A0\u202F]\d{3})+|\d+(?:[.,]\d+)?(?:\s*(?:\d+/\d+|[½¼¾⅓⅔]))?|\d+/\d+|[½¼¾⅓⅔])""" +
            """(?:(\s*(?:-|–|à)\s*)(\d+(?:[.,]\d+)?|\d+/\d+|[½¼¾⅓⅔]))?(?=\s|$)""",
    )
    private val RANGE = Regex("""^(.+?)(\s*(?:-|–|à|to)\s*)(.+)$""")

    /** Au-delà de ce poids d'ingrédients, une recette agrandie risque de dépasser le repère maximum du bol. */
    const val BOWL_MAX_GRAMS = 3000.0
    private val GRAMS = mapOf("g" to 1.0, "ml" to 1.0, "cl" to 10.0, "dl" to 100.0, "kg" to 1000.0, "l" to 1000.0)
    private val UNIT_WORD = Regex("""^\s*([A-Za-z]+)\b""")

    /**
     * Poids approximatif des ingrédients donnés en poids ou en volume (1 ml compté pour 1 g) ; les
     * cuillères, pincées et pièces ne comptent pas.
     */
    fun totalGrams(items: List<Ingredient>): Double = items.sumOf { i ->
        if (i.qty.isNotBlank()) (parse(i.qty) ?: 0.0) * (GRAMS[i.unit.trim().lowercase()] ?: 0.0)
        else {
            val t = i.name.trimStart()
            val m = LEADING.find(t)
            val unit = m?.let { UNIT_WORD.find(t.substring(it.range.last + 1))?.groupValues?.get(1)?.lowercase() }
            if (m == null || m.groupValues[3].isNotEmpty()) 0.0
            else (parse(m.groupValues[1]) ?: 0.0) * (GRAMS[unit] ?: 0.0)
        }
    }

    /** Valeur de « 500 », « 1 000 », « 1,5 », « 1/2 », « 1 1/2 », « ½ » ; null si ce n'est pas un nombre. */
    fun parse(text: String): Double? {
        val t = text.trim()
        if (THOUSANDS.matches(t)) return t.filter { it.isDigit() }.toDouble()
        val m = NUMBER.matchEntire(t) ?: return null
        val (whole, num, den, glyph) = m.destructured
        if (whole.isEmpty() && num.isEmpty() && glyph.isEmpty()) return null
        var v = if (whole.isEmpty()) 0.0 else whole.replace(',', '.').toDouble()
        if (num.isNotEmpty()) {
            val d = den.toDouble()
            if (d == 0.0) return null
            v += num.toDouble() / d
        }
        if (glyph.isNotEmpty()) v += GLYPHS.getValue(glyph)
        return v
    }

    /**
     * Quantité lisible : au 5 près à partir de 100 (« 1 500 »), à l'unité à partir de 10, au quart
     * en dessous (« 1 ½ », « ¾ »), et jamais zéro.
     */
    fun format(value: Double): String {
        if (value >= 100) {
            val n = Math.round(value / 5) * 5
            return if (n >= 1000) "${n / 1000} ${(n % 1000).toString().padStart(3, '0')}" else n.toString()
        }
        if (value >= 10) return Math.round(value).toString()
        val quarters = Math.round(value * 4).coerceAtLeast(1)
        val whole = quarters / 4
        val part = when ((quarters % 4).toInt()) { 1 -> "¼"; 2 -> "½"; 3 -> "¾"; else -> "" }
        return when {
            part.isEmpty() -> whole.toString()
            whole == 0L -> part
            else -> "$whole $part"
        }
    }

    /** [qty] multipliée par [factor] ; une fourchette (« 2-3 ») garde ses deux bornes ; le texte libre reste tel quel. */
    fun qty(qty: String, factor: Double): String {
        if (factor == 1.0) return qty
        parse(qty)?.let { return format(it * factor) }
        val m = RANGE.matchEntire(qty.trim()) ?: return qty
        val low = parse(m.groupValues[1]) ?: return qty
        val high = parse(m.groupValues[3]) ?: return qty
        return format(low * factor) + m.groupValues[2] + format(high * factor)
    }

    /** [text] dont la quantité de tête, s'il y en a une, est multipliée par [factor]. */
    fun leading(text: String, factor: Double): String {
        if (factor == 1.0) return text
        val t = text.trimStart()
        val m = LEADING.find(t) ?: return text
        val low = parse(m.groupValues[1]) ?: return text
        var out = format(low * factor)
        if (m.groupValues[3].isNotEmpty()) {
            val high = parse(m.groupValues[3]) ?: return text
            out += m.groupValues[2] + format(high * factor)
        }
        return out + t.substring(m.range.last + 1)
    }
}

/** Le même ingrédient pour [factor] fois la recette ; sans quantité séparée, celle écrite en tête du nom suit. */
fun Ingredient.scaled(factor: Double): Ingredient = when {
    factor == 1.0 -> this
    qty.isNotBlank() -> copy(qty = Scaling.qty(qty, factor))
    else -> copy(name = Scaling.leading(name, factor))
}
