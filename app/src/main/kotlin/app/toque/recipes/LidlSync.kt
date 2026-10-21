package app.toque.recipes

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.toque.Logbook
import app.toque.Prefs
import app.toque.core.machine.RootShell
import app.toque.core.recipes.OriginalDbMapper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recettes Lidl : l'appli d'origine du robot reste seule à dialoguer avec les serveurs Lidl
 * (avec son identité d'appareil, comme d'habitude). Toque copie ensuite sa base locale.
 *
 * Son service matériel est désactivé pour qu'elle ne touche jamais au port série du moteur.
 */
class LidlSync(private val context: Context, private val prefs: Prefs, private val repo: RecipeRepo, private val log: Logbook) {
    val pkg: String get() = prefs.vendorPackage

    class Components(val hardwareService: String?, val importService: String?, val launch: Intent?)

    data class Report(val ok: Boolean, val message: String, val recipes: Int = 0)

    fun installed(): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun components(): Components {
        val pm = context.packageManager
        val info = try {
            pm.getPackageInfo(pkg, PackageManager.GET_SERVICES or PackageManager.GET_DISABLED_COMPONENTS)
        } catch (_: PackageManager.NameNotFoundException) {
            return Components(null, null, null)
        }
        val services = info.services?.map { it.name } ?: emptyList()
        return Components(
            hardwareService = services.firstOrNull { it.endsWith("HardwareMachineService") },
            importService = services.firstOrNull { it.endsWith("RecipeImportService") },
            launch = pm.getLaunchIntentForPackage(pkg),
        )
    }

    private fun su(cmd: String, timeoutMs: Long = 15_000): RootShell.Result {
        val r = RootShell.run(cmd, timeoutMs)
        log.write("synchro", "su: ${cmd.take(120)} -> ${r.code} ${r.err.take(200)}")
        return r
    }

    /** Coupe (ou rétablit) l'accès de l'appli d'origine au moteur. */
    fun setVendorHardware(enabled: Boolean): Boolean {
        val c = components().hardwareService ?: return false
        return su("pm ${if (enabled) "enable" else "disable"} $pkg/$c").ok
    }

    fun forceStop() {
        su("am force-stop $pkg")
    }

    private val dbPath: String get() = "/data/data/$pkg/databases/Recipes"
    private val imagesPath: String get() = "/data/data/$pkg/files/recipe_images"

    private fun dbStamp(): String = su("ls -l $dbPath ${dbPath}-journal ${dbPath}-wal 2>/dev/null").out.trim()

    /**
     * Synchro complète : déclenche l'import de l'appli d'origine, attend que sa base change
     * (jusqu'à [maxWaitMs]), puis copie les recettes dans Toque.
     */
    fun sync(maxWaitMs: Long = 8 * 60_000L, progress: (String) -> Unit = {}): Report {
        if (!RootShell.available()) return finish(Report(false, "Accès root indisponible"))
        if (!installed()) return finish(Report(false, "Appli d'origine introuvable ($pkg)"))
        val comp = components()
        if (comp.hardwareService != null) setVendorHardware(false)
        val before = dbStamp()
        progress("Synchronisation avec les serveurs Lidl…")
        var started = false
        if (comp.importService != null) started = su("am startservice -n $pkg/${comp.importService}").ok
        if (!started) log.write("synchro", "service d'import introuvable ou refusé")
        val end = System.currentTimeMillis() + maxWaitMs
        var last = before
        var stableSince = 0L
        var changed = false
        while (System.currentTimeMillis() < end) {
            sleep(5_000)
            val now = dbStamp()
            if (now != last) {
                changed = true
                last = now
                stableSince = System.currentTimeMillis()
                progress("Réception des recettes…")
            } else if (changed && System.currentTimeMillis() - stableSince > 20_000) break
        }
        forceStop()
        val report = importNow(progress)
        val note = if (!changed) " (aucune nouveauté reçue : si ça persiste, utilisez « Ouvrir l'appli d'origine »)" else ""
        return finish(report.copy(message = report.message + note))
    }

