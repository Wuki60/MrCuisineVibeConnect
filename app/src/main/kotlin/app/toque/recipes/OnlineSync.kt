package app.toque.recipes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.toque.Logbook
import app.toque.Prefs
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.recipes.McOnline
import app.toque.core.recipes.Recipe
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recettes officielles du Monsieur Cuisine connect, téléchargées directement depuis
 * monsieur-cuisine.com (API publique, sans compte) : la liste complète, puis seulement les
 * fiches nouvelles ou modifiées, et enfin les photos (réduites pour l'écran du robot).
 * Requêtes espacées pour ne pas charger les serveurs. Reprend où elle s'est arrêtée.
 */
class OnlineSync(private val context: Context, private val prefs: Prefs, private val repo: RecipeRepo, private val log: Logbook) {
    private val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }
    private val indexFile = File(context.filesDir, "recettes-en-ligne.json")

    fun sync(progress: (String) -> Unit = {}): LidlSync.Report {
        if (!running.compareAndSet(false, true)) return LidlSync.Report(false, tr("Synchronisation déjà en cours"))
        try {
            return finish(run(progress))
        } catch (e: Exception) {
            log.crash("synchro", e)
            return finish(LidlSync.Report(false, tr("Synchronisation impossible : %s", e.message ?: e.toString())))
        } finally {
            running.set(false)
        }
    }

    private fun run(progress: (String) -> Unit): LidlSync.Report {
        val language = McOnline.languageFor(I18n.code)
        val stored = readIndex()
        val index = if (stored.first == language) HashMap(stored.second) else HashMap()

        // 1. Liste complète (résumés).
        progress(tr("Liste des recettes…"))
        val entries = ArrayList<McOnline.Entry>()
        var page = 1
        var pages = 1
        while (page <= pages) {
            val p = try {
                McOnline.parsePage(get(McOnline.listUrl(page), language))
            } catch (e: IOException) {
                return LidlSync.Report(false, tr("Serveur de recettes injoignable : %s", e.message ?: e.toString()))
            }
            pages = p.totalPages.coerceIn(1, 1000)
            entries += p.entries
            progress(tr("Liste des recettes… %d / %d", page, pages))
            page++
            pause(300)
        }
        if (entries.isEmpty()) return LidlSync.Report(false, tr("Aucune recette reçue du serveur"))

        // 2. Fiches nouvelles ou modifiées.
        val present = repo.ids()
        val todo = entries.filter { e -> index[e.apiId.toString()] != e.updated || McOnline.toqueId(e.apiId) !in present }
        val batch = ArrayList<Recipe>()
        var failures = 0
        var inARow = 0
        for ((i, e) in todo.withIndex()) {
            progress(tr("Recettes : %d / %d", i + 1, todo.size))
            try {
                val r = McOnline.parseRecipe(get(McOnline.recipeUrl(e.apiId), language))
                if (r != null) batch += r
                index[e.apiId.toString()] = e.updated
                inARow = 0
            } catch (ex: IOException) {
                failures++
                inARow++
                log.write("synchro", "recette ${e.apiId} : ${ex.message}")
                if (inARow >= 5) {
                    save(batch, index, language)
                    return LidlSync.Report(false, tr("Serveur de recettes injoignable : %s", ex.message ?: ex.toString()))
                }
            }
            if (batch.size >= 25) save(batch, index, language)
            pause(250)
        }
        save(batch, index, language)

        // 3. Le catalogue suit la liste du site (recettes retirées supprimées).
        val keep = entries.map { McOnline.toqueId(it.apiId) }.toHashSet()
        repo.removeAllExcept(keep, rebuild = false)
        val kept = entries.map { it.apiId.toString() }.toHashSet()
        index.keys.retainAll(kept)
        writeIndex(language, index)
        repo.refresh()

        // 4. Photos manquantes.
        photos(keep, progress)

        val n = repo.count
        val note = if (failures > 0) " " + tr("(%d en échec, réessayées à la prochaine synchro)", failures) else ""
        return LidlSync.Report(true, tr("%d recettes Lidl à jour", n) + note, n)
    }

    private fun save(batch: MutableList<Recipe>, index: Map<String, String>, language: String) {
        if (batch.isNotEmpty()) repo.upsertAll(ArrayList(batch), rebuild = false)
        batch.clear()
        writeIndex(language, index)
    }

    private fun photos(ids: Collection<String>, progress: (String) -> Unit) {
        val missing = ArrayList<String>()
        for (id in ids) {
            val url = repo.get(id)?.image ?: continue
            if (url.startsWith("http") && repo.imageFile(url) == null) missing += url
        }
        var inARow = 0
        for ((i, url) in missing.withIndex()) {
            progress(tr("Photos : %d / %d", i + 1, missing.size))
            try {
                savePhoto(url)
                inARow = 0
            } catch (e: IOException) {
                log.write("synchro", "photo $url : ${e.message}")
                if (++inARow >= 5) return
            }
            pause(150)
        }
    }

    /** Photo réduite (800 px de large au plus), enregistrée sous le nom de fichier de l'URL. */
    private fun savePhoto(url: String) {
        val bytes = download(url, emptyMap(), 8 * 1024 * 1024)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw IOException(tr("image illisible"))
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException(tr("image illisible"))
        if (bmp.width > MAX_WIDTH) {
            val scaled = Bitmap.createScaledBitmap(bmp, MAX_WIDTH, bmp.height * MAX_WIDTH / bmp.width, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        val name = url.substringAfterLast('/').substringBefore('?')
        val tmp = File(repo.imagesDir, "$name.part")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        if (!tmp.renameTo(File(repo.imagesDir, name))) tmp.delete()
    }

    private fun get(url: String, language: String): String =
        String(download(url, McOnline.headers(language), 4 * 1024 * 1024), Charsets.UTF_8)

    /** GET avec reprises espacées si le serveur demande de ralentir (429) ou est indisponible (5xx). */
    private fun download(url: String, headers: Map<String, String>, maxBytes: Int): ByteArray {
        var attempt = 0
        while (true) {
            attempt++
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 20_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "Toque/$version (Android; +https://github.com/Wuki60/MrCuisineVibeConnect)")
                for ((k, v) in headers) c.setRequestProperty(k, v)
                val code = c.responseCode
                if ((code == 429 || code >= 500) && attempt < 4) {
                    pause(5_000L * attempt)
                    continue
                }
                if (code != 200) throw IOException(tr("réponse %d du serveur", code))
                c.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) throw IOException(tr("réponse trop grande"))
                    }
                    return out.toByteArray()
                }
            } finally {
                c.disconnect()
            }
        }
    }

    private fun readIndex(): Pair<String, Map<String, String>> = try {
        val m = Json.parse(indexFile.readText()).obj()
        val ids = m["recipes"].obj().mapValues { (_, v) -> v?.toString().orEmpty() }
        (m.str("language").orEmpty()) to ids
    } catch (_: Exception) {
        "" to emptyMap()
    }

    private fun writeIndex(language: String, index: Map<String, String>) {
        try {
            val tmp = File(indexFile.path + ".part")
            tmp.writeText(Json.write(linkedMapOf("language" to language, "recipes" to index)))
            tmp.renameTo(indexFile)
        } catch (e: Exception) {
            log.write("synchro", "index non enregistré : $e")
        }
    }

    private fun finish(r: LidlSync.Report): LidlSync.Report {
        val stamp = SimpleDateFormat(tr("dd/MM à HH:mm"), I18n.locale).format(Date())
        prefs.lastSyncMs = System.currentTimeMillis()
        prefs.lastSyncResult = "$stamp : ${r.message}"
        log.write("synchro", r.message)
        return r
    }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val MAX_WIDTH = 800
        private val running = AtomicBoolean(false)
    }
}
