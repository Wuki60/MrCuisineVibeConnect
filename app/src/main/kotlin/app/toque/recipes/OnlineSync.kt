package app.toque.recipes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.toque.Logbook
import app.toque.Prefs
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.int
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.recipes.CommunityRecipes
import app.toque.core.recipes.McCategories
import app.toque.core.recipes.McOnline
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.UserRecipes
import app.toque.core.robot.RobotProfiles
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
        // L'avancement est aussi publié pour l'écran des Réglages, qu'il ait lancé cette synchro ou non (synchro de nuit).
        val both: (String) -> Unit = { SyncProgress.report(it); progress(it) }
        try {
            return finish(run(both))
        } catch (e: Stopped) {
            // Ce qui est fait est gardé et la prochaine synchro reprend là. L'heure de la dernière synchro
            // n'est pas touchée, pour que celle de la nuit termine le travail.
            val r = LidlSync.Report(false, tr("Synchronisation arrêtée"))
            // Des fiches ont été enregistrées sans recharger le catalogue : le faire maintenant, sans attendre.
            if (dirty) repo.load()
            prefs.lastSyncResult = SimpleDateFormat(tr("dd/MM à HH:mm"), I18n.locale).format(Date()) + " : " + r.message
            log.write("synchro", r.message)
            return r
        } catch (e: Exception) {
            log.crash("synchro", e)
            return finish(LidlSync.Report(false, tr("Synchronisation impossible : %s", e.message ?: e.toString())))
        } finally {
            running.set(false)
            SyncProgress.finished()
        }
    }

    /** Arrêt demandé depuis les Réglages (bouton Arrêter). */
    private class Stopped : Exception()

    /** Des recettes ont été enregistrées pendant cette synchronisation. */
    private var dirty = false

    private fun checkStop() {
        if (SyncProgress.stopRequested) throw Stopped()
    }

    private fun run(progress: (String) -> Unit): LidlSync.Report {
        val language = McOnline.recipeLanguage(I18n.code, prefs.recipeLanguage)
        // Recettes destinées au robot choisi (Connect : MC2.0, Smart : MC3.0).
        val device = RobotProfiles.active.lidlDevice
        val indexKey = McOnline.indexKey(language, device)
        val stored = readIndex()
        // Autre langue, autre robot ou lecture des fiches améliorée depuis : toutes les fiches sont relues.
        val index = if (stored.language == indexKey && stored.parser == McOnline.PARSER) HashMap(stored.recipes) else HashMap()

        // 1. Liste complète (résumés).
        progress(tr("Liste des recettes…"))
        val entries = ArrayList<McOnline.Entry>()
        try {
            entries += list(language, device) { page, pages -> progress(tr("Liste des recettes… %d / %d", page, pages)) }
        } catch (e: IOException) {
            return LidlSync.Report(false, tr("Serveur de recettes injoignable : %s", e.message ?: e.toString()))
        }
        if (entries.isEmpty()) return LidlSync.Report(false, tr("Aucune recette reçue du serveur"))

        // 1 bis. Option : les recettes qui n'existent pas dans cette langue, prises dans une autre.
        // Si une liste ne vient pas, les recettes étrangères déjà là sont gardées telles quelles.
        var foreignFailed = false
        if (prefs.foreignRecipes) {
            val others = ArrayList<List<McOnline.Entry>>()
            for (lang in McOnline.OTHER_LANGUAGES) {
                if (lang == language) continue
                try {
                    others += list(lang, device) { page, pages -> progress(tr("Autres langues (%s)… %d / %d", lang.substringBefore('-'), page, pages)) }
                } catch (e: IOException) {
                    log.write("synchro", "liste $lang : ${e.message}")
                    foreignFailed = true
                    break
                }
            }
            if (!foreignFailed) entries += McOnline.missingTranslations(entries, others)
        }

        // 2. Fiches nouvelles ou modifiées ; pour les autres, seuls l'auteur et la note sont mis à jour.
        val present = repo.ids()
        val todo = entries.filter { e ->
            McOnline.updatedOf(index[e.apiId.toString()]) != e.updated || McOnline.toqueId(e.apiId) !in present
        }
        val todoIds = todo.map { it.apiId }.toHashSet()
        val rescored = ArrayList<Recipe>()
        for (e in entries) {
            if (SyncProgress.stopRequested) {
                save(rescored, index, indexKey)
                throw Stopped()
            }
            val key = e.apiId.toString()
            if (e.apiId in todoIds || index[key] == e.stamp) continue
            val r = repo.get(McOnline.toqueId(e.apiId)) ?: continue
            rescored += McOnline.withScore(r, e)
            index[key] = e.stamp
            if (rescored.size >= 100) save(rescored, index, indexKey)
        }
        save(rescored, index, indexKey)
        // Noms des catégories dans la langue des recettes, pour les fiches prises dans une autre langue.
        var categoryNames = emptyMap<Long, String>()
        if (todo.any { it.language.isNotBlank() && it.language != language }) {
            try {
                categoryNames = McCategories.parse(get(McCategories.url(), language))
            } catch (e: IOException) {
                log.write("synchro", "catégories : ${e.message}")
            }
        }
        val batch = ArrayList<Recipe>()
        var failures = 0
        var inARow = 0
        for ((i, e) in todo.withIndex()) {
            if (SyncProgress.stopRequested) {
                save(batch, index, indexKey)
                throw Stopped()
            }
            progress(tr("Recettes : %d / %d", i + 1, todo.size))
            try {
                val lang = e.language.ifBlank { language }
                val r = McOnline.parseRecipe(get(McOnline.recipeUrl(e.apiId), lang), if (lang != language) categoryNames else emptyMap())
                if (r != null) batch += if (lang != language) r.copy(language = lang) else r
                index[e.apiId.toString()] = e.stamp
                inARow = 0
            } catch (ex: IOException) {
                failures++
                inARow++
                log.write("synchro", "recette ${e.apiId} : ${ex.message}")
                if (inARow >= 5) {
                    save(batch, index, indexKey)
                    return LidlSync.Report(false, tr("Serveur de recettes injoignable : %s", ex.message ?: ex.toString()))
                }
            }
            if (batch.size >= 25) save(batch, index, indexKey)
            pause(250)
        }
        save(batch, index, indexKey)
        checkStop()

        // 3. Le catalogue suit la liste du site (recettes retirées supprimées). Les recettes perso
        // ne sont jamais touchées (voir RecipeRepo.removeAllExcept).
        val kept = entries.map { it.apiId.toString() }.toHashSet()
        if (foreignFailed) kept += stored.recipes.keys
        val keep = kept.mapNotNull { it.toLongOrNull()?.let(McOnline::toqueId) }.toHashSet()
        val removes = present.any { it !in keep && !UserRecipes.isUser(it) && !CommunityRecipes.isCommunity(it) }
        repo.removeAllExcept(keep, rebuild = false)
        index.keys.retainAll(kept)
        writeIndex(indexKey, index)
        // Rien d'enregistré ni de retiré : le catalogue en mémoire est déjà le bon. Le recharger prend une
        // vingtaine de secondes sur le robot, pendant lesquelles un arrêt demandé ne peut pas être pris en compte.
        if (dirty || removes) repo.refresh()

        // 4. Photos manquantes.
        photos(keep, progress)

        val n = repo.count
        val foreign = entries.count { it.language.isNotBlank() && it.language != language }
        val note = (if (foreign > 0) " " + tr("(dont %d dans d'autres langues)", foreign) else "") +
            (if (failures > 0) " " + tr("(%d en échec, réessayées à la prochaine synchro)", failures) else "")
        return LidlSync.Report(true, tr("%d recettes Lidl à jour", n) + note, n)
    }

    /** Liste complète des recettes officielles dans [language] (lève IOException si le site ne répond pas). */
    private fun list(language: String, device: String, progress: (Int, Int) -> Unit): List<McOnline.Entry> {
        val out = ArrayList<McOnline.Entry>()
        var page = 1
        var pages = 1
        while (page <= pages) {
            val p = McOnline.parsePage(get(McOnline.listUrl(page, device), language))
            pages = p.totalPages.coerceIn(1, 1000)
            out += p.entries
            progress(page, pages)
            checkStop()
            page++
            pause(300)
        }
        return out
    }

    private fun save(batch: MutableList<Recipe>, index: Map<String, String>, language: String) {
        if (batch.isNotEmpty()) {
            repo.upsertAll(ArrayList(batch), rebuild = false)
            dirty = true
        }
        batch.clear()
        writeIndex(language, index)
    }

    private fun photos(ids: Collection<String>, progress: (String) -> Unit) {
        val missing = ArrayList<String>()
        for (id in ids) {
            checkStop()
            val url = repo.get(id)?.image ?: continue
            if (url.startsWith("http") && repo.imageFile(url) == null) missing += url
        }
        var inARow = 0
        for ((i, url) in missing.withIndex()) {
            checkStop()
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

    private class Index(val language: String, val parser: Int, val recipes: Map<String, String>)

    private fun readIndex(): Index = try {
        val m = Json.parse(indexFile.readText()).obj()
        val ids = m["recipes"].obj().mapValues { (_, v) -> v?.toString().orEmpty() }
        Index(m.str("language").orEmpty(), m.int("parser") ?: 1, ids)
    } catch (_: Exception) {
        Index("", 0, emptyMap())
    }

    private fun writeIndex(language: String, index: Map<String, String>) {
        try {
            val tmp = File(indexFile.path + ".part")
            tmp.writeText(Json.write(linkedMapOf("language" to language, "parser" to McOnline.PARSER, "recipes" to index)))
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
