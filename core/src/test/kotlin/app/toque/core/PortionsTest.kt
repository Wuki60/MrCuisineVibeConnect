package app.toque.core

import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Portions
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.Step
import app.toque.core.recipes.StepMachine
import app.toque.core.recipes.Weigh
import app.toque.core.recipes.adjusted
import app.toque.core.recipes.forServings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortionsTest {
    private val flour = Ingredient("500", "g", "farine")
    private val milk = Ingredient("0,5", "l", "lait")
    private val eggs = Ingredient("3", "", "œufs")
    private val oil = Ingredient("1", "c. à s.", "huile")
    private val salt = Ingredient(name = "sel")
    private val shallots = Ingredient("2", "", "échalotes (60 g)")
    private val yeast = Ingredient("7", "g", "levure boulangère")
    private val cook = StepMachine(seconds = 1200, speed = 1, tempC = 100)
    private val recipe = Recipe(
        id = "web-9", title = "Pâte", servings = 4,
        ingredients = listOf(IngredientGroup("", listOf(flour, milk, eggs, oil, salt, shallots, yeast))),
        steps = listOf(
            Step("Mettre 300 g de farine et 7 g de levure dans le bol.", weigh = Weigh("farine", 300)),
            Step("Ajouter 200 g de farine, 500 ml de lait et 1 000 g d'eau.", weigh = Weigh("eau", 1000)),
            Step("Cuire dans un moule de 24 cm, 2 gousses d'ail, 3 l'oignon.", machine = cook),
        ),
    )

    @Test fun rangeGoesFromHalfToDouble() {
        assertEquals(2..8, Portions.range(4))
        assertEquals(1..2, Portions.range(1))
        assertEquals(3..10, Portions.range(5))
        assertTrue(Portions.range(0).isEmpty())
    }

    @Test fun everythingFollowsWithHandWrittenRounding() {
        val r = recipe.adjusted(RecipeAdjustments().withServings(6, 4))
        assertEquals(6, r.servings)
        val i = r.allIngredients
        assertEquals("750", i[0].qty)
        assertEquals("0,75", i[1].qty)
        assertEquals("5", i[2].qty) // 4,5 œufs → 5 (au-delà de 3, à l’unité)
        assertEquals("1 ½", i[3].qty)
        assertEquals("", i[4].qty) // « sel » sans quantité : inchangé
        assertEquals("3", i[5].qty)
        assertEquals("échalotes (90 g)", i[5].name)
        assertEquals("11", i[6].qty) // 10,5 g → 11 g (au gramme sous 20 g)
        // Les étapes disent la même chose que la liste.
        assertEquals("Mettre 450 g de farine et 11 g de levure dans le bol.", r.steps[0].text)
        assertEquals(450, r.steps[0].weigh!!.grams)
        assertEquals("Ajouter 300 g de farine, 750 ml de lait et 1 500 g d'eau.", r.steps[1].text)
        assertEquals(1500, r.steps[1].weigh!!.grams)
        // Ni les dimensions, ni ce qui se compte dans le texte, ni la machine.
        assertEquals(recipe.steps[2].text, r.steps[2].text)
        assertEquals(cook, r.steps[2].machine)
    }

    @Test fun halfARecipeNeverGoesToZero() {
        val r = recipe.adjusted(RecipeAdjustments().withServings(2, 4))
        val i = r.allIngredients
        assertEquals("250", i[0].qty)
        assertEquals("0,25", i[1].qty)
        assertEquals("1 ½", i[2].qty)
        assertEquals("½", i[3].qty)
        assertEquals("1", i[5].qty)
        assertEquals("3,5", i[6].qty)
        assertEquals("Mettre 150 g de farine et 3,5 g de levure dans le bol.", r.steps[0].text)
    }

    @Test fun personalCorrectionsAreScaledToo() {
        val a = RecipeAdjustments().withQty(flour.copy(), "600").withServings(8, 4)
        val r = recipe.adjusted(a)
        assertEquals("1200", r.allIngredients[0].qty)
        assertEquals("1000", r.allIngredients[0].originalQty) // la recette, pour 8
    }

    @Test fun servingsSurviveEncodingAndInvalidValuesAreDropped() {
        val a = RecipeAdjustments().withServings(6, 4)
        assertEquals(6, RecipeAdjustments.decode(a.encode()).servings)
        assertEquals(0, RecipeAdjustments().withServings(4, 4).servings)
        assertEquals(0, RecipeAdjustments().withServings(20, 4).servings)
        assertTrue(RecipeAdjustments().withServings(4, 4).isEmpty)
        // Recette sans nombre de personnes : rien ne change.
        assertEquals(recipe.copy(servings = 0).adjusted(RecipeAdjustments(servings = 6)).allIngredients[0].qty, "500")
    }

    @Test fun totalWeightForTheBowlWarning() {
        // 500 + 500 + 7 g de la liste (le reste ne se pèse pas) + 1 000 g d'eau hors liste.
        assertEquals(2007, Portions.totalGrams(recipe))
    }
    @Test fun countedNamesAgreeInNumber() {
        assertEquals("oignons", Portions.agree("oignon", 1.0, 2.0, "fr"))
        assertEquals("petits oignons rouges, émincé", Portions.agree("petit oignon rouge, émincé", 1.0, 2.0, "fr"))
        assertEquals("gousse d'ail", Portions.agree("gousses d'ail", 2.0, 1.0, "fr"))
        assertEquals("pincées de sel", Portions.agree("pincée de sel", 1.0, 2.0, "fr"))
        assertEquals("œufs", Portions.agree("œuf", 1.0, 3.0, "fr"))
        assertEquals("œuf", Portions.agree("œufs", 3.0, 1.5, "fr"))
        assertEquals("poireaux", Portions.agree("poireau", 1.0, 2.0, "fr"))
        assertEquals("poireau", Portions.agree("poireaux", 2.0, 1.0, "fr"))
        assertEquals("bocal", Portions.agree("bocaux", 2.0, 1.0, "fr"))
        assertEquals("choux-fleurs", Portions.agree("chou-fleur", 1.0, 2.0, "fr"))
        assertEquals("noix de muscade", Portions.agree("noix de muscade", 1.0, 2.0, "fr"))
        assertEquals("radis", Portions.agree("radis", 2.0, 1.0, "fr"))
        assertEquals("gros oignon", Portions.agree("gros oignons", 2.0, 1.0, "fr"))
        assertEquals("citrons (jus)", Portions.agree("citron (jus)", 1.0, 2.0, "fr"))
        assertEquals("c. à s. d'huile", Portions.agree("c. à s. d'huile", 1.0, 2.0, "fr"))
        assertEquals("oignon(s)", Portions.agree("oignon(s)", 1.0, 2.0, "fr"))
        // 1 ½ reste au singulier ; pas de changement de nombre, pas de changement de mot.
        assertEquals("oignon", Portions.agree("oignon", 1.0, 1.5, "fr"))
        assertEquals("tomates", Portions.agree("tomates", 3.0, 6.0, "fr"))
        // Autres langues : inchangé.
        assertEquals("onion", Portions.agree("onion", 1.0, 2.0, "en"))
    }

    @Test fun scaledRecipeAgreesInNumber() {
        val r = Recipe(
            id = "perso-x", title = "Soupe", servings = 4, language = "fr",
            ingredients = listOf(IngredientGroup("", listOf(
                Ingredient("1", "", "oignon"), Ingredient("2", "", "gousses d'ail"),
                Ingredient("500", "g", "carottes"), Ingredient("1", "pincée", "sel"),
            ))),
        )
        assertEquals(listOf("2 oignons", "4 gousses d'ail", "1000 g carottes", "2 pincées sel"),
            r.forServings(8).allIngredients.map { it.display })
        assertEquals(listOf("½ oignon", "1 gousse d'ail", "250 g carottes", "½ pincée sel"),
            r.forServings(2).allIngredients.map { it.display })
    }
}
