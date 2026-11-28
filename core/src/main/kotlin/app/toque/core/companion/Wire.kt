package app.toque.core.companion

import app.toque.core.json.Json
import app.toque.core.look.ColourWheel
import app.toque.core.machine.Accessory
import app.toque.core.machine.ManualLimits
import app.toque.core.machine.ManualSettings
import app.toque.core.recipes.RecipeChip

/*
 * Modèles qui voyagent entre le robot et le téléphone. Chacun s'écrit (toMap) et se relit (fromMap)
 * avec le JSON maison : le robot écrit, l'appli du téléphone relit avec les mêmes classes. La lecture
 * est tolérante (champ absent : valeur par défaut) pour qu'une version plus récente du robot reste lisible.
 * Le contrat exact est décrit dans docs/COMPANION.md.
 */

/** Version du protocole, annoncée par /api/hello et dans le QR code. */
const val COMPANION_PROTOCOL = 1

internal object W {
    fun s(m: Map<String, Any?>, k: String): String = m[k] as? String ?: ""
    fun sn(m: Map<String, Any?>, k: String): String? = m[k] as? String
    fun i(m: Map<String, Any?>, k: String): Int = (m[k] as? Number)?.toInt() ?: 0
    fun inull(m: Map<String, Any?>, k: String): Int? = (m[k] as? Number)?.toInt()
    fun l(m: Map<String, Any?>, k: String): Long = (m[k] as? Number)?.toLong() ?: 0L
    fun lnull(m: Map<String, Any?>, k: String): Long? = (m[k] as? Number)?.toLong()
    fun b(m: Map<String, Any?>, k: String): Boolean = m[k] as? Boolean ?: false
    fun d(m: Map<String, Any?>, k: String): Double = (m[k] as? Number)?.toDouble() ?: 0.0
    @Suppress("UNCHECKED_CAST")
    fun o(m: Map<String, Any?>, k: String): Map<String, Any?>? = m[k] as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    fun objs(m: Map<String, Any?>, k: String): List<Map<String, Any?>> =
        (m[k] as? List<Any?>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
    fun ints(m: Map<String, Any?>, k: String): List<Int> = (m[k] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    fun parse(text: String): Map<String, Any?> = Json.parse(text) as? Map<String, Any?> ?: throw Json.ParseException("objet attendu", 0)
}

/**
 * Erreur renvoyée par l'API : { "error": { "code": "...", "message": "..." } }. Une recette refusée dit
 * aussi où : [field] (title, servings, totalMinutes, difficulty, content, ingredients, steps) et [step]
 * (rang de l'étape, -1 sinon) ; absents des autres erreurs.
 */
data class ApiError(val code: String, val message: String, val field: String = "", val step: Int = -1) {
    fun toMap(): Map<String, Any?> = linkedMapOf("error" to linkedMapOf<String, Any?>("code" to code, "message" to message).apply {
        if (field.isNotEmpty()) put("field", field)
        if (step >= 0) put("step", step)
    })

    companion object {
        fun fromMap(m: Map<String, Any?>): ApiError {
            val e = W.o(m, "error") ?: emptyMap()
            return ApiError(W.s(e, "code"), W.s(e, "message"), W.s(e, "field"), W.inull(e, "step") ?: -1)
        }

        fun response(status: Int, code: String, message: String, field: String = "", step: Int = -1): HttpResponse =
            HttpResponse.json(status, ApiError(code, message, field, step).toMap())
    }
}

/** Réponse de GET /api/hello, sans authentification : seulement de quoi reconnaître un robot Toque. */
data class Hello(val service: String = SERVICE, val protocol: Int = COMPANION_PROTOCOL, val pairing: Boolean = false) {
    fun toMap(): Map<String, Any?> = linkedMapOf("service" to service, "protocol" to protocol, "pairing" to pairing)

    companion object {
        const val SERVICE = "toque-companion"
        fun fromMap(m: Map<String, Any?>) = Hello(W.s(m, "service"), W.i(m, "protocol"), W.b(m, "pairing"))
    }
}

/** Corps de POST /api/pair : le code tapé ou le secret du QR code (un seul des deux). */
data class PairRequest(val code: String? = null, val secret: String? = null) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        if (code != null) put("code", code)
        if (secret != null) put("secret", secret)
    }

    companion object {
        fun fromMap(m: Map<String, Any?>) = PairRequest(W.sn(m, "code"), W.sn(m, "secret"))
    }
}

data class RobotWire(
    /** Identifiant aléatoire stable du robot (retrouver le robot quand son adresse change). */
    val id: String = "",
    val name: String = "",
    val appVersion: String = "",
    val language: String = "fr",
    val protocol: Int = COMPANION_PROTOCOL,
    /** Couleur choisie sur le robot, « #RRGGBB » en majuscules ; vide : inconnue (robot plus ancien). */
    val accent: String = "",
    /** Thème du robot, « dark » ou « light » ; vide : inconnu (robot plus ancien). */
    val theme: String = "",
    /**
     * POST /api/robot/appearance accepte toute couleur qui passe la garde de la roue
     * ([ColourWheel.accepts]), pas seulement les dix de [RobotLooks.ACCENTS]. Faux chez un robot plus
     * ancien (champ absent) : le téléphone ne lui propose que les dix.
     */
    val anyAccent: Boolean = false,
    /**
     * Modèle du robot : l'identifiant de sa fiche ([app.toque.core.robot.RobotProfile.id]), « mc-connect »
     * ou « mc-smart » ; vide : inconnu (robot plus ancien, le téléphone le déduit de `limits`).
     */
    val model: String = "",
) {
    /**
     * [accent], [theme] et [model] ne sont écrits que s'ils sont connus, [anyAccent] que s'il est vrai : un
     * robot plus ancien garde exactement son JSON.
     */
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("id" to id, "name" to name, "appVersion" to appVersion,
        "language" to language, "protocol" to protocol).apply {
        if (accent.isNotEmpty()) put("accent", accent)
        if (theme.isNotEmpty()) put("theme", theme)
        if (anyAccent) put("anyAccent", true)
        if (model.isNotEmpty()) put("model", model)
    }

    /** Le robot prendrait [color] (« #RRGGBB ») envoyée par POST /api/robot/appearance. */
    fun takesAccent(color: String): Boolean {
        val a = accent(color)
        if (a.isEmpty()) return false
        return a in RobotLooks.ACCENTS || (anyAccent && ColourWheel.accepts((0xFF shl 24) or a.substring(1).toInt(16)))
    }

    companion object {
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")

        /** « #RRGGBB » en majuscules, ou vide si [s] n'est pas une couleur. */
        fun accent(s: String): String = if (COLOR.matches(s)) s.uppercase(java.util.Locale.ROOT) else ""

        /** « dark » ou « light », ou vide. */
        fun theme(s: String): String = if (s == THEME_DARK || s == THEME_LIGHT) s else ""

        fun fromMap(m: Map<String, Any?>) = RobotWire(W.s(m, "id"), W.s(m, "name"), W.s(m, "appVersion"),
            W.s(m, "language").ifEmpty { "fr" }, W.i(m, "protocol"), accent(W.s(m, "accent")), theme(W.s(m, "theme")),
            m["anyAccent"] == true, W.s(m, "model"))
    }
}

