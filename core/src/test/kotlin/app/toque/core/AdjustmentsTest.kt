package app.toque.core

import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.QuantityLinks
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.Step
import app.toque.core.recipes.Weigh
import app.toque.core.recipes.adjusted
import app.toque.core.recipes.unlistedWeighings
import app.toque.core.recipes.withSplitQuantities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdjustmentsTest {
    private val peanuts = Ingredient("400", "g", "cacahuètes, salées")
    private val sugar = Ingredient("25", "g", "sucre")
    private val milk = Ingredient("1000", "ml", "lait (UHT, 1,5 % M.G.)")
    private val salt = Ingredient(name = "1 pincée de sel")
    private val recipe = Recipe(
        id = "web-1",
        title = "Beurre de cacahuètes",
        ingredients = listOf(IngredientGroup("", listOf(peanuts, sugar, milk, salt))),
        steps = listOf(
            Step("Mettre 50 g de cacahuètes salées dans le bol mixeur.", weigh = Weigh("Mettre 50 g de cacahuètes salées dans le", 50)),
            Step("Ajouter 350 g de cacahuètes et 25 g de sucre.", weigh = Weigh("cacahuètes", 350)),
            Step("Verser 1 000 g de lait dans le bol mixeur.", weigh = Weigh("lait", 1000)),
            Step("Ajouter 500 g d’eau froide.", weigh = Weigh("Ajouter 500 g d’eau froide.", 500)),
            Step("Mixer."),
        ),
    )

    @Test fun nothingToApplyLeavesTheRecipeUntouched() {
        assertTrue(RecipeAdjustments().isEmpty)
        val split = recipe.withSplitQuantities()
        assertEquals(split, recipe.adjusted(RecipeAdjustments()))
        assertEquals(split, recipe.adjusted(RecipeAdjustments(note = "moins d'eau")))
        // Seul le sel, écrit d'un bloc, a été découpé ; son texte est le même.
        assertEquals(recipe.allIngredients.map { it.display }, split.allIngredients.map { it.display })
        assertEquals(recipe.steps, split.steps)
    }

    @Test fun weighingsAreLinkedToTheirIngredient() {
        assertEquals(mapOf(0 to 0, 1 to 0, 2 to 2), QuantityLinks.weighings(recipe))
        // L'eau n'est pas dans la liste : elle est proposée à part.
        assertEquals(listOf(3 to "eau froide"), recipe.unlistedWeighings())
    }

    @Test fun changingAnIngredientFollowsThroughTheSteps() {
        val r = recipe.adjusted(RecipeAdjustments().withQty(peanuts, "320"))
        assertEquals("320", r.allIngredients[0].qty)
        assertEquals("400", r.allIngredients[0].originalQty)
        // 400 → 320 : chaque portion suit, en proportion (× 0,8).
        assertEquals(Weigh("Mettre 40 g de cacahuètes salées dans le", 40, originalGrams = 50), r.steps[0].weigh)
        assertEquals("Mettre 40 g de cacahuètes salées dans le bol mixeur.", r.steps[0].text)
        assertEquals(280, r.steps[1].weigh!!.grams)
        // Le sucre, dans la même phrase, ne bouge pas.
        assertEquals("Ajouter 280 g de cacahuètes et 25 g de sucre.", r.steps[1].text)
        assertEquals(recipe.steps[2], r.steps[2])
        assertEquals(recipe.steps[3], r.steps[3])
    }

    @Test fun thousandsAndOtherUnitsFollowToo() {
        val r = recipe.adjusted(RecipeAdjustments().withQty(milk, "800"))
        assertEquals(800, r.steps[2].weigh!!.grams)
        assertEquals("Verser 800 g de lait dans le bol mixeur.", r.steps[2].text)
    }

    @Test fun aQuantityOnlyInTheStepsIsChangedOnItsOwn() {
        val a = RecipeAdjustments().withWeigh(3, recipe.steps[3].weigh!!, 400)
        val r = recipe.adjusted(a)
        assertEquals(Weigh("Ajouter 400 g d’eau froide.", 400, originalGrams = 500), r.steps[3].weigh)
        assertEquals("Ajouter 400 g d’eau froide.", r.steps[3].text)
        assertEquals(recipe.steps.take(3), r.steps.take(3))
    }

    @Test fun editingAnAlreadyCorrectedValueReplacesIt() {
        val first = RecipeAdjustments().withQty(peanuts, "320")
        val second = first.withQty(recipe.adjusted(first).allIngredients[0], "200")
        assertEquals(1, second.qty.size)
        val r = recipe.adjusted(second)
        assertEquals("400", r.allIngredients[0].originalQty)
        assertEquals(25, r.steps[0].weigh!!.grams)
        assertEquals(50, r.steps[0].weigh!!.originalGrams)
    }

    @Test fun goingBackToTheRecipeValueRemovesTheCorrection() {
        val a = RecipeAdjustments().withQty(peanuts, "320").withWeigh(3, recipe.steps[3].weigh!!, 400)
        val shown = recipe.adjusted(a)
        assertTrue(a.withQty(shown.allIngredients[0], "400").withWeigh(3, shown.steps[3].weigh!!, 500).isEmpty)
        assertTrue(a.withQty(shown.allIngredients[0], " ").withWeigh(3, shown.steps[3].weigh!!, 0).isEmpty)
    }

    @Test fun aRecipeChangedAtTheSourceShowsItsOwnValuesAgain() {
        val a = RecipeAdjustments().withQty(peanuts, "320").withWeigh(3, recipe.steps[3].weigh!!, 400)
        val changed = recipe.copy(
            ingredients = listOf(IngredientGroup("", listOf(peanuts.copy(qty = "450"), sugar, milk))),
            steps = recipe.steps.take(3) + Step("Ajouter 600 g d’eau.", weigh = Weigh("eau", 600)),
        )
        assertEquals(changed.withSplitQuantities(), changed.adjusted(a))
    }

    @Test fun inDoubtNothingIsLinked() {
        val two = listOf(Ingredient("150", "g", "crème fraîche"), Ingredient("60", "g", "crème liquide"))
        // Deux crèmes : la quantité exacte départage.
        assertEquals(0, QuantityLinks.link(two, "Ajouter 150 g de crème épaisse.", 150))
        // Aucune ne correspond au poids, ni aux mots : rien.
        assertNull(QuantityLinks.link(two, "Ajouter 40 g de crème.", 40))
        // Plus que la quantité de l'ingrédient : ce n'est pas lui.
        assertNull(QuantityLinks.link(listOf(Ingredient("1", "c.s.", "bouillon en poudre")), "Ajouter 800 g d'eau et 1 cube de bouillon.", 800))
        // « 2 gousses » n'est pas « 2 g ».
        assertEquals("2 gousses d'ail et 30 g de beurre", QuantityLinks.replaceGrams("2 gousses d'ail et 20 g de beurre", 20, 30))
        assertEquals("2 gousses d'ail", QuantityLinks.replaceGrams("2 gousses d'ail", 2, 3))
    }

    @Test fun quantitiesWithoutANumberAreKeptButNotPropagated() {
        val r = recipe.adjusted(RecipeAdjustments().withQty(peanuts, "un peu"))
        assertEquals("un peu", r.allIngredients[0].qty)
        assertEquals(recipe.steps, r.steps)
        assertEquals(0.5, QuantityLinks.parse("1/2")!!, 1e-9)
        assertEquals(1.5, QuantityLinks.parse("1,5")!!, 1e-9)
        assertEquals(1000.0, QuantityLinks.parse("1 000")!!, 1e-9)
        assertEquals(1500.0, QuantityLinks.parse("1\u202F500")!!, 1e-9)
        assertEquals(0.5, QuantityLinks.parse("½")!!, 1e-9)
        assertEquals(1.5, QuantityLinks.parse("1 ½")!!, 1e-9)
        assertNull(QuantityLinks.parse("2-3"))
        assertNull(QuantityLinks.parse("10 00"))
    }

    @Test fun quantitiesAreSplitOutOfOnePieceIngredients() {
        fun split(name: String) = QuantityLinks.split(Ingredient(name = name)).let { Triple(it.qty, it.unit, it.name) }
        assertEquals(Triple("100", "g", "d’emmental (45 % M.G.)"), split("100 g d’emmental (45 % M.G.)"))
        assertEquals(Triple("1 000", "g", "de lait"), split("1 000 g de lait"))
        assertEquals(Triple("1 ½", "c.c.", "de sel"), split("1 ½ c.c. de sel"))
        assertEquals(Triple("15", "ml", "d’huile d’olive"), split("15 ml d’huile d’olive"))
        assertEquals(Triple("2", "", "échalotes (60 g)"), split("2 échalotes (60 g)"))
        assertEquals(Triple("2", "", "gousses d'ail"), split("2 gousses d'ail"))
        assertEquals(Triple("", "", "Le jus de ½ citron"), split("Le jus de ½ citron"))
        assertEquals(Triple("", "", "Cannelle (facultatif)"), split("Cannelle (facultatif)"))
        assertEquals("100 g d’emmental (45 % M.G.)", QuantityLinks.split(Ingredient(name = "100 g d’emmental (45 % M.G.)")).display)
    }

    /** Recette de l'appli d'origine : ingrédients écrits d'un bloc. */
    @Test fun originalAppRecipesFollowToo() {
        val r0 = Recipe(
            id = "mc-1",
            title = "Recette d'essai",
            ingredients = listOf(IngredientGroup("", listOf(
                Ingredient(name = "100 g d’emmental (45 % M.G.)"),
                Ingredient(name = "2 échalotes (60 g)"),
                Ingredient(name = "15 ml d’huile d’olive"),
                Ingredient(name = "3 citrons non traités (de 100 g)"),
            ))),
            steps = listOf(
                Step("Râper 100 g d'emmental dans le bol.", weigh = Weigh("emmental", 100)),
                Step("Peler 2 échalotes (60 g).", weigh = Weigh("échalotes", 60)),
                Step("Verser 15 g d'huile d'olive.", weigh = Weigh("huile", 15)),
                Step("Presser 3 citrons non traités (de 100 g).", weigh = Weigh("citrons", 300)),
            ),
        )
        assertEquals(mapOf(0 to 0, 1 to 1, 2 to 2, 3 to 3), QuantityLinks.weighings(r0.withSplitQuantities()))
        val split = r0.withSplitQuantities().allIngredients
        var a = RecipeAdjustments().withQty(split[0], "80").withQty(split[1], "3").withQty(split[2], "10").withQty(split[3], "2")
        var r = r0.adjusted(a)
        assertEquals("Râper 80 g d'emmental dans le bol.", r.steps[0].text)
        assertEquals(80, r.steps[0].weigh!!.grams)
        // 2 → 3 échalotes : 60 → 90 g, dans l'étape et dans la liste.
        assertEquals(90, r.steps[1].weigh!!.grams)
        assertEquals("Peler 2 échalotes (90 g).", r.steps[1].text)
        assertEquals("3 échalotes (90 g)", r.allIngredients[1].display)
        assertEquals("Verser 10 g d'huile d'olive.", r.steps[2].text)
        // « de 100 g » : poids de chacun, 3 → 2 citrons = 200 g.
        assertEquals(200, r.steps[3].weigh!!.grams)
        assertEquals("2 citrons non traités (de 100 g)", r.allIngredients[3].display)
        // Corriger une deuxième fois remplace la correction, même si « (90 g) » a changé.
        a = a.withQty(r.allIngredients[1], "4")
        assertEquals(4, a.qty.size)
        r = r0.adjusted(a)
        assertEquals("4 échalotes (120 g)", r.allIngredients[1].display)
        assertEquals("2", r.allIngredients[1].originalQty)
        assertTrue(a.withQty(r.allIngredients[1], "2").qty.size == 3)
    }

    /** « 1 000 g » avec une espace (cas vu sur un robot). */
    @Test fun thousandsSeparatorInTheIngredient() {
        val stock = Ingredient("1 000", "g", "bouillon de légumes, maison")
        val r0 = Recipe(id = "web-2", title = "Soupe", ingredients = listOf(IngredientGroup("", listOf(stock))),
            steps = listOf(Step("Verser 1 000 g de bouillon de légumes.", weigh = Weigh("bouillon", 1000))))
        assertEquals("1 000|g|bouillon de légumes, maison", RecipeAdjustments.qtyKey(stock))
        val r = r0.adjusted(RecipeAdjustments(qty = mapOf("1 000|g|bouillon de légumes, maison" to "800")))
        assertEquals(800, r.steps[0].weigh!!.grams)
        assertEquals("Verser 800 g de bouillon de légumes.", r.steps[0].text)
    }

    @Test fun weighingStaysWithinTheScaleRange() {
        val r = recipe.adjusted(RecipeAdjustments().withQty(milk, "9000"))
        assertEquals(RecipeAdjustments.MAX_GRAMS, r.steps[2].weigh!!.grams)
        assertEquals(RecipeAdjustments.MAX_GRAMS, recipe.adjusted(RecipeAdjustments().withWeigh(3, recipe.steps[3].weigh!!, 99_999)).steps[3].weigh!!.grams)
    }

    @Test fun textRoundTripAndGarbage() {
        val a = RecipeAdjustments().withNote("  moins d'eau \"la prochaine fois\"\n").withQty(peanuts, "320")
            .withWeigh(3, recipe.steps[3].weigh!!, 400)
        assertEquals("moins d'eau \"la prochaine fois\"", a.note)
        assertEquals(a, RecipeAdjustments.decode(a.encode()))
        assertTrue(RecipeAdjustments.decode("").isEmpty)
        assertTrue(RecipeAdjustments.decode("pas du json").isEmpty)
        assertTrue(RecipeAdjustments.decode("""{"qty": {"a": 3, "b": ""}, "weigh": {"0:10": -5, "1:10": "x"}}""").isEmpty)
    }

    /** Les valeurs d'origine ne sont pas écrites dans le catalogue : la recette enregistrée reste celle de la source. */
    @Test fun noteAndMatchingQuantitiesFollowToTheSameRecipeFromTheOtherSource() {
        val mine = RecipeAdjustments().withNote("Moins de lait")
            .withQty(sugar, "20").withQty(milk, "800").withWeigh(3, recipe.steps[3].weigh!!, 400)
        // Même recette, autre source : le lait y est écrit autrement, le sucre pareil.
        val other = recipe.copy(id = "mc-1", title = "Beurre de cacahuètes", ingredients = listOf(IngredientGroup("", listOf(
            Ingredient(name = "400 g de cacahuètes salées"), Ingredient("25", "g", "sucre"), Ingredient(name = "1 l de lait")))))
        val moved = mine.carriedTo(other)
        assertEquals("Moins de lait", moved.note)
        assertEquals(mapOf(RecipeAdjustments.qtyKey(sugar) to "20"), moved.qty)
        assertTrue(moved.weigh.isEmpty())
        // Ce que la recette cible a déjà l'emporte.
        val kept = mine.carriedTo(other, RecipeAdjustments().withNote("Déjà là").withQty(sugar, "30"))
        assertEquals("Déjà là", kept.note)
        assertEquals("30", kept.qty[RecipeAdjustments.qtyKey(sugar)])
        assertEquals(RecipeAdjustments.titleKey("Velouté d’hiver "), RecipeAdjustments.titleKey("velouté d'hiver"))
    }

    @Test fun storedRecipeDoesNotCarryTheCorrection() {
        val a = RecipeAdjustments().withQty(peanuts, "320")
        val text = RecipeCodec.encode(recipe)
        assertEquals(recipe, RecipeCodec.decode(text, RecipeSource.IMPORTED))
        assertEquals(recipe.adjusted(a), RecipeCodec.decode(text, RecipeSource.IMPORTED)!!.adjusted(a))
    }
}
