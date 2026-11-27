package app.toque.ui.screens

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.core.i18n.tr
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobSpec
import app.toque.core.machine.Limits
import app.toque.core.machine.Programs
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.SmartProgram
import app.toque.core.robot.Feature
import app.toque.core.robot.RobotProfiles
import app.toque.ui.HoldButton
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/**
 * Programmes automatiques : pétrir, vapeur, rissoler (ceux de l'appli d'origine), nettoyage (+ Turbo).
 * Seulement ceux du robot choisi : le Smart a en plus ses propres programmes (mixer, sous-vide…).
 * [openOnCleaning] : ouvert sur le nettoyage (fin de recette) ; le programme part comme d'habitude, par « Démarrer » ou la molette.
 */
class ProgramsScreen(a: MainActivity, openOnCleaning: Boolean = false) : Screen(a) {
    override val title = tr("Programmes")

    private class Prog(
        val feature: Feature, val icon: Icon.Kind, val name: String, val text: String,
        var seconds: Int, val min: Int, val max: Int, val step: Int,
        /** Température réglable (programmes du Smart à température exacte) : valeur, bornes ; 0 sinon. */
        var celsius: Int = 0, val minC: Int = 0, val maxC: Int = 0,
        val make: (Long, Int) -> JobSpec,
    ) {
        lateinit var value: TextView
        lateinit var unit: TextView
        var temp: TextView? = null
        lateinit var card: LinearLayout
        lateinit var start: View
    }

    private fun smart(f: Feature, icon: Icon.Kind, text: String, p: SmartProgram, default: Int, step: Int,
                      make: (Long, Int) -> JobSpec = { ms, c -> Programs.smartProgram(p, ms, f.label, celsius = c.takeIf { it > 0 }) }) =
        Prog(f, icon, f.label, text, default, p.minS, p.maxS, step,
            celsius = if (p.minCelsius < p.maxCelsius) p.celsius else 0, minC = p.minCelsius, maxC = p.maxCelsius, make = make)