data class PhoneWire(
    val id: String = "",
    val number: Int = 0,
    val name: String = "",
    val pairedAt: Long = 0,
    val lastSeenAt: Long = 0,
    /** « qr » ou « code ». */
    val method: String = "",
    /**
     * Nom donné à ce téléphone (sur le robot ou par POST /api/phones/me), sans le « Téléphone N » par
     * défaut : vide quand il n'en a pas, et chez un robot plus ancien. [name] reste le nom à afficher ;
     * celui-ci sert à préremplir le champ « Renommer » du téléphone.
     */
    val label: String = "",
    /** Ce téléphone est en lecture seule (voir [Phone.readOnly]) : écrit seulement quand il l'est. */
    val readOnly: Boolean = false,
    /**
     * Le nom que ce téléphone reprendrait sans [label] (« Téléphone N », dans la langue du robot) : le champ
     * « Renommer » du téléphone le dit. Vide sans nom donné ([name] est alors ce nom-là), et chez un robot plus ancien.
     */
    val defaultName: String = "",
) {
    /**
     * [label], [readOnly] et [defaultName] ne sont écrits que s'ils existent ([defaultName] seulement avec un nom
     * donné) : un téléphone sans nom garde exactement son JSON d'avant.
     */
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("id" to id, "number" to number, "name" to name,
        "pairedAt" to pairedAt, "lastSeenAt" to lastSeenAt, "method" to method).apply {
        if (label.isNotEmpty()) put("label", label)
        if (readOnly) put("readOnly", true)
        if (label.isNotEmpty() && defaultName.isNotEmpty()) put("defaultName", defaultName)
    }

    companion object {
        fun of(p: Phone) = PhoneWire(p.id, p.number, p.name, p.pairedAt, p.lastSeenAt, p.method, p.label, p.readOnly,
            if (p.label.isEmpty()) "" else p.defaultName)
        fun fromMap(m: Map<String, Any?>) = PhoneWire(W.s(m, "id"), W.i(m, "number"), W.s(m, "name"),
            W.l(m, "pairedAt"), W.l(m, "lastSeenAt"), W.s(m, "method"), W.s(m, "label"), m["readOnly"] == true, W.s(m, "defaultName"))
    }
}

/**
 * Corps de POST /api/phones/me : le nom que ce téléphone se donne. Le robot le nettoie comme son propre
 * champ ([PairingManager.cleanLabel] : une ligne, [PairingManager.MAX_LABEL] caractères) ; vide : il
 * reprend « Téléphone N ».
 */
data class RenamePhoneRequest(val name: String = "") {
    fun toMap(): Map<String, Any?> = linkedMapOf("name" to name)

    companion object {
        /** Texte reçu au plus long (avant nettoyage) : au-delà, la requête est refusée plutôt que tronquée en silence. */
        const val MAX_RAW = 200

        /** Lecture stricte : un seul champ, « name », une chaîne d'au plus [MAX_RAW] caractères ; sinon IllegalArgumentException. */
        fun fromMap(m: Map<String, Any?>): RenamePhoneRequest {
            if (m.keys != setOf("name")) throw IllegalArgumentException("name")
            val n = m["name"] as? String ?: throw IllegalArgumentException("name")
            if (n.length > MAX_RAW) throw IllegalArgumentException("name")
            return RenamePhoneRequest(n)
        }
    }
}

/** Réponse d'un appairage réussi. Le jeton n'est envoyé qu'une fois : le téléphone le garde. */
data class PairReply(val token: String, val phone: PhoneWire, val robot: RobotWire) {
    fun toMap(): Map<String, Any?> = linkedMapOf("token" to token, "phone" to phone.toMap(), "robot" to robot.toMap())

    companion object {
        fun fromMap(m: Map<String, Any?>) = PairReply(W.s(m, "token"),
            PhoneWire.fromMap(W.o(m, "phone") ?: emptyMap()), RobotWire.fromMap(W.o(m, "robot") ?: emptyMap()))
    }
}

/** Qui a fait quelque chose : le robot ou un téléphone ([self] : le téléphone qui lit). */
data class Origin(val kind: String = ROBOT, val phoneId: String = "", val name: String = "", val self: Boolean = false) {
    fun toMap(): Map<String, Any?> = linkedMapOf("kind" to kind, "phoneId" to phoneId, "name" to name, "self" to self)

    companion object {
        const val ROBOT = "robot"
        const val PHONE = "phone"
        fun fromMap(m: Map<String, Any?>) = Origin(W.s(m, "kind").ifEmpty { ROBOT }, W.s(m, "phoneId"), W.s(m, "name"), W.b(m, "self"))
    }
}

data class MachineWire(
    /** OFFLINE, ARMING, READY ou LOST (LinkState). */
    val link: String = "OFFLINE",
    val linkLabel: String = "",
    /** Liaison prête et aucune alerte. */
    val ready: Boolean = false,
    val faultCode: String = "",
    val faultMessage: String = "",
    /** Au moins une mesure reçue de la base. */
    val hasSample: Boolean = false,
    val tempC: Int = 0,
    val targetTempC: Int = 0,
    /** Contenu à 60 °C ou plus. */
    val hot: Boolean = false,
    val weightG: Int = 0,
    val scaleStable: Boolean = false,
    val lidOpen: Boolean = false,
    val bowlDetected: Boolean = false,
    val bowlRemoved: Boolean = false,
    val motorOn: Boolean = false,
    val heaterOn: Boolean = false,
    val turbo: Boolean = false,
    /**
     * La base mesure la température en ce moment. Faux sur le Smart hors chauffe (sa base ne mesure
     * qu'en chauffant) : [tempC] n'est alors pas une mesure, le téléphone ne l'affiche pas. Toujours vrai
     * sur le Connect. À lire avec [hasSample]. Écrit seulement quand il est faux : absent = vrai.
     */
    val tempKnown: Boolean = true,
    /**
     * [weightG] est une vraie pesée : base prête, balance en état normal, moteur et Turbo arrêtés (lame en
     * marche, la base du Connect envoie 0). Écrit seulement quand il est faux : absent = vrai (ou robot plus ancien).
     */
    val weightValid: Boolean = true,
    /**
     * Toque demande à la base de chauffer en ce moment (le programme en cours a une température). À préférer
     * à [heaterOn], que la base dit aussi pendant des programmes qui ne chauffent pas. Toujours écrit
     * (absent : robot plus ancien, se fier à [heaterOn]).
     */
    val heating: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("link" to link, "linkLabel" to linkLabel, "ready" to ready,
        "faultCode" to faultCode, "faultMessage" to faultMessage, "hasSample" to hasSample, "tempC" to tempC,
        "targetTempC" to targetTempC, "hot" to hot, "weightG" to weightG, "scaleStable" to scaleStable,
        "lidOpen" to lidOpen, "bowlDetected" to bowlDetected, "bowlRemoved" to bowlRemoved,
        "motorOn" to motorOn, "heaterOn" to heaterOn, "turbo" to turbo).apply {
        if (!tempKnown) put("tempKnown", false)
        if (!weightValid) put("weightValid", false)
        put("heating", heating)
    }

    companion object {
        fun fromMap(m: Map<String, Any?>) = MachineWire(W.s(m, "link").ifEmpty { "OFFLINE" }, W.s(m, "linkLabel"),
            W.b(m, "ready"), W.s(m, "faultCode"), W.s(m, "faultMessage"), W.b(m, "hasSample"), W.i(m, "tempC"),
            W.i(m, "targetTempC"), W.b(m, "hot"), W.i(m, "weightG"), W.b(m, "scaleStable"), W.b(m, "lidOpen"),
            W.b(m, "bowlDetected"), W.b(m, "bowlRemoved"), W.b(m, "motorOn"), W.b(m, "heaterOn"), W.b(m, "turbo"),
            m["tempKnown"] as? Boolean ?: true, m["weightValid"] as? Boolean ?: true, W.b(m, "heating"))
    }
}

