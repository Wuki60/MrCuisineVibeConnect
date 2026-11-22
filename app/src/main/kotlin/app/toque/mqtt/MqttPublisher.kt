package app.toque.mqtt

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import app.toque.Logbook
import app.toque.Prefs
import app.toque.core.i18n.tr
import app.toque.core.machine.Snapshot
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicReference

/**
 * Publie l'état machine en MQTT (lecture seule), uniquement quand le payload utile change.
 */
class MqttPublisher(
    private val context: Context,
    private val prefs: Prefs,
    private val log: Logbook,
) {
    enum class Status { OFF, CONNECTING, CONNECTED, ERROR }

    private val utf8: Charset = Charsets.UTF_8
    private val thread = HandlerThread("toque-mqtt").apply { start() }
    private val handler = Handler(thread.looper)
    private val lastJson = AtomicReference<String?>(null)
    @Volatile private var client: MqttClient? = null
    @Volatile private var status: Status = Status.OFF
    @Volatile private var statusDetail: String = ""
    @Volatile private var generation = 0
    private var statusListener: ((Status, String) -> Unit)? = null
    /** Délai avant le prochain essai après un échec (15 s → … → 5 min). */
    private var failDelayMs = 15_000L
    /** Évite de remplir le journal : un seul « échec », puis un « connecté » à la reprise. */
    private var loggedOutage = false

    fun status(): Status = status
    fun statusDetail(): String = statusDetail

    fun onStatus(listener: ((Status, String) -> Unit)?) {
        statusListener = listener
        listener?.invoke(status, statusDetail)
    }

    /** Applique les réglages : coupe ou (re)connecte. */
    fun reconfigure() {
        val gen = ++generation
        failDelayMs = 15_000L
        loggedOutage = false
        handler.post { runSession(gen) }
    }

    fun onSnapshot(snapshot: Snapshot) {
        if (!prefs.mqttEnabled) return
        val state = MqttState.from(snapshot)
        val json = state.toJson()
        if (json == lastJson.get()) return
        lastJson.set(json)
        handler.post { publishState(json) }
    }

    fun shutdown() {
        generation++
        handler.post {
            disconnectClient()
            setStatus(Status.OFF, "")
        }
    }

    private fun runSession(gen: Int) {
        disconnectClient()
        lastJson.set(null)
        if (!prefs.mqttEnabled) {
            setStatus(Status.OFF, "")
            return
        }
        val host = prefs.mqttHost.trim()
        if (host.isEmpty()) {
            setStatus(Status.ERROR, tr("Hôte MQTT manquant"))
            return
        }
        val port = prefs.mqttPort.coerceIn(1, 65535)
        val deviceId = prefs.mqttDeviceId.ifBlank { defaultDeviceId() }.trim()
        val prefix = prefs.mqttTopicPrefix.trim().trim('/')
            .ifEmpty { "toque/$deviceId" }
        val statusTopic = "$prefix/status"
        val stateTopic = "$prefix/state"
        val clientId = "toque-$deviceId".take(23)
        setStatus(Status.CONNECTING, tr("Connexion à %s:%d…", host, port))
        try {
            val c = MqttClient(
                host = host,
                port = port,
                clientId = clientId,
                username = prefs.mqttUser.trim(),
                password = prefs.mqttPassword,
                tls = prefs.mqttTls,
                willTopic = statusTopic,
                willPayload = "offline".toByteArray(utf8),
            )
            c.connect()
            if (gen != generation) {
                c.close()
                return
            }
            client = c
            this.statusTopic = statusTopic
            this.stateTopic = stateTopic
            this.deviceIdCached = deviceId
            c.publish(statusTopic, "online".toByteArray(utf8), retain = true)
            publishDiscovery(c, deviceId, statusTopic, stateTopic)
            setStatus(Status.CONNECTED, tr("Connecté à %s:%d", host, port))
            log.write("mqtt", "connecté $host:$port → $prefix")
            loggedOutage = false
            failDelayMs = 15_000L
            schedulePing(gen)
            lastJson.get()?.let { publishState(it) }
        } catch (e: Exception) {
            disconnectClient()
            setStatus(Status.ERROR, e.message ?: e.toString())
            if (!loggedOutage) {
                log.write("mqtt", "échec connexion : $e")
                loggedOutage = true
            }
            scheduleRetry(gen)
        }
    }

    private fun scheduleRetry(gen: Int) {
        if (!prefs.mqttEnabled || gen != generation) return
        val delay = failDelayMs
        failDelayMs = minOf(failDelayMs * 2, 300_000L)
        handler.postDelayed({ if (gen == generation) runSession(generation) }, delay)
    }

    @Volatile private var statusTopic: String = ""
    @Volatile private var stateTopic: String = ""
    @Volatile private var deviceIdCached: String = ""

    /** Republie les configs Discovery (ex. après un changement de langue de l'interface). */
    fun republishDiscovery() {
        handler.post {
            val c = client
            val id = deviceIdCached
            val st = statusTopic
            val state = stateTopic
            if (c == null || !c.connected || id.isEmpty() || st.isEmpty() || state.isEmpty()) return@post
            try {
                publishDiscovery(c, id, st, state)
            } catch (e: Exception) {
                log.write("mqtt", "échec discovery : $e")
            }
        }
    }

    private fun publishDiscovery(c: MqttClient, deviceId: String, statusTopic: String, stateTopic: String) {
        for ((topic, payload) in MqttDiscovery.configs(deviceId, statusTopic, stateTopic)) {
            c.publish(topic, payload.toByteArray(utf8), retain = true)
        }
        log.write("mqtt", "Home Assistant discovery publié (${deviceId})")
    }

    private fun publishState(json: String) {
        val c = client
        val topic = stateTopic
        if (c == null || !c.connected || topic.isEmpty()) return
        try {
            c.publish(topic, json.toByteArray(utf8), retain = true)
        } catch (e: Exception) {
            disconnectClient()
            setStatus(Status.ERROR, e.message ?: e.toString())
            if (!loggedOutage) {
                log.write("mqtt", "échec publish : $e")
                loggedOutage = true
            }
            scheduleRetry(generation)
        }
    }

    private fun schedulePing(gen: Int) {
        handler.postDelayed({
            if (gen != generation) return@postDelayed
            val c = client
            if (c == null || !c.connected) {
                if (prefs.mqttEnabled) scheduleRetry(gen)
                return@postDelayed
            }
            c.ping()
            if (!c.connected) {
                setStatus(Status.ERROR, tr("Connexion MQTT perdue"))
                if (!loggedOutage) {
                    log.write("mqtt", "connexion perdue")
                    loggedOutage = true
                }
                if (prefs.mqttEnabled) scheduleRetry(gen)
                return@postDelayed
            }
            schedulePing(gen)
        }, 45_000)
    }

    private fun disconnectClient() {
        try { client?.close() } catch (_: Exception) {}
        client = null
        statusTopic = ""
        stateTopic = ""
    }

    private fun setStatus(s: Status, detail: String) {
        status = s
        statusDetail = detail
        statusListener?.invoke(s, detail)
    }

    private fun defaultDeviceId(): String {
        @Suppress("HardwareIds")
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        return id?.takeIf { it.isNotBlank() } ?: "robot"
    }
}
