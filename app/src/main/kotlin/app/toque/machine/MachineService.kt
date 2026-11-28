package app.toque.machine

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import app.toque.Logbook
import app.toque.Prefs
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.Accessory
import app.toque.core.machine.ControllerConfig
import app.toque.core.machine.DemoCodec
import app.toque.core.machine.JobState
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.MachineLoop
import app.toque.core.machine.MonotonicClock
import app.toque.core.machine.RootSerialTransport
import app.toque.core.robot.RobotProfiles
import app.toque.core.machine.SimBoard
import app.toque.core.machine.SimTransport
import app.toque.core.machine.Snapshot
import app.toque.core.machine.TcpTransport
import app.toque.core.machine.Transport
import app.toque.core.machine.Verdict
import app.toque.core.protocol.Direction
import app.toque.ui.MainActivity

/**
 * Service de pilotage, dans son propre processus (« :machine ») : un plantage de l'interface
 * n'interrompt jamais un programme en cours, qui se termine et s'arrête normalement.
 */
class MachineService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val clients = ArrayList<Messenger>()
    private var loop: MachineLoop? = null
    private var sim: SimBoard? = null
    private var lastSent: Snapshot? = null
    private var lastSentAt = 0L
    private var lastNotified = ""
    private lateinit var prefs: Prefs
    private lateinit var log: Logbook

    /**
     * Ce que ce service a constaté sur la base du robot. Dans un fichier à lui : les réglages
     * (Prefs) ne sont écrits que par l'interface, qui tourne dans un autre processus.
     */
    private val learned by lazy { getSharedPreferences("base", Context.MODE_PRIVATE) }

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        try {
            handle(msg)
        } catch (t: Throwable) {
            log.write("machine", "message ${msg.what} en échec : $t")
        }
        true
    })

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        log = Logbook(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                loop?.emergencyStop()
                log.crash("machine", error)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
        ensureChannel()
        startForeground(NOTIFICATION_ID, notification(tr("Base moteur"), tr("Démarrage…")))
        createLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        clients.clear()
        loop?.shutdown()
        loop = null
        super.onDestroy()
    }

    private fun createLoop() {
        loop?.shutdown()
        prefs = Prefs(this) // relit le fichier écrit par l'interface
        RobotProfiles.active = prefs.robot
        val mode = prefs.mode
        sim = null
        val factory: () -> Transport = when (mode) {
            Prefs.Mode.DEMO -> {
                val board = SimBoard(MonotonicClock)
                sim = board
                // Démo du robot choisi : ses règles, avec la base simulée du Connect.
                val codec = DemoCodec.forRobot(prefs.robot.newCodec())
                ({ SimTransport(board, codec) })
            }
            Prefs.Mode.TWIN -> {
                val host = prefs.twinHost
                val port = prefs.twinPort
                ({ TcpTransport(host, port) })
            }
            Prefs.Mode.HARDWARE -> {
                if (!prefs.robotConfirmed) {
                    // Jamais de trame tant que la personne n'a pas confirmé son robot : une trame d'un
                    // autre protocole pourrait être mal comprise par la base.
                    log.write("machine", "robot non confirmé : liaison avec la base fermée")
                    loop = null
                    return
                }
                val robot = prefs.robot
                val dev = prefs.serialDevice
                val vendor = prefs.vendorPackage
                log.write("machine", "robot : ${robot.displayName} ($dev, ${robot.baud} bauds)")
                ({ RootSerialTransport(dev, robot.baud, vendor, robot.extraStopPackages, robot.newCodec()) })
            }
        }
        // Le sens du capteur du bol n'est retenu que pour la vraie base : une base simulée a le sien.
        val real = mode == Prefs.Mode.HARDWARE
        val seated = if (real && learned.contains(BOWL_BIT_SEATED)) learned.getBoolean(BOWL_BIT_SEATED, true) else null
        val l = MachineLoop(
            transportFactory = factory,
            config = ControllerConfig(requireBowl = prefs.requireBowl, invertBowl = prefs.invertBowl, hotRamp = prefs.hotRamp,
                bowlAutoZero = prefs.bowlAutoZero, emptyBowlG = prefs.emptyBowlG, bowlBitSeated = seated),
            // Cadence du robot pour la vraie base ; la démo et le jumeau parlent comme le Connect.
            periodMs = if (mode == Prefs.Mode.HARDWARE) prefs.robot.framePeriodMs else RobotProfiles.MC_CONNECT.framePeriodMs,
            onSnapshot = { s -> main.post { if (real) rememberBowlSense(s); publish(s) } },
            onEvent = { e -> main.post { broadcastEvent(e) } },
            log = { line -> log.write("machine", line) },
            // Boucle bloquée et rien ne la débloque : le chien de garde a déjà envoyé des STOP ; on abandonne le
            // processus. Android relance le service (START_STICKY) et la nouvelle boucle émet des trames STOP.
            onUnrecoverable = {
                log.write("machine", "boucle bloquée : le processus machine est abandonné pour repartir proprement")
                Process.killProcess(Process.myPid())
            },
        )
        loop = l
        l.start()
        log.write("machine", "boucle démarrée en mode ${mode.label}")
    }

    /** Retient le sens du capteur du bol constaté par le contrôleur, pour reconnaître un bol retiré dès le prochain démarrage. */
    private fun rememberBowlSense(s: Snapshot) {
        if (!s.bowlSenseKnown) return
        val seated = !s.bowlSenseInverted
        if (!learned.contains(BOWL_BIT_SEATED) || learned.getBoolean(BOWL_BIT_SEATED, seated) != seated)
            learned.edit().putBoolean(BOWL_BIT_SEATED, seated).apply()
    }

    private fun publish(s: Snapshot) {
        val now = System.currentTimeMillis()
        val prev = lastSent
        val changed = prev == null || !samePublished(prev, s)
        if (!changed && now - lastSentAt < 1_000) return
        lastSent = s
        lastSentAt = now
        val m = Message.obtain(null, Ipc.SNAPSHOT)
        m.data = Ipc.snapshotToBundle(s)
        send(m)
        updateNotification(s)
    }

    private fun samePublished(a: Snapshot, b: Snapshot): Boolean =
        a.link == b.link && a.transportName == b.transportName && a.transportUp == b.transportUp &&
            a.faultCode == b.faultCode && a.faultMessage == b.faultMessage && a.hasSample == b.hasSample &&
            a.actualTempC == b.actualTempC && a.targetTempC == b.targetTempC &&
            a.rpm == b.rpm && a.speedLevel == b.speedLevel && a.weightG == b.weightG &&
            a.scaleStable == b.scaleStable && a.scaleState == b.scaleState &&
            a.lidOpen == b.lidOpen && a.bowlDetected == b.bowlDetected &&
            a.bowlRemoved == b.bowlRemoved && a.bowlSenseKnown == b.bowlSenseKnown &&
            a.bowlSenseInverted == b.bowlSenseInverted && a.motorOn == b.motorOn &&
            a.heaterOn == b.heaterOn && a.boardErrorCode == b.boardErrorCode &&
            a.firmware == b.firmware && a.rxHex == b.rxHex &&
            a.jobActive == b.jobActive && a.jobKind == b.jobKind && a.jobLabel == b.jobLabel &&
            a.jobState == b.jobState && a.pauseReason == b.pauseReason && a.endReason == b.endReason &&
            a.phaseIndex == b.phaseIndex && a.phaseCount == b.phaseCount &&
            a.elapsedMs == b.elapsedMs && a.totalMs == b.totalMs && a.remainingMs == b.remainingMs &&
            a.waitingStop == b.waitingStop && a.jobSpeed == b.jobSpeed && a.jobTempLevel == b.jobTempLevel &&
            a.jobTempC == b.jobTempC && a.jobProgram == b.jobProgram && a.jobReverse == b.jobReverse &&
            a.accessory == b.accessory && a.hotBlendConfirmed == b.hotBlendConfirmed &&
            a.jobFerment == b.jobFerment && a.turboActive == b.turboActive &&
            a.wireOperation == b.wireOperation && a.wireSpeed == b.wireSpeed &&
            a.wireTempLevel == b.wireTempLevel && a.invalidFrames == b.invalidFrames

    private fun updateNotification(s: Snapshot) {
        val text = when {
            s.hasFault -> tr("Alerte : %s", s.faultMessage)
            s.state == JobState.RUNNING -> tr("%s · reste %s", s.jobLabel, formatClock(s.remainingMs))
            s.state == JobState.PAUSED -> tr("%s en pause", s.jobLabel)
            else -> s.link.label + if (s.transportName.isNotEmpty()) " · ${s.transportName}" else ""
        }
        if (text == lastNotified) return
        lastNotified = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification(tr("Base moteur"), text))
    }

    private fun broadcastEvent(e: MachineEvent) {
        val m = Message.obtain(null, Ipc.EVENT)
        m.data = Bundle().apply {
            putString("type", e.type.name)
            putString("message", e.message)
            putInt("value", e.value)
        }
        send(m)
        log.write("évènement", "${e.type} ${e.message}")
    }

    private fun send(template: Message) {
        val dead = ArrayList<Messenger>()
        for (c in clients) {
            try {
                val m = Message.obtain(template)
                c.send(m)
            } catch (_: RemoteException) {
                dead += c
            }
        }
        clients.removeAll(dead)
        template.recycle()
    }

    private fun reply(to: Messenger?, id: Int, v: Verdict) {
        if (to == null) return
        val m = Message.obtain(null, Ipc.REPLY, id, 0)
        m.data = Ipc.verdictToBundle(v)
        try {
            to.send(m)
        } catch (_: RemoteException) {
        }
    }

    private fun handle(msg: Message) {
        val to = msg.replyTo
        val id = msg.arg1
        if (loop == null && msg.what == Ipc.RECONFIGURE) {
            // Pas de boucle (robot pas encore confirmé en « Machine réelle ») : rien ne tourne, on la crée.
            createLoop()
            reply(to, id, Verdict.OK)
            return
        }
        if (loop == null && msg.what == Ipc.REGISTER) {
            if (to != null && clients.none { it.binder == to.binder }) clients += to
            return
        }
        val l = loop ?: return
        val data = msg.data
        data.getString(Ipc.LANG)?.let { if (it != I18n.code) I18n.select(it) }
        when (msg.what) {
            Ipc.REGISTER -> {
                if (to != null && clients.none { it.binder == to.binder }) clients += to
                lastSent = null
            }
            Ipc.UNREGISTER -> clients.removeAll { it.binder == to?.binder }
            Ipc.START_JOB -> {
                val spec = Ipc.bundleToJob(data)
                if (spec == null) reply(to, id, Verdict.refuse("ipc", tr("Programme illisible")))
                else l.post { c -> reply(to, id, c.startJob(spec)) }
            }
            Ipc.PAUSE -> l.post { c -> reply(to, id, c.pause()) }
            Ipc.RESUME -> {
                val hot = data.getBoolean("hot")
                l.post { c -> reply(to, id, c.resume(hot)) }
            }
            Ipc.STOP -> l.post { c -> reply(to, id, c.stop()) }
            Ipc.CLEAR -> l.post { c -> c.clearFinished(); reply(to, id, Verdict.OK) }
            Ipc.ADJUST -> {
                val speed = if (data.containsKey("speed")) data.getInt("speed") else null
                val temp = if (data.containsKey("temp")) data.getInt("temp") else null
                val dir = if (data.containsKey("reverse"))
                    (if (data.getBoolean("reverse")) Direction.COUNTER_CLOCKWISE else Direction.CLOCKWISE) else null
                val total = if (data.containsKey("totalMs")) data.getLong("totalMs") else null
                val hot = data.getBoolean("hot")
                val smartLevel = if (data.containsKey("smartLevel")) data.getInt("smartLevel") else null
                l.post { c -> reply(to, id, c.adjust(speed, temp, dir, total, hot, smartLevel)) }
            }
            Ipc.TURBO_DOWN -> {
                val acc = try { Accessory.valueOf(data.getString("accessory") ?: "") } catch (_: Exception) { Accessory.BLADE }
                l.post { c -> reply(to, id, c.turboPress(acc)) }
            }
            Ipc.TURBO_UP -> l.post { c -> c.turboRelease() }
            Ipc.CONFIRM_COLD -> l.post { c -> reply(to, id, c.confirmCold()) }
            Ipc.TARE -> {
                val learn = !data.getBoolean("auto")
                l.post { c -> c.tare(learn) }
            }
            Ipc.FORGET_BOWL -> l.post { c -> c.forgetEmptyBowl() }
            Ipc.ACK_FAULT -> l.post { c -> reply(to, id, c.acknowledgeFault()) }
            Ipc.RECONFIGURE -> Thread {
                val busy = try { l.call { it.busy } } catch (_: Exception) { true }
                main.post {
                    if (busy) reply(to, id, Verdict.refuse("busy", tr("Impossible pendant un programme")))
                    else {
                        createLoop()
                        reply(to, id, Verdict.OK)
                    }
                }
            }.start()
            Ipc.SUSPEND_PORT -> Thread {
                val ok = try { l.suspendPort() } catch (_: Exception) { false }
                reply(to, id, if (ok) Verdict.OK else Verdict.refuse("busy", tr("Arrêtez d'abord le programme en cours")))
            }.start()
            Ipc.RESUME_PORT -> {
                l.resumePort()
                reply(to, id, Verdict.OK)
            }
            Ipc.STANDBY -> {
                val enabled = data.getBoolean("standby")
                l.post { c -> c.setStandby(enabled) }
            }
            Ipc.SIM -> {
                val b = sim
                if (b != null) {
                    if (data.containsKey("lid")) b.lidOpen = data.getBoolean("lid")
                    if (data.containsKey("error")) b.errorCode = data.getInt("error")
                    if (data.containsKey("weight")) b.contentWeightG = data.getInt("weight")
                }
            }
        }
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Api26.builder(this, CHANNEL)
        else Notification.Builder(this)
        return b.setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    /** Canal de notification (Android 8+), isolé dans [Api26] pour garder MachineService vérifiable sur Android 6. */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Api26.ensureChannel(this, CHANNEL, tr("Base moteur"))
    }

    @TargetApi(Build.VERSION_CODES.O)
    private object Api26 {
        fun ensureChannel(c: Context, id: String, name: String) {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW))
        }

        fun builder(c: Context, channelId: String): Notification.Builder = Notification.Builder(c, channelId)
    }

    companion object {
        private const val NOTIFICATION_ID = 7
        private const val CHANNEL = "machine"
        /** Valeur du bit « bol » quand le bol est en place sur ce robot (voir MachineController.bowlBitSeated). */
        private const val BOWL_BIT_SEATED = "bowlBitSeated"

        fun formatClock(ms: Long): String {
            val s = (ms + 999) / 1000
            return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
            else String.format("%02d:%02d", s / 60, s % 60)
        }
    }
}
