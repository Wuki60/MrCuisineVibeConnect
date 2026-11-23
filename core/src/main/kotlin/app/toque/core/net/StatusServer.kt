package app.toque.core.net

import app.toque.core.json.Json
import app.toque.core.machine.Snapshot
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Serveur d'état en LECTURE SEULE pour la domotique (Home Assistant…), désactivé par défaut.
 *
 * - Une seule route : `GET /api/v1/status`, qui renvoie l'état de la machine en JSON.
 * - Aucune commande : rien de ce qui arrive par le réseau ne peut démarrer, arrêter ni régler le moteur
 *   ou la chauffe. Le serveur ne voit la machine qu'au travers de [snapshot], une lecture.
 * - Jeton obligatoire (`Authorization: Bearer <jeton>`), comparé en temps constant.
 * - Une connexion à la fois, requête limitée à [MAX_REQUEST] octets et [TIMEOUT_MS] ms, réponse fermée
 *   aussitôt : un client lent ou malveillant ne peut ni bloquer la machine ni épuiser la mémoire.
 *   Le serveur tourne dans son propre fil ; il ne touche jamais la boucle de pilotage.
 * - Les trames brutes (rxHex/txHex) et l'état du fil série ne sont pas publiés.
 */
class StatusServer(
    private val port: Int,
    token: String,
    private val snapshot: () -> Snapshot?,
    private val info: () -> Map<String, Any?> = { emptyMap() },
    private val log: (String) -> Unit = {},
    private val bindAddress: InetAddress? = null,
) {
    private val expected = token.toByteArray(StandardCharsets.UTF_8)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null

    init {
        require(token.length >= MIN_TOKEN) { "jeton trop court" }
        require(port == 0 || port in 1024..65535) { "port hors plage" }
    }

    /** Port réellement ouvert (utile quand [port] vaut 0 dans les tests), -1 si arrêté. */
    val localPort: Int get() = server?.localPort ?: -1

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        if (server != null) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(java.net.InetSocketAddress(bindAddress, port), 4)
        server = s
        thread = Thread({ acceptLoop(s) }, "toque-status").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        log("serveur d'état ouvert sur le port ${s.localPort}")
    }

    @Synchronized
    fun stop() {
        val s = server ?: return
        server = null
        try { s.close() } catch (_: IOException) { }
        thread?.interrupt()
        thread = null
        log("serveur d'état fermé")
    }

    /**
     * Aucune exception ne sort de ce fil : dans le processus « :machine », une exception non rattrapée
     * déclencherait l'arrêt d'urgence de la cuisson. Au pire, le serveur se ferme et la cuisson continue.
     */
    private fun acceptLoop(s: ServerSocket) {
        try {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: SocketException) { break } catch (_: IOException) { continue }
                try {
                    c.use { handle(it) }
                } catch (_: Throwable) {
                    // Un client qui coupe ou envoie n'importe quoi ne doit jamais arrêter le serveur.
                }
            }
        } catch (t: Throwable) {
            try { log("serveur d'état arrêté : $t") } catch (_: Throwable) { }
            try { s.close() } catch (_: Throwable) { }
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = TIMEOUT_MS
        c.tcpNoDelay = true
        val head = readHead(c.getInputStream()) ?: return respond(c, 400, error("requête illisible"))
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return respond(c, 400, error("requête illisible"))
        val (method, target) = parts
        val path = target.substringBefore('?')
        if (path != PATH) return respond(c, 404, error("inconnu"))
        if (method != "GET") return respond(c, 405, error("lecture seule"), "Allow: GET\r\n")
        val auth = lines.drop(1).firstOrNull { it.startsWith("Authorization:", ignoreCase = true) }
            ?.substringAfter(':')?.trim().orEmpty()
        val given = if (auth.startsWith("Bearer ", ignoreCase = true)) auth.substring(7).trim() else ""
        if (!MessageDigest.isEqual(given.toByteArray(StandardCharsets.UTF_8), expected))
            return respond(c, 401, error("jeton absent ou faux"), "WWW-Authenticate: Bearer\r\n")
        respond(c, 200, Json.write(body(snapshot())))
    }

    private fun body(s: Snapshot?): Map<String, Any?> = linkedMapOf(
        "api" to API_VERSION,
        "device" to info(),
        "state" to s?.let { publicState(it) },
    )

    private fun error(message: String) = Json.write(mapOf("error" to message))

    private fun respond(c: Socket, code: Int, json: String, extra: String = "") {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $code ${REASONS[code] ?: "Error"}\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n" + extra + "\r\n"
        val out = c.getOutputStream()
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    companion object {
        const val PATH = "/api/v1/status"
        const val API_VERSION = 1
        const val DEFAULT_PORT = 8766
        const val MIN_TOKEN = 16
        const val MAX_REQUEST = 4096
        const val TIMEOUT_MS = 2_000

        private val REASONS = mapOf(200 to "OK", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found", 405 to "Method Not Allowed")

        /** Jeton aléatoire de 128 bits, en hexadécimal (32 caractères, facile à recopier). */
        fun newToken(random: SecureRandom = SecureRandom()): String {
            val b = ByteArray(16)
            random.nextBytes(b)
            return b.joinToString("") { String.format("%02x", it) }
        }

        /** Lit l'en-tête HTTP jusqu'à la ligne vide ; null s'il dépasse [MAX_REQUEST] ou si le client coupe. */
        internal fun readHead(input: InputStream): String? {
            val buf = ByteArray(MAX_REQUEST)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) return null
                n += r
                val text = String(buf, 0, n, StandardCharsets.ISO_8859_1)
                val end = text.indexOf("\r\n\r\n")
                if (end >= 0) return text.substring(0, end)
            }
            return null
        }

        /**
         * Ce qui est publié : liste blanche, pour qu'un champ ajouté plus tard à [Snapshot] (trames brutes,
         * diagnostic) ne parte pas sur le réseau sans décision.
         */
        fun publicState(s: Snapshot): Map<String, Any?> = linkedMapOf(
            "link" to s.link.name,
            "ready" to s.ready,
            "fault_code" to s.faultCode,
            "fault_message" to s.faultMessage,
            "has_sample" to s.hasSample,
            "sample_age_ms" to s.sampleAgeMs,
            "temperature_c" to s.actualTempC,
            "target_temperature_c" to s.targetTempC,
            "rpm" to s.rpm,
            "speed_level" to s.speedLevel,
            "weight_g" to s.weightG,
            "scale_stable" to s.scaleStable,
            "lid_open" to s.lidOpen,
            "bowl_detected" to s.bowlDetected,
            "motor_on" to s.motorOn,
            "heater_on" to s.heaterOn,
            "board_error_code" to s.boardErrorCode,
            "firmware" to s.firmware,
            "job_active" to s.jobActive,
            "job_kind" to s.jobKind,
            "job_label" to s.jobLabel,
            "job_state" to s.jobState,
            "job_program" to s.jobProgram,
            "pause_reason" to s.pauseReason,
            "end_reason" to s.endReason,
            "phase_index" to s.phaseIndex,
            "phase_count" to s.phaseCount,
            "elapsed_s" to s.elapsedMs / 1000,
            "total_s" to s.totalMs / 1000,
            "remaining_s" to (s.remainingMs + 999) / 1000,
            "job_speed" to s.jobSpeed,
            "job_temperature_c" to s.jobTempC,
            "job_reverse" to s.jobReverse,
            "turbo_active" to s.turboActive,
            "running" to s.running,
            "busy" to s.busy,
            "hot" to s.hot,
        )
    }
}
