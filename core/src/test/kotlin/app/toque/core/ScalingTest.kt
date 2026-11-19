package app.toque.core

import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.Scaling
import app.toque.core.recipes.scaled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScalingTest {
    @Test fun parsesNumbersAndFractions() {
        assertEquals(500.0, Scaling.parse("500")!!, 1e-9)
        assertEquals(1000.0, Scaling.parse("1 000")!!, 1e-9)
        assertEquals(1200.0, Scaling.parse("1\u00A0200")!!, 1e-9)
        assertEquals(1.5, Scaling.parse("1,5")!!, 1e-9)
        assertEquals(1.5, Scaling.parse("1.5")!!, 1e-9)
        assertEquals(0.5, Scaling.parse("1/2")!!, 1e-9)
        assertEquals(1.5, Scaling.parse("1 1/2")!!, 1e-9)
        assertEquals(0.5, Scaling.parse("½")!!, 1e-9)
        assertEquals(2.25, Scaling.parse("2¼")!!, 1e-9)
        assertNull(Scaling.parse(""))
        assertNull(Scaling.parse("une pincée"))
        assertNull(Scaling.parse("1/0"))
    }

    @Test fun formatsReadableQuantities() {
        assertEquals("750", Scaling.format(750.0))
        assertEquals("335", Scaling.format(333.3))
        assertEquals("1 500", Scaling.format(1500.0))
        assertEquals("2 005", Scaling.format(2004.0))
        assertEquals("67", Scaling.format(66.7))
        assertEquals("1 ½", Scaling.format(1.5))
        assertEquals("¾", Scaling.format(0.75))
        assertEquals("2", Scaling.format(2.1))
        assertEquals("¼", Scaling.format(0.05))
    }

    @Test fun scalesQuantities() {
        assertEquals("750", Scaling.qty("500", 1.5))
        assertEquals("250", Scaling.qty("500", 0.5))
        assertEquals("1 500", Scaling.qty("1 000", 1.5))
        assertEquals("500", Scaling.qty("1 000", 0.5))
        assertEquals("1 ½", Scaling.qty("1", 1.5))
        assertEquals("¼", Scaling.qty("½", 0.5))
        assertEquals("4-6", Scaling.qty("2-3", 2.0))
        assertEquals("4 à 6", Scaling.qty("2 à 3", 2.0))
        assertEquals("une pincée", Scaling.qty("une pincée", 2.0))
        assertEquals("", Scaling.qty("", 2.0))
        assertEquals("1,0", Scaling.qty("1,0", 1.0))
    }

    @Test fun scalesAQuantityWrittenInTheName() {
        assertEquals("600 g de tomates mûres", Scaling.leading("400 g de tomates mûres", 1.5))
        assertEquals("¾ concombre", Scaling.leading("½ concombre", 1.5))
        assertEquals("2 gousses d'ail", Scaling.leading("1 gousses d'ail", 2.0))
        assertEquals("4 cuil. à soupe de vinaigre", Scaling.leading("2 cuil. à soupe de vinaigre", 2.0))
        assertEquals("1 500 g de melon", Scaling.leading("1 000 g de melon", 1.5))
        assertEquals("4 à 6 tomates", Scaling.leading("2 à 3 tomates", 2.0))
        assertEquals("3 ½ pommes", Scaling.leading("1 3/4 pommes", 2.0))
        assertEquals("huile d'olive", Scaling.leading("huile d'olive", 2.0))
        assertEquals("Sel, poivre", Scaling.leading("Sel, poivre", 2.0))
        assertEquals("600 g de tomates", Ingredient(name = "400 g de tomates").scaled(1.5).display)
    }

    @Test fun addsUpWeightsAndVolumes() {
        val items = listOf(
            Ingredient("800", "g", "tomates"), Ingredient("1 000", "g", "eau"), Ingredient("1", "kg", "farine"),
            Ingredient("2", "dl", "lait"), Ingredient("1", "c.c.", "sel"), Ingredient("3", "", "œufs"),
            Ingredient(name = "400 g de tomates mûres"), Ingredient(name = "½ concombre"), Ingredient(name = "1 l d'eau"),
            Ingredient(name = "huile d'olive"),
        )
        assertEquals(800 + 1000 + 1000 + 200 + 400 + 1000.0, Scaling.totalGrams(items), 1e-9)
        assertEquals(0.0, Scaling.totalGrams(emptyList()), 1e-9)
    }

    @Test fun scalesAnIngredient() {
        val flour = Ingredient("500", "g", "farine", "tamisée")
        assertEquals("750 g farine, tamisée", flour.scaled(1.5).display)
        assertEquals(flour, flour.scaled(1.0))
        assertEquals("sel", Ingredient(name = "sel").scaled(3.0).display)
    }
}
