package app.toque.core.recipes

import java.text.Normalizer

object TextNorm {
    private val MARKS = Regex("\\p{Mn}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val STOP = hashSetOf(
        "de", "du", "des", "la", "le", "les", "l", "d", "et", "a", "au", "aux", "en", "un", "une",
        "pour", "sur", "par", "ou", "son", "sa", "ses", "mon", "ma", "mes", "the", "of", "with", "y",
        "recette", "recettes",
    )

    fun normalize(s: String): String {
        var t = s.lowercase().replace("œ", "oe").replace("æ", "ae").replace("ß", "ss")
        t = Normalizer.normalize(t, Normalizer.Form.NFD)
        t = MARKS.replace(t, "")
        t = NON_ALNUM.replace(t, " ")
        return t.trim()
    }

    /** Pluriels français simples : pommes -> pomme, choux -> chou, gâteaux -> gateau. */
    fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("aux") -> w.dropLast(1)
        w.length > 3 && (w.endsWith("s") || w.endsWith("x")) && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    fun tokens(s: String, keepStopWords: Boolean = false): List<String> {
        val n = normalize(s)
        if (n.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (w in n.split(' ')) {
            if (w.isEmpty()) continue
            if (!keepStopWords && w in STOP) continue
            out += stem(w)
        }
        return out
    }

    /** Distance de Damerau-Levenshtein (transpositions adjacentes) bornée par [max]. */
    fun distance(a: String, b: String, max: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        val n = a.length
        val m = b.length
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return max + 1
            val t = prev2
            prev2 = prev
            prev = cur
            cur = t
        }
        return prev[m]
    }
}

/** Filtres de la liste de recettes. */
data class RecipeFilter(
    val category: String = "",
    val favoritesOnly: Boolean = false,
    val source: RecipeSource? = null,
    val maxMinutes: Int = 0,
)

/**
 * Index de recherche en mémoire : tolérant aux accents, aux pluriels et aux fautes de frappe,
 * pondéré (titre > catégorie/tags > ingrédients > étapes). Quelques millisecondes pour
 * des milliers de recettes, même sur la tablette d'origine.
 */
class SearchIndex(recipes: List<Recipe>) {

    class Entry(
        val id: String,
        val title: String,
        val category: String,
        val tags: List<String>,
        val favorite: Boolean,
        val source: RecipeSource,
        val totalMinutes: Int,
        val image: String,
        val machineSteps: Int,
        val normTitle: String,
        val rating: Double = 0.0,
        val ratingCount: Int = 0,
    )

    data class Hit(val entry: Entry, val score: Double, val matched: Int)

    val entries: List<Entry>
    private val vocabulary: Array<String>
    /** terme -> (index d'entrée -> poids) */
    private val postings: HashMap<String, HashMap<Int, Int>> = HashMap()

    private companion object {
        const val W_TITLE = 10
        const val W_TAG = 5
        const val W_INGREDIENT = 4
        const val W_STEP = 1
    }

    init {
        val list = ArrayList<Entry>(recipes.size)
        for ((index, r) in recipes.withIndex()) {
            list += Entry(r.id, r.title, r.category, r.tags, r.favorite, r.source, r.totalMinutes, r.image,
                r.machineSteps, TextNorm.normalize(r.title), r.rating, r.ratingCount)
            fun add(text: String, w: Int) {
                for (t in TextNorm.tokens(text)) {
                    val p = postings.getOrPut(t) { HashMap(4) }
                    val old = p[index] ?: 0
                    if (w > old) p[index] = w
                }
            }
            add(r.title, W_TITLE)
            add(r.category, W_TAG)
            for (t in r.tags) add(t, W_TAG)
            for (i in r.allIngredients) add(i.name, W_INGREDIENT)
            for (s in r.steps) add(s.text, W_STEP)
        }
        entries = list
        vocabulary = postings.keys.toTypedArray().also { java.util.Arrays.sort(it) }
    }

    val categories: List<String> by lazy {
        entries.map { it.category }.filter { it.isNotBlank() }.distinct().sortedBy { TextNorm.normalize(it) }
    }

