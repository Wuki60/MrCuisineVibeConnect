package app.toque.core.recipes

import app.toque.core.i18n.tr
import app.toque.core.json.Json
import app.toque.core.json.bool
import app.toque.core.json.double
import app.toque.core.json.int
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str
import app.toque.core.machine.Accessory
import app.toque.core.machine.JobKind
import app.toque.core.machine.JobSpec
import app.toque.core.machine.Programs
import app.toque.core.machine.SafetyPolicy
import app.toque.core.machine.Setting
import app.toque.core.machine.Violation
import app.toque.core.protocol.Direction
import app.toque.core.protocol.Mcc
import app.toque.core.protocol.Operation
import app.toque.core.protocol.Smart
import app.toque.core.protocol.SmartProgram
import app.toque.core.robot.Feature
import app.toque.core.robot.RobotProfiles

/**
 * IMPORTED : recettes Lidl (site monsieur-cuisine.com ou appli d'origine) ; USER : recettes perso ;
 * COMMUNITY : recettes de la communauté, venues de toqueapp.org (identifiants « toqueapp-N »).
 */
enum class RecipeSource { IMPORTED, USER, COMMUNITY }

data class Ingredient(
    val qty: String = "",
    val unit: String = "",
    val name: String,
    val note: String = "",
    /** Quantité de la recette quand l'utilisateur l'a adaptée (voir [RecipeAdjustments]) ; vide sinon. Non enregistrée. */
    val originalQty: String = "",
) {
    val display: String
        get() = buildString {
            if (qty.isNotBlank()) append(qty.trim()).append(' ')
            if (unit.isNotBlank()) append(unit.trim()).append(' ')
            append(name.trim())
            if (note.isNotBlank()) append(", ").append(note.trim())
        }
}

data class IngredientGroup(val title: String = "", val items: List<Ingredient>)

enum class StepProgram(val key: String, val kind: JobKind, val feature: Feature, val heated: Boolean = false) {
    MANUAL("manuel", JobKind.MANUAL, Feature.MANUAL),
    KNEAD("petrir", JobKind.KNEAD, Feature.KNEAD),
    STEAM("vapeur", JobKind.STEAM, Feature.STEAM),
    TOAST("rissoler", JobKind.TOAST, Feature.TOAST),
    TURBO("turbo", JobKind.TURBO, Feature.TURBO),
    // Programmes du Monsieur Cuisine Smart (recettes « MC3.0 » du site) : trames de l'appli d'origine.
    MIXER("mixer", JobKind.PROGRAM, Feature.MIXER),
    SMOOTHIE("smoothie", JobKind.PROGRAM, Feature.SMOOTHIE),
    SOUS_VIDE("sous-vide", JobKind.PROGRAM, Feature.SOUS_VIDE, heated = true),
    SLOW_COOK("mijoter", JobKind.PROGRAM, Feature.SLOW_COOK, heated = true),
    FERMENT("fermenter", JobKind.PROGRAM, Feature.FERMENT, heated = true);

    val label: String get() = feature.label

