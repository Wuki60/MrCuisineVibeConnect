package app.toque.core.recipes

import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.bool
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
    /** Préfixe des identifiants Toque des recettes en ligne. */
    const val ID_PREFIX = "mc-"

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

    class Entry(val apiId: Long, val updated: String, val name: String)

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
            Entry(id, r.str("lastUpdated").orEmpty(), r.str("name")?.trim().orEmpty())
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
        )
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
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return t.ifEmpty { null }
    }
}
