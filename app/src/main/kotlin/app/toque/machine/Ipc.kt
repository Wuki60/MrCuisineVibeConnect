package app.toque.machine

import android.os.Bundle
import app.toque.core.i18n.tr
import app.toque.core.machine.Accessory
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobSpec
import app.toque.core.machine.Phase
import app.toque.core.machine.Setting
import app.toque.core.machine.Snapshot
import app.toque.core.machine.Verdict
import app.toque.core.machine.Violation
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Operation

/** Messages échangés entre l'interface et le processus machine. */
object Ipc {
    const val REGISTER = 1
    const val UNREGISTER = 2
    const val START_JOB = 10
    const val PAUSE = 11
    const val RESUME = 12
    const val STOP = 13
    const val CLEAR = 14
    const val ADJUST = 15
    const val TURBO_DOWN = 16
    const val TURBO_UP = 17
    const val TARE = 18
    const val ACK_FAULT = 19
    const val RECONFIGURE = 20
    const val SUSPEND_PORT = 21
    const val RESUME_PORT = 22
    const val SIM = 23

    /** Clé de la langue de l'interface, jointe à chaque commande. */
    const val LANG = "lang"

    const val SNAPSHOT = 100
    const val EVENT = 101
    const val REPLY = 102

    fun snapshotToBundle(s: Snapshot): Bundle {
        val b = Bundle()
        for ((k, v) in s.toMap()) when (v) {
            is String -> b.putString(k, v)
            is Int -> b.putInt(k, v)
            is Long -> b.putLong(k, v)
            is Boolean -> b.putBoolean(k, v)
        }
        return b
    }

    fun bundleToSnapshot(b: Bundle): Snapshot {
        val m = HashMap<String, Any?>()
        for (k in b.keySet()) m[k] = b.get(k)
        return Snapshot.fromMap(m)
    }

    fun jobToBundle(j: JobSpec): Bundle {
        val b = Bundle()
        b.putString("kind", j.kind.name)
        b.putString("label", j.label)
        b.putLong("totalMs", j.totalMs)
        b.putBoolean("cyclic", j.cyclic)
        b.putString("accessory", j.accessory.name)
        b.putBoolean("hot", j.hotBlendConfirmed)
        b.putIntArray("ops", j.phases.map { it.setting.operation.wire }.toIntArray())
        b.putIntArray("speeds", j.phases.map { it.setting.speed }.toIntArray())
        b.putIntArray("temps", j.phases.map { it.setting.temperatureLevel }.toIntArray())
        b.putIntArray("dirs", j.phases.map { it.setting.direction.androidValue }.toIntArray())
        b.putLongArray("durations", j.phases.map { it.durationMs }.toLongArray())
        return b
    }

    fun bundleToJob(b: Bundle): JobSpec? {
        val ops = b.getIntArray("ops") ?: return null
        val speeds = b.getIntArray("speeds") ?: return null
        val temps = b.getIntArray("temps") ?: return null
        val dirs = b.getIntArray("dirs") ?: return null
        val durations = b.getLongArray("durations") ?: return null
        val n = ops.size
        if (speeds.size != n || temps.size != n || dirs.size != n || durations.size != n || n == 0) return null
        val phases = (0 until n).map { i ->
            val op = Operation.values().firstOrNull { it.wire == ops[i] } ?: return null
            val dir = if (dirs[i] == 0) Direction.COUNTER_CLOCKWISE else Direction.CLOCKWISE
            Phase(Setting(op, speeds[i], temps[i], dir), durations[i])
        }
        return JobSpec(
            kind = try { JobKind.valueOf(b.getString("kind") ?: "") } catch (_: Exception) { return null },
            label = b.getString("label") ?: "",
            phases = phases,
            totalMs = b.getLong("totalMs"),
            cyclic = b.getBoolean("cyclic"),
            accessory = try { Accessory.valueOf(b.getString("accessory") ?: "") } catch (_: Exception) { Accessory.BLADE },
            hotBlendConfirmed = b.getBoolean("hot"),
        )
    }

    fun verdictToBundle(v: Verdict): Bundle {
        val b = Bundle()
        b.putBoolean("ok", v.ok)
        b.putStringArray("codes", v.violations.map { it.code }.toTypedArray())
        b.putStringArray("messages", v.violations.map { it.message }.toTypedArray())
        return b
    }

    fun bundleToVerdict(b: Bundle?): Verdict {
        if (b == null) return Verdict.refuse("ipc", tr("Pas de réponse de la base"))
        if (b.getBoolean("ok")) return Verdict.OK
        val codes = b.getStringArray("codes") ?: emptyArray()
        val messages = b.getStringArray("messages") ?: emptyArray()
        return Verdict.refuse(codes.indices.map { Violation(codes[it], messages.getOrElse(it) { "" }) })
    }
}
