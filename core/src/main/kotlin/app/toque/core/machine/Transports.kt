package app.toque.core.machine

import app.toque.core.i18n.tr
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.MccStreamParser
import app.toque.core.protocol.Operation
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Liaison d'octets avec la base moteur (réelle, simulée ou jumeau numérique). */
interface Transport {
    val name: String

    @Throws(IOException::class)
    fun open()

    @Throws(IOException::class)
    fun write(frame: ByteArray)

    /** Bloquant. Renvoie -1 en fin de flux. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray): Int

    fun close()
}

/** Jumeau numérique du SDK (TCP, en général via `adb reverse tcp:8765 tcp:8765`). */
class TcpTransport(private val host: String, private val port: Int) : Transport {
    override val name: String get() = tr("Jumeau numérique %s:%d", host, port)
    @Volatile private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun open() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 3_000)
        socket = s
        input = s.getInputStream()
        output = s.getOutputStream()
    }

    override fun write(frame: ByteArray) {
        val o = output ?: throw IOException(tr("non connecté"))
        o.write(frame)
        o.flush()
    }

    override fun read(buffer: ByteArray): Int {
        val i = input ?: throw IOException(tr("non connecté"))
        return i.read(buffer)
    }

    override fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
    }
}

/**
 * Base moteur simulée pour le mode démo. Physique volontairement simple et SYNTHÉTIQUE :
 * elle sert à essayer l'interface, pas à valider un comportement réel.
 */
class SimBoard(private val clock: Clock) {
    private val parser = MccStreamParser(Mcc.TX_SIZE)
    private var lastMs = clock.nowMs()
    private var op = Operation.STOP
    private var speedLevel = 0
    private var tempLevel = 0
    private var clockwise = true
    private var rpm = 0.0
    private var temp = 22.0
    private var rawWeight = 0.0
    private var tareOffset = 0.0

    @Volatile var lidOpen = false
    @Volatile var errorCode = 0
    @Volatile var bowlPresent = true
    @Volatile var contentWeightG = 0
    @Volatile var silent = false

    private val rpmForLevel = intArrayOf(0, 300, 800, 1200, 2400, 4800, 7800, 10800, 13800, 16800, 19500)

    @Synchronized
    fun reset() {
        op = Operation.STOP; speedLevel = 0; tempLevel = 0; rpm = 0.0; temp = 22.0
        parser.reset()
    }

    @Synchronized
    fun feed(bytes: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(1)
        for (frame in parser.feed(bytes)) {
            advance()
            val o = frame[3].toInt() and 0xFF
            op = Operation.values().firstOrNull { it.wire == o } ?: Operation.STOP
            speedLevel = frame[5].toInt().coerceIn(0, 10)
            tempLevel = frame[6].toInt().coerceIn(0, 19)
            clockwise = frame[11].toInt() == 0
            if ((frame[4].toInt() and 0xFF) == 0xA9) tareOffset = rawWeight
            if (op == Operation.TURBO) speedLevel = 10
            if (op == Operation.STOP) { speedLevel = 0; tempLevel = 0 }
            if (!silent) out.add(response())
        }
        return out
    }

    private fun heating(): Boolean = tempLevel > 0 && errorCode == 0 &&
        (op == Operation.STANDARD || op == Operation.STEAM || op == Operation.TOAST)

    private fun advance() {
        val now = clock.nowMs()
        val dt = ((now - lastMs).coerceIn(0, 1000)) / 1000.0
        lastMs = now
        val target = if (lidOpen || errorCode != 0 || op == Operation.STOP) 0.0 else rpmForLevel[speedLevel].toDouble()
        rpm = if (rpm < target) minOf(target, rpm + 9000 * dt) else maxOf(target, rpm - 7000 * dt)
        val goal = if (heating()) Mcc.celsiusForLevel(tempLevel).toDouble() else 22.0
        temp = if (temp < goal) minOf(goal, temp + 0.8 * dt) else maxOf(goal, temp - 0.08 * dt)
        val w = contentWeightG.toDouble()
        rawWeight += (w - rawWeight) * minOf(1.0, dt * 4)
    }

    private fun response(): ByteArray {
        val r = ByteArray(Mcc.RX_SIZE)
        val motor = rpm > 0.5 || (op != Operation.STOP && speedLevel > 0 && !lidOpen && errorCode == 0)
        val heat = heating()
        val mode = when {
            motor && heat -> 1
            heat -> 2
            motor -> 3
            else -> 0
        }
        val weight = (rawWeight - tareOffset).toInt()
        var status = mode
        if (kotlin.math.abs(rawWeight - contentWeightG) < 2) status = status or 0x04
        if (clockwise) status = status or 0x08
        if (errorCode != 0) status = status or 0x10
        if (heat && temp >= Mcc.celsiusForLevel(tempLevel)) status = status or 0x20
        if (bowlPresent) status = status or 0x40
        if (lidOpen) status = status or 0x80
        val ext = when (op) { Operation.STEAM -> 3; Operation.TOAST -> 4; Operation.KNEAD -> 5; else -> 0 }
        r[0] = 0x55; r[1] = 27; r[2] = 0xB1.toByte()
        r[3] = status.toByte(); r[4] = ext.toByte(); r[5] = errorCode.toByte(); r[6] = speedLevel.toByte()
        putWord(r, 7, rpmForLevel[speedLevel]); putWord(r, 9, rpm.toInt())
        r[11] = tempLevel.toByte(); putWord(r, 12, Mcc.celsiusForLevel(tempLevel)); putWord(r, 14, temp.toInt())
        r[16] = (weight shr 24).toByte(); r[17] = (weight shr 16).toByte(); r[18] = (weight shr 8).toByte(); r[19] = weight.toByte()
        putWord(r, 20, 5016)
        r[22] = 0x02; r[23] = 0; r[24] = 0
        r[25] = Mcc.checksum(r, 25).toByte(); r[26] = 0xAA.toByte()
        return r
    }

    private fun putWord(r: ByteArray, at: Int, v: Int) {
        val c = v.coerceIn(0, 65535)
        r[at] = (c shr 8).toByte(); r[at + 1] = c.toByte()
    }
}

class SimTransport(val board: SimBoard) : Transport {
    override val name: String get() = tr("Démo (base simulée)")
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false
    private var pending: ByteArray? = null
    private var pendingAt = 0

    override fun open() {
        closed = false
        queue.clear()
        board.reset()
    }

    override fun write(frame: ByteArray) {
        if (closed) throw IOException(tr("fermé"))
        for (r in board.feed(frame)) queue.put(r)
    }

    override fun read(buffer: ByteArray): Int {
        while (true) {
            if (closed) return -1
            val p = pending
            if (p != null) {
                val n = minOf(buffer.size, p.size - pendingAt)
                System.arraycopy(p, pendingAt, buffer, 0, n)
                pendingAt += n
                if (pendingAt >= p.size) pending = null
                return n
            }
            val next = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (next.isEmpty()) return -1
            pending = next
            pendingAt = 0
        }
    }

    override fun close() {
        closed = true
        queue.offer(ByteArray(0))
    }
}
