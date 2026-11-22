package app.toque.core

import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.McOnline
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.UserRecipes
import app.toque.core.recipes.Weigh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserRecipesTest {
    private val lidl = Recipe(
        id = "web-250008", title = "Beurre de cacahuètes", servings = 4, author = "Monsieur Cuisine Official",
        rating = 4.5, ratingCount = 120, image = "photo.jpg", language = "de-DE",
        ingredients = listOf(IngredientGroup("", listOf(Ingredient("320", "g", "cacahuètes", originalQty = "400")))),
        steps = listOf(Step("Mixer 320 g.", StepMachine(seconds = 30, speed = 8), Weigh("cacahuètes", 320, originalGrams = 400))),
    )

    @Test fun aCopyIsMineAndKeepsWhatIsShown() {
        val r = UserRecipes.copyOf(lidl, now = 1_800_000_000_000L)
        assertTrue(UserRecipes.isUser(r.id))
        assertTrue(r.id.startsWith("perso-beurre-de-cacahuetes-"))
        assertEquals(RecipeSource.USER, r.source)
        assertEquals("", r.author)
        assertEquals(0, r.ratingCount)
        assertEquals("320", r.allIngredients[0].qty)
        assertEquals("", r.allIngredients[0].originalQty)
        assertEquals(Weigh("cacahuètes", 320), r.steps[0].weigh)
        assertEquals("photo.jpg", r.image)
        assertEquals("de-DE", r.language)
        // Jamais confondue avec une recette du site.
        assertNull(McOnline.apiIdOf(r.id))
    }

    @Test fun importedFilesAlwaysBecomeMyRecipes() {
        val file = RecipeCodec.encodeFile(listOf(lidl.copy(source = RecipeSource.IMPORTED)))
        val (list, bad) = RecipeCodec.decodeFile(file, RecipeSource.USER)
        assertEquals(0, bad)
        val r = UserRecipes.imported(list.single())
        assertEquals(RecipeSource.USER, r.source)
        assertEquals("perso-web-250008", r.id)
        assertEquals(r.id, UserRecipes.imported(r).id)
    }

    @Test fun ingredientsAreTypedInOneLine() {
        val i = UserRecipes.ingredient("  500 g de   farine ")!!
        assertEquals("500", i.qty)
        assertEquals("g", i.unit)
        assertEquals("500 g de farine", i.display)
        assertEquals("2", UserRecipes.ingredient("2 œufs")!!.qty)
        assertEquals("", UserRecipes.ingredient("sel, poivre")!!.qty)
        assertNull(UserRecipes.ingredient("   "))
    }

    @Test fun whatPreventsSaving() {
        assertNotNull(UserRecipes.problem(UserRecipes.blank()))
        assertNotNull(UserRecipes.problem(Recipe(id = "perso-x", title = "Soupe")))
        assertNull(UserRecipes.problem(Recipe(id = "perso-x", title = "Soupe", steps = listOf(Step("Mixer")))))
        // Vitesse 8 en chauffe : refusée, comme à l'exécution.
        val hot = Recipe(id = "perso-x", title = "Soupe", steps = listOf(Step("Mixer", StepMachine(seconds = 60, speed = 8, tempC = 100))))
        assertTrue(UserRecipes.problem(hot)!!.startsWith("Étape 1"))
        assertFalse(UserRecipes.isUser("web-1"))
    }
}
