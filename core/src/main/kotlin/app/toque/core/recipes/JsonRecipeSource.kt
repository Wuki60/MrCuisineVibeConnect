package app.toque.core.recipes

import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.obj
import java.net.URI
import java.security.MessageDigest

/** A configured public JSON catalogue; identity is independent of its editable name and URL. */
data class JsonRecipeSource(val id: String, val name: String, val url: String, val autoSync: Boolean = true) {
    init {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(id))
        require(name.isNotBlank() && name.length <= 80) { tr("Donnez un nom à cette source (80 caractères maximum).") }
        require(isHttpsUrl(url)) { tr("Utilisez une adresse HTTPS sans identifiant ni mot de passe.") }
    }

    val origin: String get() = "json:$id"

    fun recipeId(remoteId: String): String = "$origin:" + MessageDigest.getInstance("SHA-256")
        .digest(remoteId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun owns(recipeId: String): Boolean = recipeId.startsWith("$origin:")

    companion object {
        fun isHttpsUrl(url: String): Boolean = try {
            val uri = URI(url)
            url.length <= 2048 && uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.fragment == null
        } catch (_: Exception) { false }

        fun encode(sources: List<JsonRecipeSource>): String = Json.write(sources.map {
            mapOf("id" to it.id, "name" to it.name, "url" to it.url, "autoSync" to it.autoSync)
        })

        fun decode(text: String): List<JsonRecipeSource> {
            val entries = Json.parse(text) as? List<*> ?: throw IllegalArgumentException()
            require(entries.size <= 20)
            val sources = entries.map { entry ->
                val m = entry.obj()
                JsonRecipeSource(m["id"] as? String ?: "", m["name"] as? String ?: "",
                    m["url"] as? String ?: "", m["autoSync"] as? Boolean ?: true)
            }
            require(sources.map { it.id }.distinct().size == sources.size)
            return sources
        }
    }
}

/** Strict, all-or-nothing reader of the existing toque-recettes-1 format. */
object JsonRecipeCatalog {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_RECIPES = 500

    private fun check(valid: Boolean) {
        require(valid) { tr("Catalogue JSON invalide : aucune recette n’a été modifiée.") }
    }

    private fun integer(value: Any?, range: IntRange): Boolean {
        val n = value as? Number ?: return false
        val d = n.toDouble()
        return d.isFinite() && d == n.toInt().toDouble() && n.toInt() in range
    }

    /** Bound recursion before handing untrusted text to the small recursive JSON decoder. */
    private fun checkNesting(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; check(depth <= 64) }
                '}', ']' -> { depth--; check(depth >= 0) }
            }
        }
        check(depth == 0 && !quoted)
    }

    fun parse(text: String, source: JsonRecipeSource): List<Recipe> {
        check(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        checkNesting(text)
        val root = Json.parse(text).obj()
        check(root["format"] == RecipeCodec.FORMAT)
        val entries = root["recipes"] as? List<*>
        check(entries != null && entries.size <= MAX_RECIPES)
        val ids = HashSet<String>()
        return entries!!.map { entry ->
            check(entry is Map<*, *>)
            val m = entry.obj()
            val remoteId = (m["id"] as? String)?.trim().orEmpty()
            check(remoteId.isNotEmpty() && remoteId.length <= 256 && ids.add(remoteId))
            val steps = m["steps"] as? List<*>
            check(steps != null && steps.isNotEmpty() && steps.size <= 500)
            for (step in steps!!) {
                val s = step.obj()
                check((s["text"] as? String)?.isNotBlank() == true)
                s["weigh"]?.let { check(it is Map<*, *> && integer(it.obj()["grams"], 1..5000)) }
                s["machine"]?.let { machine ->
                    check(machine is Map<*, *>)
                    val mc = machine.obj()
                    check(StepProgram.of(mc["program"] as? String) != null)
                    check(integer(mc["seconds"], 1..5940) && integer(mc["speed"], 0..10) && integer(mc["tempC"], 0..130))
                    check(mc["reverse"] == null || mc["reverse"] is Boolean)
                    check(mc["accessory"] == null || mc["accessory"] in listOf("blade", "whisk"))
                }
            }
            val recipe = RecipeCodec.fromMap(m, RecipeSource.USER)
            check(recipe != null && recipe.steps.size == steps.size && recipe.steps.all { it.machine?.valid != false })
            recipe!!.copy(id = source.recipeId(remoteId), source = RecipeSource.USER, favorite = false,
                steps = recipe.steps.map { it.copy(needsReview = it.machine != null || it.needsReview) })
        }
    }
}
