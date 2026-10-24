package app.toque.core.machine

import app.toque.core.i18n.tr
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Boucle de pilotage : un fil unique émet une trame toutes les 100 ms, reçoit les états,
 * rouvre la liaison si elle tombe. Toute interaction avec le contrôleur passe par [call].
 */
class MachineLoop(
    private val transportFactory: () -> Transport,
    private val clock: Clock = MonotonicClock,
    config: ControllerConfig = ControllerConfig(),
    private val periodMs: Long = 100,
    private val onSnapshot: (Snapshot) -> Unit = {},
    private val onEvent: (MachineEvent) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "toque-machine").apply { priority = Thread.MAX_PRIORITY }
    }
    private val controller = MachineController(clock, config)
    @Volatile private var transport: Transport? = null
    private var nextOpenAt = 0L
    private var openFailures = 0
    @Volatile private var running = false
    @Volatile var suspended = false
        private set

    init {
        controller.listener = { e -> onEvent(e) }
    }

    fun start() {
        if (running) return
        running = true
        exec.scheduleWithFixedDelay({ safeTick() }, 0, periodMs, TimeUnit.MILLISECONDS)
    }

    /** Exécute [block] sur le fil machine et renvoie son résultat. */
    fun <T> call(block: (MachineController) -> T): T =
        exec.submit(Callable { block(controller) }).get(3, TimeUnit.SECONDS)

    fun post(block: (MachineController) -> Unit) {
        exec.execute {
            try {
                block(controller)
            } catch (t: Throwable) {
                log("commande en échec : $t")
            }
        }
    }

    private fun safeTick() {
        try {
            tick()
        } catch (t: Throwable) {
            log("erreur de boucle : $t")
            try {
                transport?.write(controller.stopFrame())
            } catch (_: Throwable) {
            }
        }
    }

    private fun tick() {
        val now = clock.nowMs()
        if (transport == null && !suspended && now >= nextOpenAt) openTransport(now)
        val frame = controller.tick()
        val t = transport
        if (t != null) {
            try {
                t.write(frame)
            } catch (e: IOException) {
                controller.onWriteFailed(e.message ?: tr("erreur d'écriture"))
                closeTransport(t, tr("écriture impossible"))
            }
        }
        onSnapshot(controller.snapshot())
    }

    private fun openTransport(now: Long) {
        val t = try {
            transportFactory()
        } catch (e: Throwable) {
            nextOpenAt = now + retryDelayMs(++openFailures)
            log("transport indisponible : $e")
            return
        }
        try {
            t.open()
        } catch (e: Throwable) {
            nextOpenAt = now + retryDelayMs(++openFailures)
            controller.transportName = t.name
            log("ouverture impossible (${t.name}) : ${e.message}")
            lastOpenError = e.message ?: e.toString()
            return
        }
        lastOpenError = ""
        openFailures = 0
        transport = t
        controller.onTransportUp(t.name)
        log("liaison ouverte : ${t.name}")
        val reader = Thread({ readLoop(t) }, "toque-rx")
        reader.isDaemon = true
        reader.priority = Thread.MAX_PRIORITY
        reader.start()
    }

    @Volatile var lastOpenError: String = ""
        private set

    private fun readLoop(t: Transport) {
        val buffer = ByteArray(256)
        var reason = tr("fin de flux")
        try {
            while (true) {
                val n = t.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                val copy = buffer.copyOf(n)
                exec.execute {
                    if (transport === t) controller.onBytes(copy, n)
                }
            }
        } catch (e: IOException) {
            reason = e.message ?: tr("erreur de lecture")
        } catch (e: Throwable) {
            reason = e.toString()
        }
        if (running) exec.execute { if (transport === t) closeTransport(t, reason) }
    }

    private fun closeTransport(t: Transport, reason: String) {
        if (transport !== t) return
        transport = null
        try {
            t.close()
        } catch (_: Throwable) {
        }
        controller.onTransportDown(reason)
        nextOpenAt = clock.nowMs() + 2_000
        log("liaison fermée : $reason")
    }

    /**
     * Libère le port série (pour laisser l'appli d'origine se synchroniser). Refusé si un
     * programme est en cours. Renvoie vrai si le port est libéré.
     */
    fun suspendPort(): Boolean = call { c ->
        if (c.busy) return@call false
        suspended = true
        val t = transport
        if (t != null) {
            repeat(3) {
                try {
                    t.write(c.stopFrame())
                    Thread.sleep(60)
                } catch (_: Throwable) {
                }
            }
            transport = null
            try {
                t.close()
            } catch (_: Throwable) {
            }
            c.onTransportDown(tr("port libéré"))
        }
        true
    }

    fun resumePort() {
        post {
            suspended = false
            nextOpenAt = 0
            openFailures = 0
        }
    }

    /** Arrête proprement : envoie plusieurs STOP puis ferme la liaison. */
    fun shutdown() {
        if (!running) return
        running = false
        try {
            exec.submit {
                val t = transport
                if (t != null) {
                    repeat(3) {
                        try {
                            t.write(controller.stopFrame())
                            Thread.sleep(60)
                        } catch (_: Throwable) {
                        }
                    }
                    transport = null
                    try {
                        t.close()
                    } catch (_: Throwable) {
                    }
                }
            }.get(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        } finally {
            exec.shutdownNow()
        }
    }

    /** Dernier recours (plantage du processus) : écrit un STOP depuis n'importe quel fil. */
    fun emergencyStop() {
        val t = transport ?: return
        try {
            t.write(controller.stopFrame())
        } catch (_: Throwable) {
        }
    }

    companion object {
        /**
         * Délai avant de retenter d'ouvrir la liaison : 3 s, puis de plus en plus espacé jusqu'à
         * 1 min (évite, par exemple, une demande root toutes les 3 s quand Magisk refuse).
         */
        fun retryDelayMs(failures: Int): Long = minOf(3_000L shl (failures - 1).coerceIn(0, 5), 60_000L)
    }
}
