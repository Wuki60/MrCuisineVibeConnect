package app.toque.core.recipes

import app.toque.core.json.Json
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.machine.Accessory

/**
 * Conversion de la base « Recipes » de l'appli d'origine (greenDAO, schéma 9 : RECIPE, STEP,
 * MACHINE_VALUES, INGREDIENTS_BASE, INGREDIENT, TAG…) vers notre format.
 *
 * Le schéma exact des colonnes n'est pas documenté publiquement : la correspondance se fait par
 * noms de colonnes probables, de façon tolérante. Tout réglage machine importé est marqué
 * « à vérifier » et repasse de toute façon par les règles de sécurité avant exécution.
 */
object OriginalDbMapper {

    /** Une table lue telle quelle : liste de lignes colonne -> valeur. */
    class Table(val name: String, val rows: List<Map<String, Any?>>) {
        private val columns: Set<String> = rows.firstOrNull()?.keys?.map { it.uppercase() }?.toSet() ?: emptySet()
        fun has(col: String) = col.uppercase() in columns
        fun pick(vararg candidates: String): String? = candidates.firstOrNull { has(it) }
    }

    class Result(
        val recipes: List<Recipe>,
        val skipped: Int,
        val notes: List<String>,
        val maxUpdated: Long,
    )

    private fun Map<String, Any?>.get(col: String?): Any? {
        if (col == null) return null
        this[col]?.let { return it }
        for ((k, v) in this) if (k.equals(col, ignoreCase = true)) return v
        return null
    }

    private fun Any?.asLong(): Long? = when (this) {
        is Number -> toLong()
        is String -> trim().toLongOrNull() ?: trim().toDoubleOrNull()?.toLong()
        else -> null
    }

    private fun Any?.asInt(): Int? = asLong()?.toInt()
    private fun Any?.asText(): String? = when (this) {
        null -> null
        is String -> this
        is Number, is Boolean -> toString()
        is ByteArray -> null
        else -> toString()
    }
    private fun Any?.asBool(): Boolean = when (this) {
        is Boolean -> this
        is Number -> toInt() != 0
        is String -> equals("true", true) || this == "1"
        else -> false
    }

    /** Programme d'après le champ `mode` d'une étape (valeurs probables en plusieurs langues). */
    fun programForMode(mode: String?): StepProgram? {
        val m = mode?.lowercase()?.trim() ?: return null
        return when {
            m.isEmpty() -> null
            m.contains("knead") || m.contains("teig") || m.contains("petri") || m.contains("pétri") || m.contains("dough") -> StepProgram.KNEAD
            m.contains("steam") || m.contains("dampf") || m.contains("vapeur") || m.contains("varoma") -> StepProgram.STEAM
            m.contains("toast") || m.contains("roast") || m.contains("anbrat") || m.contains("riss") || m.contains("brown") -> StepProgram.TOAST
            m.contains("turbo") -> StepProgram.TURBO
            else -> null
        }
    }

    private fun isWeighMode(mode: String?): Boolean {
        val m = mode?.lowercase() ?: return false
        return m.contains("weigh") || m.contains("scale") || m.contains("waag") || m.contains("pes") || m.contains("wieg")
    }