/** Pause en cours, vue par le compagnon. */
data class PauseWire(
    /** USER, LID ou SAFETY (PauseReason). */
    val reason: String = "",
    val label: String = "",
    /** Début de la pause (heure murale du robot), 0 si la pause a commencé avant que le compagnon ne l'observe. */
    val since: Long = 0,
    /** Annulation automatique prévue (heure murale du robot), 0 si inconnue. */
    val autoCancelAt: Long = 0,
    /** Temps restant avant l'annulation automatique, au moment de la réponse (-1 si inconnu). */
    val autoCancelInMs: Long = -1,
    val origin: Origin = Origin(),
    /** Le couvercle a été ouvert (ou le bol retiré) pendant la pause, ou le compagnon ne l'a pas vue commencer. */
    val lidOpenedDuringPause: Boolean = false,
    val bowlRemoved: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("reason" to reason, "label" to label, "since" to since,
        "autoCancelAt" to autoCancelAt, "autoCancelInMs" to autoCancelInMs, "origin" to origin.toMap(),
        "lidOpenedDuringPause" to lidOpenedDuringPause, "bowlRemoved" to bowlRemoved)

    companion object {
        fun fromMap(m: Map<String, Any?>) = PauseWire(W.s(m, "reason"), W.s(m, "label"), W.l(m, "since"),
            W.l(m, "autoCancelAt"), W.lnull(m, "autoCancelInMs") ?: -1, Origin.fromMap(W.o(m, "origin") ?: emptyMap()),
            W.b(m, "lidOpenedDuringPause"), W.b(m, "bowlRemoved"))
    }
}

data class JobWire(
    /** MANUAL, KNEAD, STEAM, TOAST (JobKind). */
    val kind: String = "",
    val kindLabel: String = "",
    val label: String = "",
    /** STARTING, RUNNING, STOPPING, PAUSED, FINISHED, ABORTED (JobState). */
    val state: String = "",
    /** Le minuteur avance (RUNNING) : la page peut extrapoler remainingMs. */
    val counting: Boolean = false,
    val endReason: String = "",
    val phaseIndex: Int = 0,
    val phaseCount: Int = 0,
    val elapsedMs: Long = 0,
    val totalMs: Long = 0,
    val remainingMs: Long = 0,
    val speed: Int = 0,
    val tempLevel: Int = 0,
    val tempC: Int = 0,
    val reverse: Boolean = false,
    /** BLADE ou WHISK. */
    val accessory: String = "BLADE",
    val hotBlendConfirmed: Boolean = false,
    /** Début observé (heure murale du robot), 0 si inconnu. */
    val startedAt: Long = 0,
    /** Machine en attente d'un arrêt confirmé (démarrage, changement de sens, fin). */
    val waitingStop: Boolean = false,
    val pause: PauseWire? = null,
    /**
     * Fermentation (yaourt) : une cuisson [kind] MANUAL sans mélange, tiède, qui dure des heures et ne se règle
     * pas comme une cuisson manuelle. Le téléphone la montre comme un programme. Écrit seulement quand c'est vrai.
     */
    val ferment: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("kind" to kind, "kindLabel" to kindLabel, "label" to label,
        "state" to state, "counting" to counting, "endReason" to endReason, "phaseIndex" to phaseIndex,
        "phaseCount" to phaseCount, "elapsedMs" to elapsedMs, "totalMs" to totalMs, "remainingMs" to remainingMs,
        "speed" to speed, "tempLevel" to tempLevel, "tempC" to tempC, "reverse" to reverse, "accessory" to accessory,
        "hotBlendConfirmed" to hotBlendConfirmed, "startedAt" to startedAt, "waitingStop" to waitingStop,
        "pause" to pause?.toMap()).apply { if (ferment) put("ferment", true) }

    companion object {
        fun fromMap(m: Map<String, Any?>) = JobWire(W.s(m, "kind"), W.s(m, "kindLabel"), W.s(m, "label"),
            W.s(m, "state"), W.b(m, "counting"), W.s(m, "endReason"), W.i(m, "phaseIndex"), W.i(m, "phaseCount"),
            W.l(m, "elapsedMs"), W.l(m, "totalMs"), W.l(m, "remainingMs"), W.i(m, "speed"), W.i(m, "tempLevel"),
            W.i(m, "tempC"), W.b(m, "reverse"), W.s(m, "accessory").ifEmpty { "BLADE" }, W.b(m, "hotBlendConfirmed"),
            W.l(m, "startedAt"), W.b(m, "waitingStop"), W.o(m, "pause")?.let { PauseWire.fromMap(it) }, W.b(m, "ferment"))
    }
}

/** Une commande permise ou non, avec la raison affichable d'un refus. */
data class Permission(val allowed: Boolean, val code: String = "", val reason: String = "") {
    fun toMap(): Map<String, Any?> = if (allowed && code.isEmpty() && reason.isEmpty()) YES_MAP
    else linkedMapOf("allowed" to allowed, "code" to code, "reason" to reason)

    companion object {
        val YES = Permission(true)
        private val YES_MAP: Map<String, Any?> = linkedMapOf("allowed" to true, "code" to "", "reason" to "")
        fun no(code: String, reason: String) = Permission(false, code, reason)
        fun fromMap(m: Map<String, Any?>) = Permission(W.b(m, "allowed"), W.s(m, "code"), W.s(m, "reason"))
    }
}

/** Ce que ce téléphone peut faire à l'instant. Il n'existe pas d'autre commande. */
data class Permissions(
    val pause: Permission = Permission(false),
    val resume: Permission = Permission(false),
    val stop: Permission = Permission(false),
    val adjust: Permission = Permission(false),
    val tare: Permission = Permission(false),
    val sendRecipe: Permission = Permission(false),
    val removeWaiting: Permission = Permission(false),
    /** Lancer une cuisson manuelle (POST /api/machine/start) ; absent chez un robot plus ancien : refusé. */
    val start: Permission = Permission(false),
    /**
     * Effacer le programme terminé ou arrêté affiché sur le robot, comme son bouton OK
     * (POST /api/machine/clear) ; absent chez un robot plus ancien : refusé.
     */
    val clear: Permission = Permission(false),
    /**
     * Changer un favori du robot (POST /api/recipes/{id}/favorite) : toujours permis par un robot qui
     * le sait faire ; absent chez un robot plus ancien : refusé (le téléphone garde la simple étiquette).
     */
    val favorite: Permission = Permission(false),
    /**
     * Arrêter la recette guidée ouverte sur le robot (POST /api/session/stop), comme son bouton
     * « Arrêter la recette » ; seulement pendant une recette guidée. Absent chez un robot plus ancien : refusé.
     */
    val stopRecipe: Permission = Permission(false),
    /**
     * Écrire ses recettes depuis le téléphone (POST /api/recipes, GET et POST /api/recipes/{id}/edit,
     * POST /api/recipes/{id}/copy) : permis par un robot qui le sait faire ; absent chez un robot plus
     * ancien : refusé (le téléphone ne montre rien de nouveau).
     */
    val editRecipes: Permission = Permission(false),
    /**
     * Changer la couleur ou le thème du robot depuis le téléphone (POST /api/robot/appearance) : permis
     * par un robot qui le sait faire ; le robot refuse encore s'il n'est pas libre (409 « busy »).
     * Absent chez un robot plus ancien : refusé (le téléphone ne propose rien).
     */
    val setAppearance: Permission = Permission(false),
    /**
     * Ce téléphone peut changer son propre nom (POST /api/phones/me) : permis par un robot qui le sait
     * faire. Absent chez un robot plus ancien : refusé (le téléphone ne propose pas « Renommer »).
     */
    val renamePhone: Permission = Permission(false),
    /**
     * Donner à une étape d'une recette reçue (Mealie aujourd'hui, [RecipeDetail.ownSettings]) son réglage du
     * robot, sans copier la recette (GET /api/recipes/{id}/steps, POST et DELETE /api/recipes/{id}/steps/{rang}) :
     * permis par un robot qui le sait faire ; le réglage reste à vérifier sur le robot. Absent chez un robot
     * plus ancien : refusé (le téléphone ne propose rien de nouveau).
     */
    val ownSettings: Permission = Permission(false),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("pause" to pause.toMap(), "resume" to resume.toMap(),
        "stop" to stop.toMap(), "adjust" to adjust.toMap(), "tare" to tare.toMap(),
        "sendRecipe" to sendRecipe.toMap(), "removeWaiting" to removeWaiting.toMap(), "start" to start.toMap(),
        "clear" to clear.toMap(), "favorite" to favorite.toMap(), "stopRecipe" to stopRecipe.toMap(),
        "editRecipes" to editRecipes.toMap(), "setAppearance" to setAppearance.toMap(), "renamePhone" to renamePhone.toMap(),
        "ownSettings" to ownSettings.toMap())

    companion object {
        fun fromMap(m: Map<String, Any?>): Permissions {
            fun p(k: String) = W.o(m, k)?.let { Permission.fromMap(it) } ?: Permission(false)
            return Permissions(p("pause"), p("resume"), p("stop"), p("adjust"), p("tare"), p("sendRecipe"), p("removeWaiting"),
                p("start"), p("clear"), p("favorite"), p("stopRecipe"), p("editRecipes"), p("setAppearance"),
                p("renamePhone"), p("ownSettings"))
        }
    }
}

