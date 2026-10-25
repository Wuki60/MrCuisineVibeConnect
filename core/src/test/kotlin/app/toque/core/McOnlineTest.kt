package app.toque.core

import app.toque.core.recipes.McOnline
import app.toque.core.recipes.RecipeCodec
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
        // Auteur et note, aussi dans la liste (pour garder les notes à jour sans tout retélécharger).
        assertEquals("Chef d'essai", p.entries[0].author)
        assertEquals(4.4, p.entries[0].rating, 0.001)
        assertEquals(500, p.entries[0].ratingCount)
        assertEquals(3.5, p.entries[1].rating, 0.001)
        assertEquals(0, p.entries[2].ratingCount)
        assertEquals("2026-04-19 09:00:02|4.40|500|Chef d'essai", p.entries[0].stamp)
        assertEquals("2026-04-19 09:00:02", McOnline.updatedOf(p.entries[0].stamp))
        assertEquals("2025-01-02 10:00:00", McOnline.updatedOf("2025-01-02 10:00:00"))
    }

    @Test fun recipe() {
        val r = McOnline.parseRecipe(fixture("mc-online-recipe.json"))!!
        assertEquals("web-900001", r.id)
        assertEquals("Soupe d'essai", r.title)
        assertEquals("Soupes", r.category)
        assertEquals(listOf("Végétarien"), r.tags)
        assertEquals(4, r.servings)
        assertEquals(15, r.prepMinutes)
        assertEquals(40, r.totalMinutes)
        assertEquals(2, r.difficulty)
        assertEquals("https://cdn.example.test/uploads/2021/11/123essai.jpg", r.image)
        assertEquals("Astuce : servir chaud.", r.notes)
        assertEquals("Chef d'essai", r.author)
        assertEquals(4.4, r.rating, 0.001)
        assertEquals(500, r.ratingCount)
        // Le format de stockage garde l'auteur et la note.
        val back = RecipeCodec.decode(RecipeCodec.encode(r))!!
        assertEquals(r.author, back.author)
        assertEquals(r.rating, back.rating, 0.001)
        assertEquals(r.ratingCount, back.ratingCount)

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

    @Test fun reviews() {
        val p = McOnline.parseReviews(fixture("mc-online-reviews.json"))
        assertEquals(13, p.total)
        assertEquals(5, p.totalPages)
        // Les avis signalés ne sont pas affichés.
        assertEquals(2, p.reviews.size)
        with(p.reviews[0]) {
            assertEquals("Cuisinier d'essai", author); assertEquals(5, rating); assertEquals("2025-12-02", date)
            assertEquals("Très bonne recette, toute la famille a aimé.", message); assertEquals("", reply)
        }
        assertEquals("Merci, nous corrigeons la recette.", p.reviews[1].reply)
        assertEquals(900001L, McOnline.apiIdOf("web-900001"))
        assertNull(McOnline.apiIdOf("mc-26946"))
        assertTrue(McOnline.reviewsUrl(900001, 2).endsWith("/api/v1/recipe-reviews/recipes/900001/reviews?page=2&size=10"))
        assertEquals(36, McOnline.reviewHeaders("fr-FR")["X-Request-Id"]!!.length)
        val updated = McOnline.withScore(McOnline.parseRecipe(fixture("mc-online-recipe.json"))!!, McOnline.Entry(900001, "", "", "", 4.6, 510))
        assertEquals(4.6, updated.rating, 0.001); assertEquals(510, updated.ratingCount); assertEquals("Chef d'essai", updated.author)
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
