package app.toque.core.recipes

import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.bool
import app.toque.core.json.double
import app.toque.core.json.int
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.machine.Accessory

/**
 * Recettes publiées sur monsieur-cuisine.com, lues directement par l'API publique du site
 * (sans compte, sans l'appli d'origine) puis converties au modèle de Toque.
 *
 * Seules les recettes officielles du Monsieur Cuisine connect sont demandées. Chaque étape
 * porte son réglage machine (mode « customized », balance, turbo, vapeur, rissoler, pétrir).
 */
object McOnline {
    const val API = "https://mc-api.tecpal.com"
    /** Monsieur Cuisine connect. */
    const val DEVICE = "MC2.0"
    const val PAGE_SIZE = 100
    /** Préfixe des identifiants Toque des recettes du site (« mc- » : celles de l'appli d'origine). */
    const val ID_PREFIX = "web-"

    /** En-têtes attendus par l'API ; « device-type: web » fait renvoyer le nom des ingrédients. */
    fun headers(language: String): Map<String, String> =
        linkedMapOf("Accept" to "application/json", "Accept-Language" to language, "device-type" to "web")

    fun listUrl(page: Int): String =
        "$API/api/v2/recipes/page/$page/size/$PAGE_SIZE?filters%5BdeviceTypes%5D%5B%5D=$DEVICE&filters%5Bsource%5D%5B%5D=official"

    fun recipeUrl(apiId: Long): String = "$API/api/v2/recipes/$apiId"

    fun toqueId(apiId: Long): String = ID_PREFIX + apiId

    /** Langue des recettes d'après celle de l'interface (le français par défaut). */
    fun languageFor(uiCode: String): String = when (uiCode.substringBefore('-').lowercase()) {
        "fr" -> "fr-FR"
        "de" -> "de-DE"
        "it" -> "it-IT"
        "es" -> "es-ES"
        "nl" -> "nl-NL"
        "pl" -> "pl-PL"
        "cs" -> "cs-CZ"
        "pt" -> "pt-PT"
        "en" -> "en-US"
        else -> "fr-FR"
    }

    class Entry(
        val apiId: Long,
        val updated: String,
        val name: String,
        val author: String = "",
        val rating: Double = 0.0,
        val ratingCount: Int = 0,
    ) {
        /** Empreinte enregistrée dans l'index de synchro : fiche modifiée, ou seulement sa note. */
        val stamp: String get() = "$updated|${ratingText(rating)}|$ratingCount|$author"
    }

    /** Date de mise à jour d'une empreinte d'index (les anciens index n'ont que la date). */
    fun updatedOf(stamp: String?): String? = stamp?.substringBefore('|')

    private fun ratingText(r: Double): String = String.format(java.util.Locale.US, "%.2f", r)

    class Page(val total: Int, val totalPages: Int, val entries: List<Entry>)

    private fun Any?.long(): Long? = when (this) {
        is Number -> toLong()
        is String -> trim().toLongOrNull()
        else -> null
    }

    /** Une page de la liste des recettes (résumés : identifiant, date de mise à jour, nom). */
    fun parsePage(json: String): Page {
        val d = Json.parse(json).obj()["data"].obj()
        val entries = d["recipes"].list().mapNotNull { any ->
            val r = any.obj()
            val id = r["id"].long() ?: return@mapNotNull null
            Entry(
                id, r.str("lastUpdated").orEmpty(), r.str("name")?.trim().orEmpty(),
                author = r["author"].obj().str("name")?.trim().orEmpty(),
                rating = (r.double("rating") ?: 0.0).coerceIn(0.0, 5.0),
                ratingCount = (r.int("totalRating") ?: 0).coerceAtLeast(0),
            )
        }
        return Page(d.int("total") ?: entries.size, d.int("totalPage") ?: 1, entries)
    }

