package app.toque.core

import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.UserRecipes
import app.toque.core.site.SiteSync
import app.toque.core.site.ToqueSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SiteTest {
    /** Faux site : répond selon l'action (« a=… »), garde les requêtes. */
    private class FakeSite : ToqueSite.Transport {
        class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String?)
        val calls = ArrayList<Call>()
        val replies = HashMap<String, (Call) -> ToqueSite.Reply>()
        var down = false

        override fun send(method: String, url: String, headers: Map<String, String>, body: String?): ToqueSite.Reply {
            if (down) throw IOException("hors ligne")
            val c = Call(method, url, headers, body)
            calls += c
            val action = url.substringAfter("a=").substringBefore('&')
            return replies[action]?.invoke(c) ?: ToqueSite.Reply(404, """{"error":"unknown","message":"Action inconnue."}""")
        }
    }

    private fun recipeJson(n: Int, title: String, updated: String, translated: String? = null) =
        """{"id":"toqueapp-$n","title":"$title","source":"user","favorite":true,"servings":4,
           "ingredients":[{"group":"","items":[{"qty":"200","unit":"g","name":"farine","note":""}]}],
           "steps":[{"text":"Mélanger","machine":{"program":"manuel","seconds":30,"speed":4,"tempC":0}}],
           "author":"marie","rating":4.5,"ratingCount":2,
           "site":{"id":$n,"updatedAt":"$updated"${translated?.let { ""","translation":{"language":"en","updatedAt":"$it"}""" } ?: ""}}}"""

    private fun page(next: Int?, vararg recipes: String) =
        ToqueSite.Reply(200, """{"format":"toque-recettes-1","recipes":[${recipes.joinToString(",")}],"next_page":${next ?: "null"}}""")

    @Test fun pairSendsTheCodeAndKeepsTheToken() {
        val t = FakeSite()
        t.replies["pair"] = { ToqueSite.Reply(200, """{"token":"abc123","username":"maxime"}""") }
        val r = ToqueSite(t).pair(" k7p2qa ", "Monsieur Cuisine Connect", "mc-connect")
        assertTrue(r is ToqueSite.Link.Ok)
        assertEquals("abc123", (r as ToqueSite.Link.Ok).token)
        assertEquals("maxime", r.username)
        val c = t.calls.single()
        assertEquals("POST", c.method)
        assertEquals("https://toqueapp.org/api.php?a=pair", c.url)
        val body = Json.parse(c.body!!).obj()
        assertEquals("k7p2qa", body["code"])
        assertEquals("mc-connect", body["model"])
        assertNull(c.headers["Authorization"])
    }

    @Test fun loginAsksForTheTwoFactorCode() {
        val t = FakeSite()
        t.replies["login"] = { c ->
            val otp = Json.parse(c.body!!).obj()["otp"]
            if (otp == null) ToqueSite.Reply(401, """{"error":"otp_required","message":"Tapez le code."}""")
            else ToqueSite.Reply(200, """{"token":"tok","username":"maxime"}""")
        }
        val site = ToqueSite(t)
        val first = site.login("m@x.fr", "secret", "", "Robot", "mc-smart")
        assertTrue(first is ToqueSite.Link.OtpRequired)
        val second = site.login("m@x.fr", "secret", "123456", "Robot", "mc-smart")
        assertTrue(second is ToqueSite.Link.Ok)
        val bad = FakeSite().also { it.replies["login"] = { ToqueSite.Reply(401, """{"error":"invalid","message":"E-mail ou mot de passe incorrect."}""") } }
        val r = ToqueSite(bad).login("m@x.fr", "faux", "", "Robot", "mc-smart")
        assertEquals("E-mail ou mot de passe incorrect.", (r as ToqueSite.Link.Failed).message)
    }

    @Test fun robotCallsCarryTheTokenTwice() {
        val t = FakeSite()
        t.replies["favorite"] = { ToqueSite.Reply(200, """{"ok":true,"favorite":true}""") }
        ToqueSite(t).setFavorite("tok", "toqueapp-12", true)
        val c = t.calls.single()
        assertEquals("Bearer tok", c.headers["Authorization"])
        assertEquals("tok", c.headers["X-Toque-Token"])
        assertEquals(true, Json.parse(c.body!!).obj()["favorite"])
    }

    @Test fun siteMessagesAskedInTheAppLanguage() {
        val t = FakeSite()
        t.replies["favorite"] = { ToqueSite.Reply(200, """{"ok":true,"favorite":true}""") }
        ToqueSite(t).setFavorite("tok", "toqueapp-12", true)
        assertEquals(app.toque.core.i18n.I18n.code, t.calls.single().headers["Accept-Language"])
    }

    @Test fun communityRecipesAreAlwaysCommunityAndNeverFavorite() {
        val t = FakeSite()
        t.replies["community"] = {
            page(2, recipeJson(12, "Pain", "2026-11-20T10:00:00Z", translated = "2026-11-22T08:00:00Z"),
                """{"id":"perso-evil","title":"Pas du site"}""")
        }
        val p = ToqueSite(t).community("2026-11-01T00:00:00Z", 1, "en")
        val r = p.recipes.single()
        assertEquals("toqueapp-12", r.id)
        assertEquals(RecipeSource.COMMUNITY, r.source)
        assertFalse(r.favorite)
        assertEquals("marie", r.author)
        assertEquals(2, p.nextPage)
        assertEquals("2026-11-22T08:00:00Z", p.newestStamp)
        assertTrue(t.calls.single().url.contains("since=2026-11-01T00%3A00%3A00Z"))
        assertTrue(t.calls.single().url.contains("lang=en"))
    }

    @Test fun savingAPersonalRecipeSendsOnlyTheRecipe() {
        val t = FakeSite()
        t.replies["recipe"] = { ToqueSite.Reply(201, """{"id":31,"recipe":{}}""") }
        val mine = UserRecipes.blank().copy(title = "Ma tarte", favorite = true, author = "moi")
        assertEquals(31L, ToqueSite(t).saveRecipe("tok", mine, public = true, siteId = null))
        val body = Json.parse(t.calls.single().body!!).obj()
        assertEquals(true, body["public"])
        assertNull(body["id"])
        val recipe = body["recipe"].obj()
        assertEquals("Ma tarte", recipe["title"])
        for (k in listOf("id", "source", "favorite", "author")) assertFalse(k, recipe.containsKey(k))
    }

    @Test fun refusalsBecomeSiteExceptions() {
        val t = FakeSite()
        t.replies["me"] = { ToqueSite.Reply(401, """{"error":"unauthorized","message":"Robot non relié."}""") }
        try {
            ToqueSite(t).me("tok")
            throw AssertionError("exception attendue")
        } catch (e: ToqueSite.SiteException) {
            assertEquals(401, e.status)
            assertEquals("unauthorized", e.code)
        }
    }

    @Test fun languageOfTheInterface() {
        assertEquals("fr", ToqueSite.langFor("fr"))
        assertEquals("en", ToqueSite.langFor("en-US"))
        assertEquals("uk", ToqueSite.langFor("uk"))
        assertEquals("", ToqueSite.langFor("ja"))
    }

    // ------------------------------------------------------------------ synchro

    private class MemoryStore : SiteSync.Store {
        override var token = ""
        override var since = ""
        override var lang = ""
        override var fullMs = 0L
        override var pendingFavorites: Map<String, Boolean> = emptyMap()
        override var ownSiteIds: Set<Long> = emptySet()
        val recipes = LinkedHashMap<String, Recipe>()
        val favorites = HashSet<String>()
        var unlinkedCalls = 0
        override fun communityIds() = recipes.keys.filter { it.startsWith("toqueapp-") }.toSet()
        override fun communityFavorites() = favorites.filter { it.startsWith("toqueapp-") }.toSet()
        override fun save(recipes: List<Recipe>) { for (r in recipes) this.recipes[r.id] = r }
        override fun setFavorites(changes: Map<String, Boolean>) {
            for ((id, on) in changes) if (on) favorites += id else favorites -= id
        }
        override fun removeCommunityExcept(keep: Set<String>) {
            recipes.keys.removeAll { it.startsWith("toqueapp-") && it !in keep && it !in favorites }
        }
        override fun unlinked() { token = ""; unlinkedCalls++ }
    }

    @Test fun withoutAccountTheCommunityComesPageByPage() {
        val t = FakeSite()
        t.replies["community"] = { c ->
            if (c.url.contains("page=1")) page(2, recipeJson(1, "Pain", "2026-11-20T10:00:00Z"), recipeJson(2, "Soupe", "2026-11-21T10:00:00Z"))
            else page(null, recipeJson(3, "Tarte", "2026-11-19T10:00:00Z"))
        }
        val store = MemoryStore()
        store.recipes["perso-1"] = UserRecipes.blank().copy(id = "perso-1", title = "À moi")
        store.recipes["toqueapp-9"] = Recipe("toqueapp-9", "Retirée du site", source = RecipeSource.COMMUNITY)
        store.recipes["toqueapp-8"] = Recipe("toqueapp-8", "Retirée mais favorite", source = RecipeSource.COMMUNITY)
        store.favorites += "toqueapp-8"
        val r = SiteSync(ToqueSite(t), store, now = { 1_000_000L }).run(community = true, lang = "fr")
        assertTrue(r.message, r.ok)
        assertEquals(3, r.added)
        assertEquals(1, r.removed)
        assertEquals(setOf("perso-1", "toqueapp-1", "toqueapp-2", "toqueapp-3", "toqueapp-8"), store.recipes.keys)
        assertEquals("2026-11-21T10:00:00Z", store.since)
        assertEquals(1_000_000L, store.fullMs)
        // Aucun appel avec une clé : pas de compte.
        assertTrue(t.calls.none { it.headers.containsKey("Authorization") })
    }

    @Test fun nextTimeOnlyWhatChangedAndNothingRemoved() {
        val t = FakeSite()
        t.replies["community"] = { page(null, recipeJson(5, "Nouvelle", "2026-11-23T09:00:00Z")) }
        val store = MemoryStore().apply {
            since = "2026-11-21T10:00:00Z"; lang = "fr"; fullMs = 1_000L
            recipes["toqueapp-1"] = Recipe("toqueapp-1", "Pain", source = RecipeSource.COMMUNITY)
        }
        val r = SiteSync(ToqueSite(t), store, now = { 2_000L }).run(community = true, lang = "fr")
        assertEquals(1, r.added)
        assertEquals(0, r.removed)
        assertTrue(t.calls.single().url.contains("since=2026-11-21T10%3A00%3A00Z"))
        assertTrue(store.recipes.containsKey("toqueapp-1"))
        assertEquals("2026-11-23T09:00:00Z", store.since)
        assertEquals(1_000L, store.fullMs)
    }

    @Test fun anotherLanguageAsksForEverythingAgain() {
        val t = FakeSite()
        t.replies["community"] = { page(null) }
        val store = MemoryStore().apply { since = "2026-11-21T10:00:00Z"; lang = "fr"; fullMs = 1_000L }
        SiteSync(ToqueSite(t), store, now = { 2_000L }).run(community = true, lang = "de")
        assertFalse(t.calls.single().url.contains("since="))
        assertEquals("de", store.lang)
    }

    @Test fun ownRecipesPutOnTheSiteAreNotTakenTwice() {
        val t = FakeSite()
        t.replies["community"] = { page(null, recipeJson(7, "Ma tarte", "2026-11-23T09:00:00Z"), recipeJson(8, "Autre", "2026-11-23T09:00:00Z")) }
        val store = MemoryStore().apply { ownSiteIds = setOf(7L) }
        SiteSync(ToqueSite(t), store).run(community = true, lang = "fr")
        assertEquals(setOf("toqueapp-8"), store.recipes.keys)
    }

    @Test fun accountFavoritesArePushedThenPulled() {
        val t = FakeSite()
        val siteFavorites = linkedSetOf(1)
        t.replies["favorite"] = { c ->
            val b = Json.parse(c.body!!).obj()
            val n = (b["id"] as String).removePrefix("toqueapp-").toInt()
            if (n == 99) ToqueSite.Reply(404, """{"error":"not_found","message":"Recette introuvable."}""")
            else {
                if (b["favorite"] == true) siteFavorites += n else siteFavorites -= n
                ToqueSite.Reply(200, """{"ok":true}""")
            }
        }
        t.replies["favorites"] = { page(null, *siteFavorites.map { recipeJson(it, "R$it", "2026-11-20T10:00:00Z") }.toTypedArray()) }
        val store = MemoryStore().apply {
            token = "tok"
            pendingFavorites = linkedMapOf("toqueapp-4" to true, "toqueapp-99" to true)
            recipes["toqueapp-4"] = Recipe("toqueapp-4", "R4", source = RecipeSource.COMMUNITY)
            recipes["toqueapp-6"] = Recipe("toqueapp-6", "R6", source = RecipeSource.COMMUNITY)
            favorites += setOf("toqueapp-4", "toqueapp-6", "lidl-1")
        }
        val r = SiteSync(ToqueSite(t), store).run(community = false, lang = "fr")
        assertTrue(r.message, r.ok)
        assertTrue(store.pendingFavorites.isEmpty())
        assertEquals(setOf(1, 4), siteFavorites)
        // toqueapp-6 n'est plus favori sur le compte : retiré ici ; les favoris Lidl ne bougent pas.
        assertEquals(setOf("toqueapp-1", "toqueapp-4", "lidl-1"), store.favorites)
        assertTrue(store.recipes.containsKey("toqueapp-1"))
        assertTrue(t.calls.filter { it.url.contains("a=favorites") }.single().url.contains("lang=fr"))
    }

    @Test fun offlineFavoritesWaitAndAreNotOverwritten() {
        val t = FakeSite().apply { down = true }
        val store = MemoryStore().apply {
            token = "tok"
            pendingFavorites = mapOf("toqueapp-4" to true)
            favorites += "toqueapp-4"
        }
        val r = SiteSync(ToqueSite(t), store).run(community = true, lang = "fr")
        assertFalse(r.ok)
        assertEquals(mapOf("toqueapp-4" to true), store.pendingFavorites)
        assertEquals(setOf("toqueapp-4"), store.favorites)
        assertEquals("", store.since)
    }

    @Test fun aRobotUnlinkedOnTheSiteForgetsTheAccount() {
        val t = FakeSite()
        t.replies["favorites"] = { ToqueSite.Reply(401, """{"error":"unauthorized","message":"Robot non relié."}""") }
        t.replies["community"] = { page(null) }
        val store = MemoryStore().apply { token = "tok" }
        val r = SiteSync(ToqueSite(t), store).run(community = true, lang = "fr")
        assertEquals(1, store.unlinkedCalls)
        assertEquals("", store.token)
        assertFalse(r.ok)
        // La communauté continue sans compte.
        assertTrue(t.calls.any { it.url.contains("a=community") })
    }
}
