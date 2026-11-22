package app.toque.core

import app.toque.core.recipes.McOnline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationsTest {
    private fun e(id: Long, t: Long, lang: String) = McOnline.Entry(id, "", "", translationId = t, language = lang)

    @Test fun onlyRecipesMissingInMyLanguageAreAddedOnce() {
        val fr = listOf(e(250008, 115205, "fr-FR"), e(1421869, 387248, "fr-FR"))
        val en = listOf(e(1415144, 387248, "en-US"), e(2457552, 2780361, "en-US"))
        val de = listOf(e(1416007, 387247, "de-DE"), e(9000001, 2780361, "de-DE"), e(9000002, 0, "de-DE"))
        val added = McOnline.missingTranslations(fr, listOf(en, de))
        // La citronnade existe en français ; 2780361 est pris en anglais (premier), pas en allemand ;
        // sans identifiant de traduction, on ne prend rien.
        assertEquals(listOf(2457552L, 1416007L), added.map { it.apiId })
    }

    @Test fun servingUnitsThatCountPeople() {
        for (u in listOf("portions", "Portionen", "porzioni", "raciones", "personnes", "Personen", "servings", "porcje", "porções", "porties", ""))
            assertTrue(u, McOnline.isPeople(u))
        for (u in listOf("pots", "Gläser", "Stück", "bocaux"))
            assertFalse(u, McOnline.isPeople(u))
    }
}