    /** Fiche complète (réponse de [recipeUrl]) convertie en recette Toque ; null si inutilisable. */
    fun parseRecipe(json: String): Recipe? {
        val r = Json.parse(json).obj()["data"].obj()["recipe"].obj()
        val apiId = r["id"].long() ?: return null
        val title = r.str("name")?.trim().orEmpty()
        if (title.isEmpty()) return null

        val sizes = r["servingSizes"].list().map { it.obj() }
        val defaultSize = r.int("defaultServingSizeId")
        val size = sizes.firstOrNull { it.int("id") == defaultSize } ?: sizes.firstOrNull() ?: emptyMap()

        val categories = r["categories"].list().mapNotNull { it.obj().str("name")?.trim()?.takeIf { n -> n.isNotEmpty() } }
        val tags = r["tags"].list().mapNotNull { t ->
            (if (t is String) t else t.obj().str("name"))?.trim()?.takeIf { it.isNotEmpty() }
        }

        val notes = ArrayList<String>()
        val servingUnit = size.str("servingUnit")?.trim().orEmpty()
        val amount = size.int("amount") ?: 0
        if (amount > 0 && servingUnit.isNotEmpty() && !servingUnit.lowercase().startsWith("portion") &&
            !servingUnit.lowercase().startsWith("pers")) notes += tr("Pour %d %s", amount, servingUnit)
        plain(r.str("description"))?.let { notes += it }
        plain(r.str("hintsAndTips"))?.let { notes += it }

        val steps = steps(size["steps"].list().map { it.obj() })
        if (steps.isEmpty()) plain(size.str("instruction"))?.let { notes += it }

        val thumb = r["thumbnail"].obj().str("landscape") ?: r["detailsImage"].obj().str("landscape")
        return Recipe(
            id = toqueId(apiId),
            title = title,
            category = categories.firstOrNull().orEmpty(),
            tags = (categories.drop(1) + tags).distinct(),
            servings = amount,
            prepMinutes = r.int("preparationDuration") ?: 0,
            totalMinutes = r.int("duration") ?: 0,
            difficulty = (r.int("complexityLevel") ?: 1).coerceIn(1, 3),
            ingredients = ingredients(size),
            steps = steps,
            source = RecipeSource.IMPORTED,
            image = thumb?.trim().orEmpty(),
            notes = notes.joinToString("\n\n"),
            author = r["author"].obj().str("name")?.trim().orEmpty(),
            rating = (r.double("rating") ?: 0.0).coerceIn(0.0, 5.0),
            ratingCount = (r.int("totalRating") ?: 0).coerceAtLeast(0),
        )
    }

    /** Recette déjà téléchargée dont seuls l'auteur ou la note ont changé sur le site. */
    fun withScore(r: Recipe, e: Entry): Recipe =
        r.copy(author = e.author.ifEmpty { r.author }, rating = e.rating, ratingCount = e.ratingCount)

    // ------------------------------------------------------------------ avis

    /** Identifiant sur le site d'une recette Toque téléchargée du site, sinon null. */
    fun apiIdOf(toqueId: String): Long? =
        if (toqueId.startsWith(ID_PREFIX)) toqueId.removePrefix(ID_PREFIX).toLongOrNull() else null

    /** Avis publics d'une recette (le serveur exige un en-tête X-Request-Id, voir [reviewHeaders]). */
    fun reviewsUrl(apiId: Long, page: Int, size: Int = 10): String =
        "$API/api/v1/recipe-reviews/recipes/$apiId/reviews?page=$page&size=$size"

    fun reviewHeaders(language: String): Map<String, String> =
        headers(language) + ("X-Request-Id" to java.util.UUID.randomUUID().toString())

    class Review(val author: String, val rating: Int, val message: String, val date: String, val reply: String)

    class ReviewPage(val total: Int, val page: Int, val totalPages: Int, val reviews: List<Review>)

    fun parseReviews(json: String): ReviewPage {
        val d = Json.parse(json).obj()["data"].obj()
        val reviews = d["reviews"].list().mapNotNull { any ->
            val r = any.obj()
            if (r.bool("isReported") == true) return@mapNotNull null
            val message = plain(r.str("message")).orEmpty()
            val rating = (r.int("rating") ?: 0).coerceIn(0, 5)
            if (message.isEmpty() && rating == 0) return@mapNotNull null
            val reply = when (val c = r["comment"]) {
                is String -> c
                is Map<*, *> -> c.obj().str("message") ?: c.obj().str("comment") ?: c.obj().str("text")
                else -> null
            }.let { plain(it) }.orEmpty()
            Review(
                author = r["author"].obj().str("name")?.trim().orEmpty(),
                rating = rating,
                message = message,
                date = r.str("updatedAt")?.trim()?.substringBefore(' ').orEmpty(),
                reply = reply,
            )
        }
        val total = d.int("total") ?: reviews.size
        return ReviewPage(total, d.int("currentPage") ?: 1, (d.int("totalPage") ?: 1).coerceAtLeast(1), reviews)
    }

