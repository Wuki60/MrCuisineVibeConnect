package app.toque.core.site

import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.int
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.recipes.CommunityRecipes
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import java.io.IOException
import java.net.URLEncoder

/**
 * Client de l'API de toqueapp.org (www/api.php du site). Le transport HTTP est fourni : celui du
 * robot dans l'appli, un faux dans les tests. Le jeton du robot n'est jamais écrit dans les messages.
 */
class ToqueSite(private val transport: Transport, val baseUrl: String = DEFAULT_URL) {

    fun interface Transport {
        /** Requête ; lève IOException si le site ne répond pas. */
        fun send(method: String, url: String, headers: Map<String, String>, body: String?): Reply
    }

    class Reply(val code: Int, val body: String)

    /** Refus du site : [code] est celui de l'API (« unauthorized », « limited »…), [status] le code HTTP. */
    class SiteException(val status: Int, val code: String, message: String) : IOException(message)

    sealed class Link {
        class Ok(val token: String, val username: String) : Link()
        /** Compte à double authentification : redemander avec le code de l'appli d'authentification. */
        class OtpRequired(val message: String) : Link()
        class Failed(val code: String, val message: String) : Link()
    }

    class Me(val username: String, val device: String, val favorites: Int)

    /** Une page de recettes de la communauté ; [nextPage] null à la dernière. */
    class Page(val recipes: List<Recipe>, val nextPage: Int?, val newestStamp: String)

    /** Relie ce robot avec le code à 6 caractères affiché dans Profil › Mes robots. */
    fun pair(code: String, device: String, model: String): Link =
        link("pair", linkedMapOf("code" to code.trim(), "device" to device, "model" to model))

    /** Relie ce robot avec l'e-mail et le mot de passe du compte (le mot de passe n'est pas gardé). */
    fun login(email: String, password: String, otp: String, device: String, model: String): Link {
        val body = linkedMapOf<String, Any?>("email" to email.trim(), "password" to password, "device" to device, "model" to model)
        if (otp.isNotBlank()) body["otp"] = otp.trim()
        return link("login", body)
    }

    private fun link(action: String, body: Map<String, Any?>): Link {
        val r = call("POST", action, null, body, emptyMap(), throwOnError = false)
        val m = parse(r.body)
        val token = m.str("token")
        if (r.code in 200..299 && !token.isNullOrBlank()) return Link.Ok(token, m.str("username").orEmpty())
        val code = m.str("error") ?: "http_${r.code}"
        val message = m.str("message") ?: tr("Réponse %d du site", r.code)
        return if (code == "otp_required") Link.OtpRequired(message) else Link.Failed(code, message)
    }

    /** Délie ce robot sur le site (sa clé n'y marche plus). */
    fun logout(token: String) {
        call("POST", "logout", token, emptyMap<String, Any?>())
    }

    fun me(token: String): Me {
        val m = parse(call("GET", "me", token).body)
        return Me(m.str("username").orEmpty(), m.str("device").orEmpty(), m.int("favorites") ?: 0)
    }

    /** Les favoris du compte, dans la langue [lang] quand la communauté les a traduits. */
    fun favorites(token: String, lang: String): List<Recipe> =
        recipes(parse(call("GET", "favorites", token, query = mapOf("lang" to lang)).body))

    fun setFavorite(token: String, recipeId: String, favorite: Boolean) {
        call("POST", "favorite", token, linkedMapOf("id" to recipeId, "favorite" to favorite))
    }

    /**
     * Publie ou met à jour une recette perso sur le site. [siteId] : son numéro sur le site si elle y
     * est déjà. Rend le numéro du site. [public] faux : gardée privée (visible seulement par son auteur).
     */
    fun saveRecipe(token: String, recipe: Recipe, public: Boolean, siteId: Long?): Long {
        val data = RecipeCodec.toMap(recipe).toMutableMap()
        for (k in listOf("id", "source", "favorite", "author", "rating", "ratingCount", "image")) data.remove(k)
        val body = linkedMapOf<String, Any?>("recipe" to data, "public" to public)
        if (siteId != null) body["id"] = siteId
        val m = parse(call("POST", "recipe", token, body).body)
        return (m.int("id") ?: throw IOException(tr("Réponse inattendue du site"))).toLong()
    }

    fun deleteRecipe(token: String, siteId: Long) {
        call("POST", "delete-recipe", token, linkedMapOf("id" to siteId))
    }

    /** Recettes publiques ; [since] (ISO) : seulement celles changées depuis ; [lang] : traductions voulues. */
    fun community(since: String, page: Int, lang: String): Page {
        val q = linkedMapOf("page" to page.toString())
        if (since.isNotBlank()) q["since"] = since
        if (lang.isNotBlank()) q["lang"] = lang
        val m = parse(call("GET", "community", null, query = q).body)
        val list = recipes(m)
        val stamps = m["recipes"].list().flatMap { r ->
            val site = r.obj()["site"].obj()
            listOfNotNull(site.str("updatedAt"), site["translation"].obj().str("updatedAt"))
        }
        return Page(list, m.int("next_page"), stamps.maxOrNull().orEmpty())
    }

    private fun recipes(m: Map<String, Any?>): List<Recipe> = m["recipes"].list().mapNotNull { any ->
        val r = any.obj()
        val id = r.str("id").orEmpty()
        if (!CommunityRecipes.isCommunity(id) || CommunityRecipes.siteId(id) == null) return@mapNotNull null
        // Toujours une recette de la communauté, quoi que dise la réponse (jamais une recette perso).
        RecipeCodec.fromMap(r, RecipeSource.COMMUNITY)?.copy(source = RecipeSource.COMMUNITY, favorite = false)
    }

    private fun call(
        method: String, action: String, token: String?, body: Map<String, Any?>? = null,
        query: Map<String, String> = emptyMap(), throwOnError: Boolean = true,
    ): Reply {
        val url = StringBuilder(baseUrl).append("/api.php?a=").append(action)
        for ((k, v) in query) url.append('&').append(k).append('=').append(URLEncoder.encode(v, "UTF-8"))
        // Messages du site dans la langue de l'appli (fr, en, de, es, ca, uk ; sinon français).
        val headers = linkedMapOf("Accept" to "application/json", "Accept-Language" to I18n.code)
        if (token != null) {
            headers["Authorization"] = "Bearer $token"
            // Certains hébergeurs retirent l'en-tête Authorization : le site lit aussi celui-ci.
            headers["X-Toque-Token"] = token
        }
        val text = body?.let { Json.write(it) }
        if (text != null) headers["Content-Type"] = "application/json; charset=utf-8"
        val r = transport.send(method, url.toString(), headers, text)
        if (throwOnError && r.code !in 200..299) {
            val m = parse(r.body)
            throw SiteException(r.code, m.str("error") ?: "http_${r.code}", m.str("message") ?: tr("Réponse %d du site", r.code))
        }
        return r
    }

    private fun parse(text: String): Map<String, Any?> = try {
        Json.parse(text).obj()
    } catch (_: Exception) {
        emptyMap()
    }

    companion object {
        const val DEFAULT_URL = "https://toqueapp.org"
        /** Langues des recettes du site (traductions). */
        val LANGUAGES = setOf("fr", "en", "de", "es", "ca", "uk", "it", "nl", "pl", "pt")

        /** Langue à demander au site pour l'interface [uiCode] (« fr », « en-US »…), ou "" si le site ne l'a pas. */
        fun langFor(uiCode: String): String = uiCode.substringBefore('-').lowercase().takeIf { it in LANGUAGES }.orEmpty()
    }
}
