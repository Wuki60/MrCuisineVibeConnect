package app.toque.keyboard

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import app.toque.ToqueApp
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell

/**
 * Les claviers du robot : celui d'Android, toujours là, et ceux que son propriétaire a installés en plus.
 * Toque n'en installe aucun : il liste ceux qui sont là, en fait choisir un et ouvre leurs réglages.
 *
 * Choisir un clavier passe par root : Android 6 ne laisse pas une appli changer le clavier du système.
 * [use] est bloquant : à appeler hors du fil de l'interface.
 */
class Keyboards(private val context: Context) {
    class Failure(message: String) : Exception(message)

    /** Un clavier installé. [settings] : son écran de réglages, s'il en a un qu'on peut ouvrir. */
    class Entry(val id: String, val packageName: String, val name: String,
                val system: Boolean, val current: Boolean, val settings: Intent?)

    private val pm get() = context.packageManager
    private val log get() = ToqueApp.of(context).log

    /** Le clavier choisi dans Android (son identifiant « paquet/service »), vide si on ne sait pas. */
    fun currentId(): String =
        try { Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty() } catch (_: Exception) { "" }

    /** Les claviers installés, ceux du robot d'abord. */
    fun list(): List<Entry> {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val current = currentId()
        return imm.inputMethodList.orEmpty().map { info ->
            val flags = try { pm.getApplicationInfo(info.packageName, 0).flags } catch (_: Exception) { 0 }
            val opens = info.settingsActivity?.takeIf { it.isNotBlank() }?.let { Intent(Intent.ACTION_MAIN).setClassName(info.packageName, it) }
                ?.takeIf { pm.resolveActivity(it, 0)?.activityInfo?.exported == true }
                ?: pm.getLaunchIntentForPackage(info.packageName)
            Entry(info.id, info.packageName, info.loadLabel(pm).toString(),
                flags and ApplicationInfo.FLAG_SYSTEM != 0, info.id == current, opens)
        }.sortedBy { !it.system }
    }

    /** Fait de [id] le clavier du robot. */
    fun use(id: String) {
        if (!ID.matches(id)) throw Failure(tr("Ce clavier ne peut pas être choisi d'ici."))
        if (shell("id -u", 8_000).out.trim() != "0") throw Failure(tr("Cette action demande l'accès root (Magisk)."))
        val r = shell("ime enable '$id'; ime set '$id'", 20_000)
        // Android l'écrit dans ses réglages dès qu'il a changé de clavier : c'est là qu'on vérifie.
        val end = System.currentTimeMillis() + 3_000
        while (currentId() != id && System.currentTimeMillis() < end) Thread.sleep(150)
        if (currentId() != id) {
            log.write("clavier", "choix de $id refusé : ${last(r)}")
            throw Failure(tr("Le clavier n'a pas pu être changé : %s", last(r)))
        }
        log.write("clavier", "clavier du robot : $id")
    }

    private fun last(r: RootShell.Result): String =
        (r.out + "\n" + r.err).lines().lastOrNull { it.isNotBlank() }?.trim() ?: "code ${r.code}"

    companion object {
        /** Un identifiant de clavier (« paquet/service ») : rien d'autre ne part dans une commande root. */
        private val ID = Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")

        /** Les commandes root (les essais, sans root, la remplacent). */
        @Volatile var shell: (String, Long) -> RootShell.Result = { command, timeoutMs -> RootShell.run(command, timeoutMs) }
    }
}

/**
 * Le changement de clavier en cours : un seul à la fois, qui va au bout même si on quitte les réglages. La page
 * affichée, rouverte entre-temps ou non, le suit par [onChange]. Tout se lit et s'écrit sur le fil de
 * l'interface, sauf [running].
 */
object KeyboardJob {
    private val main = Handler(Looper.getMainLooper())

    @Volatile var running = false
        private set
    /** Ce que le changement dit : qu'il est en cours, puis la cause de son échec ; null quand il n'y a rien à dire. */
    var message: String? = null
        private set
    /** L'identifiant du clavier qu'on choisit : [message] se montre sur sa ligne. */
    var about: String? = null
        private set
    var failed = false
        private set
    /** La page affichée, prévenue à chaque changement. */
    var onChange: (() -> Unit)? = null

    /**
     * Lance [job], qui porte sur le clavier [about], hors du fil de l'interface, sauf si un changement est déjà
     * en cours (false). [first] s'affiche tout de suite ; à la fin, [done] est appelé si tout s'est bien passé,
     * sinon la cause de l'échec reste dans [message].
     */
    fun start(about: String, first: String, job: () -> Unit, done: () -> Unit): Boolean {
        if (running) return false
        running = true
        failed = false
        this.about = about
        message = first
        onChange?.invoke()
        Thread {
            var error: String? = null
            try {
                job()
            } catch (e: Keyboards.Failure) {
                error = e.message
            } catch (e: Exception) {
                error = e.toString()
            }
            main.post {
                running = false
                failed = error != null
                message = error
                if (error == null) this@KeyboardJob.about = null
                onChange?.invoke()
                if (error == null) done()
            }
        }.start()
        return true
    }

    /** Une page ouverte après un échec repart sans son message. */
    fun forget() {
        if (running) return
        message = null
        about = null
        failed = false
    }
}
