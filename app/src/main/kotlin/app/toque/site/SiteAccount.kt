package app.toque.site

import android.os.Handler
import android.os.Looper
import app.toque.Http
import app.toque.ToqueApp
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.recipes.CommunityRecipes
import app.toque.core.recipes.Recipe
import app.toque.core.site.SiteSync
import app.toque.core.site.ToqueSite
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * toqueapp.org dans l'appli : compte relié (facultatif), recettes de la communauté (sans compte),
 * favoris et recettes perso envoyés au site. Tout le réseau passe par un seul fil d'arrière-plan ;
 * les réponses reviennent sur le fil de l'interface.
 */
class SiteAccount(private val app: ToqueApp) {
    private val prefs get() = app.prefs
    private val repo get() = app.recipes
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "toque-site").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val listeners = ArrayList<() -> Unit>()

    val client = ToqueSite(ToqueSite.Transport { method, url, headers, body ->
        val (code, text) = Http.send(method, url, headers, body)
        ToqueSite.Reply(code, text)
    })

    /** Synchro en cours (bouton désactivé dans les Réglages). */
    val busy: Boolean get() = running.get()

    private val store = object : SiteSync.Store {
        override val token: String get() = prefs.siteToken
        override var since: String
            get() = prefs.siteSince
            set(v) { prefs.siteSince = v }
        override var lang: String
            get() = prefs.siteLang
            set(v) { prefs.siteLang = v }
        override var fullMs: Long
            get() = prefs.siteFullMs
            set(v) { prefs.siteFullMs = v }
        override var pendingFavorites: Map<String, Boolean>
            get() = prefs.sitePendingFavorites
            set(v) { prefs.sitePendingFavorites = v }
        override val ownSiteIds: Set<Long> get() = prefs.siteLinks.values.toSet()
        override fun communityIds() = repo.communityIds()
        override fun communityFavorites() = repo.communityFavorites()
        override fun save(recipes: List<Recipe>) { if (recipes.isNotEmpty()) repo.upsertAll(recipes, rebuild = false) }
        override fun setFavorites(changes: Map<String, Boolean>) = repo.setFavorites(changes)
        override fun removeCommunityExcept(keep: Set<String>) = repo.removeCommunityExcept(keep)
        override fun unlinked() {
            prefs.forgetSite()
            app.log.write("site", "robot délié sur le site : compte oublié")
        }
    }

    /** Prévient (sur le fil de l'interface) quand le compte ou la synchro changent. */
    fun onChange(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    private fun changed() = main.post { for (l in ArrayList(listeners)) l() }

    private val deviceName: String get() = prefs.robot.displayName
    private val model: String get() = prefs.robot.id

    // ------------------------------------------------------------------ compte

    /** Relie le robot avec le code affiché sur le site (Profil › Mes robots). */
    fun linkWithCode(code: String, done: (ToqueSite.Link) -> Unit) = link(done) { client.pair(code, deviceName, model) }

    /** Relie le robot avec l'e-mail et le mot de passe ([otp] : code de double authentification, si demandé). */
    fun linkWithPassword(email: String, password: String, otp: String, done: (ToqueSite.Link) -> Unit) =
        link(done) { client.login(email, password, otp, deviceName, model) }

    private fun link(done: (ToqueSite.Link) -> Unit, call: () -> ToqueSite.Link) {
        exec.execute {
            val r = try {
                call()
            } catch (e: IOException) {
                ToqueSite.Link.Failed("offline", tr("Site injoignable : vérifiez le Wi-Fi."))
            }
            if (r is ToqueSite.Link.Ok) {
                prefs.siteToken = r.token
                prefs.siteUser = r.username
                // Les favoris de la communauté déjà mis sur le robot partent sur le compte.
                prefs.sitePendingFavorites = prefs.sitePendingFavorites + repo.communityFavorites().associateWith { true }
                app.log.write("site", "robot relié au compte ${r.username}")
            }
            main.post { done(r) }
            changed()
            if (r is ToqueSite.Link.Ok) syncNow()
        }
    }

    /** Délie le robot : le site oublie sa clé (si joignable), le robot oublie le compte. Les recettes restent. */
    fun unlink() {
        val token = prefs.siteToken
        prefs.forgetSite()
        app.log.write("site", "robot délié du compte")
        changed()
        if (token.isBlank()) return
        exec.execute {
            try {
                client.logout(token)
            } catch (_: IOException) {
                // Hors ligne : la clé reste sur le site ; on peut délier le robot depuis le profil.
            }
        }
    }

    // ------------------------------------------------------------------ synchro

    /** Synchro complète (fil d'arrière-plan, bloquante) : appelée la nuit et par le bouton des Réglages. */
    fun syncNow(): SiteSync.Result? {
        if (!prefs.siteCommunity && !prefs.siteLinked) return null
        if (!running.compareAndSet(false, true)) return null
        changed()
        try {
            val r = try {
                SiteSync(client, store).run(prefs.siteCommunity, ToqueSite.langFor(I18n.code))
            } catch (e: Exception) {
                app.log.crash("site", e)
                SiteSync.Result(false, 0, 0, 0, tr("toqueapp.org : erreur (%s)", e.message ?: e.toString()))
            }
            if (r.added + r.updated > 0) repo.load()
            prefs.siteLastMs = System.currentTimeMillis()
            prefs.siteLastResult = SimpleDateFormat(tr("dd/MM à HH:mm"), I18n.locale).format(Date()) + " : " + r.message
            app.log.write("site", r.message)
            return r
        } finally {
            running.set(false)
            changed()
        }
    }

    /** Synchro en arrière-plan ; [done] reçoit le résultat sur le fil de l'interface (null : rien à faire). */
    fun sync(done: (SiteSync.Result?) -> Unit = {}) {
        exec.execute {
            val r = syncNow()
            main.post { done(r) }
        }
    }

    /** Recettes de la communauté activées ou non. Coupées : celles reçues partent (sauf les favorites). */
    fun setCommunity(on: Boolean) {
        prefs.siteCommunity = on
        exec.execute {
            if (on) {
                syncNow()
            } else {
                prefs.siteSince = ""
                repo.removeCommunityExcept(emptySet())
                changed()
            }
        }
    }

    /** Étoile touchée sur une recette de la communauté : envoyée au compte (tout de suite, ou plus tard si hors ligne). */
    fun favoriteChanged(id: String, favorite: Boolean) {
        if (!prefs.siteLinked || !CommunityRecipes.isCommunity(id)) return
        prefs.sitePendingFavorites = prefs.sitePendingFavorites + (id to favorite)
        exec.execute {
            try {
                SiteSync(client, store).pushFavorites()
            } catch (e: ToqueSite.SiteException) {
                if (e.status == 401) {
                    store.unlinked()
                    changed()
                }
            }
        }
    }

    // ------------------------------------------------------------------ mes recettes sur le site

    /** La recette perso est sur le site (privée ou publique). */
    fun isOnline(id: String): Boolean = prefs.siteLinks.containsKey(id)

    fun isPublic(id: String): Boolean = id in prefs.sitePublic

    /** Adresse de la recette perso sur le site, ou null. */
    fun urlOf(id: String): String? = prefs.siteLinks[id]?.let { "${client.baseUrl}/recipe.php?id=$it" }

    /**
     * Envoie une recette perso au compte : [public] vrai, publiée pour tous ; faux, gardée sur le
     * compte seulement. [done] reçoit null si c'est fait, sinon le message à montrer.
     */
    fun publish(recipe: Recipe, public: Boolean, done: (String?) -> Unit) {
        exec.execute {
            val error = try {
                val token = prefs.siteToken
                if (token.isBlank()) throw IOException(tr("Aucun compte Toque relié"))
                val known = prefs.siteLinks[recipe.id]
                val id = try {
                    client.saveRecipe(token, recipe, public, known)
                } catch (e: ToqueSite.SiteException) {
                    // Supprimée sur le site entre-temps : on la remet.
                    if (e.status == 404 && known != null) client.saveRecipe(token, recipe, public, null) else throw e
                }
                prefs.siteLinks = prefs.siteLinks + (recipe.id to id)
                prefs.sitePublic = if (public) prefs.sitePublic + recipe.id else prefs.sitePublic - recipe.id
                prefs.siteKeepLocal = prefs.siteKeepLocal - recipe.id
                null
            } catch (e: ToqueSite.SiteException) {
                if (e.status == 401) {
                    store.unlinked()
                    changed()
                }
                e.message ?: tr("Refusé par le site")
            } catch (e: IOException) {
                e.message?.takeIf { it == tr("Aucun compte Toque relié") } ?: tr("Site injoignable : vérifiez le Wi-Fi.")
            }
            main.post { done(error) }
        }
    }

    /** Une recette perso modifiée, déjà sur le site : la mettre à jour (même visibilité). */
    fun updateIfOnline(recipe: Recipe, done: (String?) -> Unit) {
        if (!prefs.siteLinked || !isOnline(recipe.id)) return
        publish(recipe, isPublic(recipe.id), done)
    }
}
