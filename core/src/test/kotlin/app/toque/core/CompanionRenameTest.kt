package app.toque.core

import app.toque.core.companion.ApiError
import app.toque.core.companion.HttpRequest
import app.toque.core.companion.PairingManager
import app.toque.core.companion.Permissions
import app.toque.core.companion.PhoneWire
import app.toque.core.companion.RecipeDetail
import app.toque.core.companion.RenamePhoneRequest
import app.toque.core.companion.StateWire
import app.toque.core.companion.Wires
import app.toque.core.companion.client.CompanionClient
import app.toque.core.companion.client.CompanionException
import app.toque.core.json.Json
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

/** Un téléphone se renomme lui-même (POST /api/phones/me), et la fiche dit d'où vient la recette (source). */
class CompanionRenameTest {

    private fun state(f: HubFixture, token: String) = StateWire.fromMap(f.json(f.req("GET", "/api/state", token)))
    private fun phoneOf(f: HubFixture, token: String) = f.hub.pairing.authenticate(token)!!
    private fun rename(f: HubFixture, token: String?, body: Any?) = f.req("POST", "/api/phones/me", token, body)

    @Test fun aPhoneRenamesItselfOverHttp() {
        val f = HubFixture()
        val first = f.pair()
        val second = f.pair()
        val before = state(f, first).revision

        // Même nettoyage que sur le robot : une ligne, espaces réduits.
        val r = rename(f, first, mapOf("name" to "  Cuisine\tde   Léa\n"))
        assertEquals(200, r.status)
        val wire = PhoneWire.fromMap(f.json(r))
        assertEquals("Cuisine de Léa", wire.name)
        assertEquals("Cuisine de Léa", wire.label)
        // Le nom qu'il reprendrait sans celui-là, dans la langue du robot : le champ « Renommer » du téléphone le dit.
        assertEquals("Téléphone 1", wire.defaultName)
        assertEquals(1, wire.number)
        assertEquals(phoneOf(f, first).id, wire.id)
        assertEquals("Cuisine de Léa", phoneOf(f, first).name)

        // L'état le dit tout de suite : nouvelle révision, « me » porte le nom et le nom donné.
        val s = state(f, first)
        assertTrue("une nouvelle révision", s.revision > before)
        assertEquals("Cuisine de Léa", s.me.name)
        assertEquals("Cuisine de Léa", s.me.label)
        assertEquals("Téléphone 1", s.me.defaultName)
        // L'autre téléphone garde le sien : sans nom donné, « name » est déjà le nom par défaut.
        assertEquals("Téléphone 2", state(f, second).me.name)
        assertEquals("", state(f, second).me.label)
        assertEquals("", state(f, second).me.defaultName)

        // Le même nom une seconde fois : rien ne change, pas de nouvelle révision.
        val rev = state(f, first).revision
        assertEquals(200, rename(f, first, mapOf("name" to "Cuisine de Léa")).status)
        assertEquals(rev, state(f, first).revision)

        // Gardé par le robot (le magasin des téléphones).
        val again = PairingManager(store = f.store)
        assertEquals("Cuisine de Léa", again.list.first { it.number == 1 }.name)
    }

    @Test fun aLongNameIsCutAndAnEmptyNameGivesTheDefaultBack() {
        val f = HubFixture()
        val t = f.pair()
        val long = PhoneWire.fromMap(f.json(rename(f, t, mapOf("name" to "Le téléphone de la cuisine du haut"))))
        assertEquals("Le téléphone de la cuisi", long.name)
        assertEquals(PairingManager.MAX_LABEL, long.label.length)

        for (empty in listOf("", "   ", "\n\t")) {
            assertEquals(200, rename(f, t, mapOf("name" to "Nom")).status)
            val r = rename(f, t, mapOf("name" to empty))
            assertEquals(200, r.status)
            assertEquals("Téléphone 1", PhoneWire.fromMap(f.json(r)).name)
            // Sans nom donné, le champ n'est pas écrit : le JSON d'un téléphone sans nom reste celui d'avant.
            assertFalse(f.json(r).containsKey("label"))
            assertFalse(f.json(r).containsKey("defaultName"))
            assertEquals("", phoneOf(f, t).label)
        }
    }

