package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobSpec
import app.toque.core.machine.Programs
import app.toque.core.machine.Snapshot
import app.toque.ui.HoldButton
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/** Programmes automatiques : pétrir, vapeur, rissoler (ceux de l'appli d'origine), nettoyage (+ Turbo). */
class ProgramsScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Programmes")

    private class Prog(
        val icon: Icon.Kind, val name: String, val text: String,
        var seconds: Int, val min: Int, val max: Int, val step: Int,
        val make: (Long) -> JobSpec,
    ) {
        lateinit var value: TextView
        lateinit var unit: TextView
        lateinit var card: LinearLayout
        lateinit var start: View
    }

    private val progs = listOf(
        Prog(Icon.Kind.KNEAD, JobKind.KNEAD.label, tr("Pâtes à pain, pizza, brioche. Alterne sens inverse et normal à vitesse 4."),
            45, 30, 120, 5) { Programs.knead(it) },
        Prog(Icon.Kind.STEAM, JobKind.STEAM.label, tr("Mettez au moins 500 g d'eau dans le bol. 120 °C, la lame reste immobile."),
            1200, 300, 3600, 60) { Programs.steam(it) },
        Prog(Icon.Kind.FLAME, JobKind.TOAST.label, tr("Dorer oignons, lardons, viandes à 130 °C, avec un brassage doux en sens inverse."),
            420, 180, 840, 30) { Programs.toast(it) },
        Prog(Icon.Kind.DROP, tr("Nettoyage"), tr("1 L d'eau chaude et une goutte de liquide vaisselle. La vitesse monte de 4 à 10."),
            90, 60, 180, 15) { Programs.clean(it) },
    )
    private val cleaning = progs.size - 1
    private var selected = 0
    private lateinit var turbo: HoldButton

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(8), Ui.u(24), Ui.u(20))
        val row = Ui.horizontal(c, Gravity.TOP)
        for ((i, p) in progs.withIndex()) {
            val card = Ui.vertical(c)
            card.setPadding(Ui.u(22), Ui.u(20), Ui.u(22), Ui.u(20))
            p.card = card
            val badge = FrameLayout(c)
            badge.background = Ui.round(Ui.ACCENT_DIM, 28f)
            badge.addView(Icon(c, p.icon, Ui.ACCENT), Ui.flp(Ui.u(32), Ui.u(32), Gravity.CENTER))
            card.addView(badge, LinearLayout.LayoutParams(Ui.u(56), Ui.u(56)))
            card.addView(Ui.ellipsize(Ui.text(c, p.name, 26f, Ui.TEXT, Ui.MEDIUM)), Ui.lp(t = 14))
            card.addView(Ui.text(c, p.text, 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }, Ui.lp(t = 6))
            if (i == cleaning) rinseRecipe()?.let { r ->
                // La recette de rinçage du catalogue Lidl, quand elle est synchronisée.
                card.addView(Ui.button(c, tr("Recette Lidl"), Ui.Style.GHOST, Icon.Kind.BOOK, height = 46, textSize = 16f) {
                    a.app.recipes.get(r)?.let { a.push(RecipeScreen(a, it)) }
                }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 8))
            }
            card.addView(Ui.spacer(c, weight = 1f), LinearLayout.LayoutParams(1, 0, 1f))
            val dur = Ui.horizontal(c)
            dur.addView(Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.MINUS, height = 56) { selected = i; bump(p, -1) }, Ui.lp(Ui.u(56), Ui.WRAP))
            // Le nombre en grand sur une ligne, l'unité en dessous : les cartes sont étroites à 4 par ligne.
            val gauge = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
            p.value = Ui.fitText(c, 38f, 24f, Ui.TEXT, Ui.LIGHT).apply { gravity = Gravity.CENTER }
            gauge.addView(p.value, Ui.lp(Ui.MATCH, Ui.u(45)))
            p.unit = Ui.text(c, "", 14f, Ui.TEXT2).apply { gravity = Gravity.CENTER }
            gauge.addView(p.unit, Ui.lp(Ui.MATCH, Ui.WRAP, t = 1))
            dur.addView(gauge, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
            dur.addView(Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 56) { selected = i; bump(p, 1) }, Ui.lp(Ui.u(56), Ui.WRAP))
            card.addView(dur, Ui.lp(t = 10))
            p.start = Ui.button(c, tr("Démarrer"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 66) {
                selected = i
                a.startJob(p.make(p.seconds * 1000L))
            }
            card.addView(p.start, Ui.lp(t = 14))
            card.isClickable = true
            card.setOnClickListener { selected = i; refresh() }
            row.addView(card, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (i < progs.size - 1) rightMargin = Ui.u(16) })
        }
        root.addView(row, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))

        val bottom = Ui.horizontal(c)
        bottom.addView(Ui.text(c, tr("Turbo : impulsions à vitesse maximale, sans chauffe, 3 s maximum par appui."), 17f, Ui.TEXT2),
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        turbo = HoldButton(c, tr("Maintenir pour Turbo"), onDown = {
            a.machine.turboDown(app.toque.core.machine.Accessory.BLADE) { v -> if (!v.ok) a.refusal(v) }
        }, onUp = { a.machine.turboUp() })
        bottom.addView(turbo, Ui.lp(Ui.WRAP, Ui.u(64)))
        root.addView(bottom, Ui.lp(t = 16))
        refresh()
        return root
    }

    private fun rinseRecipe(): String? =
        a.app.recipes.index.entries.firstOrNull { Programs.isRinseRecipe(it.title) }?.id

    private fun bump(p: Prog, dir: Int) {
        p.seconds = (p.seconds + dir * p.step).coerceIn(p.min, p.max)
        refresh()
    }

    private fun refresh() {
        for ((i, p) in progs.withIndex()) {
            val s = p.seconds
            p.value.text = when {
                s < 60 -> s.toString()
                s % 60 == 0 -> (s / 60).toString()
                else -> String.format("%d:%02d", s / 60, s % 60)
            }
            p.unit.text = if (s < 60) "s" else "min"
            p.card.background = Ui.round(Ui.SURFACE, 26f, if (i == selected) Ui.ACCENT else Ui.STROKE, if (i == selected) 2.5f else 1f)
        }
    }

    override fun onSnapshot(s: Snapshot) {
        for (p in progs) Ui.setButtonEnabled(p.start, !s.busy)
        turbo.isEnabled = !s.busy || s.turboActive
        turbo.alpha = if (turbo.isEnabled) 1f else 0.35f
    }

    override fun onKnob(delta: Int): Boolean {
        bump(progs[selected], delta)
        return true
    }

    override fun onKnobPress(): Boolean {
        if (!a.snapshot.busy) a.startJob(progs[selected].make(progs[selected].seconds * 1000L))
        return true
    }
}
