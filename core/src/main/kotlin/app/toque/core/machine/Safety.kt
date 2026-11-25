package app.toque.core.machine

import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.Operation
import app.toque.core.protocol.Smart
import app.toque.core.protocol.SmartProgram

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
    /**
     * Monsieur Cuisine Smart : programme automatique de l'appli d'origine, envoyé avec sa trame
     * capturée. [speed], [direction] et [temperatureLevel] sont alors ceux du programme (règles
     * de sécurité, affichage). Null pour tout le reste.
     */
    val program: SmartProgram? = null,
    /**
     * Monsieur Cuisine Smart : niveau de sa table à 21 niveaux (0..20), pour une température
     * absente de la table de Toque (40 °C). [temperatureLevel] vaut alors le niveau de Toque juste
     * en dessous (37 °C pour 40 °C), utilisé par les règles de sécurité. Null sinon.
     */
    val smartLevel: Int? = null,
    /**
     * Smart, avec [program] : température exacte choisie (recette), dans la plage que l'appli
     * d'origine permet pour ce programme ([SmartProgram.allowsCelsius]) ; null = celle de la capture.
     */
    val programCelsius: Int? = null,
) {
    val heated: Boolean get() = temperatureLevel > 0
    val temperatureC: Int get() = when {
        program != null -> programCelsius ?: program.targetCelsius
        smartLevel != null -> Smart.celsiusForLevel(smartLevel)
        else -> Mcc.celsiusForLevel(temperatureLevel)
    }
    val isRest: Boolean get() = operation == Operation.STOP || operation == Operation.SLEEP

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
    TURBO(trKey("Turbo")),

    /** Smart : programme automatique de l'appli d'origine ([SmartProgram]), trame capturée. */
    PROGRAM(trKey("Programme"));

    val label: String get() = tr(fr)
}

/**
 * Smart : ce que la minuterie de la trame (octets 25-27) affiche pendant une phase. La carte ne
 * décompte rien : c'est seulement ce que l'appli d'origine y écrit (capture 16-etapes-mc30).
 */
sealed class PhaseTimer {
    /** Durée totale du travail, la même du début à la fin (Toque ; Saisir de l'appli d'origine). */
    object Total : PhaseTimer()
    /** Temps restant au début de la phase (mijoter, fermenter, malaxer, prélavage). */
    object Remaining : PhaseTimer()
    /** Valeur fixe, en secondes (nettoyage long : 15 min 30, puis 1 min 45). */
    data class Fixed(val seconds: Int) : PhaseTimer()
}

data class Phase(
    val setting: Setting,
    val durationMs: Long,
    val timer: PhaseTimer = PhaseTimer.Total,
    /**
     * Smart : la phase finit dès que la carte signale « température atteinte » (bit 5 de l'octet
     * d'état), au plus après [durationMs] (préchauffe du nettoyage long).
     */
    val untilTemperature: Boolean = false,
)

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
    /**
     * Smart, Malaxer de l'écran Fonctions : comme l'appli d'origine, le pétrissage suivant attend
     * 30 min ([Limits.KNEAD_COOLDOWN_MS]). Pas posé pour les étapes « Pétrir » des recettes.
     */
    val cooldown: Boolean = false,
    /**
     * Fermentation (yaourt) : une cuisson manuelle sans mélange, à [Limits.MAX_C_FERMENT] au plus,
     * qui peut durer [Limits.MAX_MS_FERMENT]. Gardé par [SafetyPolicy.checkJob], réglages en cours compris.
     */
    val ferment: Boolean = false,
) {
    /** Programme à étapes de l'appli d'origine du Smart : chaque phase est une trame capturée. */
    val capturedSteps: Boolean get() = phases.size > 1 && phases.all { it.setting.program != null }
}

/** Raison précise d'un refus, affichable telle quelle. */
data class Violation(val code: String, val message: String)

