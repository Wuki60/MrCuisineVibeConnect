package app.toque.ui.screens

import android.content.Intent
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.toque.Prefs
import app.toque.core.machine.RootShell
import app.toque.core.machine.Snapshot
import app.toque.recipes.LidlSync
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui

class SettingsScreen(a: MainActivity) : Screen(a) {
    override val title = "Réglages"
    private lateinit var modeRow: LinearLayout
    private lateinit var twinBox: LinearLayout
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var syncText: TextView
    private lateinit var bowlText: TextView
    private lateinit var linkText: TextView
    private var mode = a.prefs.mode
    private var syncing = false

    override fun build(): View {
        val col = Ui.vertical(c)
        col.setPadding(Ui.u(24), Ui.u(6), Ui.u(24), Ui.u(24))
        val columns = Ui.horizontal(c, Gravity.TOP)
        val left = Ui.vertical(c)
        val right = Ui.vertical(c)
        columns.addView(left, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.u(18) })
        columns.addView(right, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        col.addView(columns)

        // ---- Machine
        val machine = section(left, "Machine", Icon.Kind.POT)
        modeRow = Ui.horizontal(c)
        machine.addView(modeRow, Ui.lp(t = 4))
        twinBox = Ui.horizontal(c)
        host = field(a.prefs.twinHost, "Hôte")
        port = field(a.prefs.twinPort.toString(), "Port").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        twinBox.addView(host, LinearLayout.LayoutParams(0, Ui.u(58), 1f).apply { rightMargin = Ui.u(10) })
        twinBox.addView(port, LinearLayout.LayoutParams(Ui.u(120), Ui.u(58)))
        machine.addView(twinBox, Ui.lp(t = 12))
        linkText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        machine.addView(linkText, Ui.lp(t = 12))
        machine.addView(Ui.button(c, "Appliquer", Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 58) { applyMode() }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 14))
        renderModes()

        // ---- Sécurité
        val safety = section(left, "Sécurité", Icon.Kind.WARNING)
        safety.addView(toggle("Exiger la détection du bol", a.prefs.requireBowl) { on ->
            a.prefs.requireBowl = on
            a.machine.reconfigure { a.refusal(it) }
        })
        bowlText = Ui.text(c, "", 15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }
        safety.addView(bowlText, Ui.lp(t = 6))
        safety.addView(toggle("Sons", a.prefs.sound) { a.prefs.sound = it }, Ui.lp(t = 10))

