package app.toque.ui

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import app.toque.core.i18n.tr
import app.toque.core.update.Release
import app.toque.ui.screens.CookScreen
import app.toque.update.Updater

/**
 * Prévient quand une nouvelle version de Toque est publiée sur GitHub.
 *
 * Vérifie au démarrage (une fois le Wi-Fi monté) et à chaque sortie de veille, au plus toutes
 * les 6 heures. La fenêtre ne s'ouvre jamais pendant un programme, une recette guidée, une pesée
 * ou une autre fenêtre : elle attend un moment calme. « Plus tard » ne repropose pas la même
 * version avant le lendemain ; la tuile Réglages la signale en attendant.
 */
class UpdateAlert(private val a: MainActivity) {
    private val main = Handler(Looper.getMainLooper())
    private var checking = false
    private var lastAttemptMs = 0L
    private var waiting: Release? = null

    /** Version plus récente trouvée (null si à jour ou pas encore vérifié). */
    var available: Release? = null
        private set

    /** Appelé quand [available] change (l'accueil met à jour sa tuile). */
    var onChange: (() -> Unit)? = null

    private val retry = Runnable { waiting?.let { offer(it) } }

    /** Démarrage de Toque : on laisse le temps au Wi-Fi de se connecter. */
    fun onStart() {
        main.removeCallbacks(startCheck)
        main.postDelayed(startCheck, START_DELAY_MS)
    }

    fun onStop() {
        main.removeCallbacks(startCheck)
        main.removeCallbacks(retry)
    }

    /** Sortie de veille : petit délai pour que le geste qui réveille ne touche pas la fenêtre. */
    fun onWake() {
        main.postDelayed({ check() }, 1_200)
    }

    private val startCheck = Runnable { check() }

    private fun check() {
        if (!a.prefs.updateAlerts) return
        available?.let { offer(it); return }
        val now = System.currentTimeMillis()
        if (checking) return
        if (now - a.prefs.lastUpdateCheckMs in 0 until CHECK_EVERY_MS) return
        // Hors ligne : on ne réessaie pas à chaque réveil.
        if (now - lastAttemptMs in 0 until RETRY_OFFLINE_MS) return
        checking = true
        lastAttemptMs = now
        Thread {
            try {
                val u = Updater(a)
                val r = u.latest()
                a.prefs.lastUpdateCheckMs = System.currentTimeMillis()
                if (u.isNewer(r) && r.apkUrl != null) main.post { found(r) }
            } catch (_: Updater.Failure) {
                // Pas de réseau ou GitHub indisponible : rien à signaler, on réessaiera.
            } catch (e: Exception) {
                a.app.log.crash("maj", e)
            } finally {
                main.post { checking = false }
            }
        }.start()
    }

    private fun found(r: Release) {
        available = r
        onChange?.invoke()
        offer(r)
    }

    /** Version vue dans les réglages : la tuile et l'alerte la connaissent aussi. */
    fun known(r: Release, newer: Boolean) {
        if (newer) { available = r; onChange?.invoke() }
        else if (available != null) { available = null; onChange?.invoke() }
    }

    private fun offer(r: Release) {
        main.removeCallbacks(retry)
        waiting = null
        if (!a.prefs.updateAlerts) return
        val now = System.currentTimeMillis()
        if (a.prefs.updateAskedVersion == r.version && now - a.prefs.updateAskedMs in 0 until ASK_AGAIN_MS) return
        if (!calm()) {
            waiting = r
            main.postDelayed(retry, 60_000)
            return
        }
        a.prefs.updateAskedVersion = r.version
        a.prefs.updateAskedMs = now
        UpdateFlow.offer(a, r)
    }

    /** Rien en cours : pas de programme, pas de recette guidée, pas de fenêtre, pas en veille. */
    private fun calm(): Boolean {
        val s = a.current
        return !a.snapshot.busy && !a.asleep && !a.overlayShown && s !is CookScreen && s?.keepAwake != true
    }

    companion object {
        const val START_DELAY_MS = 45_000L
        const val CHECK_EVERY_MS = 6 * 3_600_000L
        const val RETRY_OFFLINE_MS = 15 * 60_000L
        const val ASK_AGAIN_MS = 20 * 3_600_000L
    }
}

/** Fenêtre « Version X disponible », puis téléchargement et installation avec leur progression. */
object UpdateFlow {
    fun notes(r: Release?): String {
        val text = r?.notes?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(4)?.joinToString("\n") ?: ""
        return if (text.length > 280) text.substring(0, 280) + "…" else text
    }

    /** [onFailed] : message d'erreur lisible, déjà affiché en bas de l'écran. */
    fun offer(a: MainActivity, r: Release, onFailed: (String) -> Unit = {}) {
        val n = notes(r)
        a.confirm(
            tr("Version %s disponible", r.version),
            (if (n.isEmpty()) "" else n + "\n\n") +
                tr("Toque va la télécharger depuis GitHub, l'installer puis redémarrer (environ une minute). " +
                    "Le moteur reste arrêté pendant l'opération."),
            tr("Installer"), tr("Plus tard"), icon = Icon.Kind.BELL,
        ) { install(a, r, onFailed) }
    }

    private fun install(a: MainActivity, r: Release, onFailed: (String) -> Unit) {
        if (a.snapshot.busy) {
            a.toast(tr("Arrêtez d'abord le programme en cours"), Ui.WARN)
            return
        }
        a.machine.suspendPort { v ->
            if (!v.ok) { a.refusal(v); return@suspendPort }
            val card = Ui.card(a, 28f, Ui.SURFACE2)
            card.setPadding(Ui.u(32), Ui.u(28), Ui.u(32), Ui.u(30))
            val head = Ui.horizontal(a)
            head.addView(Icon(a, Icon.Kind.DOWNLOAD, Ui.ACCENT), LinearLayout.LayoutParams(Ui.u(32), Ui.u(32)).apply { rightMargin = Ui.u(14) })
            head.addView(Ui.text(a, tr("Mise à jour vers la version %s", r.version), 24f, Ui.TEXT, Ui.MEDIUM))
            card.addView(head)
            val text = Ui.text(a, tr("Téléchargement… %d %%", 0), 19f, Ui.TEXT2)
            card.addView(text, Ui.lp(t = 18))
            val bar = ProgressLine(a)
            card.addView(bar, Ui.lp(Ui.MATCH, Ui.u(8), t = 14))
            a.showOverlay(card, 620, dismissOnScrim = false)

            val main = Handler(Looper.getMainLooper())
            var done = false
            val fail = { message: String ->
                if (!done) {
                    done = true
                    a.closeOverlay()
                    a.machine.resumePort()
                    a.toast(message, Ui.WARN)
                    onFailed(message)
                }
            }
            Updater.onInstallError = { msg -> fail(msg) }
            Thread {
                val u = Updater(a)
                try {
                    val file = u.download(r) { p -> main.post { text.text = tr("Téléchargement… %d %%", p); bar.progress = p / 100f } }
                    main.post {
                        text.text = tr("Installation… Toque va redémarrer.\nSi Android demande une confirmation, touchez « Installer ».")
                        text.gravity = Gravity.START
                        bar.progress = 1f
                    }
                    u.install(file)
                } catch (e: Updater.Failure) {
                    main.post { fail(e.message ?: tr("Erreur")) }
                } catch (e: Exception) {
                    a.app.log.crash("maj", e)
                    main.post { fail(tr("Erreur inattendue : %s", e.message)) }
                }
            }.start()
        }
    }
}
