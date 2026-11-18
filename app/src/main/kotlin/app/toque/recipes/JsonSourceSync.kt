package app.toque.recipes

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.toque.ToqueApp
import app.toque.core.i18n.tr
import app.toque.core.recipes.JsonCatalogHttp
import app.toque.core.recipes.JsonRecipeCatalog
import app.toque.core.recipes.JsonRecipeSource
import app.toque.core.recipes.JsonSourceSyncPolicy
import java.util.concurrent.atomic.AtomicBoolean

/** Fetches recipe data only; it never starts or adjusts a machine program. */
object JsonSourceSync {
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val lastAttempt = HashMap<String, Long>()
    private val checkedThisStartup = HashSet<String>()
    private var started = false
    var activeSourceId: String? = null
        private set

    fun canChangeCatalog(app: ToqueApp): Boolean = JsonSourceSyncPolicy.canChangeCatalog(
        app.machine.connected, app.machine.snapshot.busy, app.prefs.guidedRecipe)

    /** Called on the main thread. Recheck source configuration and idle state before committing. */
    fun sync(app: ToqueApp, source: JsonRecipeSource, automatic: Boolean = false, done: (Boolean, String) -> Unit = { _, _ -> }) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!canChangeCatalog(app)) {
            done(false, tr("Terminez la recette et attendez que le robot soit au repos."))
            return
        }
        if (!running.compareAndSet(false, true)) {
            done(false, tr("Synchronisation déjà en cours"))
            return
        }
        activeSourceId = source.id
        val etag = app.prefs.jsonSourceEtag(source.id)
        Thread {
            try {
                val response = JsonCatalogHttp().fetch(source, etag)
                val recipes = response.text?.let { JsonRecipeCatalog.parse(it, source) }
                main.post {
                    try {
                        val current = app.prefs.jsonRecipeSources.firstOrNull { it.id == source.id }
                        if (!JsonSourceSyncPolicy.stillConfigured(source, current, automatic)) {
                            done(false, tr("La source a changé : relancez sa synchronisation."))
                        } else if (!canChangeCatalog(app)) {
                            done(false, tr("Terminez la recette et attendez que le robot soit au repos."))
                        } else {
                            if (recipes != null) app.recipes.replaceJsonSource(current!!, recipes)
                            val message = if (recipes == null) tr("Catalogue inchangé.") else tr("%d recettes synchronisées.", recipes.size)
                            app.prefs.jsonSourceResult(source.id, message, System.currentTimeMillis(), response.etag)
                            checkedThisStartup += source.id
                            app.log.write("source-json", "${source.id}: $message")
                            done(true, message)
                        }
                    } catch (e: Exception) { error(app, source, e, done) }
                    finally { activeSourceId = null; running.set(false) }
                }
            } catch (e: Exception) {
                main.post { try { error(app, source, e, done) } finally { activeSourceId = null; running.set(false) } }
            }
        }.start()
    }

    private fun error(app: ToqueApp, source: JsonRecipeSource, e: Exception, done: (Boolean, String) -> Unit) {
        val message = tr("Synchronisation impossible : %s", e.message ?: e.toString())
        if (app.prefs.jsonRecipeSources.any { it.id == source.id && it.url == source.url }) app.prefs.jsonSourceResult(source.id, message)
        app.log.crash("source-json", e)
        done(false, message)
    }

    /** No request until the user configures a source. Retry offline startups without a tight loop. */
    @Suppress("DEPRECATION")
    fun start(app: ToqueApp) {
        if (started) return
        started = true
        main.postDelayed(object : Runnable {
            override fun run() {
                try {
                    val now = System.currentTimeMillis()
                    val elapsed = SystemClock.elapsedRealtime()
                    val network = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    if (!running.get() && canChangeCatalog(app) && now >= 1_735_689_600_000L && network.activeNetworkInfo?.isConnected == true) {
                        val source = app.prefs.jsonRecipeSources.firstOrNull {
                            it.autoSync && (it.id !in checkedThisStartup || now - app.prefs.jsonSourceLastSync(it.id) >= 86_400_000L) &&
                                elapsed - (lastAttempt[it.id] ?: -900_000L) >= 900_000L
                        }
                        if (source != null) {
                            lastAttempt[source.id] = elapsed
                            sync(app, source, automatic = true)
                        }
                    }
                } finally { main.postDelayed(this, 60_000L) }
            }
        }, 30_000L)
    }
}
