package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Standby
import app.toque.ui.Ui

/**
 * Veille de l'écran, en deux blocs. « Au repos » : après combien de temps sans geste l'écran passe au noir avec
 * l'heure. « Pendant un programme » (un yaourt dure 8 h, l'écran n'a pas à rester allumé en plein) : après
 * combien de temps il baisse sa luminosité, et à quel point ; puis après combien de temps il passe au noir, avec
 * l'étape et le temps restant à la place de l'heure.
 *
 * Un réglage, une ligne : son nom et ce qu'on verra, en quelques mots ; ses choix à droite. Le même réglage
 * porte le même nom dans les deux blocs.
 */
class StandbyScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Veille de l'écran")
    private lateinit var col: LinearLayout
    /** Les pastilles de la page telle qu'elle est dessinée. */
    private val allPills = ArrayList<TextView>()
    /** Fin de l'aperçu d'une luminosité : l'écran reprend la sienne. */
    private val endPreview = Runnable { brightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }

    override fun build(): View {
        col = Ui.vertical(c)
        col.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(20))
        render()
        return Ui.scroll(c, col)
    }

    override fun onHide() {
        view.removeCallbacks(endPreview)
        endPreview.run()
    }

    private fun render() {
        col.removeAllViews()
        allPills.clear()
        val p = a.prefs
        fun after(m: Int) = if (m == 0) tr("Jamais") else tr("%d min", m)

        val rest = block(tr("Au repos"))
        rest.addView(row(tr("Mise en veille après"), tr("Écran noir avec l'heure."), pills(Standby.REST_AFTER, p.standbyMinutes, ::after) {
            p.standbyMinutes = it
            render()
        }))
        col.addView(rest)

        val job = block(tr("Pendant un programme"))
        job.addView(row(tr("Baisser la luminosité après"), tr("L'écran reste affiché, moins lumineux."), pills(Standby.DIM_AFTER, p.jobDimMinutes, ::after) {
            p.jobDimMinutes = it
            render()
        }))
        // À quel point : seulement quand la luminosité baisse.
        if (p.jobDimMinutes > 0) {
            job.addView(Ui.divider(c))
            job.addView(row(tr("Luminosité réduite"), null, pills(Standby.DIM_LEVELS, p.jobDimBrightness, { if (it == 0) tr("Minimum") else tr("%d %%", it) }) {
                p.jobDimBrightness = it
                render()
                preview(it)
            }))
        }
        job.addView(Ui.divider(c))
        job.addView(row(tr("Mise en veille après"), tr("Écran noir avec l'étape et le temps restant."), pills(Standby.STANDBY_AFTER, p.jobStandbyMinutes, ::after) {
            p.jobStandbyMinutes = it
            render()
        }))
        col.addView(job, Ui.lp(t = 12))

        col.addView(Ui.text(c, Ui.guillemets(tr("Délais comptés depuis votre dernier geste. L'écran se rallume au moindre geste, à la fin d'un programme ou pour une alerte.")),
            16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }, Ui.lp(t = 12))
        alignPills()
    }

    /** Un bloc de réglages, encadré, avec son titre : « Au repos », « Pendant un programme ». */
    private fun block(title: String): LinearLayout {
        val card = Ui.card(c, 24f)
        card.setPadding(Ui.u(20), Ui.u(14), Ui.u(20), Ui.u(8))
        card.contentDescription = title
        card.addView(Ui.text(c, title, 21f, Ui.TEXT, Ui.MEDIUM), Ui.lp(b = 2))
        return card
    }

    /** Un réglage : son nom et, dessous, ce qu'on verra ([about]) ; ses choix à droite. */
    private fun row(label: String, about: String?, choices: View): LinearLayout {
        val row = Ui.horizontal(c)
        row.minimumHeight = Ui.u(68)
        row.setPadding(0, Ui.u(6), 0, Ui.u(6))
        val left = Ui.vertical(c)
        left.addView(Ui.text(c, label, 18f, Ui.TEXT))
        if (about != null) left.addView(Ui.text(c, Ui.guillemets(about), 16f, Ui.TEXT2), Ui.lp(t = 2))
        row.addView(left, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(12) })
        row.addView(choices)
        return row
    }

    /** Les choix d'un réglage en pastilles, [selected] remplie ; un appui choisit. */
    private fun pills(values: List<Int>, selected: Int, name: (Int) -> String, onPick: (Int) -> Unit): View {
        val row = Ui.horizontal(c)
        // Une valeur enregistrée qui n'est plus proposée : la plus proche est montrée choisie.
        val shown = values.minByOrNull { Math.abs(it - selected) } ?: selected
        for ((i, value) in values.withIndex()) {
            val on = value == shown
            val pill = Ui.text(c, name(value), 18f, if (on) Ui.ON_ACCENT else Ui.TEXT, Ui.MEDIUM).apply {
                gravity = Gravity.CENTER
                setPadding(Ui.u(10), 0, Ui.u(10), 0)
                background = Ui.pressable(if (on) Ui.round(Ui.ACCENT, 26f) else Ui.round(Ui.SURFACE2, 26f, Ui.STROKE, 1f), 26f)
                isSelected = on
                isClickable = true
                setOnClickListener { onPick(value) }
            }
            allPills += pill
            row.addView(pill, LinearLayout.LayoutParams(Ui.WRAP, Ui.u(52)).apply { if (i > 0) leftMargin = Ui.u(8) })
        }
        return row
    }

    /**
     * Toutes les pastilles de la page à la largeur de la plus large : alignées à droite, leurs colonnes tombent
     * les unes sous les autres d'une ligne à l'autre, dans toutes les langues.
     */
    private fun alignPills() {
        val widest = allPills.maxOf { Math.ceil(it.paint.measureText(it.text.toString()).toDouble()).toInt() + it.paddingLeft + it.paddingRight }
            .coerceAtLeast(Ui.u(64))
        for (pill in allPills) pill.layoutParams.width = widest
    }

    /** Montre un instant la luminosité choisie : à l'œil, un pourcentage ne dit rien. */
    private fun preview(percent: Int) {
        view.removeCallbacks(endPreview)
        brightness(Standby.level(percent))
        view.postDelayed(endPreview, PREVIEW_MS)
    }

    private fun brightness(value: Float) {
        val lp = a.window.attributes
        if (lp.screenBrightness == value) return
        lp.screenBrightness = value
        a.window.attributes = lp
    }

    companion object {
        const val PREVIEW_MS = 1_500L
    }
}