    private fun ingredients(size: Map<String, Any?>): List<IngredientGroup> {
        val groupNames = LinkedHashMap<Long, String>()
        size["ingredientGroups"].list().map { it.obj() }.sortedBy { it.int("order") ?: 0 }.forEach { g ->
            val id = g["id"].long() ?: return@forEach
            groupNames[id] = g.str("name")?.trim().orEmpty()
        }
        val byGroup = LinkedHashMap<Long?, MutableList<Ingredient>>()
        byGroup[null] = ArrayList()
        for (id in groupNames.keys) byGroup[id] = ArrayList()
        size["ingredients"].list().map { it.obj() }.sortedBy { it.int("order") ?: 0 }.forEach { i ->
            val name = i.str("name")?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            val group = i["ingredientGroupId"].long()?.takeIf { it in groupNames }
            byGroup.getOrPut(group) { ArrayList() } += Ingredient(
                qty = i.str("amount")?.trim()?.removeSuffix(".0").orEmpty(),
                unit = i.str("unit")?.trim().orEmpty(),
                name = name,
            )
        }
        return byGroup.mapNotNull { (id, items) ->
            if (items.isEmpty()) null else IngredientGroup(if (id == null) "" else groupNames[id].orEmpty(), items)
        }
    }

    /**
     * Étapes. Dans l'API, le réglage machine est souvent une étape à part, sans texte (« Mode »),
     * qui suit l'étape décrivant le geste : on le rattache à cette étape quand elle n'a pas déjà
     * un réglage. Tout réglage importé est à vérifier par l'utilisateur avant d'être lancé.
     */
    private fun steps(raw: List<Map<String, Any?>>): List<Step> {
        val out = ArrayList<Step>()
        for (s in raw.sortedBy { it.int("order") ?: 0 }) {
            val description = s.str("description")?.trim().orEmpty()
            val name = s.str("name")?.trim().orEmpty()
            val ds = s["deviceSetting"]?.obj()?.takeIf { it.isNotEmpty() }
            val text = description.ifEmpty { if (ds == null) name else "" }
            if (ds == null) {
                if (text.isNotEmpty()) out += Step(text)
                continue
            }
            val mode = ds.str("mode")?.trim()?.lowercase().orEmpty()
            val settings = ds["settings"].list().map { it.obj() }.sortedBy { it.int("order") ?: 0 }.ifEmpty { listOf(emptyMap()) }
            for ((k, st) in settings.withIndex()) {
                val label = if (k == 0) text else ""
                if (mode == "scale") {
                    val grams = st.int("weight") ?: 0
                    if (grams <= 0) {
                        if (label.isNotEmpty()) out += Step(label)
                        continue
                    }
                    out += Step(label.ifEmpty { tr("Peser %d g", grams) }, weigh = Weigh(label.ifEmpty { name }.take(40), grams))
                    continue
                }
                val machine = machine(mode, st, ds.bool("reverse") ?: false, text.ifEmpty { name })
                if (machine == null) {
                    val note = tr("Programme « %s » non pris en charge : à faire à la main.", mode)
                    out += Step(if (label.isEmpty()) note else "$label\n$note")
                    continue
                }
                val previous = out.lastOrNull()
                if (label.isEmpty() && previous != null && previous.machine == null && previous.weigh == null && previous.text.isNotEmpty()) {
                    out[out.size - 1] = previous.copy(machine = machine, needsReview = true)
                } else {
                    out += Step(label.ifEmpty { machine.summary }, machine, needsReview = true)
                }
            }
        }
        return out
    }

    private fun machine(mode: String, st: Map<String, Any?>, reverse: Boolean, text: String): StepMachine? {
        val seconds = (st.int("time") ?: 0).coerceAtLeast(0)
        return when {
            mode == "customized" -> {
                val speedF = (st["speedFloat"] as? Number)?.toDouble() ?: st.int("speed")?.toDouble() ?: 0.0
                val speed = Math.ceil(speedF).toInt().coerceIn(0, 10)
                StepMachine(
                    program = StepProgram.MANUAL,
                    seconds = seconds,
                    speed = speed,
                    tempC = (st.int("temperature") ?: 0).coerceAtLeast(0),
                    reverse = reverse,
                    accessory = if (text.lowercase().contains("fouet") && speed <= 4) Accessory.WHISK else Accessory.BLADE,
                )
            }
            mode == "turbo" -> StepMachine(program = StepProgram.TURBO, seconds = seconds, pulses = maxOf(1, seconds))
            mode == "steaming" -> StepMachine(program = StepProgram.STEAM, seconds = seconds)
            mode == "roasting" -> StepMachine(program = StepProgram.TOAST, seconds = seconds)
            mode.startsWith("kneading") -> StepMachine(program = StepProgram.KNEAD, seconds = seconds)
            else -> null
        }
    }

    /** Texte brut à partir d'un éventuel HTML léger. */
    fun plain(html: String?): String? {
        if (html.isNullOrBlank()) return null
        val t = html
            .replace(Regex("(?i)<br\\s*/?>|</p>|</li>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&rsquo;", "’")
            .lines().joinToString("\n") { it.replace(Regex("[ \\t\u00a0]{2,}"), " ").trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return t.ifEmpty { null }
    }
}
