package app.toque.core

import app.toque.core.recipes.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class JsonCatalogHttpTest {
    private val source = JsonRecipeSource("a", "Catalogue", "https://example.org/catalog.json")
    private class Connection(url: URL, private val status: Int, private val body: ByteArray = "{}".toByteArray(),
        private val headers: Map<String, String> = emptyMap(), private val length: Int = body.size) : HttpURLConnection(url) {
        var closed = false
        override fun connect() { }
        override fun usingProxy() = false
        override fun disconnect() { closed = true }
        override fun getResponseCode() = status
        override fun getHeaderField(name: String) = headers[name]
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentLength() = length
    }
    private fun refused(block: () -> Unit) {
        try { block(); fail("Expected network rejection") } catch (_: IOException) { }
    }

    @Test fun githubContentsApiUsesRawMediaTypeAndChecksResponse() {
        val github = source.copy(url = "https://api.github.com/repos/owner/repo/contents/catalog.json")
        val c = Connection(URL(github.url), 200, "{\"format\":\"toque-recettes-1\",\"recipes\":[]}".toByteArray(), mapOf("ETag" to "\"v1\""))
        val result = JsonCatalogHttp { c }.fetch(github)
        assertEquals("application/vnd.github.raw+json", c.getRequestProperty("Accept"))
        assertEquals("\"v1\"", result.etag)
        assertNotNull(result.text)
        assertFalse(c.instanceFollowRedirects)
        assertTrue(c.closed)
    }
    @Test fun notModifiedRequiresAnExistingConditionalRequest() {
        val c = Connection(URL(source.url), 304)
        assertNull(JsonCatalogHttp { c }.fetch(source, "\"v1\"").text)
        assertEquals("\"v1\"", c.getRequestProperty("If-None-Match"))
        refused { JsonCatalogHttp { Connection(it, 304) }.fetch(source) }
    }
    @Test fun redirectsCannotDowngradeOrLoopForever() {
        var count = 0
        refused { JsonCatalogHttp { count++; Connection(it, 302, headers = mapOf("Location" to "http://example.org/insecure")) }.fetch(source) }
        assertEquals(1, count)
        count = 0
        refused { JsonCatalogHttp { count++; Connection(it, 302, headers = mapOf("Location" to "/again")) }.fetch(source) }
        assertEquals(5, count)
    }
    @Test fun sizeLimitAlsoAppliesWithoutContentLength() {
        refused { JsonCatalogHttp { Connection(it, 200, length = JsonRecipeCatalog.MAX_BYTES + 1) }.fetch(source) }
        refused { JsonCatalogHttp { Connection(it, 200, ByteArray(JsonRecipeCatalog.MAX_BYTES + 1), length = -1) }.fetch(source) }
        refused { JsonCatalogHttp { Connection(it, 500) }.fetch(source) }
    }
    @Test fun cookingAndEditedSourcesDeferAnImport() {
        assertTrue(JsonSourceSyncPolicy.canChangeCatalog(true, false, ""))
        assertFalse(JsonSourceSyncPolicy.canChangeCatalog(false, false, ""))
        assertFalse(JsonSourceSyncPolicy.canChangeCatalog(true, true, ""))
        assertFalse(JsonSourceSyncPolicy.canChangeCatalog(true, false, "active-recipe"))
        assertFalse(JsonSourceSyncPolicy.stillConfigured(source, null, false))
        assertFalse(JsonSourceSyncPolicy.stillConfigured(source, source.copy(url = "https://example.org/other.json"), false))
        assertFalse(JsonSourceSyncPolicy.stillConfigured(source, source.copy(autoSync = false), true))
        assertTrue(JsonSourceSyncPolicy.stillConfigured(source, source.copy(autoSync = false), false))
    }
}
