package app.toque.core

import app.toque.core.recipes.McOnline
import app.toque.core.recipes.StepProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversion des recettes de l'API monsieur-cuisine.com (données d'essai inventées, même structure). */
class McOnlineTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().use { it.readText() }

    @Test fun page() {
        val p = McOnline.parsePage(fixture("mc-online-page.json"))
        assertEquals(3, p.total)
        assertEquals(1, p.totalPages)
        assertEquals(listOf(900001L, 900002L, 900003L), p.entries.map { it.apiId })
        assertEquals("2026-04-19 09:00:02", p.entries[0].updated)
        assertEquals("", p.entries[2].updated)
    }

    @Test fun recipe() {
        val r = McOnline.parseRecipe(fixture("mc-online-recipe.json"))!!
        assertEquals("mc-900001", r.id)
        assertEquals("Soupe d'essai", r.title)
        assertEquals("Soupes", r.category)
        assertEquals(listOf("Végétarien"), r.tags)
        assertEquals(4, r.servings)
        assertEquals(15, r.prepMinutes)
        assertEquals(40, r.totalMinutes)
        assertEquals(2, r.difficulty)
        assertEquals("https://cdn.example.test/uploads/2021/11/123essai.jpg", r.image)
        assertEquals("Astuce : servir chaud.", r.notes)

        // Ingrédients : sans groupe d'abord (dans l'ordre), puis les groupes ; sans nom = ignoré.
        assertEquals(listOf("", "POUR LA GARNITURE"), r.ingredients.map { it.title })
        assertEquals(listOf("1 oignon", "200 g carottes"), r.ingredients[0].items.map { it.display })
        assertEquals("10 g persil", r.ingredients[1].items.single().display)

        val s = r.steps
        assertEquals(9, s.size)
        // Le réglage « Mode » est rattaché à l'étape qui décrit le geste.
        assertEquals("Mettre l'oignon dans le bol mixeur.", s[0].text)
        assertEquals(StepProgram.MANUAL, s[0].machine!!.program)
        assertEquals(5, s[0].machine!!.seconds)
        assertEquals(5, s[0].machine!!.speed)
        assertTrue(s[0].needsReview)
        // Balance.
        assertEquals(200, s[1].weigh!!.grams)
        assertNull(s[1].machine)
        // Réglage après une pesée : étape à part, avec chauffe et sens inverse.
        with(s[2].machine!!) {
            assertEquals(100, tempC); assertEquals(1, speed); assertEquals(1200, seconds); assertTrue(reverse)
        }
        assertEquals(StepProgram.TURBO, s[3].machine!!.program)
        assertEquals(2, s[3].machine!!.pulses)
        assertEquals(StepProgram.STEAM, s[4].machine!!.program)
        assertEquals(600, s[4].machine!!.seconds)
        assertEquals(StepProgram.TOAST, s[5].machine!!.program)
        assertEquals(StepProgram.KNEAD, s[6].machine!!.program)
        // Programme absent du Monsieur Cuisine connect : étape manuelle, signalée.
        assertNull(s[7].machine)
        assertTrue(s[7].text.startsWith("Cuire sous vide.\n"))
        assertEquals("Servir.", s[8].text)

        // Tous les réglages importés passent les règles de sécurité.
        for (step in s) step.machine?.let { assertTrue("${it.summary} : ${it.violations()}", it.valid) }
    }

    @Test fun unusable() {
        assertNull(McOnline.parseRecipe("""{"data":{"recipe":{"id":1,"name":"  "}}}"""))
        assertNull(McOnline.parseRecipe("""{"code":404,"message":"Recipe not found"}"""))
        assertNotNull(McOnline.parseRecipe("""{"data":{"recipe":{"id":"2","name":"Vide"}}}"""))
    }

    @Test fun headersAndUrls() {
        assertEquals("web", McOnline.headers("fr-FR")["device-type"])
        assertEquals("fr-FR", McOnline.languageFor("fr"))
        assertEquals("en-US", McOnline.languageFor("en"))
        assertTrue(McOnline.listUrl(2).contains("/page/2/size/100?"))
        assertTrue(McOnline.listUrl(1).contains("MC2.0"))
    }
}
