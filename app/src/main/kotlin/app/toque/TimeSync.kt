package app.toque

import android.content.Context
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.time.ZoneLookup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Date et heure du robot, réglées toutes seules :
 *  - le fuseau horaire est trouvé d'après la connexion Internet (géolocalisation de l'adresse IP),
 *    ou choisi à la main dans les réglages, puis appliqué au système (root) ;
 *  - l'heure vient du réseau (NTP d'Android, activé ici) ; si elle est encore fausse de plus de
 *    deux minutes, elle est corrigée d'après l'en-tête Date des serveurs interrogés.
 * Le robot n'a pas de pile d'horloge : au démarrage il se croit le 1er janvier 1970.
 */
class TimeSync(private val context: Context, private val prefs: Prefs, private val log: Logbook) {
    class Result(val ok: Boolean, val zone: String, val message: String, val deferred: Boolean = false)

    fun run(): Result {
        // Heure réseau d'Android (NTP), au cas où elle aurait été désactivée.
        RootShell.run("settings put global auto_time 1")
        val wanted = prefs.timeZone
        var zone: String? = null
        var source = tr("choix manuel")
        var serverMs = 0L
        if (wanted == ZoneLookup.AUTO) {
            val nowMs = System.currentTimeMillis()
            for (p in ZoneLookup.PROVIDERS) {
                // Horloge pas encore réglée : les services HTTPS échouent (TLS) ; on garde le HTTP.
                if (!ZoneLookup.usable(p.url, nowMs)) continue
                try {
                    val (body, date) = get(p.url)
                    if (date > 0) serverMs = date
                    val z = ZoneLookup.zoneOf(body)
                    if (z != null && z in available) {
                        zone = z
                        source = p.name
                        break
                    }
                    log.write("heure", "${p.name} : pas de fuseau dans la réponse")
                } catch (e: Exception) {
                    log.write("heure", "${p.name} : ${e.message ?: e.toString()}")
                }
            }
            if (zone == null) {
                if (!ZoneLookup.clockPlausible(nowMs)) {
                    if (deferredLogged.compareAndSet(false, true)) {
                        log.write("heure", "horloge pas encore synchronisée, fuseau reporté")
                    }
                    return Result(false, current(), tr("Nouvelle tentative automatique."), deferred = true)
                }
                return Result(false, current(), tr("Fuseau horaire introuvable : vérifiez le Wi-Fi."))
            }
        } else {
            zone = wanted.takeIf { it in available } ?: return Result(false, current(), tr("Fuseau horaire inconnu : %s", wanted))
        }
        val z = zone!!
        if (!RootShell.available()) return Result(false, current(), tr("Accès root nécessaire pour régler l'heure du robot."))

        if (z != current()) {
            val r = RootShell.run("service call alarm 3 s16 $z")
            if (!r.ok || prop() != z) {
                // Repli : propriété système puis annonce du changement aux applis.
                RootShell.run("setprop persist.sys.timezone $z && am broadcast -a android.intent.action.TIMEZONE_CHANGED --es time-zone $z")
            }
            TimeZone.setDefault(null)
            log.write("heure", "fuseau $z ($source)")
        }
        val fixed = fixClock(serverMs)
        val ok = prop() == z || current() == z
        prefs.lastZone = z
        val now = clock()
        return if (ok) Result(true, z, if (fixed) tr("Heure corrigée : %s (%s)", now, z) else tr("Il est %s (%s)", now, z))
        else Result(false, current(), tr("Le système a refusé le fuseau %s", z))
    }

    /** Corrige l'horloge si elle s'écarte de plus de 2 min de l'heure donnée par un serveur. */
    private fun fixClock(serverMs: Long): Boolean {
        if (serverMs < MIN_PLAUSIBLE_MS) return false
        if (Math.abs(System.currentTimeMillis() - serverMs) < 120_000L) return false
        val r = RootShell.run("service call alarm 2 i64 $serverMs")
        if (Math.abs(System.currentTimeMillis() - serverMs) > 60_000L) {
            val utc = SimpleDateFormat("MMddHHmmyyyy.ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            RootShell.run("date -u ${utc.format(Date(serverMs))}")
        }
        val ok = Math.abs(System.currentTimeMillis() - serverMs) < 60_000L
        log.write("heure", if (ok) "horloge corrigée d'après le réseau" else "correction de l'horloge refusée (${r.err.trim()})")
        return ok
    }

    private fun get(url: String): Pair<String, Long> {
        val r = Http.get(url, mapOf("Accept" to "application/json"), timeoutMs = 10_000, maxBytes = 64 * 1024)
        return r.body to r.serverMs
    }

    private fun prop(): String = RootShell.run("getprop persist.sys.timezone").out.trim()

    companion object {
        /** 1er janvier 2025 : en dessous, l'heure d'un serveur n'est pas crédible. */
        private const val MIN_PLAUSIBLE_MS = 1_735_689_600_000L
        private val available: Set<String> by lazy { TimeZone.getAvailableIDs().toHashSet() }

        fun current(): String = TimeZone.getDefault().id

        fun clock(): String = SimpleDateFormat("HH:mm", Locale.FRANCE).apply { timeZone = TimeZone.getDefault() }.format(Date())

        @Volatile private var started = false

        /** Une seule ligne de journal neutre par processus pendant l'attente de NTP. */
        private val deferredLogged = AtomicBoolean(false)

        /** Report de l'attente NTP avant de passer aux délais longs (≈ 3,5–4 min au total). */
        private const val FAST_DEFERRALS = 5

        /**
         * Au lancement de l'interface : réglage dès que le réseau répond (le Wi-Fi met un moment à
         * se connecter au démarrage du robot), puis toutes les 12 heures.
         */
        fun start(context: Context, prefs: Prefs, log: Logbook) {
            if (started) return
            started = true
            val t = Thread {
                val retries = longArrayOf(5_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000)
                var fast = 0      // reports de l'attente NTP
                var failures = 0  // échecs réels (réseau, root…)
                while (true) {
                    val r = try {
                        TimeSync(context, prefs, log).run()
                    } catch (e: Throwable) {
                        log.write("heure", "erreur : ${e.message}")
                        Result(false, current(), "")
                    }
                    if (r.ok) {
                        fast = 0
                        failures = 0
                    }
                    val wait = when {
                        r.ok -> 12 * 3600_000L
                        r.deferred && fast < FAST_DEFERRALS -> ZoneLookup.retryDelayMs(fast++)
                        // Attente NTP trop longue : on ralentit (5 min puis plus) au lieu de sonder chaque minute.
                        // `failures` est réutilisé exprès : le report fait avancer l'échelle des échecs réels.
                        r.deferred -> retries[minOf(FAST_DEFERRALS - 1 + failures++, retries.size - 1)]
                        else -> retries[minOf(failures++, retries.size - 1)]
                    }
                    try {
                        Thread.sleep(wait)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }
            t.isDaemon = true
            t.name = "toque-heure"
            t.start()
        }
    }
}
