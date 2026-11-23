package app.toque.core.site

import app.toque.core.i18n.tr
import app.toque.core.recipes.CommunityRecipes
import app.toque.core.recipes.Recipe
import java.io.IOException

/**
 * Synchro avec toqueapp.org. Sans compte : les recettes publiques de la communauté (seulement
 * celles changées depuis la dernière fois, et une liste complète par semaine pour enlever celles
 * retirées du site). Avec un compte relié : d'abord les favoris changés sur le robot, envoyés au
 * site, puis les favoris du compte (recettes du site), repris sur le robot.
 */
class SiteSync(private val site: ToqueSite, private val store: Store, private val now: () -> Long = System::currentTimeMillis) {

    /** Ce que la synchro lit et écrit dans l'appli (réglages et recettes). */
    interface Store {
        val token: String
        var since: String
        var lang: String
        var fullMs: Long
        var pendingFavorites: Map<String, Boolean>
        /** Numéros sur le site des recettes perso de ce robot qui y sont (jamais reprises en double). */
        val ownSiteIds: Set<Long>
        /** Identifiants des recettes de la communauté déjà sur le robot. */
        fun communityIds(): Set<String>
        /** Recettes de la communauté en favori sur le robot. */
        fun communityFavorites(): Set<String>
        /** Ajoute ou met à jour des recettes (sans recharger le catalogue). */
        fun save(recipes: List<Recipe>)
        fun setFavorites(changes: Map<String, Boolean>)
        /** Retire les recettes de la communauté absentes de [keep] (les favorites restent). */
        fun removeCommunityExcept(keep: Set<String>)
        /** La clé du robot n'est plus acceptée (robot délié sur le site) : oublier le compte. */
        fun unlinked()
    }

    class Result(val ok: Boolean, val added: Int, val updated: Int, val removed: Int, val message: String)

    /** Arrêt demandé (on garde ce qui est fait ; la prochaine synchro reprend). */
    class Stopped : Exception()

    /**
     * [community] : recevoir les recettes de la communauté ; [lang] : langue des traductions
     * (ToqueSite.langFor de la langue de l'interface). [stop] est appelé entre deux pages.
     */
    fun run(community: Boolean, lang: String, stop: () -> Boolean = { false }): Result {
        val before = store.communityIds()
        var added = 0
        var updated = 0
        var removed = 0
        val problems = ArrayList<String>()
        var accountFavorites: Set<String>? = null

        fun count(list: List<Recipe>) {
            for (r in list) if (r.id in before) updated++ else added++
        }

        if (store.token.isNotBlank()) {
            try {
                if (pushFavorites()) {
                    val favs = site.favorites(store.token, lang).filter { CommunityRecipes.siteId(it.id) !in store.ownSiteIds }
                    val fresh = favs.filter { it.id !in before }
                    store.save(favs)
                    added += fresh.size
                    val ids = favs.mapTo(HashSet()) { it.id }
                    accountFavorites = ids
                    val local = store.communityFavorites()
                    val changes = LinkedHashMap<String, Boolean>()
                    for (id in ids) if (id !in local) changes[id] = true
                    for (id in local) if (id !in ids) changes[id] = false
                    if (changes.isNotEmpty()) store.setFavorites(changes)
                } else {
                    problems += tr("favoris pas encore envoyés")
                }
            } catch (e: ToqueSite.SiteException) {
                if (e.status == 401) {
                    store.unlinked()
                    problems += tr("robot délié du compte sur le site")
                } else problems += e.message.orEmpty()
            } catch (e: IOException) {
                problems += tr("site injoignable")
            }
        }

        if (community) {
            val full = store.since.isBlank() || store.lang != lang || now() - store.fullMs > WEEK_MS
            val since = if (full) "" else store.since
            val seen = HashSet<String>()
            var newest = since
            var page: Int? = 1
            try {
                while (page != null) {
                    if (stop()) throw Stopped()
                    val p = site.community(since, page, lang)
                    val list = p.recipes.filter { CommunityRecipes.siteId(it.id) !in store.ownSiteIds && it.id !in seen }
                    count(list.filter { it.id !in (accountFavorites ?: emptySet()) })
                    store.save(list)
                    list.mapTo(seen) { it.id }
                    if (p.newestStamp > newest) newest = p.newestStamp
                    page = p.nextPage
                }
                if (full) {
                    val keep = HashSet(seen)
                    accountFavorites?.let { keep += it }
                    removed = (before - keep - store.communityFavorites()).size
                    store.removeCommunityExcept(keep)
                    store.fullMs = now()
                }
                // La prochaine fois, seulement ce qui a changé depuis la plus récente reçue.
                store.since = newest
                store.lang = lang
            } catch (e: ToqueSite.SiteException) {
                problems += e.message.orEmpty()
            } catch (e: IOException) {
                if (problems.none { it == tr("site injoignable") }) problems += tr("site injoignable")
            }
        }

        val parts = ArrayList<String>()
        if (added > 0) parts += tr("%d recettes de la communauté ajoutées", added)
        if (updated > 0) parts += tr("%d mises à jour", updated)
        if (removed > 0) parts += tr("%d retirées", removed)
        val message = (if (parts.isEmpty()) tr("toqueapp.org : rien de nouveau") else "toqueapp.org : " + parts.joinToString(", ")) +
            (if (problems.isEmpty()) "" else " (" + problems.joinToString(", ") + ")")
        return Result(problems.isEmpty(), added, updated, removed, message)
    }

    /**
     * Envoie au site les favoris changés sur le robot. Vrai si tout est parti (ou rien à envoyer).
     * Une recette retirée du site (404) est oubliée. Lève SiteException 401 si le robot est délié.
     */
    fun pushFavorites(): Boolean {
        val token = store.token
        if (token.isBlank()) return true
        var all = true
        for ((id, on) in store.pendingFavorites) {
            try {
                site.setFavorite(token, id, on)
            } catch (e: ToqueSite.SiteException) {
                if (e.status == 401) throw e
                if (e.status != 404) {
                    all = false
                    continue
                }
            } catch (e: IOException) {
                all = false
                break
            }
            store.pendingFavorites = store.pendingFavorites - id
        }
        return all
    }

    companion object {
        const val WEEK_MS = 7 * 24 * 3600_000L
    }
}