data class MachineStepWire(
    /** manuel, petrir, vapeur, rissoler, turbo (StepProgram.key). */
    val program: String = "manuel",
    val seconds: Int = 0,
    val speed: Int = 0,
    val tempC: Int = 0,
    val reverse: Boolean = false,
    val accessory: String = "BLADE",
    val pulses: Int = 0,
    /** « 20 min · 100 °C · vitesse 1 », dans la langue du robot. */
    val summary: String = "",
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("program" to program, "seconds" to seconds, "speed" to speed,
        "tempC" to tempC, "reverse" to reverse, "accessory" to accessory, "pulses" to pulses, "summary" to summary)

    companion object {
        fun fromMap(m: Map<String, Any?>) = MachineStepWire(W.s(m, "program"), W.i(m, "seconds"), W.i(m, "speed"),
            W.i(m, "tempC"), W.b(m, "reverse"), W.s(m, "accessory").ifEmpty { "BLADE" }, W.i(m, "pulses"), W.s(m, "summary"))
    }
}

data class WeighWire(val label: String = "", val grams: Int = 0, val weighedG: Int? = null) {
    fun toMap(): Map<String, Any?> = linkedMapOf("label" to label, "grams" to grams, "weighedG" to weighedG)

    companion object {
        fun fromMap(m: Map<String, Any?>) = WeighWire(W.s(m, "label"), W.i(m, "grams"), W.inull(m, "weighedG"))
    }
}

data class StepWire(
    val index: Int = 0,
    val text: String = "",
    val machine: MachineStepWire? = null,
    val weigh: WeighWire? = null,
    val needsReview: Boolean = false,
    /** Contenu chaud et vitesse ≥ 4 : la confirmation se fait sur le robot. */
    val hotBlendOnRobot: Boolean = false,
    /**
     * Fiche d'une recette qui prend les réglages du propriétaire ([RecipeDetail.ownSettings]) : l'empreinte du
     * texte de l'étape dans la recette de la source ([app.toque.core.recipes.OwnStepSettings.keys]), à renvoyer
     * avec un réglage pour dire de quelle étape il s'agit. Vide ailleurs (et chez un robot plus ancien).
     */
    val key: String = "",
    /**
     * L'étape porte le réglage du propriétaire : « robot » (tapé sur le robot) ou « phone » (venu d'un
     * téléphone, à vérifier sur le robot : [needsReview]) ; vide sinon.
     */
    val own: String = "",
) {
    /** [key] et [own] ne sont écrits que s'ils existent : les autres étapes gardent exactement leur JSON. */
    fun toMap(): Map<String, Any?> = linkedMapOf("index" to index, "text" to text, "machine" to machine?.toMap(),
        "weigh" to weigh?.toMap(), "needsReview" to needsReview, "hotBlendOnRobot" to hotBlendOnRobot).apply {
        if (key.isNotEmpty()) put("key", key)
        if (own.isNotEmpty()) put("own", own)
    }

    companion object {
        fun fromMap(m: Map<String, Any?>) = StepWire(W.i(m, "index"), W.s(m, "text"),
            W.o(m, "machine")?.let { MachineStepWire.fromMap(it) }, W.o(m, "weigh")?.let { WeighWire.fromMap(it) },
            W.b(m, "needsReview"), W.b(m, "hotBlendOnRobot"), W.s(m, "key"), W.s(m, "own"))
    }
}

