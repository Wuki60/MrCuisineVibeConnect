package app.toque.core.update

import app.toque.core.json.Json

/** Une version publiée sur GitHub (page « Releases » du dépôt). */
data class Release(
    val tag: String,
    val version: String,
    val title: String,
    val notes: String,
    val apkName: String?,
    val apkUrl: String?,
    val apkSize: Long,
)

/** Lecture de l'API GitHub « dernière version » et comparaison de numéros de version. */
object Releases {
    const val REPO = "Wuki60/MrCuisineVibeConnect"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val PAGE_URL = "https://github.com/$REPO/releases"

    /** « v0.2.0 » → « 0.2.0 ». */
    fun versionOf(tag: String): String = tag.trim().removePrefix("v").removePrefix("V").trim()

    /** Compare deux versions « 1.2.3 » chiffre par chiffre (« 0.2 » = « 0.2.0 »). */
    fun compare(a: String, b: String): Int {
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    fun isNewer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

    private fun parts(v: String): List<Int> =
        versionOf(v).split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** Analyse la réponse de `GET /repos/{dépôt}/releases/latest`. */
    fun parse(json: String): Release {
        @Suppress("UNCHECKED_CAST")
        val o = Json.parse(json) as? Map<String, Any?> ?: throw IllegalArgumentException("réponse GitHub illisible")
        val tag = o["tag_name"] as? String ?: throw IllegalArgumentException("version sans numéro")
        @Suppress("UNCHECKED_CAST")
        val assets = (o["assets"] as? List<Any?>).orEmpty().mapNotNull { it as? Map<String, Any?> }
        val apk = assets.firstOrNull { (it["name"] as? String)?.endsWith(".apk", ignoreCase = true) == true }
        return Release(
            tag = tag,
            version = versionOf(tag),
            title = (o["name"] as? String)?.takeIf { it.isNotBlank() } ?: tag,
            notes = (o["body"] as? String).orEmpty().trim(),
            apkName = apk?.get("name") as? String,
            apkUrl = apk?.get("browser_download_url") as? String,
            apkSize = (apk?.get("size") as? Number)?.toLong() ?: 0L,
        )
    }
}
