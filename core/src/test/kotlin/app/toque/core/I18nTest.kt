package app.toque.core

import app.toque.core.i18n.En
import app.toque.core.i18n.I18n
import app.toque.core.i18n.Lang
import app.toque.core.i18n.tr
import app.toque.core.machine.Accessory
import app.toque.core.machine.JobKind
import app.toque.core.machine.LinkState
import app.toque.core.machine.PauseReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Vérifie que chaque texte passé à tr()/trKey() dans le code (cœur et appli) a sa traduction
 * anglaise, sans paramètre manquant ou en trop, et qu'aucune traduction n'est orpheline.
 */
class I18nTest {
    @After fun reset() {
        I18n.lang = Lang.FR
    }

    private val roots = listOf(File("src/main/kotlin"), File("../app/src/main/kotlin"))
    private val call = Regex("""\btr(?:Key)?\(\s*""")
    private val placeholder = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]""")

    /** Textes du code → fichiers où ils apparaissent. */
    private fun sourceKeys(): Map<String, MutableList<String>> {
        val keys = LinkedHashMap<String, MutableList<String>>()
        for (root in roots) {
            assertTrue("dossier introuvable : ${root.absolutePath}", root.isDirectory)
            root.walk().filter { it.extension == "kt" }.forEach { f ->
                val src = f.readText()
                for (m in call.findAll(src)) {
                    var i = m.range.last + 1
                    if (src[i] != '"') continue
                    val key = StringBuilder()
                    while (true) {
                        val (raw, end) = literal(src, i)
                        assertTrue("gabarit \$ dans un texte à traduire (${f.name}) : $raw", !Regex("""(?<!\\)\$[{A-Za-z_]""").containsMatchIn(raw))
                        key.append(unescape(raw))
                        var k = end
                        while (src[k].isWhitespace()) k++
                        if (src[k] == '+') {
                            k++
                            while (src[k].isWhitespace()) k++
                            if (src[k] == '"') { i = k; continue }
                        }
                        break
                    }
                    keys.getOrPut(key.toString()) { ArrayList() } += f.name
                }
            }
        }
        return keys
    }

    private fun literal(src: String, start: Int): Pair<String, Int> {
        var j = start + 1
        val b = StringBuilder()
        while (true) {
            val ch = src[j]
            if (ch == '\\') { b.append(src, j, j + 2); j += 2; continue }
            if (ch == '"') break
            b.append(ch); j++
        }
        return b.toString() to j + 1
    }

    private fun unescape(s: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\') {
                b.append(when (s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> s[i + 1] })
                i += 2
            } else { b.append(ch); i++ }
        }
        return b.toString()
    }

    @Test fun everyTextHasAnEnglishTranslation() {
        val keys = sourceKeys()
        assertTrue("trop peu de textes trouvés (${keys.size})", keys.size > 300)
        val missing = keys.filterKeys { it !in En.map }.map { "${it.value.first()} : ${it.key}" }
        assertEquals("Textes sans traduction anglaise :\n" + missing.joinToString("\n"), 0, missing.size)
        val unused = En.map.keys.filter { it !in keys }
        assertEquals("Traductions inutilisées :\n" + unused.joinToString("\n"), 0, unused.size)
    }

    @Test fun placeholdersMatch() {
        val bad = En.map.filter { (fr, en) -> placeholder.findAll(fr).map { it.value }.toList() != placeholder.findAll(en).map { it.value }.toList() }
        assertEquals("Paramètres différents :\n" + bad.entries.joinToString("\n"), 0, bad.size)
    }

    @Test fun switchingLanguage() {
        I18n.lang = Lang.EN
        assertEquals("Settings", tr("Réglages"))
        assertEquals("When heating, speed 3 maximum", tr("En chauffe, vitesse %d maximum", 3))
        assertEquals("Downloading version 0.3.0… 42 %", tr("Téléchargement de la version %s… %d %%", "0.3.0", 42))
        val labels: () -> List<String> = {
            Accessory.values().map { it.label } + JobKind.values().map { it.label } +
                LinkState.values().map { it.label } + PauseReason.values().map { it.label }
        }
        val english = labels()
        I18n.lang = Lang.FR
        val french = labels()
        I18n.lang = Lang.EN
        assertEquals(french.map { En.map[it] }, english)
        assertEquals("Knead", JobKind.KNEAD.label)
        I18n.lang = Lang.FR
        assertEquals("Réglages", tr("Réglages"))
        assertEquals("Pétrir", JobKind.KNEAD.label)
        assertNotEquals(tr("Réglages"), "Settings")
    }

    @Test fun languageSetting() {
        assertEquals(Lang.FR, I18n.resolve("auto", "fr"))
        assertEquals(Lang.EN, I18n.resolve("auto", "de"))
        assertEquals(Lang.EN, I18n.resolve(null, "en"))
        assertEquals(Lang.FR, I18n.resolve("fr", "en"))
        assertEquals(Lang.EN, I18n.resolve("en", "fr"))
    }
}