/** Recette guidée en cours sur le robot. */
data class SessionWire(
    val recipeId: String = "",
    val title: String = "",
    val servings: Int = 0,
    val stepCount: Int = 0,
    val current: Int = 0,
    val done: List<Int> = emptyList(),
    val running: List<Int> = emptyList(),
    /** nothing, weigh, hand, launch, hot_blend, resume, finish (WaitingFor.key). */
    val waitingFor: String = "nothing",
    /** Début (heure murale du robot). */
    val startedAt: Long = 0,
    val steps: List<StepWire> = emptyList(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("recipeId" to recipeId, "title" to title, "servings" to servings,
        "stepCount" to stepCount, "current" to current, "done" to done, "running" to running,
        "waitingFor" to waitingFor, "startedAt" to startedAt, "steps" to steps.map { it.toMap() })

    companion object {
        fun fromMap(m: Map<String, Any?>) = SessionWire(W.s(m, "recipeId"), W.s(m, "title"), W.i(m, "servings"),
            W.i(m, "stepCount"), W.i(m, "current"), W.ints(m, "done"), W.ints(m, "running"),
            W.s(m, "waitingFor").ifEmpty { "nothing" }, W.l(m, "startedAt"), W.objs(m, "steps").map { StepWire.fromMap(it) })
    }
}

/** La recette envoyée par un téléphone, en attente sur l'accueil du robot. */
data class WaitingWire(
    val recipeId: String = "",
    val title: String = "",
    val stepCount: Int = 0,
    val totalMinutes: Int = 0,
    val servings: Int = 0,
    val category: String = "",
    val user: Boolean = false,
    val hasImage: Boolean = false,
    /** Envoi (= réception, le robot est le serveur), heure murale du robot. */
    val sentAt: Long = 0,
    val origin: Origin = Origin(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("recipeId" to recipeId, "title" to title, "stepCount" to stepCount,
        "totalMinutes" to totalMinutes, "servings" to servings, "category" to category, "user" to user,
        "hasImage" to hasImage, "sentAt" to sentAt, "origin" to origin.toMap())

    companion object {
        fun fromMap(m: Map<String, Any?>) = WaitingWire(W.s(m, "recipeId"), W.s(m, "title"), W.i(m, "stepCount"),
            W.i(m, "totalMinutes"), W.i(m, "servings"), W.s(m, "category"), W.b(m, "user"), W.b(m, "hasImage"),
            W.l(m, "sentAt"), Origin.fromMap(W.o(m, "origin") ?: emptyMap()))
    }
}

/** Une activité du journal : une recette, une cuisson manuelle ou un programme. */
data class ActivityWire(
    val id: Long = 0,
    /** recipe, manual, program. */
    val kind: String = "",
    val title: String = "",
    val recipeId: String = "",
    val stepCount: Int = 0,
    /** Étapes faites à la main (sans réglage machine). */
    val handSteps: Int = 0,
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    /** running, finished, stopped, cancelled, fault, abandoned. */
    val outcome: String = "running",
    /** « 3 min · 60 °C · sans mélange » pour une cuisson, « 45 s » pour un programme. */
    val summary: String = "",
    val totalMs: Long = 0,
    /** Qui l'a terminée (arrêt depuis un téléphone…). */
    val endOrigin: Origin? = null,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "kind" to kind, "title" to title, "recipeId" to recipeId,
        "stepCount" to stepCount, "handSteps" to handSteps, "startedAt" to startedAt, "endedAt" to endedAt,
        "outcome" to outcome, "summary" to summary, "totalMs" to totalMs, "endOrigin" to endOrigin?.toMap())

    companion object {
        fun fromMap(m: Map<String, Any?>) = ActivityWire(W.l(m, "id"), W.s(m, "kind"), W.s(m, "title"),
            W.s(m, "recipeId"), W.i(m, "stepCount"), W.i(m, "handSteps"), W.l(m, "startedAt"), W.l(m, "endedAt"),
            W.s(m, "outcome").ifEmpty { "running" }, W.s(m, "summary"), W.l(m, "totalMs"),
            W.o(m, "endOrigin")?.let { Origin.fromMap(it) })
    }
}

/** Une ligne du journal. [seq] croît d'une unité à chaque ligne : le téléphone retient la dernière notifiée. */
data class JournalEntry(
    val seq: Long = 0,
    val at: Long = 0,
    val kind: String = "",
    val text: String = "",
    /** Catégorie d'alerte (waiting, step_end, job_end, pause) ou "" si la ligne n'alerte pas. */
    val alert: String = "",
    val origin: Origin = Origin(),
    /** Activité à laquelle la ligne appartient (0 : aucune). */
    val activityId: Long = 0,
    /** Étape concernée, -1 si aucune. */
    val stepIndex: Int = -1,
    /** Durée écoulée du programme au moment de la ligne (arrêt…), 0 sinon. */
    val elapsedMs: Long = 0,
    /** Précision selon le genre : raison de pause (USER, LID, BOWL, SAFETY), attente (weigh, hand…). */
    val detail: String = "",
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("seq" to seq, "at" to at, "kind" to kind, "text" to text,
        "alert" to alert, "origin" to origin.toMap(), "activityId" to activityId, "stepIndex" to stepIndex,
        "elapsedMs" to elapsedMs, "detail" to detail)

    companion object {
        fun fromMap(m: Map<String, Any?>) = JournalEntry(W.l(m, "seq"), W.l(m, "at"), W.s(m, "kind"), W.s(m, "text"),
            W.s(m, "alert"), Origin.fromMap(W.o(m, "origin") ?: emptyMap()), W.l(m, "activityId"),
            W.inull(m, "stepIndex") ?: -1, W.l(m, "elapsedMs"), W.s(m, "detail"))
    }
}

data class JournalPage(
    /** Numéro de la dernière ligne écrite (0 si aucune). */
    val lastSeq: Long = 0,
    /** Lignes, de la plus récente à la plus ancienne. */
    val entries: List<JournalEntry> = emptyList(),
    /** Activités, de la plus récente à la plus ancienne. */
    val activities: List<ActivityWire> = emptyList(),
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("lastSeq" to lastSeq, "entries" to entries.map { it.toMap() },
        "activities" to activities.map { it.toMap() })

    companion object {
        fun fromMap(m: Map<String, Any?>) = JournalPage(W.l(m, "lastSeq"),
            W.objs(m, "entries").map { JournalEntry.fromMap(it) }, W.objs(m, "activities").map { ActivityWire.fromMap(it) })
    }
}

/** Limites de sécurité utiles à l'affichage des réglages (le robot revérifie tout). */
data class LimitsWire(
    val temperaturesC: List<Int> = emptyList(),
    val maxSpeed: Int = 10,
    val maxSpeedHeated: Int = 3,
    val maxSpeedReverse: Int = 3,
    val whiskMaxSpeed: Int = 4,
    val maxTempNoStirC: Int = 120,
    val hotC: Int = 60,
    val minMs: Long = 1_000,
    val maxMs: Long = 99 * 60_000L,
    val maxMsHighSpeed: Long = 15 * 60_000L,
    val pauseMaxMs: Long = 10 * 60_000L,
    /** Durée minimale réglable d'une cuisson manuelle (écran du robot). */
    val manualMinMs: Long = 5_000L,
    /** Au-dessus de cette vitesse, la durée maximale est [maxMsHighSpeed]. */
    val highSpeedAbove: Int = 6,
    /** Chauffe lame à l'arrêt (vitesse 0) possible sur ce robot (pas le Smart) ; absent chez un robot plus ancien : oui. */
    val heatNoStir: Boolean = true,
) {
    private val cachedMap: Map<String, Any?> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        linkedMapOf("temperaturesC" to temperaturesC, "maxSpeed" to maxSpeed,
            "maxSpeedHeated" to maxSpeedHeated, "maxSpeedReverse" to maxSpeedReverse, "whiskMaxSpeed" to whiskMaxSpeed,
            "maxTempNoStirC" to maxTempNoStirC, "hotC" to hotC, "minMs" to minMs, "maxMs" to maxMs,
            "maxMsHighSpeed" to maxMsHighSpeed, "pauseMaxMs" to pauseMaxMs, "manualMinMs" to manualMinMs,
            "highSpeedAbove" to highSpeedAbove, "heatNoStir" to heatNoStir)
    }

    fun toMap(): Map<String, Any?> = cachedMap

    /** Bornes de saisie d'une cuisson manuelle ; un champ absent ou nul (robot plus ancien) garde la valeur de Toque. */
    fun manual(): ManualLimits {
        val d = ManualLimits()
        fun pos(v: Int, def: Int) = if (v > 0) v else def
        val noStir = app.toque.core.protocol.Mcc.levelForCelsius(maxTempNoStirC)
        return ManualLimits(pos(maxSpeedHeated, d.maxSpeedHeated), pos(maxSpeedReverse, d.maxSpeedReverse),
            pos(whiskMaxSpeed, d.whiskMaxSpeed), if (noStir > 0) noStir else d.maxTempLevelNoStir,
            pos((manualMinMs / 1000).toInt(), d.minSeconds), pos((maxMs / 1000).toInt(), d.maxSeconds),
            pos((maxMsHighSpeed / 1000).toInt(), d.maxSecondsHighSpeed), pos(highSpeedAbove, d.highSpeedAbove),
            temperaturesC.ifEmpty { d.temperaturesC }, heatNoStir)
    }

    companion object {
        fun fromMap(m: Map<String, Any?>) = LimitsWire(W.ints(m, "temperaturesC"), W.i(m, "maxSpeed"),
            W.i(m, "maxSpeedHeated"), W.i(m, "maxSpeedReverse"), W.i(m, "whiskMaxSpeed"), W.i(m, "maxTempNoStirC"),
            W.i(m, "hotC"), W.l(m, "minMs"), W.l(m, "maxMs"), W.l(m, "maxMsHighSpeed"), W.l(m, "pauseMaxMs"),
            W.l(m, "manualMinMs"), W.i(m, "highSpeedAbove"), m["heatNoStir"] as? Boolean ?: true)
    }
}