    private fun prefixMatches(token: String): List<String> {
        var lo = java.util.Arrays.binarySearch(vocabulary, token)
        if (lo < 0) lo = -lo - 1
        val out = ArrayList<String>()
        var i = lo
        while (i < vocabulary.size && vocabulary[i].startsWith(token) && out.size < 64) {
            out += vocabulary[i]
            i++
        }
        return out
    }

    /** Termes candidats pour un mot de la requête, avec leur qualité de correspondance. */
    private fun candidates(token: String): Map<String, Double> {
        val c = HashMap<String, Double>()
        if (postings.containsKey(token)) c[token] = 1.0
        if (token.length >= 2) for (t in prefixMatches(token)) if (t != token) c[t] = maxOf(c[t] ?: 0.0, 0.75)
        if (token.length >= 4) {
            val max = if (token.length >= 8) 2 else 1
            for (t in vocabulary) {
                if (c.containsKey(t)) continue
                if (kotlin.math.abs(t.length - token.length) > max) continue
                if (t[0] != token[0] && token.length < 6) continue
                val d = TextNorm.distance(token, t, max)
                if (d <= max) c[t] = if (d == 1) 0.55 else 0.35
            }
        }
        return c
    }

    private fun accepts(e: Entry, f: RecipeFilter): Boolean {
        if (f.category.isNotEmpty() && e.category != f.category) return false
        if (f.favoritesOnly && !e.favorite) return false
        if (f.source != null && e.source != f.source) return false
        if (f.maxMinutes > 0 && (e.totalMinutes <= 0 || e.totalMinutes > f.maxMinutes)) return false
        return true
    }

    fun search(query: String, filter: RecipeFilter = RecipeFilter(), limit: Int = 500): List<Hit> {
        val tokens = TextNorm.tokens(query).distinct()
        if (tokens.isEmpty()) {
            return entries.filter { accepts(it, filter) }
                .sortedWith(compareByDescending<Entry> { it.favorite }.thenBy { it.normTitle })
                .take(limit).map { Hit(it, 0.0, 0) }
        }
        val perToken = tokens.map { tok ->
            val scores = HashMap<Int, Double>()
            for ((term, quality) in candidates(tok)) {
                val p = postings[term] ?: continue
                for ((doc, weight) in p) {
                    val s = quality * weight
                    if (s > (scores[doc] ?: 0.0)) scores[doc] = s
                }
            }
            scores
        }
        val normQuery = TextNorm.normalize(query)
        val totals = HashMap<Int, Double>()
        val counts = HashMap<Int, Int>()
        for (scores in perToken) for ((doc, s) in scores) {
            totals[doc] = (totals[doc] ?: 0.0) + s
            counts[doc] = (counts[doc] ?: 0) + 1
        }
        val hits = ArrayList<Hit>()
        for ((doc, total) in totals) {
            val e = entries[doc]
            if (!accepts(e, filter)) continue
            val matched = counts[doc] ?: 0
            var score = total
            if (matched == tokens.size) score += 100.0
            if (e.normTitle.startsWith(normQuery)) score += 20.0 else if (e.normTitle.contains(normQuery)) score += 10.0
            if (e.favorite) score += 1.0
            hits += Hit(e, score, matched)
        }
        val full = hits.filter { it.matched == tokens.size }
        val result = if (full.isNotEmpty()) full else hits
        return result.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.entry.normTitle }).take(limit)
    }

    /** « Qu'est-ce que je peux faire avec… » : classe par nombre d'ingrédients présents. */
    fun withIngredients(list: List<String>, filter: RecipeFilter = RecipeFilter()): List<Hit> {
        val wanted = list.map { it.trim() }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return emptyList()
        val counts = HashMap<Int, Int>()
        for (w in wanted) {
            val docs = HashSet<Int>()
            for (tok in TextNorm.tokens(w)) for ((term, q) in candidates(tok)) {
                if (q < 0.5) continue
                val p = postings[term] ?: continue
                for ((doc, weight) in p) if (weight >= W_INGREDIENT) docs += doc
            }
            for (d in docs) counts[d] = (counts[d] ?: 0) + 1
        }
        return counts.filter { accepts(entries[it.key], filter) }
            .map { Hit(entries[it.key], it.value.toDouble(), it.value) }
            .sortedWith(compareByDescending<Hit> { it.matched }.thenBy { it.entry.normTitle })
    }
}
