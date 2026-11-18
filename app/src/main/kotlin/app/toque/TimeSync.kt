package app.toque

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import app.toque.core.i18n.tr
import app.toque.core.machine.RootShell
import app.toque.core.time.ZoneLookup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Date et heure du robot, réglées toutes seules :
 *  - le fuseau horaire est trouvé d'après la connexion Internet (géolocalisation de l'adresse IP),
 *    ou choisi à la main dans les réglages, puis appliqué au système (root) ;
 *  - l'heure vient du réseau (NTP d'Android, activé ici) ; si elle est encore fausse de plus de
 *    deux minutes, elle est corrigée d'après l'en-tête Date des serveurs interrogés.
 * Le robot n'a pas de pile d'horloge : au démarrage il se croit le 1er janvier 1970.
 */
class TimeSync(private val context: Context, private val prefs: Prefs, private val log: Logbook) {
    class Result(val ok: Boolean, val zone: String, val message: String)

    fun run(): Result {
        val wanted = prefs.timeZone
        var zone: String? = null
        var source = tr("choix manuel")
        var serverMs = 0L
        if (wanted == ZoneLookup.AUTO) {
            for (p in ZoneLookup.PROVIDERS) {
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
            if (zone == null) return Result(false, current(), tr("Fuseau horaire introuvable : vérifiez le Wi-Fi."))
        } else {
            zone = wanted.takeIf { it in available } ?: return Result(false, current(), tr("Fuseau horaire inconnu : %s", wanted))
        }
        val z = zone!!
        if (!RootShell.available()) return Result(false, current(), tr("Accès root nécessaire pour régler l'heure du robot."))

        // Heure réseau d'Android (NTP), au cas où elle aurait été désactivée.
        RootShell.run("settings put global auto_time 1")
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

        /**
         * Vrai quand l'horloge du robot est crédible. Sans pile d'horloge, elle repart en 1970 (ou
         * 2010) à chaque démarrage, jusqu'au premier réglage par le réseau : l'heure ne doit pas
         * être affichée avant.
         */
        fun clockSet(): Boolean = System.currentTimeMillis() >= MIN_PLAUSIBLE_MS

        /** Vrai quand le robot est connecté à un réseau (le Wi-Fi, sur le robot). */
        fun online(context: Context): Boolean = try {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo?.isConnected == true
        } catch (_: Exception) {
            false
        }

        /** L'heure ne s'affiche que si elle est réglée et que le Wi-Fi est connecté. */
        fun showClock(context: Context): Boolean = clockSet() && online(context)

        @Volatile private var started = false
        @Volatile private var lastOk = false
        private val lock = Object()
        private var woken = false

        /** Relance tout de suite le réglage (le réseau vient de changer). */
        private fun wake() = synchronized(lock) {
            woken = true
            lock.notifyAll()
        }

        /**
         * Au lancement de l'interface : réglage dès que le réseau répond, puis toutes les 12 heures.
         * Le Wi-Fi met un moment à se connecter au démarrage du robot : chaque changement de réseau
         * relance le réglage sans attendre le prochain essai, tant que l'heure n'est pas réglée.
         */
        fun start(context: Context, prefs: Prefs, log: Logbook) {
            if (started) return
            started = true
            context.applicationContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (isInitialStickyBroadcast) return
                    if (!lastOk || !clockSet()) wake()
                }
            }, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
            val t = Thread {
                val retries = longArrayOf(5_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000)
                var i = 0
                while (true) {
                    val r = try {
                        TimeSync(context, prefs, log).run()
                    } catch (e: Throwable) {
                        log.write("heure", "erreur : ${e.message}")
                        Result(false, current(), "")
                    }
                    lastOk = r.ok
                    val wait = if (r.ok) 12 * 3600_000L else retries[minOf(i++, retries.size - 1)]
                    if (r.ok) i = 0
                    try {
                        val network = synchronized(lock) {
                            if (!woken) lock.wait(wait)
                            val w = woken
                            woken = false
                            w
                        }
                        if (network) {
                            i = 0
                            Thread.sleep(2_000) // laisse au Wi-Fi le temps d'obtenir le DNS
                        }
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
