package app.toque.core

import app.toque.core.json.Json
import app.toque.core.recipes.OriginalDbMapper
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import app.toque.core.recipes.StepProgram
import app.toque.core.recipes.TextNorm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipesTest {
    /** Jeu de recettes de test (données d'essai uniquement, jamais livrées dans l'appli). */
    private fun builtin(): List<Recipe> {
        val text = javaClass.getResourceAsStream("/fixtures/recettes-test.json")!!.bufferedReader().use { it.readText() }
        val (list, bad) = RecipeCodec.decodeFile(text, RecipeSource.IMPORTED)
        assertEquals(0, bad)
        return list
    }

    @Test fun jsonRoundTrip() {
        val text = """{"a":[1,2.5,"é\n\"x\"",true,null,{"b":-3e2}],"u":"é"}"""
        val v = Json.parse(text)
        val once = Json.write(v)
        assertEquals(once, Json.write(Json.parse(once)))
        assertEquals(once, Json.write(Json.parse(Json.write(v, pretty = true))))
        assertEquals("é\n\"x\"", (v as Map<*, *>)["a"].let { (it as List<*>)[2] })
    }

    @Test fun fixtureRecipesAreValidAndSafe() {
        val list = builtin()
        assertTrue(list.size >= 12)
        assertEquals(list.size, list.map { it.id }.distinct().size)
        for (r in list) {
            assertTrue(r.title, r.steps.isNotEmpty() && r.ingredients.isNotEmpty())
            for (s in r.steps) s.machine?.let { m ->
                assertTrue("${r.title} : ${s.text} -> ${m.violations()}", m.valid)
            }
            val again = RecipeCodec.decode(RecipeCodec.encode(r), RecipeSource.IMPORTED)
            assertEquals(r, again)
        }
        assertTrue(list.any { r -> r.steps.any { it.machine?.program == StepProgram.KNEAD } })
        assertTrue(list.any { r -> r.steps.any { it.machine?.program == StepProgram.STEAM } })
        assertTrue(list.any { r -> r.steps.any { it.machine?.program == StepProgram.TOAST } })
    }

    @Test fun textNormalisation() {
        assertEquals("creme brulee", TextNorm.normalize("Crème  Brûlée !"))
        assertEquals(listOf("pomme", "terre"), TextNorm.tokens("Pommes de terre"))
        assertEquals(listOf("gateau"), TextNorm.tokens("gâteaux"))
        assertEquals(1, TextNorm.distance("bechamel", "bechamle", 2))
        assertEquals(1, TextNorm.distance("risoto", "risotto", 2))
    }

    @Test fun searchIsForgiving() {
        val idx = SearchIndex(builtin())
        fun top(q: String) = idx.search(q).firstOrNull()?.entry?.id
        assertEquals("bechamel", top("bechamel"))
        assertEquals("bechamel", top("BÉCHAMEL"))
        assertEquals("bechamel", top("bechamle"))
        assertEquals("risotto-champignons", top("risoto"))
        assertEquals("risotto-champignons", top("champignon"))
        assertEquals("compote-pommes", top("compote pomme"))
        assertEquals("pate-crepes", top("crepe"))
        assertEquals("veloute-potiron", top("potiro"))
        assertTrue(idx.search("lentile").any { it.entry.id == "soupe-lentilles-corail" })
        assertTrue(idx.search("vapeur").any { it.entry.id == "legumes-vapeur" })
        assertTrue(idx.search("").size == idx.entries.size)
        val soups = idx.search("", app.toque.core.recipes.RecipeFilter(category = "Soupes"))
        assertEquals(2, soups.size)
        val withWhat = idx.withIngredients(listOf("oignon", "huile d'olive"))
        assertTrue(withWhat.first().matched == 2)
    }

    @Test fun searchSpeed() {
        val base = builtin()
        val many = (0 until 80).flatMap { n -> base.map { it.copy(id = it.id + n, title = it.title + " $n") } }
        val idx = SearchIndex(many)
        val t0 = System.nanoTime()
        repeat(50) { idx.search("soupe potiro creme") }
        val ms = (System.nanoTime() - t0) / 1e6 / 50
        assertTrue("recherche trop lente : $ms ms", ms < 60)
    }

    @Test fun mapsAnOriginalStyleDatabase() {
        fun t(name: String, vararg rows: Map<String, Any?>) = name to OriginalDbMapper.Table(name, rows.toList())
        val tables = mapOf(
            t("RECIPE",
                mapOf("_id" to 1L, "ID" to "4711", "NAME" to "Soupe test", "LANGUAGE" to "fr", "DURATION_TOTAL" to 30L,
                    "YIELD" to 4L, "UPDATED" to 1700000000000L, "IMAGE_NAME" to "soupe.jpg", "REMOVE" to 0L),
                mapOf("_id" to 2L, "ID" to "4712", "NAME" to "Suppe", "LANGUAGE" to "de", "REMOVE" to 0L),
                mapOf("_id" to 3L, "ID" to "4713", "NAME" to "Supprimée", "LANGUAGE" to "fr", "REMOVE" to 1L)),
            t("STEP",
                mapOf("_id" to 10L, "RECIPE_ID" to 1L, "STEP" to 2L, "TEXT" to "Cuire", "MODE" to null, "MACHINE_VALUES_ID" to 100L),
                mapOf("_id" to 11L, "RECIPE_ID" to 1L, "STEP" to 1L, "TEXT" to "Hacher", "MODE" to null, "MACHINE_VALUES_ID" to 101L),
                mapOf("_id" to 12L, "RECIPE_ID" to 1L, "STEP" to 3L, "TEXT" to "Pétrir la pâte", "MODE" to "kneading", "MACHINE_VALUES_ID" to 102L)),
            t("MACHINE_VALUES",
                mapOf("_id" to 100L, "REVERSE" to 1L, "SPEED" to 1L, "TEMP" to 100L, "TIME" to 1200L, "WEIGHT" to 0L),
                mapOf("_id" to 101L, "REVERSE" to 0L, "SPEED" to 5L, "TEMP" to 0L, "TIME" to 5L, "WEIGHT" to 0L),
                mapOf("_id" to 102L, "REVERSE" to 0L, "SPEED" to 4L, "TEMP" to 0L, "TIME" to 90L, "WEIGHT" to 0L)),
            t("INGREDIENTS_BASE", mapOf("_id" to 50L, "RECIPE_ID" to 1L, "NAME" to "")),
            t("INGREDIENT",
                mapOf("_id" to 60L, "INGREDIENTS_BASE_ID" to 50L, "NAME" to "carottes", "AMOUNT" to "500", "UNIT" to "g")),
        )
        val res = OriginalDbMapper.map(tables)
        assertEquals(1, res.recipes.size)
        val r = res.recipes.single()
        assertEquals("mc-4711", r.id)
        assertEquals(listOf("Hacher", "Cuire", "Pétrir la pâte"), r.steps.map { it.text })
        val cook = r.steps[1].machine
        assertNotNull(cook)
        assertEquals(100, cook!!.tempC); assertTrue(cook.reverse); assertEquals(1200, cook.seconds)
        assertEquals(StepProgram.KNEAD, r.steps[2].machine!!.program)
        assertTrue(r.steps.all { it.machine == null || it.needsReview })
        assertEquals("500 g carottes", r.allIngredients.single().display)
        assertEquals(1700000000000L, res.maxUpdated)
    }
}
