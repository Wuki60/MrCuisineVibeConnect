package app.toque.core

import app.toque.core.json.Json
import app.toque.core.recipes.*
import org.junit.Assert.*
import org.junit.Test

class JsonRecipeSourceTest {
    private val source = JsonRecipeSource("source-a", "My recipes", "https://example.org/catalog.json")
    private val recipe = """{"id":"web-123","title":"Test","source":"imported","steps":[{"text":"Check before starting","machine":{"program":"manuel","seconds":30,"speed":5,"tempC":0,"reverse":false,"accessory":"blade"}}]}"""
    private fun catalog(vararg entries: String) = """{"format":"toque-recettes-1","recipes":[${entries.joinToString(",")}]}"""
    private fun refused(text: String) {
        try { JsonRecipeCatalog.parse(text, source); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun sameRemoteIdIsIsolatedBySourceAndStableAcrossEdits() {
        val r = JsonRecipeCatalog.parse(catalog(recipe), source).single()
        assertNotEquals("web-123", r.id)
        assertNotEquals(r.id, JsonRecipeCatalog.parse(catalog(recipe), source.copy(id = "source-b")).single().id)
        assertEquals(r.id, JsonRecipeCatalog.parse(catalog(recipe), source.copy(name = "Renamed", url = "https://example.com/new.json")).single().id)
        assertTrue(source.owns(r.id))
        assertEquals(RecipeSource.USER, r.source)
        assertTrue(r.steps.single().needsReview)
    }
    @Test fun configuredSourcesRoundTripWithoutNetworkOrCredentials() {
        assertEquals(listOf(source), JsonRecipeSource.decode(JsonRecipeSource.encode(listOf(source))))
        for (url in listOf("http://example.org/a", "https://user:password@example.org/a", "file:///tmp/a", "https://example.org/a#part")) {
            assertFalse(JsonRecipeSource.isHttpsUrl(url))
        }
        assertTrue(JsonRecipeSource.isHttpsUrl("https://api.github.com/repos/owner/repo/contents/catalog.json?ref=main"))
    }
    @Test fun invalidEntryRejectsEntireCatalogue() {
        refused(catalog(recipe, "{}"))
        refused(catalog(recipe, recipe))
        refused("{}")
        refused("""{"format":"other","recipes":[]}""")
        assertTrue(JsonRecipeCatalog.parse(catalog(), source).isEmpty())
    }
    @Test fun unsafeAndSilentlyIgnoredMachineValuesAreRefused() {
        refused(catalog(recipe.replace("\"speed\":5", "\"speed\":11")))
        refused(catalog(recipe.replace("\"tempC\":0", "\"tempC\":80")))
        refused(catalog(recipe.replace("\"seconds\":30", "\"seconds\":-1")))
        refused(catalog(recipe.replace("\"seconds\":30", "\"seconds\":1.5")))
        refused(catalog(recipe.replace("\"program\":\"manuel\"", "\"program\":\"unknown\"")))
    }
    @Test fun exampleCatalogueUsesTheExistingFormat() {
        val text = javaClass.getResourceAsStream("/fixtures/json-source-example.json")!!.bufferedReader().use { it.readText() }
        val r = JsonRecipeCatalog.parse(text, source).single()
        assertTrue(r.steps.single().machine!!.valid)
        assertTrue(r.steps.single().needsReview)
        assertEquals(0, r.steps.single().machine!!.tempC)
    }
    @Test fun boundsAreCheckedBeforeReplacingAnyStoredRecipes() {
        val entries = (0..JsonRecipeCatalog.MAX_RECIPES).map { recipe.replace("web-123", "r-$it") }
        refused(catalog(*entries.toTypedArray()))
        refused(catalog(recipe.replace("\"id\":\"web-123\"", "\"id\":\"\"")))
        refused("[".repeat(1000) + "0" + "]".repeat(1000))
        // JSON-looking punctuation inside a quoted instruction is not structural nesting.
        val punctuated = recipe.replace("Check before starting", "Read [brackets] and {notes}")
        assertEquals(1, JsonRecipeCatalog.parse(catalog(punctuated), source).size)
    }
}
