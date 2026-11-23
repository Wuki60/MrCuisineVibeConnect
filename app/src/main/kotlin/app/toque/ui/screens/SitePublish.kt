package app.toque.ui.screens

import android.widget.LinearLayout
import app.toque.core.i18n.tr
import app.toque.core.recipes.Recipe
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Ui

/** Une recette perso et toqueapp.org (compte relié) : la publier, la garder sur le compte, ou sur ce robot seulement. */
object SitePublish {

    /** Après l'enregistrement d'une recette perso : mise à jour si elle est déjà en ligne, sinon on demande (une fois). */
    fun afterSave(a: MainActivity, r: Recipe) {
        val site = a.app.site
        if (!a.prefs.siteLinked) return
        if (site.isOnline(r.id)) {
            site.updateIfOnline(r) { error -> if (error != null) a.toast(tr("Pas mise à jour sur toqueapp.org : %s", error), Ui.WARN) }
        } else if (r.id !in a.prefs.siteKeepLocal) {
            // Une fois envoyée, la fiche ouverte montre où elle en est sur le site.
            ask(a, r) {
                val page = a.current as? RecipeScreen
                if (page != null && page.recipe.id == r.id) a.replace(RecipeScreen(a, a.app.recipes.get(r.id) ?: r))
            }
        }
    }

    /** Le choix : pour tous, sur le compte seulement (privée), ou sur ce robot seulement. */
    fun ask(a: MainActivity, r: Recipe, onDone: () -> Unit = {}) {
        val site = a.app.site
        val online = site.isOnline(r.id)
        val card = Ui.card(a, 28f, Ui.SURFACE2)
        card.setPadding(Ui.u(32), Ui.u(28), Ui.u(32), Ui.u(26))
        val head = Ui.horizontal(a)
        head.addView(Icon(a, Icon.Kind.GLOBE, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(34), Ui.u(34)).apply { rightMargin = Ui.u(14) })
        head.addView(Ui.text(a, tr("Mettre la recette sur toqueapp.org ?"), 24f, Ui.TEXT, Ui.MEDIUM))
        card.addView(head)
        card.addView(Ui.text(a, tr("Publiée, « %s » est visible par tous sur le site et arrive sur les robots des autres. Sur votre compte seulement, elle reste privée.", r.title),
            18f, Ui.TEXT2).apply { setLineSpacing(0f, 1.2f) }, Ui.lp(t = 14, b = 20))
        fun send(public: Boolean) {
            a.closeOverlay()
            a.toast(tr("Envoi à toqueapp.org…"))
            site.publish(r, public) { error ->
                if (error == null) a.toast(if (public) tr("Recette publiée sur toqueapp.org") else tr("Recette gardée sur votre compte toqueapp.org"), Ui.OK)
                else a.toast(tr("Pas envoyée : %s", error), Ui.WARN)
                onDone()
            }
        }
        card.addView(Ui.button(a, tr("Publier pour tous"), Ui.Style.PRIMARY, Icon.Kind.UPLOAD, height = 60) { send(true) },
            Ui.lp(Ui.MATCH, Ui.WRAP, b = 10))
        card.addView(Ui.button(a, tr("Sur mon compte seulement (privée)"), Ui.Style.SECONDARY, height = 60) { send(false) },
            Ui.lp(Ui.MATCH, Ui.WRAP, b = 10))
        card.addView(Ui.button(a, if (online) tr("Annuler") else tr("Garder sur ce robot"), Ui.Style.GHOST, height = 56) {
            // Plus demandé pour cette recette ; on peut changer d'avis depuis sa fiche.
            if (!online) a.prefs.siteKeepLocal = a.prefs.siteKeepLocal + r.id
            a.closeOverlay()
        }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 4))
        a.showOverlay(card, 680, dismissOnScrim = false)
    }

    /** Ligne de la fiche d'une recette perso : où elle en est sur le site (null sans compte relié). */
    fun status(a: MainActivity, r: Recipe): String? {
        if (!a.prefs.siteLinked) return null
        val site = a.app.site
        return when {
            !site.isOnline(r.id) -> tr("Pas sur toqueapp.org · toucher pour la mettre en ligne")
            site.isPublic(r.id) -> tr("Publiée sur toqueapp.org · toucher pour changer")
            else -> tr("Sur votre compte toqueapp.org (privée) · toucher pour changer")
        }
    }
}
