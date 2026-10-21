package app.toque.ui.screens

import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.toque.Prefs
import app.toque.core.i18n.tr
import app.toque.core.machine.Snapshot
import app.toque.core.protocol.Mcc
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

/** Valeurs brutes de la base moteur, trames émises/reçues, et commandes de la base simulée. */
class DiagnosticsScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Diagnostic")
    private lateinit var table: TextView
    private lateinit var frames: TextView

    override fun build(): View {
        val root = Ui.horizontal(c, Gravity.TOP)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(20))
        val left = Ui.card(c, 22f)
        table = Ui.text(c, "", 16f, Ui.TEXT).apply { typeface = Typeface.MONOSPACE; setLineSpacing(0f, 1.2f) }
        left.addView(Ui.scroll(c, table), Ui.lp(Ui.MATCH, Ui.MATCH))
        root.addView(left, LinearLayout.LayoutParams(0, Ui.MATCH, 1f).apply { rightMargin = Ui.u(16) })
        val right = Ui.vertical(c)
        val fcard = Ui.card(c, 22f)
        fcard.addView(Ui.text(c, tr("TRAMES"), 15f, Ui.TEXT2, Ui.MEDIUM))
        frames = Ui.text(c, "", 15f, Ui.COOL).apply { typeface = Typeface.MONOSPACE; setLineSpacing(0f, 1.25f) }
        fcard.addView(frames, Ui.lp(t = 8))
        right.addView(fcard)
        if (a.prefs.mode == Prefs.Mode.DEMO) {
            val sim = Ui.card(c, 22f)
            sim.addView(Ui.text(c, tr("BASE SIMULÉE"), 15f, Ui.TEXT2, Ui.MEDIUM))
            val r1 = Ui.horizontal(c)
            r1.addView(Ui.button(c, tr("Ouvrir couvercle"), Ui.Style.SECONDARY, Icon.Kind.LID, height = 54, textSize = 16f) { a.machine.simulate(lid = true) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
            r1.addView(Ui.button(c, tr("Fermer"), Ui.Style.SECONDARY, height = 54, textSize = 16f) { a.machine.simulate(lid = false) })
            sim.addView(r1, Ui.lp(t = 10))
            val r2 = Ui.horizontal(c)
            r2.addView(Ui.button(c, tr("Erreur moteur"), Ui.Style.SECONDARY, Icon.Kind.WARNING, height = 54, textSize = 16f) { a.machine.simulate(error = 5) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
            r2.addView(Ui.button(c, tr("Effacer"), Ui.Style.SECONDARY, height = 54, textSize = 16f) { a.machine.simulate(error = 0) })
            sim.addView(r2, Ui.lp(t = 10))
            right.addView(sim, Ui.lp(t = 14))
        }
        root.addView(right, LinearLayout.LayoutParams(Ui.u(420), Ui.MATCH))
        return root
    }

    override fun onSnapshot(s: Snapshot) {
        fun yn(b: Boolean) = if (b) tr("oui") else tr("non")
        table.text = buildString {
            append(tr("Liaison").padEnd(17)).append(s.link.label).append('\n')
            append(tr("Transport").padEnd(17)).append(s.transportName.ifEmpty { "—" }).append(if (s.transportUp) " (" + tr("ouvert") + ")" else " (" + tr("fermé") + ")").append('\n')
            append(tr("Âge échantillon").padEnd(17)).append(if (s.sampleAgeMs < 0) "—" else "${s.sampleAgeMs} ms").append('\n')
            append(tr("Alerte").padEnd(17)).append(s.faultMessage.ifEmpty { tr("aucune") }).append('\n')
            append(tr("Moteur / chauffe").padEnd(17)).append(yn(s.motorOn)).append(" / ").append(yn(s.heaterOn)).append('\n')
            append(tr("Régime").padEnd(17)).append(s.rpm).append(" ").append(tr("tr/min (niveau %d)", s.speedLevel)).append("\n")
            append(tr("Température").padEnd(17)).append(s.actualTempC).append(" ").append(tr("°C (consigne %d °C)", s.targetTempC)).append("\n")
            append(tr("Poids").padEnd(17)).append(s.weightG).append(" g · ").append(s.scaleState).append(if (s.scaleStable) " · " + tr("stable") else "").append('\n')
            append(tr("Couvercle ouvert").padEnd(17)).append(yn(s.lidOpen)).append('\n')
            append(tr("Bol détecté").padEnd(17)).append(yn(s.bowlDetected)).append('\n')
            append(tr("Erreur base").padEnd(17)).append(if (s.boardErrorCode == 0) tr("aucune") else "${s.boardErrorCode} · ${Mcc.boardErrorMessage(s.boardErrorCode)}").append('\n')
            append(tr("Firmware base").padEnd(17)).append(s.firmware).append('\n')
            append(tr("Programme").padEnd(17)).append(s.jobLabel.ifEmpty { "—" }).append(' ').append(s.jobState).append('\n')
            append(tr("Commande").padEnd(17)).append(s.wireOperation).append(" v").append(s.wireSpeed).append(" t").append(s.wireTempLevel).append('\n')
            append(tr("Trames TX/RX").padEnd(17)).append(s.txFrames).append(" / ").append(s.rxFrames).append(" · ").append(tr("invalides %d", s.invalidFrames))
        }
        frames.text = "TX " + s.txHex.ifEmpty { "—" } + "\n\nRX " + s.rxHex.ifEmpty { "—" }
    }
}

/** Journal de l'appli (démarrages, synchros, alertes, plantages). */
class LogScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Journal")
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView

    override fun build(): View {
        val root = Ui.vertical(c)
        root.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(20))
        val card = Ui.card(c, 22f)
        text = Ui.text(c, "", 14f, Ui.TEXT2).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        scroll = Ui.scroll(c, text)
        card.addView(scroll, Ui.lp(Ui.MATCH, Ui.MATCH))
        root.addView(card, LinearLayout.LayoutParams(Ui.MATCH, 0, 1f))
        val row = Ui.horizontal(c)
        row.addView(Ui.button(c, tr("Rafraîchir"), Ui.Style.SECONDARY, Icon.Kind.SYNC, height = 56) { load() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        row.addView(Ui.button(c, tr("Effacer"), Ui.Style.GHOST, height = 56) { a.app.log.clear(); load() })
        root.addView(row, Ui.lp(t = 12))
        return root
    }

    private fun load() {
        text.text = a.app.log.read().ifEmpty { tr("Journal vide.") }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onShow() = load()
}

/** Réglage du bouton rotatif : on tourne, l'appli retient les codes reçus. */
class KnobScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Bouton rotatif")
    private lateinit var info: TextView
    private lateinit var last: TextView
    private var capture: String? = null

    override fun build(): View {
        val root = Ui.vertical(c, Gravity.CENTER_HORIZONTAL)
        root.setPadding(Ui.u(40), Ui.u(20), Ui.u(40), Ui.u(20))
        info = Ui.text(c, "", 20f, Ui.TEXT2).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) }
        root.addView(info, Ui.lp(Ui.MATCH, Ui.WRAP))
        last = Ui.text(c, "—", 60f, Ui.TEXT, Ui.THIN)
        root.addView(last, Ui.lp(Ui.WRAP, Ui.WRAP, t = 20))
        val row = Ui.horizontal(c, Gravity.CENTER)
        row.addView(Ui.button(c, tr("Tourner à gauche"), Ui.Style.SECONDARY, height = 60) { ask("left") }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        row.addView(Ui.button(c, tr("Tourner à droite"), Ui.Style.SECONDARY, height = 60) { ask("right") }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        row.addView(Ui.button(c, tr("Appuyer"), Ui.Style.SECONDARY, height = 60) { ask("press") }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        row.addView(Ui.button(c, tr("Par défaut"), Ui.Style.GHOST, height = 60) {
            a.prefs.knobLeft = -1; a.prefs.knobRight = -1; a.prefs.knobPress = -1
            refresh()
        })
        root.addView(row, Ui.lp(t = 30))
        refresh()
        return root
    }

    private fun ask(which: String) {
        capture = which
        info.text = tr("Faites maintenant l'action « %s » sur le bouton du robot…", when (which) {
            "left" -> tr("tourner à gauche")
            "right" -> tr("tourner à droite")
            else -> tr("appuyer")
        })
        a.knobListener = { e -> onKey(e) }
    }

    private fun onKey(e: KeyEvent) {
        last.text = KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")
        when (capture) {
            "left" -> a.prefs.knobLeft = e.keyCode
            "right" -> a.prefs.knobRight = e.keyCode
            "press" -> a.prefs.knobPress = e.keyCode
        }
        capture = null
        a.knobListener = null
        refresh()
    }

    private fun refresh() {
        fun name(k: Int, def: String) = if (k < 0) tr("%s (par défaut)", def) else KeyEvent.keyCodeToString(k).removePrefix("KEYCODE_")
        info.text = tr("Gauche : %s\nDroite : %s\nAppui : %s\n\nLes molettes (défilement) sont aussi reconnues automatiquement.",
            name(a.prefs.knobLeft, "DPAD_LEFT"), name(a.prefs.knobRight, "DPAD_RIGHT"), name(a.prefs.knobPress, "DPAD_CENTER"))
    }

    override fun onHide() {
        a.knobListener = null
    }

    override fun onKnob(delta: Int): Boolean {
        last.text = if (delta > 0) "→" else "←"
        return true
    }

    override fun onKnobPress(): Boolean {
        last.text = "●"
        return true
    }
}
