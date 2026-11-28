package app.toque.machine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.machine.Accessory
import app.toque.core.machine.EventType
import app.toque.core.machine.JobSpec
import app.toque.core.machine.LinkState
import app.toque.core.machine.MachineEvent
import app.toque.core.machine.Snapshot
import app.toque.core.machine.Verdict

/** Côté interface : liaison avec [MachineService]. Tous les rappels arrivent sur le fil principal. */
class MachineClient(private val context: Context) {
    var snapshot = Snapshot()
        private set
    /**
     * Le service machine a déjà envoyé un état. Faux au démarrage : sur le robot, il ne dit rien
     * tant qu'il ouvre la liaison série, et [snapshot] est encore celui par défaut.
     */
    var heard = false
        private set
    val connected: Boolean get() = service != null

    private val snapshotListeners = ArrayList<(Snapshot) -> Unit>()
    private val eventListeners = ArrayList<(MachineEvent) -> Unit>()
    private val pending = HashMap<Int, (Verdict) -> Unit>()
    private var nextId = 1
    private var service: Messenger? = null
    private var bound = false
    private val main = Handler(Looper.getMainLooper())

    private val incoming = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            Ipc.SNAPSHOT -> {
                snapshot = Ipc.bundleToSnapshot(msg.data)
                heard = true
                for (l in ArrayList(snapshotListeners)) l(snapshot)
            }
            Ipc.EVENT -> {
                val type = try { EventType.valueOf(msg.data.getString("type") ?: "") } catch (_: Exception) { null }
                if (type != null) {
                    val e = MachineEvent(type, msg.data.getString("message") ?: "", msg.data.getInt("value"))
                    for (l in ArrayList(eventListeners)) l(e)
                }
            }
            Ipc.REPLY -> pending.remove(msg.arg1)?.invoke(Ipc.bundleToVerdict(msg.data))
        }
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = Messenger(binder)
            sendRaw(Ipc.REGISTER, null, null)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            snapshot = snapshot.copy(link = LinkState.OFFLINE, transportUp = false)
            for (l in ArrayList(snapshotListeners)) l(snapshot)
            // Android relance le service (START_STICKY) et rétablit la liaison tout seul.
        }
    }

    fun connect() {
        if (bound) return
        val intent = Intent(context, MachineService::class.java)
        context.startService(intent)
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
    }

    fun onSnapshot(l: (Snapshot) -> Unit): () -> Unit {
        snapshotListeners += l
        l(snapshot)
        return { snapshotListeners -= l }
    }

    fun onEvent(l: (MachineEvent) -> Unit): () -> Unit {
        eventListeners += l
        return { eventListeners -= l }
    }

    private fun sendRaw(what: Int, data: Bundle?, cb: ((Verdict) -> Unit)?) {
        val s = service
        if (s == null) {
            cb?.let { c -> main.post { c(Verdict.refuse("ipc", tr("Service machine indisponible, nouvelle tentative…"))) } }
            connect()
            return
        }
        val id = nextId++
        if (cb != null) {
            pending[id] = cb
            main.postDelayed({
                pending.remove(id)?.invoke(Verdict.refuse("timeout", tr("La base ne répond pas")))
            }, 5_000)
        }
        val m = Message.obtain(null, what, id, 0)
        m.replyTo = incoming
        // La langue voyage avec chaque commande : le processus machine rédige ses messages dans la même.
        m.data = (data ?: Bundle()).apply { putString(Ipc.LANG, I18n.code) }
        try {
            s.send(m)
        } catch (e: RemoteException) {
            pending.remove(id)?.invoke(Verdict.refuse("ipc", tr("Liaison avec le service perdue")))
        }
    }

    fun start(spec: JobSpec, cb: (Verdict) -> Unit) = sendRaw(Ipc.START_JOB, Ipc.jobToBundle(spec), cb)
    fun pause(cb: (Verdict) -> Unit = {}) = sendRaw(Ipc.PAUSE, null, cb)
    fun resume(hot: Boolean = false, cb: (Verdict) -> Unit) =
        sendRaw(Ipc.RESUME, Bundle().apply { putBoolean("hot", hot) }, cb)
    fun stop(cb: (Verdict) -> Unit = {}) = sendRaw(Ipc.STOP, null, cb)
    fun clearFinished(cb: ((Verdict) -> Unit)? = null) = sendRaw(Ipc.CLEAR, null, cb)
    /** [auto] : tare faite par Toque (recette), qui n'apprend jamais le poids du bol vide. */
    fun tare(auto: Boolean = false) = sendRaw(Ipc.TARE, if (auto) Bundle().apply { putBoolean("auto", true) } else null, null)
    fun forgetEmptyBowl() = sendRaw(Ipc.FORGET_BOWL, null, null)
    fun acknowledge(cb: (Verdict) -> Unit) = sendRaw(Ipc.ACK_FAULT, null, cb)
    fun turboDown(accessory: Accessory, cb: (Verdict) -> Unit) =
        sendRaw(Ipc.TURBO_DOWN, Bundle().apply { putString("accessory", accessory.name) }, cb)
    fun turboUp() = sendRaw(Ipc.TURBO_UP, null, null)

    /** Smart : la personne confirme que le contenu est froid (température pas encore mesurée), avant le Turbo. */
    fun confirmCold(cb: (Verdict) -> Unit) = sendRaw(Ipc.CONFIRM_COLD, null, cb)
    fun reconfigure(cb: (Verdict) -> Unit) = sendRaw(Ipc.RECONFIGURE, null, cb)
    fun suspendPort(cb: (Verdict) -> Unit) = sendRaw(Ipc.SUSPEND_PORT, null, cb)
    fun resumePort(cb: (Verdict) -> Unit = {}) = sendRaw(Ipc.RESUME_PORT, null, cb)
    fun setStandby(enabled: Boolean) =
        sendRaw(Ipc.STANDBY, Bundle().apply { putBoolean("standby", enabled) }, null)

    fun adjust(speed: Int? = null, temp: Int? = null, reverse: Boolean? = null, totalMs: Long? = null,
               hot: Boolean = false, smartLevel: Int? = null, cb: (Verdict) -> Unit) {
        val b = Bundle()
        speed?.let { b.putInt("speed", it) }
        temp?.let { b.putInt("temp", it) }
        // Smart : niveau de sa table pour 40 °C (absent de celle du Connect), avec [temp].
        smartLevel?.let { b.putInt("smartLevel", it) }
        reverse?.let { b.putBoolean("reverse", it) }
        totalMs?.let { b.putLong("totalMs", it) }
        b.putBoolean("hot", hot)
        sendRaw(Ipc.ADJUST, b, cb)
    }

    /** Commandes de la base simulée (mode démo). */
    fun simulate(lid: Boolean? = null, error: Int? = null, weight: Int? = null) {
        val b = Bundle()
        lid?.let { b.putBoolean("lid", it) }
        error?.let { b.putInt("error", it) }
        weight?.let { b.putInt("weight", it) }
        sendRaw(Ipc.SIM, b, null)
    }
}