        // ---- Recettes Lidl
        val lidl = section(right, "Recettes Lidl", Icon.Kind.SYNC)
        lidl.addView(Ui.text(c, "L'appli d'origine du robot reste seule à dialoguer avec les serveurs Lidl ; Toque copie ensuite ses recettes.",
            16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) })
        syncText = Ui.text(c, "", 16f, Ui.TEXT)
        lidl.addView(syncText, Ui.lp(t = 10))
        val b1 = Ui.horizontal(c)
        b1.addView(Ui.button(c, "Synchroniser", Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 58) { sync(full = true) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        b1.addView(Ui.button(c, "Importer", Ui.Style.SECONDARY, height = 58) { sync(full = false) })
        lidl.addView(b1, Ui.lp(t = 12))
        lidl.addView(Ui.button(c, "Ouvrir l'appli d'origine", Ui.Style.GHOST, Icon.Kind.LINK, height = 56) { a.openVendorApp() }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        lidl.addView(toggle("Synchro automatique chaque nuit", a.prefs.autoSync) { a.prefs.autoSync = it }, Ui.lp(t = 10))

        // ---- Outils
        val tools = section(right, "Outils", Icon.Kind.SETTINGS)
        tools.addView(link("Diagnostic de la base moteur") { a.push(DiagnosticsScreen(a)) })
        tools.addView(link("Bouton rotatif") { a.push(KnobScreen(a)) })
        tools.addView(link("Journal") { a.push(LogScreen(a)) })
        tools.addView(link("Paramètres Android") {
            try { a.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        })
        tools.addView(link("Revenir à l'appli d'origine") { restoreVendor() })
        val version = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (_: Exception) { "?" }
        tools.addView(Ui.text(c, "Toque $version · protocole d'après le Monsieur Cuisine Developer SDK (licence MIT). " +
            "Projet indépendant, non affilié à Lidl ni à SilverCrest.", 14f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 12))

        return Ui.scroll(c, col)
    }

    private fun section(parent: LinearLayout, title: String, icon: Icon.Kind): LinearLayout {
        val card = Ui.card(c, 24f)
        val head = Ui.horizontal(c)
        head.addView(Icon(c, icon, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(24), Ui.u(24)).apply { rightMargin = Ui.u(10) })
        head.addView(Ui.text(c, title, 21f, Ui.TEXT, Ui.MEDIUM))
        card.addView(head, Ui.lp(b = 10))
        parent.addView(card, Ui.lp(b = 16))
        return card
    }

    private fun field(value: String, hint: String) = EditText(c).apply {
        setText(value)
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.uf(19f))
        setTextColor(Ui.TEXT)
        setHintTextColor(Ui.TEXT3)
        setSingleLine()
        background = Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f)
        setPadding(Ui.u(14), 0, Ui.u(14), 0)
    }

    private fun toggle(label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = Ui.horizontal(c)
        row.addView(Ui.text(c, label, 18f, Ui.TEXT), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        var state = initial
        val sw = Ui.text(c, "", 16f, Ui.TEXT, Ui.MEDIUM).apply { gravity = Gravity.CENTER }
        fun paint() {
            sw.text = if (state) "Oui" else "Non"
            sw.setTextColor(if (state) 0xFF1A0E05.toInt() else Ui.TEXT2)
            sw.background = if (state) Ui.round(Ui.ACCENT, 24f) else Ui.round(Ui.SURFACE3, 24f)
        }
        paint()
        sw.setOnClickListener { state = !state; paint(); onChange(state) }
        row.addView(sw, LinearLayout.LayoutParams(Ui.u(84), Ui.u(46)))
        return row
    }

    private fun link(label: String, onClick: () -> Unit): LinearLayout {
        val row = Ui.horizontal(c)
        row.setPadding(0, Ui.u(14), 0, Ui.u(14))
        row.addView(Ui.text(c, label, 18f, Ui.TEXT), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        row.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT3), LinearLayout.LayoutParams(Ui.u(20), Ui.u(20)))
        row.background = Ui.pressable(Ui.round(0, 8f), 8f)
        row.isClickable = true
        row.setOnClickListener { onClick() }
        return row
    }

    private fun renderModes() {
        modeRow.removeAllViews()
        for (m in Prefs.Mode.values()) {
            modeRow.addView(Ui.toggleChip(c, when (m) {
                Prefs.Mode.DEMO -> "Démo"
                Prefs.Mode.TWIN -> "Jumeau (PC)"
                Prefs.Mode.HARDWARE -> "Machine réelle"
            }, mode == m) { mode = m; renderModes() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        }
        twinBox.visibility = if (mode == Prefs.Mode.TWIN) View.VISIBLE else View.GONE
        linkText.text = when (mode) {
            Prefs.Mode.DEMO -> "Base moteur simulée : pour essayer l'appli sur n'importe quel appareil Android."
            Prefs.Mode.TWIN -> "Jumeau numérique du SDK lancé sur un PC (outils/jumeau.py). Avec « adb reverse tcp:8765 tcp:8765 », gardez 127.0.0.1."
            Prefs.Mode.HARDWARE -> "Pilote le vrai moteur par le port série. Exige le root. L'appli d'origine est coupée du moteur (réversible)."
        }
    }

    private fun applyMode() {
        if (a.snapshot.busy) {
            a.toast("Arrêtez d'abord le programme en cours", Ui.WARN)
            return
        }
        if (mode == Prefs.Mode.HARDWARE && a.prefs.mode != Prefs.Mode.HARDWARE) {
            a.confirm(
                "Piloter la vraie machine",
                "Toque va prendre le contrôle du moteur et de la chauffe à la place de l'appli d'origine.\n\n" +
                    "Premier essai : bol vide, couvercle fermé, à portée de l'interrupteur principal. " +
                    "Si quoi que ce soit semble anormal, coupez l'interrupteur.",
                "Continuer", danger = true,
            ) { prepareHardware() }
            return
        }
        save()
    }

    private fun save() {
        a.prefs.mode = mode
        a.prefs.twinHost = host.text.toString().trim().ifEmpty { "127.0.0.1" }
        a.prefs.twinPort = port.text.toString().trim().toIntOrNull() ?: 8765
        a.machine.reconfigure { v -> if (v.ok) a.toast("Mode : ${mode.label}", Ui.OK) else a.refusal(v) }
        a.refreshTitle()
    }

    private fun prepareHardware() {
        a.toast("Vérifications…")
        Thread {
            val problems = ArrayList<String>()
            if (!RootShell.available()) problems += "accès root refusé"
            val sync = LidlSync(a, a.prefs, a.app.recipes, a.app.log)
            if (problems.isEmpty() && sync.installed()) {
                if (sync.components().hardwareService != null && !sync.setVendorHardware(false))
                    problems += "impossible de couper l'appli d'origine du moteur"
                sync.forceStop()
            }
            view.post {
                if (problems.isNotEmpty()) {
                    a.confirm("Machine réelle indisponible", problems.joinToString("\n") { "• $it" }, "OK", "Fermer") {}
                } else save()
            }
        }.start()
    }

    private fun restoreVendor() {
        a.confirm(
            "Revenir à l'appli d'origine",
            "Toque libère le moteur, rend l'accès à l'appli Monsieur Cuisine d'origine et passe en mode Démo. " +
                "Choisissez ensuite l'appli d'origine comme écran d'accueil.",
            "Revenir",
        ) {
            a.prefs.mode = Prefs.Mode.DEMO
            a.machine.reconfigure {
                Thread {
                    val sync = LidlSync(a, a.prefs, a.app.recipes, a.app.log)
                    sync.setVendorHardware(true)
                    val launch = sync.components().launch
                    view.post {
                        if (launch != null) a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else a.toast("Appli d'origine introuvable", Ui.WARN)
                    }
                }.start()
            }
        }
    }

    private fun sync(full: Boolean) {
        if (syncing) return
        if (a.prefs.mode != Prefs.Mode.HARDWARE) {
            a.toast("Disponible une fois Toque installé sur le robot (mode Machine réelle)", Ui.WARN)
            return
        }
        syncing = true
        syncText.text = "Synchronisation en cours… (quelques minutes)"
        Thread {
            val s = LidlSync(a, a.prefs, a.app.recipes, a.app.log)
            val r = if (full) s.sync { msg -> view.post { syncText.text = msg } } else s.importNow { msg -> view.post { syncText.text = msg } }
            view.post {
                syncing = false
                refreshSync()
                a.toast(r.message, if (r.ok) Ui.OK else Ui.WARN)
            }
        }.start()
    }

    private fun refreshSync() {
        val last = a.prefs.lastSyncResult
        syncText.text = (if (last.isEmpty()) "Jamais synchronisé" else "Dernière synchro le $last") +
            "\n${a.app.recipes.count} recettes dans Toque"
    }

    override fun onShow() {
        mode = a.prefs.mode
        renderModes()
        if (!syncing) refreshSync()
    }

    override fun onSnapshot(s: Snapshot) {
        bowlText.text = if (s.hasSample) "Capteur du bol en ce moment : " + (if (s.bowlDetected) "bol détecté" else "bol NON détecté") +
            ". Vérifiez qu'il change quand vous retirez le bol avant d'activer cette option." else "Base non connectée"
    }
}
