package app.toque.core.recipes

import app.toque.core.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Public HTTPS data only. Redirects never relax certificate checks or downgrade to HTTP. */
class JsonCatalogHttp(private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    data class Response(val text: String?, val etag: String)

    fun fetch(source: JsonRecipeSource, etag: String = ""): Response {
        var url = URL(source.url)
        repeat(5) {
            if (!JsonRecipeSource.isHttpsUrl(url.toString())) throw IOException(tr("La source doit rester en HTTPS."))
            val c = connect(url)
            try {
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", "Toque JSON recipes")
                c.setRequestProperty("Accept", if (url.host.equals("api.github.com", true)) "application/vnd.github.raw+json" else "application/json")
                if (etag.isNotEmpty() && etag.length <= 512 && '\r' !in etag && '\n' !in etag) c.setRequestProperty("If-None-Match", etag)
                when (val code = c.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        val location = c.getHeaderField("Location") ?: throw IOException(tr("Redirection de catalogue invalide."))
                        url = URL(url, location)
                    }
                    304 -> {
                        if (etag.isEmpty()) throw IOException(tr("Le serveur a répondu %d.", code))
                        return Response(null, etag)
                    }
                    200 -> {
                        if (c.contentLength > JsonRecipeCatalog.MAX_BYTES) throw IOException(tr("Catalogue trop volumineux (2 Mio maximum)."))
                        val bytes = c.inputStream.use { input ->
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (out.size() + n > JsonRecipeCatalog.MAX_BYTES) throw IOException(tr("Catalogue trop volumineux (2 Mio maximum)."))
                                out.write(buffer, 0, n)
                            }
                            out.toByteArray()
                        }
                        val nextTag = c.getHeaderField("ETag")?.takeIf { tag -> tag.length <= 512 && '\r' !in tag && '\n' !in tag }.orEmpty()
                        return Response(String(bytes, Charsets.UTF_8), nextTag)
                    }
                    else -> throw IOException(tr("Le serveur a répondu %d.", code))
                }
            } finally { c.disconnect() }
        }
        throw IOException(tr("Trop de redirections pour ce catalogue."))
    }
}