object Limits {
    const val MAX_SPEED_HEATED = 3
    const val MAX_SPEED_REVERSE = 3
    const val MAX_SPEED_KNEAD = 4
    /** Sans mélange (vitesse 0) : 120 °C au plus, le niveau du programme Vapeur ; au-delà, l'opération vapeur n'a pas été essayée. */
    const val MAX_TEMP_LEVEL_NO_STIR = 17
    const val HOT_C = 60
    /**
     * Seuil de la confirmation « contenu chaud » avant de mixer à vitesse 4 ou plus. Certaines bases ne
     * mesurent qu'en paliers (37, 45, 50, 55, 60… : la table de la machine, voir #100) : 55 °C affiché
     * peut cacher 59 °C. Le palier juste sous [HOT_C] compte donc déjà comme chaud. Le Turbo et la
     * montée progressive gardent [HOT_C].
     */
    const val HOT_BLEND_C = 55
    const val MAX_MS_HIGH_SPEED = 15 * 60_000L    // vitesse > 6
    const val MAX_MS_DEFAULT = 99 * 60_000L
    const val MAX_MS_KNEAD = 120_000L
    const val MAX_MS_TOAST = 840_000L
    const val MAX_MS_TURBO_PRESS = 3_000L
    /** Smart : durée de validité de la confirmation « contenu froid » avant le Turbo. */
    const val COLD_CONFIRM_MS = 5 * 60_000L
    const val MIN_MS = 1_000L
    /** Prélavage et nettoyage long : eau de lavage à 40 °C au plus (appli d'origine : 37 et 40 °C). */
    const val MAX_C_CLEANING = 40
    /** Malaxer : l'appli d'origine fait attendre 30 min entre deux pétrissages (refroidissement). */
    const val KNEAD_COOLDOWN_MS = 30 * 60_000L
    /**
     * Fermentation (yaourt) : chauffe douce, lame à l'arrêt, au-delà des 99 min d'une cuisson
     * manuelle. 12 h au plus, comme le Fermenter de l'appli d'origine du Smart et du Thermomix TM6.
     */
    const val MAX_MS_FERMENT = 12 * 3600_000L
    /** Fermentation : 45 °C au plus. Même si la base gardait la chauffe sans Toque, elle reste tiède. */
    const val MAX_C_FERMENT = 45
}

