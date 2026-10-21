package app.toque.ui.screens

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import app.toque.Prefs
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.machine.Snapshot
import app.toque.core.update.Release
import app.toque.recipes.LidlSync
import app.toque.ui.Icon
import app.toque.ui.MainActivity
import app.toque.ui.Screen
import app.toque.ui.Ui
import app.toque.update.Updater

class SettingsScreen(a: MainActivity) : Screen(a) {
    override val title = tr("Réglages")
    private lateinit var modeRow: LinearLayout
    private lateinit var twinBox: LinearLayout
    private lateinit var host: EditText
    private lateinit var port: EditText
    private lateinit var syncText: TextView
    private lateinit var bowlText: TextView
    private lateinit var linkText: TextView
    private var mode = a.prefs.mode
    private var syncing = false

    private enum class Upd { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, ERROR }
    private val updater by lazy { Updater(a) }
    private lateinit var updText: TextView
    private lateinit var updButtons: LinearLayout
    private var upd = Upd.IDLE
    private var release: Release? = null
    private var updMessage = ""
    private var updPercent = 0

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
        val machine = section(left, tr("Machine"), Icon.Kind.POT)
        modeRow = Ui.horizontal(c)
        machine.addView(modeRow, Ui.lp(t = 4))
        twinBox = Ui.horizontal(c)
        host = field(a.prefs.twinHost, tr("Hôte"))
        port = field(a.prefs.twinPort.toString(), "Port").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        twinBox.addView(host, LinearLayout.LayoutParams(0, Ui.u(58), 1f).apply { rightMargin = Ui.u(10) })
        twinBox.addView(port, LinearLayout.LayoutParams(Ui.u(120), Ui.u(58)))
        machine.addView(twinBox, Ui.lp(t = 12))
        linkText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        machine.addView(linkText, Ui.lp(t = 12))
        machine.addView(Ui.button(c, tr("Appliquer"), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 58) { applyMode() }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 14))
        renderModes()

        // ---- Sécurité
        val safety = section(left, tr("Sécurité"), Icon.Kind.WARNING)
        safety.addView(toggle(tr("Exiger la détection du bol"), a.prefs.requireBowl) { on ->
            a.prefs.requireBowl = on
            a.machine.reconfigure { a.refusal(it) }
        })
        bowlText = Ui.text(c, "", 15f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }
        safety.addView(bowlText, Ui.lp(t = 6))
        safety.addView(toggle(tr("Sons"), a.prefs.sound) { a.prefs.sound = it }, Ui.lp(t = 10))

        // ---- Langue (titre bilingue : trouvable quelle que soit la langue affichée)
        val language = section(right, "Langue · Language", Icon.Kind.INFO)
        val options = listOf(I18n.AUTO to tr("Automatique (langue du système)")) + I18n.languages.map { it.code to it.name }
        language.addView(dropdown(options, a.prefs.language) { code ->
            a.prefs.language = code
            a.applyLanguage()
        }, Ui.lp(Ui.MATCH, Ui.u(58), t = 4))

        // ---- Recettes Lidl
        val lidl = section(right, tr("Recettes Lidl"), Icon.Kind.SYNC)
        lidl.addView(Ui.text(c, tr("L'appli d'origine du robot reste seule à dialoguer avec les serveurs Lidl ; Toque copie ensuite ses recettes."),
            16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) })
        syncText = Ui.text(c, "", 16f, Ui.TEXT)
        lidl.addView(syncText, Ui.lp(t = 10))
        val b1 = Ui.horizontal(c)
        b1.addView(Ui.button(c, tr("Synchroniser"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 58) { sync(full = true) }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
        b1.addView(Ui.button(c, tr("Importer"), Ui.Style.SECONDARY, height = 58) { sync(full = false) })
        lidl.addView(b1, Ui.lp(t = 12))
        lidl.addView(Ui.button(c, tr("Ouvrir l'appli d'origine"), Ui.Style.GHOST, Icon.Kind.LINK, height = 56) { a.openVendorApp() }, Ui.lp(Ui.WRAP, Ui.WRAP, t = 10))
        lidl.addView(toggle(tr("Synchro automatique chaque nuit"), a.prefs.autoSync) { a.prefs.autoSync = it }, Ui.lp(t = 10))

        // ---- Mises à jour
        val updates = section(left, tr("Mises à jour"), Icon.Kind.INFO)
        updText = Ui.text(c, "", 16f, Ui.TEXT2).apply { setLineSpacing(0f, 1.15f) }
        updates.addView(updText)
        updButtons = Ui.horizontal(c)
        updates.addView(updButtons, Ui.lp(t = 12))
        Updater.onInstallError = { msg -> updateFailed(msg) }
        renderUpdate()

        // ---- Outils
        val tools = section(right, tr("Outils"), Icon.Kind.SETTINGS)
        tools.addView(link(tr("Diagnostic de la base moteur")) { a.push(DiagnosticsScreen(a)) })
        tools.addView(link(tr("Bouton rotatif")) { a.push(KnobScreen(a)) })
        tools.addView(link(tr("Journal")) { a.push(LogScreen(a)) })
        tools.addView(link(tr("Paramètres Android")) {
            try { a.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        })
        tools.addView(link(tr("Revenir à l'appli d'origine")) { restoreVendor() })
        val version = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (_: Exception) { "?" }
        tools.addView(Ui.text(c, tr("Toque %s · protocole d'après le Monsieur Cuisine Developer SDK (licence MIT). " +
            "Projet indépendant, non affilié à Lidl ni à SilverCrest.", version), 14f, Ui.TEXT3).apply { setLineSpacing(0f, 1.1f) }, Ui.lp(t = 12))

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
            sw.text = if (state) tr("Oui") else tr("Non")
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

    /** Menu déroulant : la valeur choisie, et la liste qui s'ouvre en dessous. */
    private fun dropdown(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): View {
        val box = Ui.horizontal(c)
        box.setPadding(Ui.u(16), 0, Ui.u(14), 0)
        box.background = Ui.pressable(Ui.round(Ui.SURFACE2, 16f, Ui.STROKE, 1f), 16f)
        val label = options.firstOrNull { it.first == selected }?.second ?: options.first().second
        box.addView(Ui.text(c, label, 19f, Ui.TEXT), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        box.addView(Icon(c, Icon.Kind.CHEVRON, Ui.TEXT2).apply { rotation = 90f }, LinearLayout.LayoutParams(Ui.u(22), Ui.u(22)))
        box.isClickable = true
        box.setOnClickListener {
            val list = Ui.vertical(c)
            list.setPadding(0, Ui.u(6), 0, Ui.u(6))
            val scroll = ScrollView(c).apply {
                addView(list)
                background = Ui.round(Ui.SURFACE3, 16f, Ui.STROKE, 1f)
            }
            val popup = PopupWindow(scroll, box.width, ViewGroup.LayoutParams.WRAP_CONTENT, false)
            for ((code, name) in options) {
                val row = Ui.text(c, name, 19f, if (code == selected) Ui.ACCENT else Ui.TEXT)
                row.setPadding(Ui.u(16), Ui.u(14), Ui.u(16), Ui.u(14))
                row.background = Ui.pressable(Ui.round(0, 0f), 0f)
                row.isClickable = true
                row.setOnClickListener {
                    popup.dismiss()
                    if (code != selected) onPick(code)
                }
                list.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
            }
            popup.setBackgroundDrawable(ColorDrawable(0))
            popup.isOutsideTouchable = true
            popup.elevation = Ui.uf(8f)
            popup.showAsDropDown(box, 0, Ui.u(6))
        }
        return box
    }

    private fun renderModes() {
        modeRow.removeAllViews()
        for (m in Prefs.Mode.values()) {
            modeRow.addView(Ui.toggleChip(c, when (m) {
                Prefs.Mode.DEMO -> tr("Démo")
                Prefs.Mode.TWIN -> tr("Jumeau (PC)")
                Prefs.Mode.HARDWARE -> tr("Machine réelle")
            }, mode == m) { mode = m; renderModes() }, Ui.lp(Ui.WRAP, Ui.WRAP, r = 8))
        }
        twinBox.visibility = if (mode == Prefs.Mode.TWIN) View.VISIBLE else View.GONE
        linkText.text = when (mode) {
            Prefs.Mode.DEMO -> tr("Base moteur simulée : pour essayer l'appli sur n'importe quel appareil Android.")
            Prefs.Mode.TWIN -> tr("Jumeau numérique du SDK lancé sur un PC (tools/twin.py). Avec « adb reverse tcp:8765 tcp:8765 », gardez 127.0.0.1.")
            Prefs.Mode.HARDWARE -> tr("Pilote le vrai moteur par le port série. Exige le root. L'appli d'origine est coupée du moteur (réversible).")
        }
    }

    private fun applyMode() {
        if (a.snapshot.busy) {
            a.toast(tr("Arrêtez d'abord le programme en cours"), Ui.WARN)
            return
        }
        if (mode == Prefs.Mode.HARDWARE && a.prefs.mode != Prefs.Mode.HARDWARE) {
            a.confirm(
                tr("Piloter la vraie machine"),
                tr("Toque va prendre le contrôle du moteur et de la chauffe à la place de l'appli d'origine.\n\n" +
                    "Premier essai : bol vide, couvercle fermé, à portée de l'interrupteur principal. " +
                    "Si quoi que ce soit semble anormal, coupez l'interrupteur."),
                tr("Continuer"), danger = true,
            ) { prepareHardware() }
            return
        }
        save()
    }

    private fun save() {
        a.prefs.mode = mode
        a.prefs.twinHost = host.text.toString().trim().ifEmpty { "127.0.0.1" }
        a.prefs.twinPort = port.text.toString().trim().toIntOrNull() ?: 8765
        a.machine.reconfigure { v -> if (v.ok) a.toast(tr("Mode : %s", mode.label), Ui.OK) else a.refusal(v) }
        a.refreshTitle()
    }

    private fun prepareHardware() {
        a.toast(tr("Vérifications…"))
        Thread {
            val problems = ArrayList<String>()
            if (!RootShell.available()) problems += tr("accès root refusé")
            val sync = LidlSync(a, a.prefs, a.app.recipes, a.app.log)
            if (problems.isEmpty() && sync.installed()) {
                if (sync.components().hardwareService != null && !sync.setVendorHardware(false))
                    problems += tr("impossible de couper l'appli d'origine du moteur")
                sync.forceStop()
            }
            view.post {
                if (problems.isNotEmpty()) {
                    a.confirm(tr("Machine réelle indisponible"), problems.joinToString("\n") { "• $it" }, "OK", tr("Fermer")) {}
                } else save()
            }
        }.start()
    }

    private fun restoreVendor() {
        a.confirm(
            tr("Revenir à l'appli d'origine"),
            tr("Toque libère le moteur, rend l'accès à l'appli Monsieur Cuisine d'origine et passe en mode Démo. " +
                "Choisissez ensuite l'appli d'origine comme écran d'accueil."),
            tr("Revenir"),
        ) {
            a.prefs.mode = Prefs.Mode.DEMO
            a.machine.reconfigure {
                Thread {
                    val sync = LidlSync(a, a.prefs, a.app.recipes, a.app.log)
                    sync.setVendorHardware(true)
                    val launch = sync.components().launch
                    view.post {
                        if (launch != null) a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        else a.toast(tr("Appli d'origine introuvable"), Ui.WARN)
                    }
                }.start()
            }
        }
    }

    private fun sync(full: Boolean) {
        if (syncing) return
        if (a.prefs.mode != Prefs.Mode.HARDWARE) {
            a.toast(tr("Disponible une fois Toque installé sur le robot (mode Machine réelle)"), Ui.WARN)
            return
        }
        syncing = true
        syncText.text = tr("Synchronisation en cours… (quelques minutes)")
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
        syncText.text = (if (last.isEmpty()) tr("Jamais synchronisé") else tr("Dernière synchro le %s", last)) +
            "\n" + tr("%d recettes dans Toque", a.app.recipes.count)
    }

    // ------------------------------------------------------------------ mises à jour

    private fun renderUpdate() {
        val r = release
        val installed = tr("Version installée : %s", updater.installedVersion)
        updText.setTextColor(if (upd == Upd.ERROR) Ui.WARN else if (upd == Upd.AVAILABLE) Ui.TEXT else Ui.TEXT2)
        updText.text = when (upd) {
            Upd.IDLE -> installed + "\n" + tr("Les nouvelles versions sont publiées sur GitHub.")
            Upd.CHECKING -> installed + "\n" + tr("Recherche sur GitHub…")
            Upd.UP_TO_DATE -> installed + "\n" + tr("Toque est à jour.")
            Upd.AVAILABLE -> installed + "\n" + tr("Nouvelle version disponible : %s", r?.version) + notes(r)
            Upd.DOWNLOADING -> tr("Téléchargement de la version %s… %d %%", r?.version, updPercent)
            Upd.INSTALLING -> tr("Installation de la version %s… Toque va redémarrer.\n" +
                "Si Android demande une confirmation, touchez « Installer ».", r?.version)
            Upd.ERROR -> installed + "\n" + updMessage
        }
        updButtons.removeAllViews()
        when (upd) {
            Upd.IDLE, Upd.UP_TO_DATE, Upd.ERROR ->
                updButtons.addView(Ui.button(c, tr("Rechercher une mise à jour"), Ui.Style.PRIMARY, Icon.Kind.SYNC, height = 58) { checkUpdate() })
            Upd.AVAILABLE -> {
                updButtons.addView(Ui.button(c, tr("Installer la version %s", r?.version), Ui.Style.PRIMARY, Icon.Kind.CHECK, height = 58) { installUpdate() },
                    Ui.lp(Ui.WRAP, Ui.WRAP, r = 10))
                updButtons.addView(Ui.button(c, tr("Plus tard"), Ui.Style.GHOST, height = 58) { upd = Upd.IDLE; renderUpdate() })
            }
            else -> {}
        }
    }

    private fun notes(r: Release?): String {
        val text = r?.notes?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(4)?.joinToString("\n") ?: ""
        if (text.isEmpty()) return ""
        return "\n\n" + if (text.length > 280) text.substring(0, 280) + "…" else text
    }

    private fun checkUpdate() {
        if (upd == Upd.CHECKING || upd == Upd.DOWNLOADING || upd == Upd.INSTALLING) return
        upd = Upd.CHECKING
        renderUpdate()
        Thread {
            try {
                val r = updater.latest()
                view.post {
                    release = r
                    upd = if (updater.isNewer(r)) Upd.AVAILABLE else Upd.UP_TO_DATE
                    renderUpdate()
                    if (upd == Upd.AVAILABLE) installUpdate()
                }
            } catch (e: Updater.Failure) {
                view.post { upd = Upd.ERROR; updMessage = e.message ?: tr("Erreur"); renderUpdate() }
            } catch (e: Exception) {
                a.app.log.crash("maj", e)
                view.post { upd = Upd.ERROR; updMessage = tr("Erreur inattendue : %s", e.message); renderUpdate() }
            }
        }.start()
    }

    /** Propose l'installation dans une fenêtre (toujours visible, même si la carte est hors écran). */
    private fun installUpdate() {
        val r = release ?: return
        if (a.snapshot.busy) {
            a.toast(tr("Arrêtez d'abord le programme en cours"), Ui.WARN)
            return
        }
        a.confirm(
            tr("Version %s disponible", r.version),
            notes(r).trim().let { if (it.isEmpty()) "" else it + "\n\n" } +
                tr("Toque va la télécharger depuis GitHub, l'installer puis redémarrer (environ une minute). " +
                    "Le moteur reste arrêté pendant l'opération."),
            tr("Installer"), tr("Plus tard"), icon = Icon.Kind.SYNC,
        ) {
            a.machine.suspendPort { v ->
                if (!v.ok) { a.refusal(v); return@suspendPort }
                upd = Upd.DOWNLOADING
                updPercent = 0
                renderUpdate()
                Thread {
                    try {
                        val file = updater.download(r) { p -> view.post { updPercent = p; renderUpdate() } }
                        view.post { upd = Upd.INSTALLING; renderUpdate() }
                        updater.install(file)
                    } catch (e: Updater.Failure) {
                        view.post { updateFailed(e.message ?: tr("Erreur")) }
                    } catch (e: Exception) {
                        a.app.log.crash("maj", e)
                        view.post { updateFailed(tr("Erreur inattendue : %s", e.message)) }
                    }
                }.start()
            }
        }
    }

    private fun updateFailed(message: String) {
        a.machine.resumePort()
        upd = Upd.ERROR
        updMessage = message
        renderUpdate()
        a.toast(message, Ui.WARN)
    }

    override fun onShow() {
        mode = a.prefs.mode
        renderModes()
        if (!syncing) refreshSync()
    }

    override fun onSnapshot(s: Snapshot) {
        bowlText.text = if (s.hasSample) tr("Capteur du bol en ce moment : %s. Vérifiez qu'il change quand vous retirez le bol avant d'activer cette option.",
            if (s.bowlDetected) tr("bol détecté") else tr("bol NON détecté")) else tr("Base non connectée")
    }
}