/** Tout ce que le téléphone affiche, propre au téléphone qui le demande (permissions, origines). */
data class StateWire(
    val protocol: Int = COMPANION_PROTOCOL,
    /** Croît à chaque changement visible ; GET /api/state?since=N attend qu'il dépasse N. */
    val revision: Long = 0,
    /** Heure murale du robot au moment de la réponse. */
    val serverTime: Long = 0,
    val robot: RobotWire = RobotWire(),
    val me: PhoneWire = PhoneWire(),
    val machine: MachineWire = MachineWire(),
    val job: JobWire? = null,
    val can: Permissions = Permissions(),
    val session: SessionWire? = null,
    val waiting: WaitingWire? = null,
    /** Dernière activité terminée (écran « au repos »). */
    val last: ActivityWire? = null,
    /** Numéro de la dernière ligne du journal (voir GET /api/journal?after=). */
    val journalSeq: Long = 0,
    val limits: LimitsWire = LimitsWire(),
    /**
     * Recette guidée quittée sur le robot sans être finie ni arrêtée (« Reprendre » sur son accueil), même
     * forme que [session] ; aussi après un redémarrage de Toque. null quand il n'y en a pas, et pendant
     * une session (elle la remplace). Écrit seulement quand elle existe.
     */
    val suspendedSession: SessionWire? = null,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("protocol" to protocol, "revision" to revision,
        "serverTime" to serverTime, "robot" to robot.toMap(), "me" to me.toMap(), "machine" to machine.toMap(),
        "job" to job?.toMap(), "can" to can.toMap(), "session" to session?.toMap(), "waiting" to waiting?.toMap(),
        "last" to last?.toMap(), "journalSeq" to journalSeq, "limits" to limits.toMap()).apply {
        if (suspendedSession != null) put("suspendedSession", suspendedSession.toMap())
    }

    companion object {
        fun fromMap(m: Map<String, Any?>) = StateWire(W.i(m, "protocol"), W.l(m, "revision"), W.l(m, "serverTime"),
            RobotWire.fromMap(W.o(m, "robot") ?: emptyMap()), PhoneWire.fromMap(W.o(m, "me") ?: emptyMap()),
            MachineWire.fromMap(W.o(m, "machine") ?: emptyMap()), W.o(m, "job")?.let { JobWire.fromMap(it) },
            Permissions.fromMap(W.o(m, "can") ?: emptyMap()), W.o(m, "session")?.let { SessionWire.fromMap(it) },
            W.o(m, "waiting")?.let { WaitingWire.fromMap(it) }, W.o(m, "last")?.let { ActivityWire.fromMap(it) },
            W.l(m, "journalSeq"), LimitsWire.fromMap(W.o(m, "limits") ?: emptyMap()),
            W.o(m, "suspendedSession")?.let { SessionWire.fromMap(it) })
    }
}

/** Une recette du catalogue (liste). */
data class RecipeSummary(
    val id: String = "",
    val title: String = "",
    val category: String = "",
    val totalMinutes: Int = 0,
    val favorite: Boolean = false,
    /** Recette perso (« Ma recette »). */
    val user: Boolean = false,
    val hasImage: Boolean = false,
    val machineSteps: Int = 0,
    val rating: Double = 0.0,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "title" to title, "category" to category,
        "totalMinutes" to totalMinutes, "favorite" to favorite, "user" to user, "hasImage" to hasImage,
        "machineSteps" to machineSteps, "rating" to rating)

    companion object {
        fun fromMap(m: Map<String, Any?>) = RecipeSummary(W.s(m, "id"), W.s(m, "title"), W.s(m, "category"),
            W.i(m, "totalMinutes"), W.b(m, "favorite"), W.b(m, "user"), W.b(m, "hasImage"), W.i(m, "machineSteps"), W.d(m, "rating"))
    }
}

/**
 * Une page du catalogue. [filters] : les pastilles du robot, dans son ordre, sur la première page
 * seulement (offset 0) ; null sur les pages suivantes et chez un robot plus ancien.
 */
data class CatalogPageWire(val total: Int = 0, val offset: Int = 0, val items: List<RecipeSummary> = emptyList(),
                           val filters: List<CatalogChipWire>? = null) {
    fun toMap(): Map<String, Any?> = linkedMapOf("total" to total, "offset" to offset, "items" to items.map { it.toMap() }).apply {
        if (filters != null) put("filters", filters.map { it.toMap() })
    }

    companion object {
        fun fromMap(m: Map<String, Any?>) = CatalogPageWire(W.i(m, "total"), W.i(m, "offset"),
            W.objs(m, "items").map { RecipeSummary.fromMap(it) },
            if (m["filters"] is List<*>) W.objs(m, "filters").map { CatalogChipWire.fromMap(it) } else null)
    }
}

/** Une pastille du robot : le filtre à envoyer (`filter`, et `category` pour une catégorie). */
data class CatalogChipWire(val filter: String = "", val category: String = "") {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>("filter" to filter).apply { if (category.isNotEmpty()) put("category", category) }

    /** La pastille, ou null pour un filtre que ce téléphone ne connaît pas (robot plus récent). */
    fun toChip(): RecipeChip? {
        val kind = RecipeChip.Kind.of(filter) ?: return null
        if (kind == RecipeChip.Kind.CATEGORY) return if (category.isBlank()) null else RecipeChip.category(category)
        return RecipeChip(kind)
    }

    companion object {
        fun of(c: RecipeChip) = CatalogChipWire(c.kind.key, if (c.kind == RecipeChip.Kind.CATEGORY) c.category else "")
        fun fromMap(m: Map<String, Any?>) = CatalogChipWire(W.s(m, "filter"), W.s(m, "category"))
    }
}

data class IngredientWire(val qty: String = "", val unit: String = "", val name: String = "", val note: String = "", val display: String = "") {
    fun toMap(): Map<String, Any?> = linkedMapOf("qty" to qty, "unit" to unit, "name" to name, "note" to note, "display" to display)

    companion object {
        fun fromMap(m: Map<String, Any?>) = IngredientWire(W.s(m, "qty"), W.s(m, "unit"), W.s(m, "name"), W.s(m, "note"), W.s(m, "display"))
    }
}

data class IngredientGroupWire(val title: String = "", val items: List<IngredientWire> = emptyList()) {
    fun toMap(): Map<String, Any?> = linkedMapOf("title" to title, "items" to items.map { it.toMap() })

    companion object {
        fun fromMap(m: Map<String, Any?>) = IngredientGroupWire(W.s(m, "title"), W.objs(m, "items").map { IngredientWire.fromMap(it) })
    }
}