    @Test fun aPhoneCannotRenameAnotherOne() {
        val f = HubFixture()
        val first = f.pair()
        val second = f.pair()
        val other = phoneOf(f, second).id
        // Le corps ne nomme aucun téléphone : un identifiant en plus est refusé, rien ne change.
        val r = rename(f, first, mapOf("name" to "Pirate", "id" to other))
        assertEquals(400, r.status)
        assertEquals("bad_request", ApiError.fromMap(f.json(r)).code)
        assertEquals("Téléphone 1", phoneOf(f, first).name)
        assertEquals("Téléphone 2", phoneOf(f, second).name)
        // Son propre nom seulement : celui du jeton.
        assertEquals(200, rename(f, first, mapOf("name" to "Pirate")).status)
        assertEquals("Pirate", phoneOf(f, first).name)
        assertEquals("Téléphone 2", phoneOf(f, second).name)
    }

    @Test fun badBodiesChangeNothing() {
        val f = HubFixture()
        val t = f.pair()
        for (body in listOf(null, emptyMap<String, Any?>(), mapOf("name" to 12), mapOf("name" to null), mapOf("label" to "x"),
            mapOf("name" to "x".repeat(RenamePhoneRequest.MAX_RAW + 1)), listOf("x"))) {
            val r = rename(f, t, body)
            assertEquals("$body", 400, r.status)
            assertEquals("Téléphone 1", phoneOf(f, t).name)
        }
        // Un nom de MAX_RAW caractères est encore pris (puis coupé).
        assertEquals(200, rename(f, t, mapOf("name" to "x".repeat(RenamePhoneRequest.MAX_RAW))).status)
        // Pas du JSON : refusé comme partout.
        val headers = linkedMapOf("host" to "192.168.0.12:8088", "authorization" to "Bearer $t", "content-type" to "text/plain")
        assertEquals(415, f.api.handle(HttpRequest("POST", "/api/phones/me", emptyMap(), headers, "Nom".toByteArray(), "192.168.0.20")).status)
        // PUT n'est pas une méthode de ce serveur ; GET sur la route : 405.
        assertEquals(405, f.req("GET", "/api/phones/me", t).status)
    }

    @Test fun anUnpairedOrRemovedPhoneIsRefused() {
        val f = HubFixture()
        val good = f.pair()
        val removed = f.pair()
        f.hub.pairing.remove(phoneOf(f, removed).id)
        for (token in listOf(null, "", "0".repeat(64), removed)) {
            val r = rename(f, token, mapOf("name" to "Intrus"))
            assertEquals("$token", 401, r.status)
            assertEquals("unauthorized", ApiError.fromMap(f.json(r)).code)
        }
        assertEquals(listOf("Téléphone 1"), f.hub.pairing.list.map { it.name })
        assertEquals(200, rename(f, good, mapOf("name" to "Cuisine")).status)
    }

    @Test fun theRobotAnnouncesRenaming() {
        val f = HubFixture()
        val t = f.pair()
        val m = f.json(f.req("GET", "/api/state", t))
        @Suppress("UNCHECKED_CAST")
        val can = m["can"] as Map<String, Any?>
        assertTrue(can.containsKey("renamePhone"))
        assertTrue(state(f, t).can.renamePhone.allowed)
        // Un robot plus ancien n'en dit rien : refusé, le téléphone ne propose pas « Renommer ».
        assertFalse(Permissions.fromMap(can - "renamePhone").renamePhone.allowed)
        // Un téléphone plus ancien lit sans peine un « me » qui porte un nom donné.
        val withLabel = PhoneWire(id = "1eeddd36", number = 1, name = "Cuisine", method = "code", label = "Cuisine").toMap()
        assertEquals("Cuisine", PhoneWire.fromMap(withLabel).label)
        assertEquals("", PhoneWire.fromMap(withLabel - "label").label)
        // Le nom par défaut suit le nom donné, et un robot qui ne l'envoie pas encore est lu sans peine.
        val named = PhoneWire(id = "1eeddd36", number = 1, name = "Cuisine", method = "code", label = "Cuisine", defaultName = "Téléphone 1")
        assertEquals("Téléphone 1", named.toMap()["defaultName"])
        assertEquals(named, PhoneWire.fromMap(named.toMap()))
        assertEquals("", PhoneWire.fromMap(named.toMap() - "defaultName").defaultName)
    }

