package app.toque

import app.toque.core.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HTTP minimal : GET (heure, avis : corps et heure du serveur, en-tête Date) et [send] pour toqueapp.org. */
object Http {
    class Response(val body: String, val serverMs: Long)

    var userAgent = "Toque (Android; +https://github.com/Wuki60/MrCuisineVibeConnect)"

    /** Heure du serveur (en-tête Date) quel que soit le code de réponse ; 0 si absente. */
    fun serverDate(url: String, timeoutMs: Int = 8_000): Long {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", userAgent)
            c.responseCode
            return c.date
        } finally {
            c.disconnect()
        }
    }

    /**
     * Requête quelconque (site toqueapp.org) : rend le code et le corps, même pour une erreur
     * (le site y explique le refus). IOException si le serveur ne répond pas.
     */
    fun send(method: String, url: String, headers: Map<String, String>, body: String?,
             timeoutMs: Int = 20_000, maxBytes: Int = 3 * 1024 * 1024): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.requestMethod = method
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", userAgent)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val stream = (if (code >= 400) c.errorStream else c.inputStream) ?: return code to ""
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException(tr("réponse trop grande"))
                }
                return code to String(out.toByteArray(), Charsets.UTF_8)
            }
        } finally {
            c.disconnect()
        }
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000, maxBytes: Int = 1024 * 1024): Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val code = c.responseCode
            val date = c.date
            if (code != 200) throw IOException(tr("réponse %d du serveur", code))
            c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException(tr("réponse trop grande"))
                }
                return Response(String(out.toByteArray(), Charsets.UTF_8), date)
            }
        } finally {
            c.disconnect()
        }
    }
}