/** Fiche d'une recette. */
data class RecipeDetail(
    val id: String = "",
    val title: String = "",
    val category: String = "",
    val servings: Int = 0,
    val servingUnit: String = "",
    val prepMinutes: Int = 0,
    val totalMinutes: Int = 0,
    /** 1 facile, 2 moyen, 3 difficile. */
    val difficulty: Int = 1,
    val favorite: Boolean = false,
    val user: Boolean = false,
    val author: String = "",
    val rating: Double = 0.0,
    val ratingCount: Int = 0,
    val hasImage: Boolean = false,
    val ingredients: List<IngredientGroupWire> = emptyList(),
    val steps: List<StepWire> = emptyList(),
    /**
     * Nombre de personnes de la recette elle-même ; [servings] est celui choisi sur le robot (quantités
     * et pesées adaptées). 0 : pas de réglage (recette sans nombre de personnes, ou robot plus ancien).
     */
    val baseServings: Int = 0,
    /** Plage permise (Portions.range de [baseServings]) ; 0 sans réglage. */
    val minServings: Int = 0,
    val maxServings: Int = 0,
    /** Poids estimé des ingrédients pesés ou mesurés, en grammes (Portions.totalGrams). */
    val totalGrams: Int = 0,
    /** Recette agrandie dont les ingrédients dépassent ce que le bol accepte (Portions.bowlTooFull). */
    val bowlTooFull: Boolean = false,
    /**
     * Langue dans laquelle le robot écrit les nombres de cette recette (la sienne, sinon celle de son
     * interface) : le téléphone recalcule les quantités comme lui. Vide chez un robot plus ancien.
     */
    val language: String = "",
    /**
     * D'où vient la recette : [SOURCE_IMPORTED] (Lidl), [SOURCE_USER], [SOURCE_COMMUNITY] ou [SOURCE_MEALIE]
     * (le nom de [app.toque.core.recipes.RecipeSource] en minuscules). Vide chez un robot plus ancien : le
     * téléphone s'en tient alors à [user]. Une valeur inconnue (robot plus récent) se lit comme une recette Lidl.
     */
    val source: String = "",
    /**
     * Le propriétaire peut donner à une étape son réglage du robot depuis le téléphone, sans copier la recette
     * (une recette Mealie, chez un robot qui le sait faire) : chaque étape porte alors sa [StepWire.key] et, s'il
     * y en a un, l'auteur de ce réglage ([StepWire.own]). Écrit seulement s'il est vrai : faux chez un robot plus ancien.
     */
    val ownSettings: Boolean = false,
) {
    /** Recette d'un serveur Mealie, en lecture seule sur le robot (« Depuis Mealie »). */
    val mealie: Boolean get() = source == SOURCE_MEALIE
    /** Recette de la communauté toqueapp.org. */
    val community: Boolean get() = source == SOURCE_COMMUNITY

    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "title" to title, "category" to category,
        "servings" to servings, "servingUnit" to servingUnit, "prepMinutes" to prepMinutes,
        "totalMinutes" to totalMinutes, "difficulty" to difficulty, "favorite" to favorite, "user" to user,
        "author" to author, "rating" to rating, "ratingCount" to ratingCount, "hasImage" to hasImage,
        "ingredients" to ingredients.map { it.toMap() }, "steps" to steps.map { it.toMap() },
        "baseServings" to baseServings, "minServings" to minServings, "maxServings" to maxServings,
        "totalGrams" to totalGrams, "bowlTooFull" to bowlTooFull, "language" to language).apply {
        if (source.isNotEmpty()) put("source", source)
        if (ownSettings) put("ownSettings", true)
    }

    companion object {
        const val SOURCE_IMPORTED = "imported"
        const val SOURCE_USER = "user"
        const val SOURCE_COMMUNITY = "community"
        const val SOURCE_MEALIE = "mealie"

        fun fromMap(m: Map<String, Any?>) = RecipeDetail(W.s(m, "id"), W.s(m, "title"), W.s(m, "category"),
            W.i(m, "servings"), W.s(m, "servingUnit"), W.i(m, "prepMinutes"), W.i(m, "totalMinutes"),
            W.i(m, "difficulty").coerceAtLeast(1), W.b(m, "favorite"), W.b(m, "user"), W.s(m, "author"),
            W.d(m, "rating"), W.i(m, "ratingCount"), W.b(m, "hasImage"),
            W.objs(m, "ingredients").map { IngredientGroupWire.fromMap(it) }, W.objs(m, "steps").map { StepWire.fromMap(it) },
            W.i(m, "baseServings"), W.i(m, "minServings"), W.i(m, "maxServings"), W.i(m, "totalGrams"), W.b(m, "bowlTooFull"),
            W.s(m, "language"), W.s(m, "source"), m["ownSettings"] == true)
    }
}

/** Corps de POST /api/recipes/{id}/servings. */
data class ServingsRequest(val servings: Int = 0) {
    fun toMap(): Map<String, Any?> = linkedMapOf("servings" to servings)

    companion object {
        /** Lecture stricte : un seul champ, « servings », entier de 1 à 99 ; sinon IllegalArgumentException. */
        fun fromMap(m: Map<String, Any?>): ServingsRequest {
            if (m.keys != setOf("servings")) throw IllegalArgumentException("servings")
            val n = when (val v = m["servings"]) {
                is Long -> v
                is Int -> v.toLong()
                is Double -> if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e6) v.toLong() else throw IllegalArgumentException("servings")
                else -> throw IllegalArgumentException("servings")
            }
            if (n !in 1..99) throw IllegalArgumentException("servings")
            return ServingsRequest(n.toInt())
        }
    }
}

/** Corps de POST /api/recipes/{id}/favorite. */
/**
 * Corps de POST /api/robot/appearance : [accent] (« #RRGGBB », une couleur qui passe la garde de la roue,
 * [RobotLooks.accepts]) et/ou [theme] (« dark » ou « light ») ; null : inchangé.
 */
data class AppearanceRequest(val accent: String? = null, val theme: String? = null) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        if (accent != null) put("accent", accent)
        if (theme != null) put("theme", theme)
    }

    companion object {
        /**
         * Lecture stricte : « accent » et/ou « theme », rien d'autre, au moins un ; une couleur que la garde
         * accepte (majuscules ou minuscules) et un thème connu ; sinon IllegalArgumentException. Que le robot
         * prenne une couleur hors des dix est vu ensuite (CompanionHub.setAppearance).
         */
        fun fromMap(m: Map<String, Any?>): AppearanceRequest {
            if (m.isEmpty() || !setOf("accent", "theme").containsAll(m.keys)) throw IllegalArgumentException("appearance")
            val accent = if ("accent" in m) {
                val a = (m["accent"] as? String)?.let { RobotWire.accent(it) } ?: ""
                if (!RobotLooks.accepts(a)) throw IllegalArgumentException("accent")
                a
            } else null
            val theme = if ("theme" in m) {
                val t = (m["theme"] as? String)?.let { RobotWire.theme(it) } ?: ""
                if (t.isEmpty()) throw IllegalArgumentException("theme")
                t
            } else null
            return AppearanceRequest(accent, theme)
        }
    }
}

/** Les dix couleurs de Réglages › Apparence du robot (app/…/ui/Ui.kt, ACCENTS), « #RRGGBB » ; la première est l'orange d'origine. */
object RobotLooks {
    val ACCENTS: List<String> = listOf("#FF8A3D", "#FFB547", "#E8D44D", "#8BD85F", "#3DDCB0", "#4FC3F7", "#6E9BFF", "#B48CFF", "#F2639A", "#FF6F61")

    /** « #RRGGBB » (déjà en majuscules) : une des dix, ou une couleur qui passe la garde de la roue. */
    fun accepts(hex: String): Boolean {
        if (hex.isEmpty() || RobotWire.accent(hex) != hex) return false
        return hex in ACCENTS || ColourWheel.accepts((0xFF shl 24) or hex.substring(1).toInt(16))
    }
}

