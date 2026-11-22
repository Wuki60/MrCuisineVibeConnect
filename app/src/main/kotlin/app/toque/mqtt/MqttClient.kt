package app.toque.mqtt

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Client MQTT 3.1.1 minimal (CONNECT / PUBLISH / PING / DISCONNECT).
 * Publication seule : aucun SUBSCRIBE. QoS 0 pour l'état.
 */
class MqttClient(
    private val host: String,
    private val port: Int,
    private val clientId: String,
    private val username: String,
    private val password: String,
    private val tls: Boolean,
    private val willTopic: String,
    private val willPayload: ByteArray,
    private val keepAliveSec: Int = 60,
) : Closeable {
    private val utf8: Charset = Charsets.UTF_8
    private val open = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    @Volatile private var input: DataInputStream? = null
    @Volatile private var output: DataOutputStream? = null

    val connected: Boolean get() = open.get()

    @Throws(IOException::class)
    fun connect() {
        if (open.get()) return
        // Socket clair d'abord (timeout de connexion), puis enveloppe TLS avec SNI + vérif du nom.
        val plain = Socket()
        plain.tcpNoDelay = true
        plain.connect(InetSocketAddress(host, port), 8_000)
        val s: Socket = if (tls) {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(plain, host, port, true) as SSLSocket
            ssl.startHandshake()
            val verifier = HttpsURLConnection.getDefaultHostnameVerifier()
            if (!verifier.verify(host, ssl.session)) {
                try { ssl.close() } catch (_: Exception) {}
                throw IOException("hostname not verified: $host")
            }
            ssl
        } else {
            plain
        }
        s.soTimeout = (keepAliveSec + 10) * 1000
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
        socket = s
        output = out
        input = inp
        writeConnect(out)
        out.flush()
        readConnAck(inp)
        open.set(true)
        Thread({ readLoop() }, "toque-mqtt-rx").apply { isDaemon = true; start() }
    }

    @Throws(IOException::class)
    fun publish(topic: String, payload: ByteArray, retain: Boolean) {
        val out = output ?: throw IOException("not connected")
        if (!open.get()) throw IOException("not connected")
        synchronized(out) {
            writePublish(out, topic, payload, retain)
            out.flush()
        }
    }

    fun ping() {
        val out = output ?: return
        if (!open.get()) return
        try {
            synchronized(out) {
                out.writeByte(0xC0)
                out.writeByte(0x00)
                out.flush()
            }
        } catch (_: IOException) {
            closeQuiet()
        }
    }

    override fun close() {
        closeQuiet(sendDisconnect = true)
    }

    private fun closeQuiet(sendDisconnect: Boolean = false) {
        if (!open.getAndSet(false)) {
            try { socket?.close() } catch (_: Exception) {}
            return
        }
        try {
            if (sendDisconnect) {
                output?.let { out ->
                    synchronized(out) {
                        out.writeByte(0xE0)
                        out.writeByte(0x00)
                        out.flush()
                    }
                }
            }
        } catch (_: Exception) {
        }
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
    }

    private fun readLoop() {
        val inp = input ?: return
        try {
            while (open.get()) {
                val first = inp.read()
                if (first < 0) break
                val remaining = readRemainingLength(inp)
                if (remaining > 0) {
                    val skip = ByteArray(remaining)
                    inp.readFully(skip)
                }
            }
        } catch (_: Exception) {
        } finally {
            closeQuiet()
        }
    }

    private fun writeConnect(out: DataOutputStream) {
        val payload = ByteArrayOutputStream()
        val p = DataOutputStream(payload)
        writeUtf(p, "MQTT")
        p.writeByte(4)
        var flags = 0x02 // clean session
        if (willTopic.isNotEmpty()) {
            flags = flags or 0x04 // will
            flags = flags or 0x20 // will retain
        }
        if (username.isNotEmpty()) flags = flags or 0x80
        if (password.isNotEmpty()) flags = flags or 0x40
        p.writeByte(flags)
        p.writeShort(keepAliveSec)
        writeUtf(p, clientId.take(23).ifEmpty { "toque" })
        if (willTopic.isNotEmpty()) {
            writeUtf(p, willTopic)
            writeBin(p, willPayload)
        }
        if (username.isNotEmpty()) writeUtf(p, username)
        if (password.isNotEmpty()) writeUtf(p, password)
        val body = payload.toByteArray()
        out.writeByte(0x10)
        writeRemainingLength(out, body.size)
        out.write(body)
    }

    private fun readConnAck(inp: DataInputStream) {
        val first = inp.readUnsignedByte()
        if (first and 0xF0 != 0x20) throw IOException("expected CONNACK, got 0x${first.toString(16)}")
        val rem = readRemainingLength(inp)
        if (rem < 2) throw IOException("short CONNACK")
        inp.readUnsignedByte()
        val rc = inp.readUnsignedByte()
        if (rem > 2) {
            val skip = ByteArray(rem - 2)
            inp.readFully(skip)
        }
        if (rc != 0) throw IOException("CONNACK refused: $rc")
    }

    private fun writePublish(out: DataOutputStream, topic: String, payload: ByteArray, retain: Boolean) {
        val vh = ByteArrayOutputStream()
        val v = DataOutputStream(vh)
        writeUtf(v, topic)
        val header = vh.toByteArray()
        var type = 0x30
        if (retain) type = type or 0x01
        out.writeByte(type)
        writeRemainingLength(out, header.size + payload.size)
        out.write(header)
        out.write(payload)
    }

    private fun writeUtf(out: DataOutputStream, s: String) {
        val b = s.toByteArray(utf8)
        if (b.size > 0xFFFF) throw IOException("string too long")
        out.writeShort(b.size)
        out.write(b)
    }

    private fun writeBin(out: DataOutputStream, b: ByteArray) {
        if (b.size > 0xFFFF) throw IOException("payload too long")
        out.writeShort(b.size)
        out.write(b)
    }

    private fun writeRemainingLength(out: DataOutputStream, length: Int) {
        var x = length
        do {
            var dig = x % 128
            x /= 128
            if (x > 0) dig = dig or 0x80
            out.writeByte(dig)
        } while (x > 0)
    }

    private fun readRemainingLength(inp: DataInputStream): Int {
        var multiplier = 1
        var value = 0
        do {
            val enc = inp.readUnsignedByte()
            value += (enc and 0x7F) * multiplier
            multiplier *= 128
            if (multiplier > 128 * 128 * 128) throw IOException("remaining length overflow")
            if (enc and 0x80 == 0) break
        } while (true)
        return value
    }
}
