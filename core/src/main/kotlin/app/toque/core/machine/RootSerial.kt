package app.toque.core.machine

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Exécution de commandes via `su` (Magisk / Kitsune Mask). */
object RootShell {
    class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
    }

    private fun drain(input: InputStream): Thread {
        val buffer = ByteArrayOutputStream()
        val t = Thread {
            try {
                val b = ByteArray(4096)
                while (true) {
                    val n = input.read(b)
                    if (n < 0) break
                    synchronized(buffer) { buffer.write(b, 0, n) }
                }
            } catch (_: IOException) {
            }
        }
        t.isDaemon = true
        t.start()
        drained[t] = buffer
        return t
    }

    private val drained = java.util.WeakHashMap<Thread, ByteArrayOutputStream>()

    fun run(command: String, timeoutMs: Long = 10_000): Result {
        val p = try {
            ProcessBuilder("su", "-c", command).start()
        } catch (e: IOException) {
            return Result(-1, "", "su introuvable : ${e.message}")
        }
        try {
            p.outputStream.close()
        } catch (_: IOException) {
        }
        val to = drain(p.inputStream)
        val te = drain(p.errorStream)
        var code = -1
        val waiter = Thread {
            try {
                code = p.waitFor()
            } catch (_: InterruptedException) {
            }
        }
        waiter.start()
        waiter.join(timeoutMs)
        if (waiter.isAlive) {
            p.destroy()
            waiter.interrupt()
            return Result(-2, text(to), "délai dépassé")
        }
        to.join(500)
        te.join(500)
        return Result(code, text(to), text(te))
    }

    private fun text(t: Thread): String {
        val b = drained[t] ?: return ""
        synchronized(b) { return b.toString("UTF-8") }
    }

    fun available(): Boolean = run("id -u", 8_000).out.trim() == "0"
}

/**
 * Liaison série réelle avec la base moteur (`/dev/ttyMT0`, 4800 bauds), via un petit pont
 * shell exécuté en root. Exige un accès exclusif : l'appli d'origine doit être arrêtée.
 */
class RootSerialTransport(
    private val device: String = "/dev/ttyMT0",
    private val baud: Int = 4800,
    private val vendorPackage: String? = "de.silpion.mc2",
) : Transport {
    override val name: String get() = "Base moteur ($device)"

    @Volatile private var process: Process? = null

    override fun open() {
        val probe = RootShell.run(
            "id -u; for b in /data/adb/magisk/busybox /sbin/.magisk/busybox/busybox /system/xbin/busybox /system/bin/busybox; " +
                "do if [ -x \$b ]; then echo BB=\$b; break; fi; done; [ -e $device ] && echo DEV=ok",
            10_000,
        )
        val lines = probe.out.lines().map { it.trim() }
        if (lines.firstOrNull() != "0") throw IOException("Accès root refusé (Magisk installé et autorisé ?)")
        val bb = lines.firstOrNull { it.startsWith("BB=") }?.substring(3)
            ?: throw IOException("busybox introuvable (fourni par Magisk)")
        if (lines.none { it == "DEV=ok" }) throw IOException("$device absent")

        if (vendorPackage != null) RootShell.run("am force-stop $vendorPackage", 8_000)

        val holders = RootShell.run(
            "B=$bb; for p in /proc/[0-9]*; do for f in \$p/fd/*; do " +
                "if [ \"\$(\$B readlink \$f 2>/dev/null)\" = \"$device\" ]; then " +
                "echo \"\${p#/proc/} \$(\$B tr '\\000' ' ' < \$p/cmdline)\"; break; fi; done; done",
            20_000,
        )
        val foreign = ArrayList<String>()
        for (line in holders.out.lines()) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val pid = l.substringBefore(' ')
            val cmd = l.substringAfter(' ', "")
            if (cmd.contains("cat") && cmd.contains(device) || cmd.trim() == "cat" || cmd.contains("busybox cat")) {
                RootShell.run("kill $pid", 5_000) // ancien pont de cette appli
            } else {
                foreign += "$pid $cmd".trim()
            }
        }
        if (foreign.isNotEmpty())
            throw IOException("Le port série est déjà utilisé par : ${foreign.joinToString()}")

        val script = "D=$device; B=$bb; " +
            "\$B stty -F \$D $baud raw -echo cs8 -cstopb -parenb -crtscts clocal cread || exit 3; " +
            "\$B cat \$D & R=\$!; trap 'kill \$R 2>/dev/null' EXIT INT TERM HUP; " +
            "\$B cat > \$D; kill \$R 2>/dev/null"
        val p = ProcessBuilder("su", "-c", script).start()
        process = p
        try {
            Thread.sleep(400)
        } catch (_: InterruptedException) {
        }
        val exited = try {
            p.exitValue()
        } catch (_: IllegalThreadStateException) {
            null
        }
        if (exited != null) {
            val err = try {
                p.errorStream.bufferedReader().readText().trim()
            } catch (_: IOException) {
                ""
            }
            process = null
            throw IOException("Pont série arrêté (code $exited) $err")
        }
    }

    override fun write(frame: ByteArray) {
        val p = process ?: throw IOException("port fermé")
        p.outputStream.write(frame)
        p.outputStream.flush()
    }

    override fun read(buffer: ByteArray): Int {
        val p = process ?: return -1
        return p.inputStream.read(buffer)
    }

    override fun close() {
        val p = process ?: return
        process = null
        try {
            p.outputStream.close()
        } catch (_: IOException) {
        }
        try {
            Thread.sleep(150)
        } catch (_: InterruptedException) {
        }
        p.destroy()
    }
}
