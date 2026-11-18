package app.toque.ui.screens

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.Snapshot
import app.toque.core.recipes.JsonRecipeSource
import app.toque.recipes.JsonSourceSync
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/** Manage public catalogue URLs; no default external source or executable plugins. */
class RecipeSourcesScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Sources de recettes JSON")
    private lateinit var list: LinearLayout
    private val statusViews = HashMap<String, TextView>()

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(24))
        root.addView(Ui.text(c, tr("Ajoutez des catalogues publics HTTPS au format toque-recettes-1. Ils s’ajoutent aux recettes Lidl et restent disponibles hors connexion."), 17f, Ui.TEXT2))
        root.addView(Ui.button(c, tr("Ajouter une source"), Ui.Style.PRIMARY, height = 54) { edit(null) }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 14))
        list = Ui.vertical(c)
        root.addView(list, Ui.lp(t = 14))
        render()
        return Ui.scroll(c, root)
    }

    private fun render() {
        list.removeAllViews()
        statusViews.clear()
        val sources = a.prefs.jsonRecipeSources
        if (sources.isEmpty()) list.addView(Ui.text(c, tr("Aucune source JSON configurée."), 17f, Ui.TEXT2))
        for (source in sources) {
            val card = Ui.card(c)
            card.addView(Ui.text(c, source.name, 22f, Ui.TEXT, Ui.MEDIUM))
            card.addView(Ui.text(c, source.url, 15f, Ui.TEXT2), Ui.lp(t = 6))
            val status = Ui.text(c, "", 16f, Ui.TEXT2)
            statusViews[source.id] = status
            card.addView(status, Ui.lp(t = 10))
            val automatic = Switch(c).apply {
                text = tr("Synchroniser au démarrage et chaque jour")
                setTextColor(Ui.TEXT)
                textSize = 16f
                isChecked = source.autoSync
                setOnCheckedChangeListener { _, checked ->
                    a.prefs.jsonRecipeSources = a.prefs.jsonRecipeSources.map { if (it.id == source.id) it.copy(autoSync = checked) else it }
                }
            }
            card.addView(automatic, Ui.lp(t = 10))
            val row = Ui.horizontal(c)
            row.addView(Ui.button(c, tr("Synchroniser"), Ui.Style.PRIMARY, height = 52) {
                val current = a.prefs.jsonRecipeSources.firstOrNull { it.id == source.id } ?: return@button
                JsonSourceSync.sync(a.app, current) { ok, message ->
                    a.toast(message, if (ok) Ui.OK else Ui.WARN)
                    refreshStatuses()
                }
                refreshStatuses()
            }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
            row.addView(Ui.button(c, tr("Modifier"), Ui.Style.SECONDARY, height = 52) { edit(source) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
            row.addView(Ui.button(c, tr("Retirer"), Ui.Style.GHOST, height = 52) { remove(source) })
            card.addView(row, Ui.lp(t = 12))
            list.addView(card, Ui.lp(t = 12))
        }
        refreshStatuses()
    }

    private fun refreshStatuses() {
        for ((id, view) in statusViews) {
            val last = a.prefs.jsonSourceLastSync(id)
            val message = if (JsonSourceSync.activeSourceId == id) tr("Synchronisation en cours… (quelques minutes)")
                else a.prefs.jsonSourceResult(id).ifEmpty { tr("Jamais synchronisé") }
            val date = if (last > 0) "\n" + tr("Dernière réussite : %s", SimpleDateFormat("dd/MM/yyyy HH:mm", I18n.locale).format(Date(last))) else ""
            view.text = message + date
        }
    }

    private fun edit(existing: JsonRecipeSource?) {
        if (existing == null && a.prefs.jsonRecipeSources.size >= 20) {
            a.toast(tr("20 sources maximum."), Ui.WARN)
            return
        }
        val form = Ui.vertical(c)
        form.setPadding(Ui.u(24), Ui.u(10), Ui.u(24), 0)
        val name = EditText(c).apply { hint = tr("Nom de la source"); setText(existing?.name.orEmpty()); setSingleLine(true) }
        val url = EditText(c).apply {
            hint = tr("Adresse du catalogue (HTTPS)")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.url.orEmpty())
            maxLines = 3
        }
        form.addView(name)
        form.addView(url)
        val dialog = AlertDialog.Builder(c).setTitle(if (existing == null) tr("Ajouter une source") else tr("Modifier la source"))
            .setView(form).setNegativeButton(tr("Annuler"), null).setPositiveButton(tr("Enregistrer"), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val current = a.prefs.jsonRecipeSources
                    val source = JsonRecipeSource(existing?.id ?: UUID.randomUUID().toString(), name.text.toString().trim(),
                        url.text.toString().trim(), current.firstOrNull { it.id == existing?.id }?.autoSync ?: true)
                    require(current.none { it.id != source.id && it.url == source.url }) { tr("Cette adresse est déjà configurée.") }
                    a.prefs.jsonRecipeSources = if (existing == null) current + source else current.map { if (it.id == source.id) source else it }
                    if (existing?.url != source.url) a.prefs.clearJsonSourceStatus(source.id)
                    dialog.dismiss()
                    render()
                } catch (e: IllegalArgumentException) {
                    a.toast(e.message ?: tr("Source invalide."), Ui.WARN)
                }
            }
        }
        dialog.show()
    }

    private fun remove(source: JsonRecipeSource) {
        if (!JsonSourceSync.canChangeCatalog(a.app)) {
            a.toast(tr("Terminez la recette et attendez que le robot soit au repos."), Ui.WARN)
            return
        }
        a.confirm(tr("Retirer la source"), tr("Les recettes de cette source seront retirées, sauf vos favoris qui resteront disponibles hors connexion."), tr("Retirer"), danger = true) {
            // The confirmation may have been left open while a program started.
            if (!JsonSourceSync.canChangeCatalog(a.app)) {
                a.toast(tr("Terminez la recette et attendez que le robot soit au repos."), Ui.WARN)
            } else {
                try {
                    a.app.recipes.removeJsonSource(source)
                    a.prefs.jsonRecipeSources = a.prefs.jsonRecipeSources.filterNot { it.id == source.id }
                    a.prefs.clearJsonSourceStatus(source.id)
                    render()
                } catch (e: Exception) { a.toast(tr("Source non retirée : %s", e.message ?: e.toString()), Ui.WARN) }
            }
        }
    }

    override fun onSnapshot(s: Snapshot) = refreshStatuses()
}
