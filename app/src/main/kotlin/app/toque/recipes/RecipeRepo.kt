package app.toque.recipes

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.SearchIndex
import java.io.File
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

    val imagesDir: File get() = File(context.filesDir, "images").apply { mkdirs() }

    fun onChange(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    private fun notifyChanged() = main.post { for (l in ArrayList(listeners)) l() }

    fun load() = exec.execute { rebuild() }

    private fun rebuild() {
        val db = helper.readableDatabase
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
            val gone = ArrayList<String>()
            db.rawQuery("SELECT id FROM recipes", null).use { c -> while (c.moveToNext()) c.getString(0).let { if (it !in keep) gone += it } }
            db.beginTransaction()
            try {
                for (id in gone) db.delete("recipes", "id = ?", arrayOf(id))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            if (rebuild) rebuild()
        }.get()
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