data class FavoriteRequest(val favorite: Boolean = false) {
    fun toMap(): Map<String, Any?> = linkedMapOf("favorite" to favorite)

    companion object {
        /** Lecture stricte : un seul champ, « favorite », booléen ; sinon IllegalArgumentException. */
        fun fromMap(m: Map<String, Any?>): FavoriteRequest {
            if (m.keys != setOf("favorite")) throw IllegalArgumentException("favorite")
            return FavoriteRequest(m["favorite"] as? Boolean ?: throw IllegalArgumentException("favorite"))
        }
    }
}

/** Réponse de POST /api/recipes/{id}/favorite : la recette et son état de favori enregistré. */
data class FavoriteReply(val id: String = "", val favorite: Boolean = false) {
    fun toMap(): Map<String, Any?> = linkedMapOf("id" to id, "favorite" to favorite)

    companion object {
        fun fromMap(m: Map<String, Any?>) = FavoriteReply(W.s(m, "id"), W.b(m, "favorite"))
    }
}

/** Réponse d'une commande acceptée. */
data class CommandReply(val ok: Boolean = true, val revision: Long = 0) {
    fun toMap(): Map<String, Any?> = linkedMapOf("ok" to ok, "revision" to revision)

    companion object {
        fun fromMap(m: Map<String, Any?>) = CommandReply(W.b(m, "ok"), W.l(m, "revision"))
    }
}

/**
 * Corps de POST /api/machine/adjust : au moins un champ ; totalMs et addMs s'excluent.
 * [reverse] : vrai = sens inverse, faux = sens normal.
 */
data class AdjustRequest(
    val speed: Int? = null,
    val tempC: Int? = null,
    val totalMs: Long? = null,
    val addMs: Long? = null,
    val reverse: Boolean? = null,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        if (speed != null) put("speed", speed)
        if (tempC != null) put("tempC", tempC)
        if (totalMs != null) put("totalMs", totalMs)
        if (addMs != null) put("addMs", addMs)
        if (reverse != null) put("reverse", reverse)
    }

    companion object {
        /**
         * Lecture stricte : un nombre non entier, hors bornes, ou d'un autre type est refusé
         * (IllegalArgumentException) au lieu d'être tronqué.
         */
        fun fromMap(m: Map<String, Any?>): AdjustRequest {
            fun num(k: String, min: Long, max: Long): Long? {
                val v = m[k] ?: return null
                val n = when (v) {
                    is Long -> v
                    is Int -> v.toLong()
                    is Double -> if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong() else throw IllegalArgumentException(k)
                    else -> throw IllegalArgumentException(k)
                }
                if (n < min || n > max) throw IllegalArgumentException(k)
                return n
            }
            val rev = m["reverse"]
            if (rev != null && rev !is Boolean) throw IllegalArgumentException("reverse")
            return AdjustRequest(num("speed", 0, 10)?.toInt(), num("tempC", 0, 300)?.toInt(),
                num("totalMs", 0, MAX_MS), num("addMs", -MAX_MS, MAX_MS), rev as Boolean?)
        }

        /** Borne de lecture des durées (24 h) ; les limites de sécurité sont vérifiées ensuite. */
        const val MAX_MS = 24 * 3_600_000L
    }
}

/**
 * Corps de POST /api/machine/start : une cuisson manuelle. [tempC] 0 = sans chauffe, sinon une
 * température de la table ; [accessory] BLADE ou WHISK. Tous les champs sont requis.
 */
data class StartRequest(
    val seconds: Int = 60,
    val tempC: Int = 0,
    val speed: Int = 2,
    val reverse: Boolean = false,
    val accessory: String = "BLADE",
) {
    fun toMap(): Map<String, Any?> = linkedMapOf("seconds" to seconds, "tempC" to tempC, "speed" to speed,
        "reverse" to reverse, "accessory" to accessory)

    /** Le réglage demandé, ou null si la température n'est pas dans la table. */
    fun settings(limits: ManualLimits = ManualLimits()): ManualSettings? {
        val level = if (tempC == 0) 0 else app.toque.core.protocol.Mcc.levelForCelsius(tempC)
        if (level < 0) return null
        val acc = if (accessory == "WHISK") Accessory.WHISK else Accessory.BLADE
        return ManualSettings(seconds, level, speed, reverse, acc, limits)
    }

    companion object {
        private val FIELDS = setOf("seconds", "tempC", "speed", "reverse", "accessory")

        fun of(s: ManualSettings) = StartRequest(s.seconds, s.tempC.takeIf { s.tempLevel > 0 } ?: 0, s.speed,
            s.reverse && s.speed > 0, s.accessory.name)

        /**
         * Lecture stricte : champ manquant, inconnu, d'un autre type, nombre non entier ou hors des
         * bornes de lecture → IllegalArgumentException. Les règles de sécurité sont vérifiées ensuite.
         */
        fun fromMap(m: Map<String, Any?>): StartRequest {
            for (k in m.keys) if (k !in FIELDS) throw IllegalArgumentException(k)
            fun num(k: String, min: Long, max: Long): Long {
                val n = when (val v = m[k]) {
                    is Long -> v
                    is Int -> v.toLong()
                    is Double -> if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong() else throw IllegalArgumentException(k)
                    else -> throw IllegalArgumentException(k)
                }
                if (n < min || n > max) throw IllegalArgumentException(k)
                return n
            }
            val rev = m["reverse"] as? Boolean ?: throw IllegalArgumentException("reverse")
            val acc = m["accessory"] as? String ?: throw IllegalArgumentException("accessory")
            if (acc != "BLADE" && acc != "WHISK") throw IllegalArgumentException("accessory")
            return StartRequest(num("seconds", 0, 24 * 3600L).toInt(), num("tempC", 0, 300).toInt(),
                num("speed", 0, 10).toInt(), rev, acc)
        }
    }
}

/** Corps de POST /api/prove : l'identifiant du téléphone et un aléa frais (32 hex). */
data class ProveRequest(val phoneId: String = "", val nonce: String = "") {
    fun toMap(): Map<String, Any?> = linkedMapOf("phoneId" to phoneId, "nonce" to nonce)

    companion object {
        fun fromMap(m: Map<String, Any?>) = ProveRequest(W.s(m, "phoneId"), W.s(m, "nonce"))
    }
}

/** Réponse de POST /api/prove (voir [CompanionProof]). */
data class ProveReply(val proof: String = "") {
    fun toMap(): Map<String, Any?> = linkedMapOf("proof" to proof)

    companion object {
        fun fromMap(m: Map<String, Any?>) = ProveReply(W.s(m, "proof"))
    }
}

/** Corps de POST /api/waiting. */
data class SendRecipeRequest(val recipeId: String = "") {
    fun toMap(): Map<String, Any?> = linkedMapOf("recipeId" to recipeId)

    companion object {
        fun fromMap(m: Map<String, Any?>) = SendRecipeRequest(W.s(m, "recipeId"))
    }
}

/** Réponse de GET /api/robot : le robot et le téléphone qui demande (vérifier que c'est bien son robot). */
data class WhoAmI(val robot: RobotWire = RobotWire(), val phone: PhoneWire = PhoneWire()) {
    fun toMap(): Map<String, Any?> = linkedMapOf("robot" to robot.toMap(), "phone" to phone.toMap())

    companion object {
        fun fromMap(m: Map<String, Any?>) = WhoAmI(RobotWire.fromMap(W.o(m, "robot") ?: emptyMap()),
            PhoneWire.fromMap(W.o(m, "phone") ?: emptyMap()))
    }
}