    /** Copie la base actuelle de l'appli d'origine (sans relancer sa synchro). */
    fun importNow(progress: (String) -> Unit = {}): Report {
        if (!RootShell.available()) return finish(Report(false, "Accès root indisponible"))
        progress("Copie des recettes…")
        val work = File(context.cacheDir, "lidl").apply { mkdirs() }
        work.listFiles()?.forEach { it.delete() }
        val dest = File(work, "Recipes").absolutePath
        val copy = su(
            "cp $dbPath $dest && (cp ${dbPath}-journal ${dest}-journal 2>/dev/null; cp ${dbPath}-wal ${dest}-wal 2>/dev/null; " +
                "cp ${dbPath}-shm ${dest}-shm 2>/dev/null; true) && chmod 666 ${dest}*",
            30_000,
        )
        if (!copy.ok || !File(dest).exists()) return finish(Report(false, "Base de l'appli d'origine introuvable"))

        val tables = try {
            readTables(dest)
        } catch (e: Exception) {
            log.crash("synchro", e)
            return finish(Report(false, "Base illisible : ${e.message}"))
        }
        writeDiagnostic(tables)
        progress("Conversion…")
        val result = OriginalDbMapper.map(tables, "fr")
        if (result.recipes.isEmpty()) return finish(Report(false, "Aucune recette lisible. Envoyez le fichier diagnostic-recettes.txt."))

        progress("Copie des photos…")
        val img = repo.imagesDir.absolutePath
        su("if [ -d $imagesPath ]; then for f in $imagesPath/*; do b=\${f##*/}; [ -e $img/\$b ] || cp \$f $img/; done; chmod 644 $img/* 2>/dev/null; fi; true", 300_000)

        repo.replaceAll(result.recipes)
        work.listFiles()?.forEach { it.delete() }
        return finish(Report(true, "${result.recipes.size} recettes Lidl à jour", result.recipes.size))
    }

    private fun finish(r: Report): Report {
        val stamp = SimpleDateFormat("dd/MM à HH:mm", Locale.FRANCE).format(Date())
        prefs.lastSyncMs = System.currentTimeMillis()
        prefs.lastSyncResult = "$stamp : ${r.message}"
        log.write("synchro", r.message)
        return r
    }

    private fun readTables(path: String): Map<String, OriginalDbMapper.Table> {
        val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        try {
            val names = ArrayList<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c -> while (c.moveToNext()) names += c.getString(0) }
            val out = HashMap<String, OriginalDbMapper.Table>()
            for (n in names) {
                if (n.startsWith("sqlite_") || n == "android_metadata") continue
                val rows = ArrayList<Map<String, Any?>>()
                db.rawQuery("SELECT * FROM \"$n\" LIMIT 200000", null).use { c ->
                    val cols = c.columnNames
                    while (c.moveToNext()) {
                        val row = HashMap<String, Any?>(cols.size * 2)
                        for (i in cols.indices) row[cols[i]] = when (c.getType(i)) {
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            Cursor.FIELD_TYPE_STRING -> c.getString(i)
                            else -> null
                        }
                        rows += row
                    }
                }
                out[n] = OriginalDbMapper.Table(n, rows)
            }
            return out
        } finally {
            db.close()
        }
    }

    /** Schéma et premières lignes de la base d'origine, pour ajuster l'import si besoin. */
    private fun writeDiagnostic(tables: Map<String, OriginalDbMapper.Table>) {
        try {
            val sb = StringBuilder("Diagnostic import recettes Lidl — ${Date()}\n\n")
            for ((name, t) in tables.toSortedMap()) {
                sb.append("== ").append(name).append(" (").append(t.rows.size).append(" lignes)\n")
                val cols = t.rows.firstOrNull()?.keys ?: emptySet()
                sb.append("colonnes : ").append(cols.joinToString()).append('\n')
                for (row in t.rows.take(3)) sb.append("  ").append(row.entries.joinToString { "${it.key}=${it.value.toString().take(60)}" }).append('\n')
                sb.append('\n')
            }
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, "diagnostic-recettes.txt").writeText(sb.toString())
        } catch (_: Exception) {
        }
    }

    private fun sleep(ms: Long) = try {
        Thread.sleep(ms)
    } catch (_: InterruptedException) {
    }
}