    companion object {
        fun of(key: String?): StepProgram? = values().firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/** Réglage machine d'une étape, en unités humaines (secondes, °C). */
data class StepMachine(
    val program: StepProgram = StepProgram.MANUAL,
    val seconds: Int = 0,
    val speed: Int = 0,
    val tempC: Int = 0,
    val reverse: Boolean = false,
    val accessory: Accessory = Accessory.BLADE,
    /** Turbo : nombre d'impulsions conseillées. */
    val pulses: Int = 0,
) {
    val temperatureLevel: Int get() = Mcc.levelAtMost(tempC)

    /** Niveau de la table du Smart pour une température absente de celle du Connect (40 °C), sinon null. */
    val smartLevel: Int? get() =
        if (tempC > 0 && Mcc.levelForCelsius(tempC) < 0 && tempC in Smart.TEMPERATURE_LEVELS) Smart.TEMPERATURE_LEVELS.indexOf(tempC) else null

    /** Température affichée : celle de la recette si le robot l'a, sinon celle de la table juste en dessous. */
    private val shownC: Int get() = if (smartLevel != null) tempC else Mcc.celsiusForLevel(temperatureLevel)

    /** Programme propre au Smart et la trame capturée qui le porte (null pour les autres). */
    private val smartProgram: SmartProgram? get() = when (program) {
        StepProgram.MIXER -> SmartProgram.MIXER
        StepProgram.SMOOTHIE -> SmartProgram.SMOOTHIE
        StepProgram.SOUS_VIDE -> SmartProgram.SOUS_VIDE
        StepProgram.SLOW_COOK -> SmartProgram.MIJOTER_BRASSE
        StepProgram.FERMENT -> SmartProgram.FERMENTER_BRASSE
        else -> null
    }

    /** Température exacte d'un programme du Smart (null : celle de l'appli d'origine). */
    private val programCelsius: Int? get() = if (program.heated && tempC > 0) tempC else null

    val summary: String
        get() = when (program) {
            StepProgram.TURBO -> if (pulses > 0) "Turbo × $pulses" else "Turbo"
            StepProgram.KNEAD -> JobKind.KNEAD.label + " " + SafetyPolicy.formatDuration(seconds * 1000L)
            StepProgram.STEAM -> JobKind.STEAM.label + " " + SafetyPolicy.formatDuration(seconds * 1000L)
            StepProgram.TOAST -> JobKind.TOAST.label + " " + SafetyPolicy.formatDuration(seconds * 1000L)
            StepProgram.MIXER, StepProgram.SMOOTHIE, StepProgram.SOUS_VIDE, StepProgram.SLOW_COOK, StepProgram.FERMENT -> buildString {
                append(program.label).append(' ').append(SafetyPolicy.formatDuration(seconds * 1000L))
                val c = programCelsius ?: smartProgram?.takeIf { program.heated }?.targetCelsius ?: 0
                if (c > 0) append(" · ").append(c).append(" °C")
            }
            StepProgram.MANUAL -> buildString {
                append(SafetyPolicy.formatDuration(seconds * 1000L))
                if (tempC > 0) append(" · ").append(shownC).append(" °C")
                append(" · ").append(if (speed == 0) tr("sans mélange") else tr("vitesse %d", speed))
                if (reverse && speed > 0) append(" · ").append(tr("inverse"))
                if (accessory == Accessory.WHISK) append(" · ").append(tr("fouet"))
            }
        }

    /** Travail exécutable par la machine (sauf Turbo, piloté à l'appui). */
    fun toJob(label: String, hotBlendConfirmed: Boolean = false): JobSpec? {
        val ms = seconds * 1000L
        return when (program) {
            StepProgram.MANUAL -> Programs.manual(
                speed, temperatureLevel,
                if (reverse) Direction.COUNTER_CLOCKWISE else Direction.CLOCKWISE,
                ms, accessory, hotBlendConfirmed, label, smartLevel = smartLevel,
            )
            StepProgram.KNEAD -> Programs.knead(ms).copy(label = label)
            StepProgram.STEAM -> Programs.steam(ms).copy(label = label)
            StepProgram.TOAST -> Programs.toast(ms).copy(label = label)
            StepProgram.TURBO -> null
            StepProgram.MIXER -> Programs.smartProgram(SmartProgram.MIXER, ms, label, hotBlendConfirmed)
            StepProgram.SMOOTHIE -> Programs.smartProgram(SmartProgram.SMOOTHIE, ms, label, hotBlendConfirmed)
            StepProgram.SOUS_VIDE -> Programs.smartProgram(SmartProgram.SOUS_VIDE, ms, label, hotBlendConfirmed, programCelsius)
            StepProgram.SLOW_COOK -> Programs.smartMijoter(ms, label, programCelsius)
            StepProgram.FERMENT -> Programs.smartFermenter(ms, label, programCelsius)
        }
    }

    /** Problèmes de sécurité indépendants de l'état de la machine. */
    fun violations(): List<Violation> {
        if (program == StepProgram.TURBO) {
            val v = ArrayList<Violation>()
            if (accessory == Accessory.WHISK) v += Violation("accessory_turbo", tr("Pas de Turbo avec le fouet"))
            return v
        }
        val v = ArrayList<Violation>()
        val caps = RobotProfiles.active.capabilities
        if (!caps.has(program.feature)) v += Violation("feature", tr("%s : absent de ce robot", program.label))
        if (program == StepProgram.MANUAL && tempC != 0 && tempC !in caps.temperaturesC)
            v += Violation("temp_table", tr("Température %d °C absente de la machine", tempC))
        val sp = smartProgram
        if (sp != null && programCelsius != null && !sp.allowsCelsius(tempC))
            v += Violation("temp_table", tr("%s : de %d à %d °C", program.label, sp.minCelsius, sp.maxCelsius))
        val job = toJob("vérification") ?: return v
        v += SafetyPolicy.checkJob(job, null)
        return v
    }

    val valid: Boolean get() = violations().isEmpty()
}

/** [originalGrams] : poids de la recette quand l'utilisateur l'a adapté (voir [RecipeAdjustments]) ; 0 sinon. Non enregistré. */
data class Weigh(val label: String, val grams: Int, val originalGrams: Int = 0)

data class Step(
    val text: String,
    val machine: StepMachine? = null,
    val weigh: Weigh? = null,
    /** Réglage importé non vérifié : l'utilisateur doit le confirmer avant d'exécuter. */
    val needsReview: Boolean = false,
)

data class Recipe(
    val id: String,
    val title: String,
    val category: String = "",
    val tags: List<String> = emptyList(),
    val servings: Int = 0,
    val prepMinutes: Int = 0,
    val totalMinutes: Int = 0,
    val difficulty: Int = 1,
    val ingredients: List<IngredientGroup> = emptyList(),
    val steps: List<Step> = emptyList(),
    val source: RecipeSource = RecipeSource.IMPORTED,
    val image: String = "",
    val favorite: Boolean = false,
    val notes: String = "",
    /** Qui a publié la recette (vide si inconnu). */
    val author: String = "",
    /** Note moyenne sur 5 et nombre de notes sur monsieur-cuisine.com (0 : aucune). */
    val rating: Double = 0.0,
    val ratingCount: Int = 0,
    /** Langue de la recette (« de-DE ») quand elle n'est pas dans celle de l'interface ; vide sinon. */
    val language: String = "",
    /** Ce que compte [servings] quand ce ne sont pas des personnes (« pots », « Gläser ») ; vide sinon. */
    val servingUnit: String = "",
    /** Robots auxquels le site destine la recette (« MC2.0 », « MC3.0 ») ; vide si inconnu (recettes perso). */
    val devices: List<String> = emptyList(),
) {
    val machineSteps: Int get() = steps.count { it.machine != null }
    val allIngredients: List<Ingredient> get() = ingredients.flatMap { it.items }
}

/** Format de fichier « toque-recettes-1 » (JSON lisible et éditable). */
object RecipeCodec {
    const val FORMAT = "toque-recettes-1"

    fun toMap(r: Recipe): Map<String, Any?> = linkedMapOf(
        "id" to r.id,
        "title" to r.title,
        "category" to r.category,
        "tags" to r.tags,
        "servings" to r.servings,
        "prepMinutes" to r.prepMinutes,
        "totalMinutes" to r.totalMinutes,
        "difficulty" to r.difficulty,
        "source" to r.source.name.lowercase(),
        "image" to r.image,
        "favorite" to r.favorite,
        "notes" to r.notes,
        "author" to r.author,
        "rating" to r.rating,
        "ratingCount" to r.ratingCount,
        "language" to r.language,
        "servingUnit" to r.servingUnit,
        "devices" to r.devices,
        "ingredients" to r.ingredients.map { g ->
            linkedMapOf(
                "group" to g.title,
                "items" to g.items.map { linkedMapOf("qty" to it.qty, "unit" to it.unit, "name" to it.name, "note" to it.note) },
            )
        },
        "steps" to r.steps.map { s ->
            val m = linkedMapOf<String, Any?>("text" to s.text)
            s.machine?.let { mc ->
                m["machine"] = linkedMapOf(
                    "program" to mc.program.key, "seconds" to mc.seconds, "speed" to mc.speed,
                    "tempC" to mc.tempC, "reverse" to mc.reverse,
                    "accessory" to mc.accessory.name.lowercase(), "pulses" to mc.pulses,
                )
            }
            s.weigh?.let { w -> m["weigh"] = linkedMapOf("label" to w.label, "grams" to w.grams) }
            if (s.needsReview) m["needsReview"] = true
            m
        },
    )

    fun fromMap(m: Map<String, Any?>, fallbackSource: RecipeSource = RecipeSource.IMPORTED): Recipe? {
        val title = m.str("title")?.trim().orEmpty()
        if (title.isEmpty()) return null
        val id = m.str("id")?.trim().takeUnless { it.isNullOrEmpty() } ?: slug(title)
        val source = RecipeSource.values().firstOrNull { it.name.equals(m.str("source"), true) } ?: fallbackSource
        val groups = m["ingredients"].list().mapNotNull { gAny ->
            val g = gAny.obj()
            val items = g["items"].list().mapNotNull { iAny ->
                when (iAny) {
                    is String -> if (iAny.isBlank()) null else Ingredient(name = iAny.trim())
                    else -> {
                        val i = iAny.obj()
                        val name = i.str("name")?.trim().orEmpty()
                        if (name.isEmpty()) null
                        else Ingredient(i.str("qty").orEmpty(), i.str("unit").orEmpty(), name, i.str("note").orEmpty())
                    }
                }
            }
            if (items.isEmpty()) null else IngredientGroup(g.str("group").orEmpty(), items)
        }
        val steps = m["steps"].list().mapNotNull { sAny ->
            val s = sAny.obj()
            val text = s.str("text")?.trim().orEmpty()
            val mc = s["machine"]?.obj()?.let { parseMachine(it) }
            val w = s["weigh"]?.obj()?.let { wm ->
                val g = wm.int("grams") ?: 0
                if (g > 0) Weigh(wm.str("label").orEmpty(), g) else null
            }
            if (text.isEmpty() && mc == null) null else Step(text, mc, w, s.bool("needsReview") ?: false)
        }
        return Recipe(
            id = id,
            title = title,
            category = m.str("category").orEmpty(),
            tags = m["tags"].list().mapNotNull { (it as? String)?.trim()?.takeIf { t -> t.isNotEmpty() } },
            servings = m.int("servings") ?: 0,
            prepMinutes = m.int("prepMinutes") ?: 0,
            totalMinutes = m.int("totalMinutes") ?: 0,
            difficulty = (m.int("difficulty") ?: 1).coerceIn(1, 3),
            ingredients = groups,
            steps = steps,
            source = source,
            image = m.str("image").orEmpty(),
            favorite = m.bool("favorite") ?: false,
            notes = m.str("notes").orEmpty(),
            author = m.str("author")?.trim().orEmpty(),
            rating = (m.double("rating") ?: 0.0).coerceIn(0.0, 5.0),
            ratingCount = (m.int("ratingCount") ?: 0).coerceAtLeast(0),
            language = m.str("language")?.trim().orEmpty(),
            servingUnit = m.str("servingUnit")?.trim().orEmpty(),
            devices = m["devices"].list().mapNotNull { (it as? String)?.trim()?.takeIf { d -> d.isNotEmpty() } },
        )
    }

    private fun parseMachine(m: Map<String, Any?>): StepMachine? {
        val program = StepProgram.of(m.str("program")) ?: return null
        val acc = Accessory.values().firstOrNull { it.name.equals(m.str("accessory"), true) } ?: Accessory.BLADE
        return StepMachine(
            program = program,
            seconds = (m.int("seconds") ?: 0).coerceAtLeast(0),
            speed = m.int("speed") ?: 0,
            tempC = m.int("tempC") ?: 0,
            reverse = m.bool("reverse") ?: false,
            accessory = acc,
            pulses = (m.int("pulses") ?: 0).coerceAtLeast(0),
        )
    }

    fun encode(r: Recipe, pretty: Boolean = false): String = Json.write(toMap(r), pretty)

    fun decode(text: String, fallbackSource: RecipeSource = RecipeSource.IMPORTED): Recipe? =
        fromMap(Json.parse(text).obj(), fallbackSource)

    fun encodeFile(recipes: List<Recipe>): String =
        Json.write(linkedMapOf("format" to FORMAT, "recipes" to recipes.map { toMap(it) }), pretty = true)

    /** Lit un fichier de recettes ; les entrées illisibles sont ignorées et comptées. */
    fun decodeFile(text: String, fallbackSource: RecipeSource): Pair<List<Recipe>, Int> {
        val root = Json.parse(text)
        val entries = when (root) {
            is List<*> -> root
            is Map<*, *> -> root.obj()["recipes"].list()
            else -> emptyList()
        }
        var bad = 0
        val out = ArrayList<Recipe>(entries.size)
        for (e in entries) {
            val r = try {
                fromMap(e.obj(), fallbackSource)
            } catch (_: Exception) {
                null
            }
            if (r == null) bad++ else out += r
        }
        return out to bad
    }

    fun slug(text: String): String {
        val base = TextNorm.normalize(text).replace(' ', '-').trim('-')
        return if (base.isEmpty()) "recette-" + System.currentTimeMillis() else base.take(48)
    }
}

/**
 * « Mes recettes » : recettes écrites ou modifiées sur le robot. Leur identifiant commence par
 * [ID_PREFIX] ; les synchronisations (site, appli d'origine) n'y touchent jamais.
 */
/** Recettes de toqueapp.org : identifiant « toqueapp-N », N étant le numéro de la recette sur le site. */
object CommunityRecipes {
    const val ID_PREFIX = "toqueapp-"

    fun isCommunity(id: String): Boolean = id.startsWith(ID_PREFIX)

    /** Numéro de la recette sur le site, ou null. */
    fun siteId(id: String): Long? = if (isCommunity(id)) id.removePrefix(ID_PREFIX).toLongOrNull() else null
}

object UserRecipes {
    const val ID_PREFIX = "perso-"
    /** Nom du fichier d'échange, dans le dossier Download du robot. */
    const val FILE_NAME = "toque-mes-recettes.json"

    fun isUser(id: String): Boolean = id.startsWith(ID_PREFIX)

    fun newId(title: String, now: Long = System.currentTimeMillis()): String =
        ID_PREFIX + RecipeCodec.slug(title).take(32).trim('-') + "-" + java.lang.Long.toString(now, 36).takeLast(6)

    /** Une recette vide, prête à remplir. */
    fun blank(now: Long = System.currentTimeMillis()): Recipe =
        Recipe(id = newId("recette", now), title = "", servings = 4, source = RecipeSource.USER)

    /**
     * Sa propre version d'une recette, telle qu'elle est affichée (quantités adaptées, nombre de
     * personnes choisi). La recette d'origine reste telle quelle.
     */
    fun copyOf(r: Recipe, now: Long = System.currentTimeMillis()): Recipe = r.copy(
        id = newId(r.title, now),
        source = RecipeSource.USER,
        favorite = false,
        author = "",
        rating = 0.0,
        ratingCount = 0,
        ingredients = r.ingredients.map { g -> g.copy(items = g.items.map { it.copy(originalQty = "") }) },
        steps = r.steps.map { s -> s.copy(weigh = s.weigh?.copy(originalGrams = 0)) },
    )

    /** Recette lue dans un fichier : toujours une recette perso. */
    fun imported(r: Recipe): Recipe = r.copy(
        id = if (isUser(r.id)) r.id else ID_PREFIX + RecipeCodec.slug(r.id).take(40),
        source = RecipeSource.USER,
        favorite = false,
    )

    /** Ce qui empêche d'enregistrer la recette, ou null. */
    fun problem(r: Recipe): String? {
        if (r.title.isBlank()) return tr("Donnez un titre à la recette")
        if (r.steps.isEmpty() && r.allIngredients.isEmpty()) return tr("Ajoutez au moins un ingrédient ou une étape")
        for ((i, s) in r.steps.withIndex()) {
            val v = s.machine?.violations()?.firstOrNull() ?: continue
            return tr("Étape %d : %s", i + 1, v.message)
        }
        return null
    }

    /** Ingrédient écrit d'une traite (« 500 g de farine ») : quantité et unité à part. */
    fun ingredient(text: String): Ingredient? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        return QuantityLinks.split(Ingredient(name = t))
    }
}

/** Exécute un réglage d'étape à la main (utile pour le mode manuel pré-rempli). */
fun StepMachine.toSetting(): Setting = Setting(
    when (program) {
        StepProgram.MANUAL -> Operation.STANDARD
        StepProgram.KNEAD -> Operation.KNEAD
        StepProgram.STEAM -> Operation.STEAM
        StepProgram.TOAST -> Operation.TOAST
        StepProgram.TURBO -> Operation.TURBO
        // Programmes du Smart : réglage manuel indicatif (le programme envoie sa propre trame).
        StepProgram.MIXER, StepProgram.SMOOTHIE, StepProgram.SOUS_VIDE, StepProgram.SLOW_COOK, StepProgram.FERMENT -> Operation.STANDARD
    },
    speed, temperatureLevel, if (reverse) Direction.COUNTER_CLOCKWISE else Direction.CLOCKWISE,
)