    private val all = listOf(
        Prog(Feature.KNEAD, Icon.Kind.KNEAD, JobKind.KNEAD.label, tr("Pâtes à pain, pizza, brioche. Alterne sens inverse et normal à vitesse 4."),
            45, 30, 120, 5) { ms, _ -> Programs.knead(ms) },
        Prog(Feature.STEAM, Icon.Kind.STEAM, JobKind.STEAM.label, tr("Mettez au moins 500 g d'eau dans le bol. 120 °C, la lame reste immobile."),
            1200, 300, 3600, 60) { ms, _ -> Programs.steam(ms) },
        Prog(Feature.TOAST, Icon.Kind.FLAME, JobKind.TOAST.label, tr("Dorer oignons, lardons, viandes à 130 °C, avec un brassage doux en sens inverse."),
            420, 180, 840, 30) { ms, _ -> Programs.toast(ms) },
        Prog(Feature.YOGURT, Icon.Kind.TIMER, Feature.YOGURT.label,
            tr("Lait tiède ensemencé, couvercle fermé. Garde %d °C sans remuer : la lame reste immobile.", Programs.YOGURT_C),
            8 * 3600, 6 * 3600, Limits.MAX_MS_FERMENT.toInt() / 1000, 15 * 60) { ms, _ -> Programs.yogurt(ms) },
        Prog(Feature.CLEANING, Icon.Kind.DROP, tr("Nettoyage"), tr("1 L d'eau chaude et une goutte de liquide vaisselle. La vitesse monte de 4 à 10."),
            90, 60, 180, 15) { ms, _ -> Programs.clean(ms) },
        // Programmes du Monsieur Cuisine Smart, avec les trames de son appli d'origine.
        smart(Feature.MIXER, Icon.Kind.SPEED, tr("Purées, soupes mixées, compotes. Vitesse 8, sans chauffe."), SmartProgram.MIXER, 30, 10),
        smart(Feature.SMOOTHIE, Icon.Kind.DROP, tr("Fruits et glace pilée. Vitesse 10, sans chauffe."), SmartProgram.SMOOTHIE, 60, 10),
        smart(Feature.EGGS, Icon.Kind.TIMER, tr("Œufs dans le panier vapeur, eau dans le bol. La durée fait la cuisson."), SmartProgram.EGGS, 8 * 60, 60),
        smart(Feature.SOUS_VIDE, Icon.Kind.THERMO, tr("Bain d'eau à température précise, sachets fermés. Brassage doux."), SmartProgram.SOUS_VIDE, 3600, 15 * 60),
        smart(Feature.SLOW_COOK, Icon.Kind.POT, tr("Cuisson lente : brasse 30 s toutes les 2 min 30 pendant 10 min, puis sans remuer."),
            SmartProgram.MIJOTER_BRASSE, 2 * 3600, 15 * 60) { ms, c -> Programs.smartMijoter(ms, Feature.SLOW_COOK.label, c.takeIf { it > 0 }) },
        smart(Feature.FERMENT, Icon.Kind.TIMER, tr("Yaourts, pâtes levées : brasse 5 min, puis garde la température."),
            SmartProgram.FERMENTER_BRASSE, 8 * 3600, 30 * 60) { ms, c -> Programs.smartFermenter(ms, Feature.FERMENT.label, c.takeIf { it > 0 }) },
        smart(Feature.RICE, Icon.Kind.POT, tr("Riz à 100 °C en remuant doucement en sens inverse."), SmartProgram.RICE, 30 * 60, 60),
        smart(Feature.KETTLE, Icon.Kind.THERMO, tr("Chauffe l'eau à 100 °C en remuant doucement."), SmartProgram.KETTLE, 10 * 60, 60),
        Prog(Feature.PREWASH, Icon.Kind.DROP, Feature.PREWASH.label, tr("Rinçage rapide du bol à l'eau : 1 min 30 à 37 °C."),
            90, 90, 90, 1) { _, _ -> Programs.smartPrelavage(Feature.PREWASH.label) },
        Prog(Feature.LONG_CLEAN, Icon.Kind.DROP, Feature.LONG_CLEAN.label, tr("Nettoyage de l'appli d'origine : chauffe l'eau à 40 °C, puis brasse fort."),
            app.toque.core.protocol.NETTOYAGE_MAX_S, app.toque.core.protocol.NETTOYAGE_MAX_S, app.toque.core.protocol.NETTOYAGE_MAX_S, 1) { _, _ ->
            Programs.smartNettoyageLong(Feature.LONG_CLEAN.label)
        },
    )
    private val isConnect = RobotProfiles.active == RobotProfiles.MC_CONNECT
    private val available = run {
        val emulate = isConnect && a.prefs.emulateSmartPrograms
        all.filter {
            RobotProfiles.active.capabilities.has(it.feature) ||
                (emulate && RobotProfiles.MC_SMART.capabilities.has(it.feature))
        }
    }
    private val progs = run {
        val hidden = a.prefs.hiddenPrograms
        available.filter { it.feature.name !in hidden }.ifEmpty { available }
    }.onEach { p ->
        if (p.maxC > p.minC) {
            val temps = allowedTemps(p)
            if (temps.isNotEmpty() && p.celsius !in temps) {
                p.celsius = temps.minByOrNull { kotlin.math.abs(it - p.celsius) } ?: temps.first()
            }
        }
    }
    private val cleaning = progs.indexOfFirst { it.feature == Feature.CLEANING }
    private var selected = if (openOnCleaning && cleaning >= 0) cleaning else 0
    private lateinit var turbo: HoldButton

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(8), Ui.u(24), Ui.u(20))
        // 4 cartes par ligne. Le Connect en a 4 (toute la hauteur) ; le Smart plus, en lignes qui défilent.
        val many = progs.size > 4
        val rows = Ui.vertical(c)
        var row = Ui.horizontal(c, Gravity.TOP)
        // Une ligne avec un réglage de température (sous-vide, mijoter, fermenter) est plus haute.
        fun rowHeight(first: Int) = if (progs.subList(first, minOf(first + 4, progs.size)).any { it.maxC > it.minC }) 456 else 400
        for ((i, p) in progs.withIndex()) {
            if (i > 0 && i % 4 == 0) {
                rows.addView(row, Ui.lp(Ui.MATCH, Ui.u(rowHeight(i - 4)), b = 16))
                row = Ui.horizontal(c, Gravity.TOP)
            }
            val card = Ui.vertical(c)
            card.setPadding(Ui.u(22), Ui.u(20), Ui.u(22), Ui.u(20))
            p.card = card
            val badge = FrameLayout(c)
            badge.background = Ui.round(Ui.ACCENT_DIM, 28f)
            badge.addView(Icon(c, p.icon, Ui.ACCENT_INK), Ui.flp(Ui.u(32), Ui.u(32), Gravity.CENTER))
            card.addView(badge, LinearLayout.LayoutParams(Ui.u(56), Ui.u(56)))
            card.addView(Ui.ellipsize(Ui.text(c, p.name, 26f, Ui.TEXT, Ui.MEDIUM)), Ui.lp(t = 14))
            card.addView(Ui.text(c, p.text, 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }, Ui.lp(t = 6))
            if (i == cleaning && cleaning >= 0) rinseRecipe()?.let { r ->
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
            if (p.maxC > p.minC) {
                // Température exacte (sous-vide, mijoter, fermenter), dans les bornes de l'appli d'origine.
                val t = Ui.horizontal(c, Gravity.CENTER_VERTICAL)
                t.addView(Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.MINUS, height = 48) { selected = i; bumpTemp(p, -1) }
                    .apply { setPadding(0, 0, 0, 0) }, Ui.lp(Ui.u(48), Ui.WRAP))
                p.temp = Ui.text(c, "", 20f, Ui.TEXT).apply { gravity = Gravity.CENTER }
                t.addView(p.temp, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
                t.addView(Ui.button(c, "", Ui.Style.SECONDARY, Icon.Kind.PLUS, height = 48) { selected = i; bumpTemp(p, 1) }
                    .apply { setPadding(0, 0, 0, 0) }, Ui.lp(Ui.u(48), Ui.WRAP))
                card.addView(t, Ui.lp(t = 8))
            }
            p.start = Ui.button(c, tr("Démarrer"), Ui.Style.PRIMARY, Icon.Kind.PLAY, height = 66) {
                selected = i
                a.startJob(buildSpec(p))
            }
            card.addView(p.start, Ui.lp(t = 14))
            card.isClickable = true
            card.setOnClickListener { selected = i; refresh() }
            row.addView(card, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (i % 4 != 3 && (many || i < progs.size - 1)) rightMargin = Ui.u(16) })
        }
        // Dernière ligne incomplète : des places vides gardent la largeur des cartes.
        if (many && progs.size % 4 != 0) for (k in progs.size % 4 until 4)
            row.addView(View(c), LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { if (k < 3) rightMargin = Ui.u(16) })
        if (many) {
            rows.addView(row, Ui.lp(Ui.MATCH, Ui.u(rowHeight(progs.size - 1 - (progs.size - 1) % 4))))
            root.addView(Ui.scroll(c, rows), LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        } else {
            root.addView(row, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        }

        val bottom = Ui.horizontal(c)
        bottom.addView(Ui.text(c, tr("Turbo : impulsions à vitesse maximale, sans chauffe, 3 s maximum par appui."), 17f, Ui.TEXT2),
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        turbo = HoldButton(c, tr("Maintenir pour Turbo"), onDown = {
            a.turboDown(app.toque.core.machine.Accessory.BLADE)
        }, onUp = { a.machine.turboUp() })
        bottom.addView(turbo, Ui.lp(Ui.WRAP, Ui.u(64)))
        root.addView(bottom, Ui.lp(t = 16))
        refresh()
        return root
    }

    private fun rinseRecipe(): String? =
        a.app.recipes.index.entries.firstOrNull { Programs.isRinseRecipe(it.title) }?.id

    private fun allowedTemps(p: Prog): List<Int> =
        RobotProfiles.active.capabilities.temperaturesC.filter { it in p.minC..p.maxC }

    private fun bump(p: Prog, dir: Int) {
        p.seconds = (p.seconds + dir * p.step).coerceIn(p.min, p.max)
        refresh()
    }

    private fun bumpTemp(p: Prog, dir: Int) {
        val temps = allowedTemps(p)
        if (temps.isNotEmpty()) {
            val idx = temps.indexOf(p.celsius).let { if (it >= 0) it else 0 }
            p.celsius = temps[(idx + dir).coerceIn(0, temps.lastIndex)]
        } else {
            p.celsius = (p.celsius + dir).coerceIn(p.minC, p.maxC)
        }
        refresh()
    }

    private fun buildSpec(p: Prog): JobSpec {
        val raw = p.make(p.seconds * 1000L, p.celsius)
        return if (isConnect) Programs.forConnect(raw) else raw
    }

    private fun refresh() {
        for ((i, p) in progs.withIndex()) {
            val s = p.seconds
            p.value.text = when {
                s < 60 -> s.toString()
                s >= 3600 -> String.format("%d:%02d", s / 3600, s / 60 % 60)
                s % 60 == 0 -> (s / 60).toString()
                else -> String.format("%d:%02d", s / 60, s % 60)
            }
            p.unit.text = if (s < 60) "s" else if (s >= 3600) "h" else "min"
            p.temp?.text = "${p.celsius} °C"
            p.card.background = Ui.round(Ui.SURFACE, 26f, if (i == selected) Ui.ACCENT_INK else Ui.STROKE, if (i == selected) 2.5f else 1f)
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
        if (!a.snapshot.busy) progs[selected].let { a.startJob(buildSpec(it)) }
        return true
    }

    companion object {
        private val ALL_FEATURES = listOf(
            Feature.KNEAD, Feature.STEAM, Feature.TOAST, Feature.YOGURT, Feature.CLEANING,
            Feature.MIXER, Feature.SMOOTHIE, Feature.EGGS, Feature.SOUS_VIDE, Feature.SLOW_COOK,
            Feature.FERMENT, Feature.RICE, Feature.KETTLE, Feature.PREWASH, Feature.LONG_CLEAN,
        )

        fun availableFeatures(prefs: app.toque.Prefs): List<Feature> {
            val active = RobotProfiles.active
            val emulate = active == RobotProfiles.MC_CONNECT && prefs.emulateSmartPrograms
            return ALL_FEATURES.filter {
                active.capabilities.has(it) || (emulate && RobotProfiles.MC_SMART.capabilities.has(it))
            }
        }
    }
}
