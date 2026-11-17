package app.toque.recipes

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeAdjustments
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import app.toque.core.recipes.adjusted
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Recettes Lidl copiées localement (SQLite) + index de recherche en mémoire.
 * L'appli fonctionne entièrement hors ligne ; seule la synchro passe par le réseau.
 */
class RecipeRepo(private val context: Context) {
    private val helper = object : SQLiteOpenHelper(context, "recettes.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE recipes (id TEXT PRIMARY KEY, title TEXT NOT NULL, json TEXT NOT NULL, updated INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE favorites (id TEXT PRIMARY KEY)")
        }

        // Table ajoutée sans changer le numéro de version de la base : une version plus ancienne
        // de l'appli, qui l'ignore, peut toujours ouvrir la même base.
        override fun onOpen(db: SQLiteDatabase) {
            if (!db.isReadOnly) db.execSQL("CREATE TABLE IF NOT EXISTS adjustments (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-recettes").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()

    @Volatile var index: SearchIndex = SearchIndex(emptyList())
        private set
    @Volatile var loaded = false
        private set
    private val favorites = HashSet<String>()
    /** Adaptations personnelles (note, quantités, pesées), par identifiant de recette. */
    private val adjustments = ConcurrentHashMap<String, RecipeAdjustments>()
    @Volatile private var adjustmentsLoaded = false

    val imagesDir: File get() = File(context.filesDir, "images").apply { mkdirs() }

    fun onChange(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    private fun notifyChanged() = main.post { for (l in ArrayList(listeners)) l() }

    fun load() = exec.execute { rebuild() }

    private fun rebuild() {
        val db = helper.writableDatabase
        loadAdjustments(db)
        val favs = HashSet<String>()
        db.rawQuery("SELECT id FROM favorites", null).use { c -> while (c.moveToNext()) favs += c.getString(0) }
        val list = ArrayList<Recipe>()
        db.rawQuery("SELECT json FROM recipes", null).use { c ->
            while (c.moveToNext()) {
                val r = try {
                    RecipeCodec.decode(c.getString(0), RecipeSource.IMPORTED)
                } catch (_: Exception) {
                    null
                } ?: continue
                list += r.copy(favorite = r.id in favs)
            }
        }
        synchronized(favorites) {
            favorites.clear()
            favorites += favs
        }
        index = SearchIndex(list)
        loaded = true
        notifyChanged()
    }

    val count: Int get() = index.entries.size

    /** Lecture d'une recette (rapide : une seule ligne). */
    fun get(id: String): Recipe? {
        helper.readableDatabase.rawQuery("SELECT json FROM recipes WHERE id = ?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return null
            val r = try {
                RecipeCodec.decode(c.getString(0), RecipeSource.IMPORTED)
            } catch (_: Exception) {
                null
            } ?: return null
            return r.copy(favorite = isFavorite(id))
        }
    }

    /** La recette avec les adaptations de l'utilisateur (quantités et pesées corrigées). */
    fun getAdjusted(id: String): Recipe? = get(id)?.let { it.adjusted(adjustmentsOf(it.id)) }

    private fun loadAdjustments(db: SQLiteDatabase) {
        if (adjustmentsLoaded) return
        synchronized(adjustments) {
            if (adjustmentsLoaded) return
            db.rawQuery("SELECT id, json FROM adjustments", null).use { c ->
                while (c.moveToNext()) {
                    val a = RecipeAdjustments.decode(c.getString(1))
                    if (!a.isEmpty) adjustments[c.getString(0)] = a
                }
            }
            adjustmentsLoaded = true
        }
    }

    /** Adaptations de l'utilisateur pour cette recette. Conservées d'une synchronisation à l'autre. */
    fun adjustmentsOf(id: String): RecipeAdjustments {
        if (!adjustmentsLoaded) loadAdjustments(helper.writableDatabase)
        return adjustments[id] ?: RecipeAdjustments()
    }

    fun setAdjustments(id: String, a: RecipeAdjustments) {
        if (!adjustmentsLoaded) loadAdjustments(helper.writableDatabase)
        if (a.isEmpty) adjustments.remove(id) else adjustments[id] = a
        exec.execute {
            val db = helper.writableDatabase
            if (a.isEmpty) db.delete("adjustments", "id = ?", arrayOf(id))
            else db.insertWithOnConflict("adjustments", null, ContentValues().apply {
                put("id", id)
                put("json", a.encode())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun isFavorite(id: String): Boolean = synchronized(favorites) { id in favorites }

    fun setFavorite(id: String, favorite: Boolean) {
        synchronized(favorites) { if (favorite) favorites += id else favorites -= id }
        exec.execute {
            val db = helper.writableDatabase
            if (favorite) db.execSQL("INSERT OR IGNORE INTO favorites (id) VALUES (?)", arrayOf(id))
            else db.execSQL("DELETE FROM favorites WHERE id = ?", arrayOf(id))
            rebuild()
        }
    }

    /**
     * Remplace tout le catalogue par l'import du jour (miroir de l'appli d'origine).
     * Les favoris sont conservés ; ceux marqués dans l'appli d'origine sont ajoutés.
     */
    fun replaceAll(recipes: List<Recipe>) {
        exec.submit { replaceAllNow(recipes) }.get()
    }

    private fun replaceAllNow(recipes: List<Recipe>) {
        val db = helper.writableDatabase
        val before = titles(db)
        db.beginTransaction()
        try {
            db.delete("recipes", null, null)
            val now = System.currentTimeMillis()
            for (r in recipes) {
                val v = ContentValues()
                v.put("id", r.id)
                v.put("title", r.title)
                v.put("json", RecipeCodec.encode(r.copy(favorite = false)))
                v.put("updated", now)
                db.insertWithOnConflict("recipes", null, v, SQLiteDatabase.CONFLICT_REPLACE)
                if (r.favorite) db.execSQL("INSERT OR IGNORE INTO favorites (id) VALUES (?)", arrayOf(r.id))
            }
            val kept = recipes.map { it.id }.toHashSet()
            carryOver(db, before.filterKeys { it !in kept })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        rebuild()
    }

    /** Ajoute ou met à jour des recettes (favoris conservés). [rebuild] : recharger l'index tout de suite. */
    fun upsertAll(recipes: List<Recipe>, rebuild: Boolean = true) {
        exec.submit {
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                val now = System.currentTimeMillis()
                for (r in recipes) {
                    val v = ContentValues()
                    v.put("id", r.id)
                    v.put("title", r.title)
                    v.put("json", RecipeCodec.encode(r.copy(favorite = false)))
                    v.put("updated", now)
                    db.insertWithOnConflict("recipes", null, v, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (rebuild) rebuild()
        }.get()
    }

    /** Identifiants de toutes les recettes enregistrées. */
    fun ids(): Set<String> = exec.submit<Set<String>> {
        val out = HashSet<String>()
        helper.readableDatabase.rawQuery("SELECT id FROM recipes", null).use { c -> while (c.moveToNext()) out += c.getString(0) }
        out
    }.get()

    /** Supprime les recettes absentes de [keep] (catalogue miroir de la source). */
    fun removeAllExcept(keep: Set<String>, rebuild: Boolean = true) {
        exec.submit {
            val db = helper.writableDatabase
            val gone = titles(db).filterKeys { it !in keep }
            db.beginTransaction()
            try {
                for (id in gone.keys) db.delete("recipes", "id = ?", arrayOf(id))
                carryOver(db, gone)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (rebuild) rebuild()
        }.get()
    }

    private fun titles(db: SQLiteDatabase): Map<String, String> {
        val out = HashMap<String, String>()
        db.rawQuery("SELECT id, title FROM recipes", null).use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1) }
        return out
    }

    /**
     * Favoris et adaptations des recettes retirées ([gone] : id → titre), reportés sur la recette
     * restante de même titre : passer des recettes de l'appli d'origine à celles du site (ou
     * l'inverse) ne les fait pas disparaître. Les anciennes lignes restent, pour un retour en arrière.
     */
    private fun carryOver(db: SQLiteDatabase, gone: Map<String, String>) {
        if (gone.isEmpty()) return
        loadAdjustments(db)
        val favs = HashSet<String>()
        db.rawQuery("SELECT id FROM favorites", null).use { c -> while (c.moveToNext()) favs += c.getString(0) }
        val wanted = gone.filterKeys { it in favs || adjustments.containsKey(it) }
        if (wanted.isEmpty()) return
        val byTitle = HashMap<String, String>()
        for ((id, title) in titles(db)) byTitle.putIfAbsent(RecipeAdjustments.titleKey(title), id)
        for ((id, title) in wanted) {
            val target = byTitle[RecipeAdjustments.titleKey(title)] ?: continue
            if (target == id) continue
            if (id in favs) db.execSQL("INSERT OR IGNORE INTO favorites (id) VALUES (?)", arrayOf(target))
            adjustments[id]?.let { a ->
                val r = get(target) ?: return@let
                val merged = a.carriedTo(r, adjustments[target] ?: RecipeAdjustments())
                if (merged.isEmpty) return@let
                adjustments[target] = merged
                db.insertWithOnConflict("adjustments", null, ContentValues().apply {
                    put("id", target)
                    put("json", merged.encode())
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    /** Recharge l'index de recherche (après des mises à jour sans [rebuild]). */
    fun refresh() {
        exec.submit { rebuild() }.get()
    }

    fun imageFile(name: String): File? {
        if (name.isBlank()) return null
        val clean = name.substringAfterLast('/')
        for (candidate in listOf("large-$clean", clean, "small-$clean")) {
            val f = File(imagesDir, candidate)
            if (f.exists() && f.length() > 0) return f
        }
        return null
    }

    fun runInBackground(block: () -> Unit) = exec.execute(block)
}