/**
 * Règles de sécurité. Reprend toutes les limites de l'appli d'origine (vitesse en chauffe,
 * sens inverse, durées, Turbo) et en ajoute : accessoire fouet, confirmation de mixage
 * chaud, températures uniquement issues de la table d'origine.
 *
 * Une cuisson manuelle peut chauffer sans mélanger (vitesse 0) ; sans chauffe, la vitesse
 * minimale reste 1.
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
        val sl = setting.smartLevel
        if (sl != null && (sl !in Smart.TEMPERATURE_LEVELS.indices || Smart.toqueLevelFor(sl) != setting.temperatureLevel))
            v += Violation("temp_range", tr("Température hors table"))
        if (v.isNotEmpty()) return v
        if (setting.isRest) return v
        val p = setting.program
        // Trame capturée : programme automatique (PROGRAM), étape de Malaxer (KNEAD) ou de Saisir (TOAST).
        if (if (p == null) kind == JobKind.PROGRAM else kind != kindOf(p)) v += Violation("program", tr("Programme incohérent"))
        val pc = setting.programCelsius
        if (pc != null && (p == null || !p.allowsCelsius(pc)))
            v += Violation("program", tr("Température impossible pour ce programme"))
        if (p != null && (setting.speed != p.speed || setting.direction != p.direction ||
                setting.temperatureLevel != p.toqueLevelFor(pc ?: p.targetCelsius) || sl != null))
            v += Violation("program", tr("Réglage fixe pour ce programme"))
        if (sl != null && kind != JobKind.MANUAL) v += Violation("program", tr("Programme incohérent"))

        val expectedOp = when (kind) {
            JobKind.MANUAL -> Programs.manualOperation(setting.speed, setting.temperatureLevel)
            JobKind.KNEAD -> Operation.KNEAD
            JobKind.STEAM -> Operation.STEAM
            JobKind.TOAST -> Operation.TOAST
            JobKind.TURBO -> Operation.TURBO
            JobKind.PROGRAM -> Operation.STANDARD
        }
        if (setting.operation != expectedOp) v += Violation("operation", tr("Opération incohérente avec le programme"))

        when (kind) {
            JobKind.MANUAL -> {
                // Vitesse 0 : chauffe sans mélange, la lame à l'arrêt (voir Programs.manualOperation). Sans chauffe, il ne resterait rien à faire.
                if (setting.speed < 1 && !setting.heated) v += Violation("speed_min", tr("Vitesse minimale : 1"))
                if (setting.speed == 0 && setting.temperatureLevel > Limits.MAX_TEMP_LEVEL_NO_STIR)
                    v += Violation("no_stir_temp", tr("Sans mélange, %d °C maximum", Mcc.celsiusForLevel(Limits.MAX_TEMP_LEVEL_NO_STIR)))
                if (setting.speed == 0 && setting.direction != Direction.CLOCKWISE)
                    v += Violation("no_stir_dir", tr("Sans mélange : sens normal uniquement"))
                if (setting.heated && setting.speed > Limits.MAX_SPEED_HEATED)
                    v += Violation("heated_speed", tr("En chauffe, vitesse %d maximum", Limits.MAX_SPEED_HEATED))
                if (setting.direction == Direction.COUNTER_CLOCKWISE && setting.speed > Limits.MAX_SPEED_REVERSE)
                    v += Violation("reverse_speed", tr("En sens inverse, vitesse %d maximum", Limits.MAX_SPEED_REVERSE))
            }
            JobKind.KNEAD -> {
                // Pas de limite « vitesse 3 en sens inverse » ici : le pétrin de Toque a toujours eu
                // une phase à vitesse 4 en sens inverse, et le Malaxer pâte molle de l'appli d'origine
                // du Smart envoie fonction 5, vitesse 4, sens inverse (capture 10). Vitesse 4 au plus.
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
            JobKind.PROGRAM -> if (p != null && p.nettoyage) {
                // Prélavage et nettoyage long de l'appli d'origine : vitesse 10 et sens inverse à
                // vitesse 4 dans de l'eau de lavage à 37 ou 40 °C (trames capturées). Jamais plus chaud.
                if (setting.temperatureC > Limits.MAX_C_CLEANING)
                    v += Violation("clean_temp", tr("Nettoyage : %d °C maximum", Limits.MAX_C_CLEANING))
            } else {
                // Mêmes limites que la cuisson manuelle (déjà respectées par les trames capturées).
                if (setting.heated && setting.speed > Limits.MAX_SPEED_HEATED)
                    v += Violation("heated_speed", tr("En chauffe, vitesse %d maximum", Limits.MAX_SPEED_HEATED))
                if (setting.direction == Direction.COUNTER_CLOCKWISE && setting.speed > Limits.MAX_SPEED_REVERSE)
                    v += Violation("reverse_speed", tr("En sens inverse, vitesse %d maximum", Limits.MAX_SPEED_REVERSE))
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
        if (kind != JobKind.TURBO && actualTemperatureC != null && actualTemperatureC >= Limits.HOT_BLEND_C &&
            setting.speed >= 4 && !hotBlendConfirmed
        ) v += Violation(HOT_BLEND, tr("Contenu chaud (%d °C) : confirmez le mixage à vitesse %d", actualTemperatureC, setting.speed))
        return v
    }

    /** Étape capturée : sorte de travail qui la porte. */
    fun kindOf(p: SmartProgram): JobKind = when (p.operation) {
        Operation.KNEAD -> JobKind.KNEAD
        Operation.TOAST -> JobKind.TOAST
        else -> JobKind.PROGRAM
    }

    /** Durée maximale autorisée pour un travail, d'après son réglage le plus rapide. */
    fun maxDurationMs(spec: JobSpec): Long {
        if (spec.ferment) return Limits.MAX_MS_FERMENT
        val fastest = spec.phases.maxOfOrNull { it.setting.speed } ?: 0
        // Programme à étapes non cyclique (nettoyage long : 1 min 45 à vitesse 10 sur 17 min) : on
        // compte le seul temps passé au-dessus de la vitesse 6.
        val highMs = if (spec.capturedSteps && !spec.cyclic)
            spec.phases.filter { it.setting.speed > 6 }.sumOf { minOf(it.durationMs, spec.totalMs) } else spec.totalMs
        val base = if (fastest > 6 && highMs > Limits.MAX_MS_HIGH_SPEED) Limits.MAX_MS_HIGH_SPEED else Limits.MAX_MS_DEFAULT
        return when (spec.kind) {
            JobKind.KNEAD -> minOf(base, Limits.MAX_MS_KNEAD)
            JobKind.TOAST -> minOf(base, Limits.MAX_MS_TOAST)
            JobKind.TURBO -> Limits.MAX_MS_TURBO_PRESS
            // Durée maximale de l'appli d'origine pour ce programme, sans dépasser la limite de
            // Toque (99 min, 15 min au-dessus de la vitesse 6). Le sous-vide de l'appli d'origine va
            // jusqu'à 12 h : pas avant d'avoir vérifié que la carte s'arrête seule quand Android se
            // tait (PROTOCOLE-SERIE-SMART.md § 8, essai pas encore fait).
            JobKind.PROGRAM -> {
                val p = spec.phases.firstOrNull()?.setting?.program
                when {
                    p == null -> 0L
                    // Cuissons longues à basse température et vitesse 1 au plus (sous-vide, mijoter,
                    // fermenter) : la durée de l'appli d'origine (jusqu'à 12 h), comme sur le robot.
                    // La base du Smart arrête le moteur seule ~4 s sans trame (Camybrith Cooking) ;
                    // l'arrêt de la chauffe dans ce cas reste à vérifier par les testeurs (docs/SMART.md).
                    p in LONG_PROGRAMS -> p.maxS * 1000L
                    else -> minOf(base, p.maxS * 1000L)
                }
            }
            else -> base
        }
    }

    fun checkJob(spec: JobSpec, actualTemperatureC: Int?): List<Violation> {
        val v = ArrayList<Violation>()
        if (spec.phases.isEmpty()) v += Violation("no_phase", tr("Programme vide"))
        if (spec.phases.all { it.setting.isRest }) v += Violation("only_rest", tr("Aucune phase active"))
        // Trames capturées : toutes les phases ou aucune, d'un seul programme de l'appli d'origine.
        val progs = spec.phases.map { it.setting.program }
        if (progs.any { it != null } && (progs.any { it == null } || progs.map { it!!.function }.toSet().size != 1))
            v += Violation("program", tr("Programme incohérent"))
        if (spec.kind == JobKind.PROGRAM && spec.phases.size == 1 && spec.cyclic)
            v += Violation("program", tr("Programme incohérent"))
        if (spec.phases.any { it.untilTemperature && it.setting.program?.nettoyage != true })
            v += Violation("program", tr("Programme incohérent"))
        if (spec.totalMs < Limits.MIN_MS) v += Violation("too_short", tr("Durée trop courte"))
        // Fermentation : longue, donc seulement lame à l'arrêt et chauffe douce, d'un bout à l'autre.
        if (spec.ferment && (spec.kind != JobKind.MANUAL || spec.phases.size != 1 || spec.phases.any {
                it.setting.speed != 0 || !it.setting.heated || it.setting.temperatureC > Limits.MAX_C_FERMENT }))
            v += Violation("ferment", tr("Fermentation : lame à l'arrêt, %d °C maximum", Limits.MAX_C_FERMENT))
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

    /** Programmes du Smart autorisés au-delà de 99 min (voir [maxDurationMs]). */
    private val LONG_PROGRAMS = setOf(
        SmartProgram.SOUS_VIDE, SmartProgram.MIJOTER_BRASSE, SmartProgram.MIJOTER_REPOS,
        SmartProgram.FERMENTER_BRASSE, SmartProgram.FERMENTER_REPOS,
    )

    /** Smart : Turbo demandé alors que la température du contenu n'est pas mesurée. */
    const val COLD_CONFIRM = "cold_confirm"

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

    /**
     * Opération envoyée à la base pour un réglage manuel. En STANDARD, la base ne chauffe pas à
     * vitesse 0 (essai sur un SKMC 1100 A1 : son ventilateur tourne, la température ne bouge pas) ;
     * la chauffe sans mélange passe donc par l'opération vapeur, qui chauffe lame à l'arrêt.
     */
    fun manualOperation(speed: Int, temperatureLevel: Int): Operation =
        if (speed == 0 && temperatureLevel > 0) Operation.STEAM else Operation.STANDARD

    /** Sens envoyé à la base pour un réglage manuel : sans mélange, toujours le sens normal, comme en Vapeur. */
    fun manualDirection(speed: Int, direction: Direction): Direction = if (speed == 0) Direction.CLOCKWISE else direction

    fun manual(
        speed: Int, temperatureLevel: Int, direction: Direction, durationMs: Long,
        accessory: Accessory = Accessory.BLADE, hotBlendConfirmed: Boolean = false, label: String = JobKind.MANUAL.label,
        /** Smart : niveau de sa table (40 °C) ; [temperatureLevel] = niveau de Toque juste en dessous. */
        smartLevel: Int? = null,
    ) = JobSpec(
        kind = JobKind.MANUAL,
        label = label,
        phases = listOf(Phase(Setting(manualOperation(speed, temperatureLevel), speed, temperatureLevel, manualDirection(speed, direction),
            smartLevel = smartLevel), durationMs)),
        totalMs = durationMs,
        cyclic = false,
        accessory = accessory,
        hotBlendConfirmed = hotBlendConfirmed,
    )

    /** Yaourt (Connect) : température de fermentation, la plus douce que la base sait tenir. */
    const val YOGURT_C = 37

    /**
     * Yaourt (Connect) : chauffe sans mélange à [YOGURT_C] pendant [durationMs] (6 à 12 h), la
     * lame immobile pour ne pas casser le caillé. Le lait est déjà tiède et ensemencé.
     */
    fun yogurt(durationMs: Long, label: String = tr("Yaourt")) =
        manual(0, Mcc.levelForCelsius(YOGURT_C), CW, durationMs, label = label).copy(ferment = true)

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

    /**
     * Nettoyage du bol : 1 L d'eau chaude et une goutte de liquide vaisselle, brassés à vitesse
     * croissante (4, 6, 8 puis 10), sans chauffe, couvercle fermé. 1 min 30 par défaut.
     * Si le bol est encore chaud (≥ 60 °C), la machine demande confirmation avant la vitesse 4.
     */
    fun clean(durationMs: Long = 90_000L): JobSpec {
        val speeds = intArrayOf(4, 6, 8, 10)
        val each = durationMs / speeds.size
        return JobSpec(
            kind = JobKind.MANUAL,
            label = tr("Nettoyage"),
            phases = speeds.map { Phase(Setting(Operation.STANDARD, it, 0, CW), each) },
            totalMs = each * speeds.size,
            cyclic = false,
        )
    }

    /** Titre d'une recette de rinçage du catalogue Lidl, quelle que soit la langue. */
    fun isRinseRecipe(title: String): Boolean {
        val t = app.toque.core.recipes.TextNorm.normalize(title)
        return t in RINSE
    }

    private val RINSE = listOf("rincage", "nettoyage", "limpieza", "aclarado", "reinigung", "spulen", "spuelen",
        "rinse", "rinsing", "cleaning", "pulizia", "risciacquo", "reiniging", "spoelen", "czyszczenie", "plukanie", "limpeza")

    /** Smart : réglage d'un programme automatique capturé ([SmartProgram]). */
    fun programSetting(p: SmartProgram, celsius: Int? = null) =
        Setting(p.operation, p.speed, p.toqueLevelFor(celsius ?: p.targetCelsius), p.direction, program = p, programCelsius = celsius)

    // ------------------------------------------------------------------ programmes à étapes (Smart)
    // Rythme de l'appli d'origine relevé le 2026-11-21 (capture 16-etapes-mc30, code `y2.f0`).
    // Chaque phase est une trame capturée ; un repos garde la fonction (et la chauffe), vitesse 0.

    private fun etape(p: SmartProgram, ms: Long, timer: PhaseTimer = PhaseTimer.Remaining, untilTemperature: Boolean = false,
                      celsius: Int? = null) =
        Phase(programSetting(p, celsius), ms, timer, untilTemperature)

    private fun etapes(kind: JobKind, label: String, totalMs: Long, cyclic: Boolean, vararg phases: Phase) =
        JobSpec(kind = kind, label = label, phases = phases.toList(), totalMs = totalMs, cyclic = cyclic)

    /** Mijoter : [30 s vitesse 1 inverse, 2 min repos] × 3, 30 s vitesse 1, puis repos jusqu'à la fin (90 °C). */
    fun smartMijoter(totalMs: Long, label: String, celsius: Int? = null): JobSpec {
        fun b() = etape(SmartProgram.MIJOTER_BRASSE, 30_000, celsius = celsius)
        fun r(ms: Long) = etape(SmartProgram.MIJOTER_REPOS, ms, celsius = celsius)
        return etapes(JobKind.PROGRAM, label, totalMs, false, b(), r(120_000), b(), r(120_000), b(), r(120_000), b(), r(totalMs))
    }

    /** Fermenter : 5 min vitesse 1 inverse, puis repos jusqu'à la fin (40 °C). */
    fun smartFermenter(totalMs: Long, label: String, celsius: Int? = null) = etapes(JobKind.PROGRAM, label, totalMs, false,
        etape(SmartProgram.FERMENTER_BRASSE, 5 * 60_000L, celsius = celsius), etape(SmartProgram.FERMENTER_REPOS, totalMs, celsius = celsius))

    /** Saisir : 5 s vitesse 1 inverse / 30 s repos, en boucle (130 °C) ; minuterie = durée totale. */
    fun smartSaisir(totalMs: Long, label: String = JobKind.TOAST.label) = etapes(JobKind.TOAST, label, totalMs, true,
        etape(SmartProgram.SAISIR_BRASSE, 5_000, PhaseTimer.Total), etape(SmartProgram.SAISIR_REPOS, 30_000, PhaseTimer.Total))

    /** Malaxer pâte ferme (2 min) : 45 s v4, 15 s repos, 20 s v4 inverse, 10 s repos, 20 s v4, 10 s repos. */
    fun smartMalaxerFerme(totalMs: Long, label: String = JobKind.KNEAD.label) = etapes(JobKind.KNEAD, label, totalMs, false,
        etape(SmartProgram.MALAXER_V4, 45_000), etape(SmartProgram.MALAXER_REPOS, 15_000),
        etape(SmartProgram.MALAXER_V4_INVERSE, 20_000), etape(SmartProgram.MALAXER_REPOS, 10_000),
        etape(SmartProgram.MALAXER_V4, 20_000), etape(SmartProgram.MALAXER_REPOS_INVERSE, 10_000))

    /** Malaxer pâte molle (2 min) : 15 s v4 inverse, 35 s repos, 70 s v4. */
    fun smartMalaxerMolle(totalMs: Long, label: String = JobKind.KNEAD.label) = etapes(JobKind.KNEAD, label, totalMs, false,
        etape(SmartProgram.MALAXER_V4_INVERSE, 15_000), etape(SmartProgram.MALAXER_REPOS_INVERSE, 35_000),
        etape(SmartProgram.MALAXER_V4, 70_000))

    /** Prélavage rapide (1 min 30) : 30 s v10, 30 s v4 inverse, 30 s v10 (37 °C). */
    fun smartPrelavage(label: String) = etapes(JobKind.PROGRAM, label, 90_000, false,
        etape(SmartProgram.PRELAVAGE_V10, 30_000), etape(SmartProgram.PRELAVAGE_V4_INVERSE, 30_000),
        etape(SmartProgram.PRELAVAGE_V10, 30_000))

    /**
     * Nettoyage long : 30 s v10 (37 °C) ; préchauffe à 40 °C, vitesse 1, jusqu'à « température
     * atteinte » (15 min au plus) ; puis 2 × [25 s v10, 15 s v4 inverse] et 25 s v10 sans chauffe.
     * Minuterie fixe comme l'appli d'origine : 15 min 30, puis 1 min 45.
     */
    fun smartNettoyageLong(label: String): JobSpec {
        val avant = PhaseTimer.Fixed(30 + 15 * 60)
        val apres = PhaseTimer.Fixed(2 * 40 + 25)
        return etapes(JobKind.PROGRAM, label, app.toque.core.protocol.NETTOYAGE_MAX_S * 1000L, false,
            etape(SmartProgram.NETTOYAGE_V10, 30_000, avant),
            etape(SmartProgram.NETTOYAGE_CHAUFFE, 15 * 60_000L, avant, untilTemperature = true),
            etape(SmartProgram.NETTOYAGE_V10, 25_000, apres), etape(SmartProgram.NETTOYAGE_V4_INVERSE, 15_000, apres),
            etape(SmartProgram.NETTOYAGE_V10, 25_000, apres), etape(SmartProgram.NETTOYAGE_V4_INVERSE, 15_000, apres),
            etape(SmartProgram.NETTOYAGE_FIN, 25_000, apres))
    }

    /** Smart : programme automatique de l'appli d'origine, une seule trame pendant [durationMs]. */
    fun smartProgram(p: SmartProgram, durationMs: Long, label: String, hotBlendConfirmed: Boolean = false, celsius: Int? = null) = JobSpec(
        kind = JobKind.PROGRAM,
        label = label,
        phases = listOf(Phase(programSetting(p, celsius), durationMs)),
        totalMs = durationMs,
        cyclic = false,
        hotBlendConfirmed = hotBlendConfirmed,
    )

    /**
     * Smart : Malaxer de l'appli d'origine, une seule trame (fonction 5, vitesse 4, sans chauffe) :
     * sens normal pour les pâtes ferme et liquide, sens inverse pour la pâte molle (captures 08,
     * 10 et 11). Gardé tel quel par [forSmart].
     */
    fun smartKnead(reverse: Boolean, durationMs: Long, label: String = JobKind.KNEAD.label) = JobSpec(
        kind = JobKind.KNEAD,
        label = label,
        phases = listOf(Phase(Setting(Operation.KNEAD, 4, 0, if (reverse) CCW else CW), durationMs)),
        totalMs = durationMs,
        cyclic = false,
    )

    /**
     * Monsieur Cuisine Smart : pétrin, vapeur et rissolage tels que l'appli d'origine les envoie
     * (codes de fonction 5, 3 et 4, captures du 2026-11-20 et du 2026-11-21) :
     *  - Malaxer : étapes du Malaxer pâte ferme ([smartMalaxerFerme]), sauf une trame unique déjà
     *    au format du Smart (pâte liquide) ;
     *  - Cuisson vapeur : une seule trame, 120 °C, vitesse 1, sens normal ;
     *  - Saisir : étapes de [smartSaisir] (130 °C, 5 s vitesse 1 inverse / 30 s repos).
     * Les autres programmes sont rendus tels quels. Durée et nom inchangés.
     */
    fun forSmart(spec: JobSpec): JobSpec {
        // Programme à étapes de l'appli d'origine : déjà au format du Smart.
        if (spec.phases.isNotEmpty() && spec.phases.all { it.setting.program != null }) return spec
        val s = when (spec.kind) {
            // Malaxer déjà au format du Smart ([smartKnead], pâte liquide) : gardé. Tout autre
            // pétrissage (recettes) suit le Malaxer pâte ferme de l'appli d'origine.
            JobKind.KNEAD -> spec.phases.singleOrNull()?.setting
                ?.takeIf { it.operation == Operation.KNEAD && it.speed == 4 && it.temperatureLevel == 0 && !spec.cyclic }
                ?: return smartMalaxerFerme(spec.totalMs, spec.label).copy(accessory = spec.accessory, hotBlendConfirmed = spec.hotBlendConfirmed)
            JobKind.STEAM -> Setting(Operation.STEAM, 1, 17, CW)
            // Rissoler / Saisir : rythme de l'appli d'origine (5 s vitesse 1 / 30 s repos, 130 °C).
            JobKind.TOAST -> return smartSaisir(spec.totalMs, spec.label).copy(accessory = spec.accessory, hotBlendConfirmed = spec.hotBlendConfirmed)
            else -> return spec
        }
        return spec.copy(phases = listOf(Phase(s, spec.totalMs)), cyclic = false)
    }
}
