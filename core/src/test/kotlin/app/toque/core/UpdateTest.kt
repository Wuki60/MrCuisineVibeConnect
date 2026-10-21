package app.toque.core

import app.toque.core.update.Releases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    @Test fun compareVersions() {
        assertTrue(Releases.isNewer("v0.2.0", "0.1.0"))
        assertTrue(Releases.isNewer("0.10.0", "0.9.9"))
        assertTrue(Releases.isNewer("1.0", "0.99.99"))
        assertFalse(Releases.isNewer("v0.2.0", "0.2.0"))
        assertFalse(Releases.isNewer("0.2", "0.2.0"))
        assertFalse(Releases.isNewer("0.1.9", "0.2.0"))
        assertEquals(0, Releases.compare("V1.2.3-beta", "1.2.3"))
    }

    @Test fun parseLatestRelease() {
        val json = """
            {"tag_name":"v0.2.0","name":"Toque 0.2.0","body":"- Mises à jour depuis GitHub\r\n",
             "draft":false,"prerelease":false,
             "assets":[
               {"name":"notes.txt","size":12,"browser_download_url":"https://example.invalid/notes.txt"},
               {"name":"Toque-0.2.0.apk","size":2345678,
                "browser_download_url":"https://github.com/Wuki60/MrCuisineVibeConnect/releases/download/v0.2.0/Toque-0.2.0.apk"}
             ]}
        """.trimIndent()
        val r = Releases.parse(json)
        assertEquals("v0.2.0", r.tag)
        assertEquals("0.2.0", r.version)
        assertEquals("Toque 0.2.0", r.title)
        assertEquals("- Mises à jour depuis GitHub", r.notes)
        assertEquals("Toque-0.2.0.apk", r.apkName)
        assertEquals(2345678L, r.apkSize)
        assertTrue(r.apkUrl!!.endsWith("/v0.2.0/Toque-0.2.0.apk"))
    }

    @Test fun releaseWithoutApk() {
        val r = Releases.parse("""{"tag_name":"v0.3.0","name":"","body":null,"assets":[]}""")
        assertEquals("v0.3.0", r.title)
        assertNull(r.apkUrl)
        assertEquals("", r.notes)
    }
}