    fun map(tables: Map<String, Table>, language: String = "fr", nowMs: Long = System.currentTimeMillis()): Result {
        fun table(vararg names: String): Table? =
            names.firstNotNullOfOrNull { n -> tables.entries.firstOrNull { it.key.equals(n, true) }?.value }

        val notes = ArrayList<String>()
        val recipeT = table("RECIPE") ?: return Result(emptyList(), 0, listOf("Table RECIPE absente"), 0)
        val stepT = table("STEP")
        val mvT = table("MACHINE_VALUES")
        val baseT = table("INGREDIENTS_BASE", "INGREDIENT_BASE")
        val ingT = table("INGREDIENT", "INGREDIENTS")
        val tagT = table("TAG", "TAGS")

        val rId = recipeT.pick("_id", "ID")
        val rServerId = recipeT.pick("ID", "RECIPE_ID", "SERVER_ID", "REMOTE_ID")
        val rName = recipeT.pick("NAME", "TITLE")
        val rLang = recipeT.pick("LANGUAGE", "LANG")
        val rRemove = recipeT.pick("REMOVE", "REMOVED", "DELETED")
        val rValidTo = recipeT.pick("VALID_TO")
        val rDuration = recipeT.pick("DURATION")
        val rTotal = recipeT.pick("DURATION_TOTAL", "TOTAL_DURATION")
        val rYield = recipeT.pick("YIELD", "SERVINGS")
        val rLevel = recipeT.pick("LEVEL", "COMPLEXITY", "DIFFICULTY")
        val rImage = recipeT.pick("IMAGE_NAME", "IMAGE")
        val rFav = recipeT.pick("FAVORITE", "IS_FAVORITE", "FAVOURITE")
        val rUpdated = recipeT.pick("UPDATED", "UPDATED_AT", "MODIFIED")
        val rInstructions = recipeT.pick("INSTRUCTIONS")
        val rIngredientsJson = recipeT.pick("INGREDIENTS_BASES", "INGREDIENTS")
        val rTags = recipeT.pick("TAGS", "CATEGORY", "CATEGORIES")
        if (rId == null || rName == null) return Result(emptyList(), recipeT.rows.size, listOf("Colonnes _id/NAME introuvables dans RECIPE"), 0)

        // MACHINE_VALUES par id
        val mvById = HashMap<Long, Map<String, Any?>>()
        if (mvT != null) {
            val c = mvT.pick("_id", "ID")
            if (c != null) for (row in mvT.rows) row.get(c).asLong()?.let { mvById[it] = row }
        }
        // Heuristiques d'unités sur l'ensemble des valeurs
        val tempCol = mvT?.pick("TEMP", "TEMPERATURE")
        val timeCol = mvT?.pick("TIME", "DURATION")
        val maxTemp = mvT?.rows?.maxOfOrNull { it.get(tempCol).asInt() ?: 0 } ?: 0
        val maxTime = mvT?.rows?.maxOfOrNull { it.get(timeCol).asLong() ?: 0 } ?: 0
        val tempIsLevel = maxTemp in 1..19
        val timeIsMs = maxTime > 100_000
        if (mvT != null) notes += "MACHINE_VALUES : température en " + (if (tempIsLevel) "niveaux" else "°C") +
            ", durée en " + (if (timeIsMs) "ms" else "s")

        // Étapes groupées par recette
        val stepsByRecipe = HashMap<Long, MutableList<Map<String, Any?>>>()
        val sRecipe = stepT?.pick("RECIPE_ID", "RECIPEID")
        if (stepT != null && sRecipe != null) for (row in stepT.rows) {
            val rid = row.get(sRecipe).asLong() ?: continue
            stepsByRecipe.getOrPut(rid) { ArrayList() } += row
        }
        val sOrder = stepT?.pick("STEP", "POSITION", "SORT", "ORDER_INDEX", "_id")
        val sText = stepT?.pick("TEXT", "DESCRIPTION", "INSTRUCTION")
        val sMode = stepT?.pick("MODE", "TYPE")
        val sMv = stepT?.pick("MACHINE_VALUES_ID", "MACHINEVALUES_ID")

        // Ingrédients
        val basesByRecipe = HashMap<Long, MutableList<Map<String, Any?>>>()
        val bRecipe = baseT?.pick("RECIPE_ID")
        if (baseT != null && bRecipe != null) for (row in baseT.rows) {
            val rid = row.get(bRecipe).asLong() ?: continue
            basesByRecipe.getOrPut(rid) { ArrayList() } += row
        }
        val bId = baseT?.pick("_id", "ID")
        val bTitle = baseT?.pick("NAME", "TITLE", "HEADLINE", "TEXT")
        val ingByBase = HashMap<Long, MutableList<Map<String, Any?>>>()
        val iBase = ingT?.pick("INGREDIENTS_BASE_ID", "INGREDIENT_BASE_ID", "BASE_ID")
        val iRecipe = ingT?.pick("RECIPE_ID")
        val ingByRecipe = HashMap<Long, MutableList<Map<String, Any?>>>()
        if (ingT != null) for (row in ingT.rows) {
            if (iBase != null) row.get(iBase).asLong()?.let { ingByBase.getOrPut(it) { ArrayList() } += row }
            else if (iRecipe != null) row.get(iRecipe).asLong()?.let { ingByRecipe.getOrPut(it) { ArrayList() } += row }
        }
        val iName = ingT?.pick("NAME", "TEXT", "TITLE", "INGREDIENT")
        val iQty = ingT?.pick("AMOUNT", "QUANTITY", "QTY", "VALUE")
        val iUnit = ingT?.pick("UNIT", "UNIT_NAME")
        val iNote = ingT?.pick("NOTE", "COMMENT", "DESCRIPTION", "ADDITIONAL")

        // Tags
        val tagsByRecipe = HashMap<Long, MutableList<String>>()
        val tRecipe = tagT?.pick("RECIPE_ID")
        val tName = tagT?.pick("NAME", "TITLE", "TAG")
        if (tagT != null && tRecipe != null && tName != null) for (row in tagT.rows) {
            val rid = row.get(tRecipe).asLong() ?: continue
            val n = row.get(tName).asText()?.trim() ?: continue
            if (n.isNotEmpty()) tagsByRecipe.getOrPut(rid) { ArrayList() } += n
        }

        val out = ArrayList<Recipe>()
        var skipped = 0
        var maxUpdated = 0L
        for (row in recipeT.rows) {
            val localId = row.get(rId).asLong()
            val title = row.get(rName).asText()?.trim()
            if (localId == null || title.isNullOrEmpty()) { skipped++; continue }
            if (rLang != null) {
                val l = row.get(rLang).asText()?.lowercase()?.trim().orEmpty()
                if (l.isNotEmpty() && !l.startsWith(language)) continue
            }
            if (rRemove != null && row.get(rRemove).asBool()) continue
            if (rValidTo != null) {
                val vt = row.get(rValidTo).asLong()
                if (vt != null && vt in 1 until nowMs) continue
            }
            val updated = rUpdated?.let { row.get(it).asLong() } ?: 0L
            if (updated > maxUpdated) maxUpdated = updated

            // Étapes
            val steps = ArrayList<Step>()
            val rawSteps = stepsByRecipe[localId].orEmpty().sortedBy { (if (sOrder != null) it.get(sOrder).asLong() else null) ?: 0L }
            for (s in rawSteps) {
                val text = (if (sText != null) s.get(sText).asText() else null)?.trim().orEmpty()
                val mode = if (sMode != null) s.get(sMode).asText() else null
                val mv = if (sMv != null) s.get(sMv).asLong()?.let { mvById[it] } else null
                var machine: StepMachine? = null
                var weigh: Weigh? = null
                if (mv != null) {
                    val speed = mv.get(mvT?.pick("SPEED", "LEVEL")).asInt() ?: 0
                    val rawTemp = mv.get(tempCol).asInt() ?: 0
                    val rawTime = mv.get(timeCol).asLong() ?: 0
                    val reverse = mv.get(mvT?.pick("REVERSE", "DIRECTION", "IS_REVERSE")).asBool()
                    val grams = mv.get(mvT?.pick("WEIGHT", "WEIGHT_G")).asInt() ?: 0
                    val seconds = (if (timeIsMs) rawTime / 1000 else rawTime).toInt().coerceAtLeast(0)
                    val tempC = if (tempIsLevel) app.toque.core.protocol.Mcc.celsiusForLevel(rawTemp) else rawTemp
                    val program = programForMode(mode)
                        ?: if (speed > 0 && seconds > 0) StepProgram.MANUAL else null
                    if (program != null) {
                        machine = StepMachine(
                            program = program,
                            seconds = seconds,
                            speed = speed.coerceIn(0, 10),
                            tempC = tempC.coerceAtLeast(0),
                            reverse = reverse,
                            accessory = if (text.lowercase().contains("fouet") && speed <= 4) Accessory.WHISK else Accessory.BLADE,
                            pulses = if (program == StepProgram.TURBO) maxOf(1, seconds) else 0,
                        )
                    }
                    if (grams > 0 || isWeighMode(mode)) weigh = Weigh(text.take(40), grams.coerceAtLeast(0))
                }
                if (text.isEmpty() && machine == null) continue
                steps += Step(text, machine, weigh?.takeIf { it.grams > 0 }, needsReview = machine != null)
            }

            // Ingrédients
            val groups = ArrayList<IngredientGroup>()
            fun ingredient(r: Map<String, Any?>): Ingredient? {
                val name = (if (iName != null) r.get(iName).asText() else null)?.trim().orEmpty()
                if (name.isEmpty()) return null
                return Ingredient(
                    qty = (if (iQty != null) r.get(iQty).asText() else null)?.trim()?.removeSuffix(".0").orEmpty(),
                    unit = (if (iUnit != null) r.get(iUnit).asText() else null)?.trim().orEmpty(),
                    name = name,
                    note = (if (iNote != null) r.get(iNote).asText() else null)?.trim().orEmpty(),
                )
            }
            for (b in basesByRecipe[localId].orEmpty()) {
                val id = if (bId != null) b.get(bId).asLong() else null
                val items = (if (id != null) ingByBase[id].orEmpty() else emptyList()).mapNotNull { ingredient(it) }
                if (items.isNotEmpty()) groups += IngredientGroup((if (bTitle != null) b.get(bTitle).asText() else null)?.trim().orEmpty(), items)
            }
            ingByRecipe[localId]?.mapNotNull { ingredient(it) }?.takeIf { it.isNotEmpty() }?.let { groups += IngredientGroup("", it) }
            if (groups.isEmpty() && rIngredientsJson != null) parseIngredientsJson(row.get(rIngredientsJson).asText())?.let { groups += it }

            // Étapes textuelles de secours (champ JSON « instructions »)
            if (steps.isEmpty() && rInstructions != null) {
                row.get(rInstructions).asText()?.let { txt -> textSteps(txt).forEach { steps += Step(it) } }
            }

            val tags = ArrayList(tagsByRecipe[localId].orEmpty())
            if (rTags != null) row.get(rTags).asText()?.let { t ->
                if (!t.trim().startsWith("[")) t.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { tags += it }
                else runCatching { Json.parse(t).list() }.getOrNull()?.forEach { e ->
                    (e as? String ?: e.obj().str("name"))?.let { tags += it }
                }
            }
            val serverId = if (rServerId != null && rServerId != rId) row.get(rServerId).asText() else null
            out += Recipe(
                id = "mc-" + (serverId?.takeIf { it.isNotBlank() } ?: localId.toString()),
                title = title,
                category = tags.firstOrNull().orEmpty(),
                tags = tags.distinct(),
                servings = (if (rYield != null) row.get(rYield).asInt() else null) ?: 0,
                prepMinutes = minutes(if (rDuration != null) row.get(rDuration) else null),
                totalMinutes = minutes(if (rTotal != null) row.get(rTotal) else null),
                difficulty = ((if (rLevel != null) row.get(rLevel).asInt() else null) ?: 1).coerceIn(1, 3),
                ingredients = groups,
                steps = steps,
                source = RecipeSource.IMPORTED,
                image = (if (rImage != null) row.get(rImage).asText() else null)?.trim().orEmpty(),
                favorite = rFav != null && row.get(rFav).asBool(),
            )
        }
        return Result(out, skipped, notes, maxUpdated)
    }

