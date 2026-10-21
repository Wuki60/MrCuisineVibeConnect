package app.toque.core.machine

import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.Operation

/** Accessoire monté dans le bol : il limite la vitesse autorisée. */
enum class Accessory(private val fr: String, val maxSpeed: Int) {
    BLADE(trKey("Lame"), 10),
    WHISK(trKey("Fouet"), 4);

    val label: String get() = tr(fr)
}

/** Réglage machine transmis à la base pendant une phase. */
data class Setting(
    val operation: Operation,
    val speed: Int,
    val temperatureLevel: Int,
    val direction: Direction,
) {
    val heated: Boolean get() = temperatureLevel > 0
    val temperatureC: Int get() = Mcc.celsiusForLevel(temperatureLevel)
    val isRest: Boolean get() = operation == Operation.STOP

    companion object {
        val STOP = Setting(Operation.STOP, 0, 0, Direction.CLOCKWISE)
        fun rest(direction: Direction = Direction.CLOCKWISE) = Setting(Operation.STOP, 0, 0, direction)
    }
}

enum class JobKind(private val fr: String) {
    MANUAL(trKey("Cuisson manuelle")),
    KNEAD(trKey("Pétrir")),
    STEAM(trKey("Vapeur")),
    TOAST(trKey("Rissoler")),
    TURBO(trKey("Turbo"));

    val label: String get() = tr(fr)
}

data class Phase(val setting: Setting, val durationMs: Long)

/**
 * Description complète d'un travail à exécuter. Les phases sont rejouées en boucle si
 * [cyclic] jusqu'à [totalMs], sinon enchaînées une seule fois.
 */
data class JobSpec(
    val kind: JobKind,
    val label: String,
    val phases: List<Phase>,
    val totalMs: Long,
    val cyclic: Boolean,
    val accessory: Accessory = Accessory.BLADE,
    val hotBlendConfirmed: Boolean = false,
)

/** Raison précise d'un refus, affichable telle quelle. */
data class Violation(val code: String, val message: String)

object Limits {
    const val MAX_SPEED_HEATED = 3
    const val MAX_SPEED_REVERSE = 3
    const val MAX_SPEED_KNEAD = 4
    const val HOT_C = 60
    const val MAX_MS_HIGH_SPEED = 15 * 60_000L    // vitesse > 6
    const val MAX_MS_DEFAULT = 99 * 60_000L
    const val MAX_MS_KNEAD = 120_000L
    const val MAX_MS_TOAST = 840_000L
    const val MAX_MS_TURBO_PRESS = 3_000L
    const val MIN_MS = 1_000L
}

/**
 * Règles de sécurité. Reprend toutes les limites de l'appli d'origine (vitesse en chauffe,
 * sens inverse, durées, Turbo) et en ajoute : accessoire fouet, confirmation de mixage
 * chaud, températures uniquement issues de la table d'origine.
 */
object SafetyPolicy {

    fun checkSetting(
        setting: Setting,
        kind: JobKind,
        accessory: Accessory,
        actualTemperatureC: Int?,
        hotBlendConfirmed: Boolean,
    ): List<Violation> {
        val v = ArrayList<Violation>(2)
        if (setting.speed !in 0..10) v += Violation("speed_range", tr("Vitesse hors plage (0 à 10)"))
        if (setting.temperatureLevel !in 0..19) v += Violation("temp_range", tr("Température hors table"))
        if (v.isNotEmpty()) return v
        if (setting.isRest) return v

        val expectedOp = when (kind) {
            JobKind.MANUAL -> Operation.STANDARD
            JobKind.KNEAD -> Operation.KNEAD
            JobKind.STEAM -> Operation.STEAM
            JobKind.TOAST -> Operation.TOAST
            JobKind.TURBO -> Operation.TURBO
        }
        if (setting.operation != expectedOp) v += Violation("operation", tr("Opération incohérente avec le programme"))

        when (kind) {
            JobKind.MANUAL -> {
                if (setting.speed < 1) v += Violation("speed_min", tr("Vitesse minimale : 1"))
                if (setting.heated && setting.speed > Limits.MAX_SPEED_HEATED)
                    v += Violation("heated_speed", tr("En chauffe, vitesse %d maximum", Limits.MAX_SPEED_HEATED))
                if (setting.direction == Direction.COUNTER_CLOCKWISE && setting.speed > Limits.MAX_SPEED_REVERSE)
                    v += Violation("reverse_speed", tr("En sens inverse, vitesse %d maximum", Limits.MAX_SPEED_REVERSE))
            }
            JobKind.KNEAD -> {
                if (setting.speed > Limits.MAX_SPEED_KNEAD) v += Violation("knead_speed", tr("Pétrissage : vitesse 4 maximum"))
                if (setting.heated) v += Violation("knead_heat", tr("Pas de chauffe en pétrissage"))
            }
            JobKind.TOAST -> {
                if (setting.speed > 1) v += Violation("toast_speed", tr("Rissoler : vitesse 1 maximum"))
                if (setting.direction != Direction.COUNTER_CLOCKWISE) v += Violation("toast_dir", tr("Rissoler se fait en sens inverse"))
            }
            JobKind.STEAM -> {
                if (setting.speed > 1) v += Violation("steam_speed", tr("Vapeur : vitesse 1 maximum"))
            }
            JobKind.TURBO -> {
                if (setting.speed != 10) v += Violation("turbo_speed", tr("Turbo = vitesse 10"))
                if (setting.heated) v += Violation("turbo_heat", tr("Turbo sans chauffe uniquement"))
                if (setting.direction != Direction.CLOCKWISE) v += Violation("turbo_dir", tr("Turbo en sens normal uniquement"))
                if (actualTemperatureC != null && actualTemperatureC >= Limits.HOT_C)
                    v += Violation("turbo_hot", tr("Turbo interdit : contenu à %d °C (≥ %d °C)", actualTemperatureC, Limits.HOT_C))
            }
        }
        if (setting.speed > accessory.maxSpeed)
            v += Violation("accessory_speed", tr("%s : vitesse %d maximum", accessory.label, accessory.maxSpeed))
        if (accessory == Accessory.WHISK && kind == JobKind.TURBO)
            v += Violation("accessory_turbo", tr("Pas de Turbo avec le fouet"))
        if (kind != JobKind.TURBO && actualTemperatureC != null && actualTemperatureC >= Limits.HOT_C &&
            setting.speed >= 4 && !hotBlendConfirmed
        ) v += Violation(HOT_BLEND, tr("Contenu chaud (%d °C) : confirmez le mixage à vitesse %d", actualTemperatureC, setting.speed))
        return v
    }