    @Test fun aRenameOnTheRobotMovesTheRevisionToo() {
        val f = HubFixture()
        val t = f.pair()
        val before = state(f, t).revision
        val p = f.hub.renamePhone(phoneOf(f, t).id, "Tablette")!!
        assertEquals("Tablette", p.name)
        val s = state(f, t)
        assertTrue(s.revision > before)
        assertEquals("Tablette", s.me.name)
        assertNull(f.hub.renamePhone("inconnu", "Nom"))
    }

    @Test fun theClientRenamesThisPhone() {
        val f = HubFixture()
        f.hub.start(0, InetAddress.getByName("127.0.0.1"))
        try {
            val c = CompanionClient("127.0.0.1", f.hub.port)
            c.pairWithCode(f.hub.pairing.open().code)
            val other = CompanionClient("127.0.0.1", f.hub.port)
            other.pairWithCode(f.hub.pairing.open().code)

            assertTrue(c.state().can.renamePhone.allowed)
            val me = c.renameMe("Cuisine de Léa")
            assertEquals("Cuisine de Léa", me.name)
            assertEquals(c.phoneId, me.id)
            assertEquals("Cuisine de Léa", c.state().me.name)
            assertEquals("Téléphone 2", other.state().me.name)
            assertEquals("Téléphone 1", c.renameMe("").name)
            try {
                c.renameMe("x".repeat(RenamePhoneRequest.MAX_RAW + 1))
                fail()
            } catch (e: CompanionException) {
                assertEquals(400, e.status)
            }
            // Oublié : le robot ne le connaît plus, le nom ne part pas.
            c.forget()
            try {
                c.renameMe("Revenant")
                fail()
            } catch (e: java.io.IOException) {
            }
            assertEquals(listOf("Téléphone 2"), f.hub.pairing.list.map { it.name })
        } finally {
            f.hub.stop()
        }
    }

    @Test fun theSheetSaysWhereTheRecipeComesFrom() {
        val base = Recipe(id = "mealie-0b1c", title = "Gâteau d'essai", servings = 4, steps = listOf(Step("Mélanger.")))
        for ((source, key) in listOf(RecipeSource.MEALIE to "mealie", RecipeSource.COMMUNITY to "community",
            RecipeSource.USER to "user", RecipeSource.IMPORTED to "imported")) {
            val d = Wires.recipe(base.copy(source = source))
            assertEquals(key, d.source)
            val back = RecipeDetail.fromMap(Json.parse(Json.write(d.toMap())) as Map<String, Any?>)
            assertEquals(key, back.source)
            assertEquals(source == RecipeSource.MEALIE, back.mealie)
            assertEquals(source == RecipeSource.COMMUNITY, back.community)
            assertEquals(source == RecipeSource.USER, back.user)
        }
        // Le nombre de personnes calculé sur le téléphone garde la source.
        val d = Wires.recipe(base.copy(source = RecipeSource.MEALIE), 4)
        assertEquals("mealie", Wires.forServings(d, 6)!!.source)
        // Robot plus ancien : pas de champ, ni Mealie ni communauté.
        val old = RecipeDetail.fromMap(d.toMap() - "source")
        assertEquals("", old.source)
        assertFalse(old.mealie)
        // Par l'API : la fiche d'une recette Lidl le dit aussi.
        val f = HubFixture()
        val t = f.pair()
        assertEquals("imported", f.json(f.req("GET", "/api/recipes/r1", t))["source"])
    }
}