    /** Durées stockées en minutes, secondes ou texte « 45 min » selon les versions. */
    private fun minutes(v: Any?): Int {
        val n = when (v) {
            is Number -> v.toLong()
            is String -> Regex("\\d+").find(v)?.value?.toLongOrNull() ?: 0L
            else -> 0L
        }
        return (if (n > 600) n / 60 else n).toInt().coerceIn(0, 24 * 60)
    }

    private fun parseIngredientsJson(text: String?): List<IngredientGroup>? {
        if (text.isNullOrBlank() || !text.trim().startsWith("[")) return null
        return try {
            Json.parse(text).list().mapNotNull { gAny ->
                val g = gAny.obj()
                val items = (g["ingredients"] ?: g["items"]).list().mapNotNull { iAny ->
                    val i = iAny.obj()
                    val name = (i.str("name") ?: i.str("text") ?: i.str("title"))?.trim().orEmpty()
                    if (name.isEmpty()) null else Ingredient(
                        (i.str("amount") ?: i.str("quantity")).orEmpty().removeSuffix(".0"),
                        i.str("unit").orEmpty(), name, (i.str("note") ?: i.str("comment")).orEmpty(),
                    )
                }
                if (items.isEmpty()) null else IngredientGroup((g.str("name") ?: g.str("title")).orEmpty(), items)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun textSteps(text: String): List<String> {
        val t = text.trim()
        if (t.startsWith("[")) return try {
            Json.parse(t).list().mapNotNull { e -> (e as? String ?: e.obj().str("text"))?.trim()?.takeIf { it.isNotEmpty() } }
        } catch (_: Exception) {
            emptyList()
        }
        return t.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