    /** Durée maximale autorisée pour un travail, d'après son réglage le plus rapide. */
    fun maxDurationMs(spec: JobSpec): Long {
        val fastest = spec.phases.maxOfOrNull { it.setting.speed } ?: 0
        val base = if (fastest > 6) Limits.MAX_MS_HIGH_SPEED else Limits.MAX_MS_DEFAULT
        return when (spec.kind) {
            JobKind.KNEAD -> minOf(base, Limits.MAX_MS_KNEAD)
            JobKind.TOAST -> minOf(base, Limits.MAX_MS_TOAST)
            JobKind.TURBO -> Limits.MAX_MS_TURBO_PRESS
            else -> base
        }
    }

    fun checkJob(spec: JobSpec, actualTemperatureC: Int?): List<Violation> {
        val v = ArrayList<Violation>()
        if (spec.phases.isEmpty()) v += Violation("no_phase", tr("Programme vide"))
        if (spec.phases.all { it.setting.isRest }) v += Violation("only_rest", tr("Aucune phase active"))
        if (spec.totalMs < Limits.MIN_MS) v += Violation("too_short", tr("Durée trop courte"))
        val max = maxDurationMs(spec)
        if (spec.totalMs > max) v += Violation("too_long", tr("Durée maximale : %s", formatDuration(max)))
        for (p in spec.phases) {
            if (p.durationMs <= 0) v += Violation("phase_duration", tr("Phase de durée nulle"))
            for (x in checkSetting(p.setting, spec.kind, spec.accessory, actualTemperatureC, spec.hotBlendConfirmed))
                if (v.none { it.code == x.code }) v += x
        }
        return v
    }

    const val HOT_BLEND = "hot_blend"

    fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s} s"
            s % 60 == 0L -> "${s / 60} min"
            else -> "${s / 60} min ${s % 60} s"
        }
    }
}

/** Programmes prédéfinis, repris de l'appli d'origine (valeurs du build 292). */
object Programs {
    private val CCW = Direction.COUNTER_CLOCKWISE
    private val CW = Direction.CLOCKWISE

    fun manual(
        speed: Int, temperatureLevel: Int, direction: Direction, durationMs: Long,
        accessory: Accessory = Accessory.BLADE, hotBlendConfirmed: Boolean = false, label: String = JobKind.MANUAL.label,
    ) = JobSpec(
        kind = JobKind.MANUAL,
        label = label,
        phases = listOf(Phase(Setting(Operation.STANDARD, speed, temperatureLevel, direction), durationMs)),
        totalMs = durationMs,
        cyclic = false,
        accessory = accessory,
        hotBlendConfirmed = hotBlendConfirmed,
    )

    /** Pétrir : 15 s inverse, repos, puis sens normal, vitesse 4. 45 s par défaut, 2 min max. */
    fun knead(durationMs: Long = 45_000L) = JobSpec(
        kind = JobKind.KNEAD,
        label = JobKind.KNEAD.label,
        phases = listOf(
            Phase(Setting(Operation.KNEAD, 4, 0, CCW), 15_000L),
            Phase(Setting.rest(CCW), 1_000L),
            Phase(Setting(Operation.KNEAD, 4, 0, CW), 104_000L),
        ),
        totalMs = durationMs,
        cyclic = true,
    )

    /**
     * Vapeur à 120 °C. Vitesse 0 : la lame ne tourne pas (l'appli d'origine la faisait parfois
     * tourner en vapeur, ce que beaucoup d'utilisateurs signalent).
     */
    fun steam(durationMs: Long = 20 * 60_000L) = JobSpec(
        kind = JobKind.STEAM,
        label = JobKind.STEAM.label,
        phases = listOf(Phase(Setting(Operation.STEAM, 0, 17, CW), durationMs)),
        totalMs = durationMs,
        cyclic = false,
    )

    /** Rissoler : 5 s vitesse 1 / 30 s pause, sens inverse, 130 °C. 7 min par défaut, 14 min max. */
    fun toast(durationMs: Long = 420_000L) = JobSpec(
        kind = JobKind.TOAST,
        label = JobKind.TOAST.label,
        phases = listOf(
            Phase(Setting(Operation.TOAST, 1, 19, CCW), 5_000L),
            Phase(Setting(Operation.TOAST, 0, 19, CCW), 30_000L),
        ),
        totalMs = durationMs,
        cyclic = true,
    )

    fun turboSetting() = Setting(Operation.TURBO, 10, 0, CW)
}
