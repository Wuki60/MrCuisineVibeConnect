package app.toque

import android.content.Context
import android.content.SharedPreferences
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey
import app.toque.core.ring.Rgb
import app.toque.core.ring.RingLook
import app.toque.core.robot.RobotProfile
import app.toque.core.robot.RobotProfiles
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Réglages persistants, lisibles par les deux processus (interface et machine).
 * Les réglages lus par `:machine` utilisent `commit()` (`sync = true`) pour une visibilité
 * inter-processus immédiate ; les réglages propres à l'interface utilisent `apply()` (non bloquant).
 */
class Prefs(context: Context) {
    @Suppress("DEPRECATION")
    private val p: SharedPreferences = context.getSharedPreferences("toque", Context.MODE_MULTI_PROCESS)

    private fun SharedPreferences.Editor.save(sync: Boolean) {
        if (sync) commit() else apply()
    }

    private fun boolPref(key: String, default: Boolean, sync: Boolean = false) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = p.getBoolean(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) { p.edit().putBoolean(key, value).save(sync) }
    }

    private fun intPref(key: String, default: Int, sync: Boolean = false) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = p.getInt(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) { p.edit().putInt(key, value).save(sync) }
    }

    private fun longPref(key: String, default: Long, sync: Boolean = false) = object : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Long = p.getLong(key, default)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) { p.edit().putLong(key, value).save(sync) }
    }

    private fun stringPref(key: String, default: String, sync: Boolean = false) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = p.getString(key, default) ?: default
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { p.edit().putString(key, value).save(sync) }
    }

    private fun stringSetPref(key: String, sync: Boolean = false) = object : ReadWriteProperty<Any?, Set<String>> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Set<String> = HashSet(p.getStringSet(key, emptySet()) ?: emptySet())
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Set<String>) { p.edit().putStringSet(key, HashSet(value)).save(sync) }
    }

    enum class Mode(val key: String, private val fr: String) {
        DEMO("demo", trKey("Démo (base simulée)")),
        TWIN("twin", trKey("Jumeau numérique (PC)")),
        HARDWARE("hardware", trKey("Machine réelle"));

        val label: String get() = tr(fr)

        companion object {
            fun of(k: String?) = values().firstOrNull { it.key == k } ?: DEMO
        }
    }

    // --- Réglages partagés avec le processus :machine (commit synchrone) ---

    var mode: Mode
        get() = Mode.of(p.getString("mode", null))
        set(v) { p.edit().putString("mode", v.key).commit() }

    /** Langue de l'interface : « auto » (celle du système), « fr » ou « en ». */
    var language: String by stringPref("language", I18n.AUTO, sync = true)

    var twinHost: String by stringPref("twinHost", "127.0.0.1", sync = true)
    var twinPort: Int by intPref("twinPort", 8765, sync = true)

    /**
     * Robot confirmé par la personne (identifiant de [RobotProfiles]) ; vide tant qu'elle ne l'a pas
     * confirmé : la liaison avec la base reste alors fermée en « Machine réelle ».
     */
    var robotId: String by stringPref("robotId", "", sync = true)

    val robotConfirmed: Boolean get() = RobotProfiles.byId(robotId) != null

    /** Fiche du robot choisi (le Connect tant que rien n'est confirmé, comme avant les fiches). */
    val robot: RobotProfile get() = RobotProfiles.byId(robotId) ?: RobotProfiles.MC_CONNECT

    /** Port série : réglage manuel sur le Connect (ancien réglage), sinon celui de la fiche. */
    var serialDevice: String
        get() = (if (robot === RobotProfiles.MC_CONNECT) p.getString("serialDevice", null) else null) ?: robot.serialDevice
        set(v) { p.edit().putString("serialDevice", v).commit() }

    /** Appli d'origine : réglage manuel sur le Connect (ancien réglage), sinon celle de la fiche. */
    var vendorPackage: String
        get() = (if (robot === RobotProfiles.MC_CONNECT) p.getString("vendorPackage", null) else null) ?: robot.vendorPackage
        set(v) { p.edit().putString("vendorPackage", v).commit() }

    var requireBowl: Boolean by boolPref("requireBowl", false, sync = true)
    /** Capteur du bol lu à l'envers (bases qui signalent « bol » quand il est retiré). */
    var invertBowl: Boolean by boolPref("invertBowl", false, sync = true)
    /** Montée progressive de la vitesse quand le contenu est chaud (≥ 60 °C). */
    var hotRamp: Boolean by boolPref("hotRamp", true, sync = true)
    /** Balance remise à zéro quand le bol vide est replacé sur le socle. */
    var bowlAutoZero: Boolean by boolPref("bowlAutoZero", true, sync = true)
    /** Masse du bol vide apprise (g), 0 = inconnue. */
    var emptyBowlG: Int by intPref("emptyBowlG", 0, sync = true)
    /** Horodatage du dernier crash (commit pour survivre à killProcess). */
    var lastCrashMs: Long by longPref("lastCrashMs", 0L, sync = true)

    // --- Réglages propres à l'interface (apply asynchrone) ---

    /** Couleur d'accent de l'interface (une de Ui.ACCENTS, ou une couleur de la roue que Ui.accepts garde), 0 = celle d'origine. */
    var accentColor: Int by intPref("accentColor", 0)
    /** Thème clair (fond clair) ; faux = bleu nuit d'origine. */
    var lightTheme: Boolean by boolPref("lightTheme", false)
    /** Un téléphone appairé peut lancer une cuisson manuelle (Réglages › Compagnon). */
    var remoteStart: Boolean by boolPref("remoteStart", true)
    /** Recettes : tare automatique en arrivant sur une étape de pesée. */
    var autoTareSteps: Boolean by boolPref("autoTareSteps", true)
    /** Recettes : enchaîner sans appui les étapes machine qui se suivent sans geste à faire. */
    var chainSteps: Boolean by boolPref("chainSteps", true)
    /** Programmes masqués de l'écran Programmes (noms de [app.toque.core.robot.Feature]), d'après #130. */
    var hiddenPrograms: Set<String> by stringSetPref("hiddenPrograms")
    var sound: Boolean by boolPref("sound", true)
    /** Mise en veille de l'écran après ce nombre de minutes sans geste (0 : jamais). */
    var standbyMinutes: Int by intPref("standbyMinutes", 10)
    /** Pendant un programme, minutes sans geste avant que l'écran baisse sa luminosité (0 : jamais). */
    var jobDimMinutes: Int by intPref("jobDimMinutes", 0)
    /** Cette luminosité réduite, en pour cent ; 0 : la plus faible que l'écran sait tenir. */
    var jobDimBrightness: Int by intPref("jobDimBrightness", 20)
    /**
     * Pendant un programme, minutes sans geste avant que l'écran passe au noir comme en veille, avec l'étape et
     * son temps restant à la place de l'heure (0 : jamais).
     */
    var jobStandbyMinutes: Int by intPref("jobStandbyMinutes", 0)

    /**
     * Ancien réglage de la molette pendant la veille : « normal » (inchangée), « dim » (veilleuse blanche
     * faible) ou « off » (éteinte). Reprend l'ancien interrupteur « standbyKnobLight » s'il a été réglé.
     * Remplacé par [ringStandbyLevel], qui le reprend tant qu'il n'a pas été réglé.
     */
    var standbyKnob: String
        get() = p.getString("standbyKnob", null)
            ?: if (p.getBoolean("standbyKnobLight", true)) "dim" else "normal"
        set(v) { p.edit().putString("standbyKnob", v).apply() }

    /** Code couleur sur l'anneau de la molette (problème, pause, à vous, en cours). */
    var knobColours: Boolean by boolPref("knobColours", true)
    /** Luminosité de l'anneau allumé : niveau 1 à 5 ; 0 tant que rien n'a été choisi (le blanc propre au robot). */
    var ringLevel: Int by intPref("ringLevel", 0)
    /** Couleur de l'anneau allumé quand le code couleur est coupé (clé de [RingLook.Tint]). */
    var ringTint: String by stringPref("ringTint", RingLook.Tint.WHITE.key)

    /**
     * Luminosité de l'anneau pendant la veille : niveau 0 (éteint) à 5, ramené au niveau de l'anneau
     * allumé s'il le dépasse. Tant qu'il n'a pas été réglé, reprend l'ancien choix [standbyKnob] :
     * comme allumé, la veilleuse (niveau 1) ou éteint.
     */
    var ringStandbyLevel: Int
        get() = if (p.contains("ringStandbyLevel")) p.getInt("ringStandbyLevel", 0) else when (standbyKnob) {
            "off" -> 0
            "dim" -> 1
            else -> RingLook.MAX_LEVEL
        }
        set(v) { p.edit().putInt("ringStandbyLevel", v).apply() }

    /** Couleur de l'anneau pendant la veille quand le code couleur est coupé (clé de [RingLook.Tint]). */
    var ringStandbyTint: String by stringPref("ringStandbyTint", RingLook.Tint.WHITE.key)

    /** Couleur personnalisée de l'anneau allumé : son mélange de rouge, de vert et de blanc ; null tant que rien n'est composé. */
    var ringCustom: Rgb?
        get() = RingLook.parseMix(p.getString("ringCustom", null))
        set(v) { p.edit().putString("ringCustom", v?.toString()).apply() }

    /** Couleur personnalisée de l'anneau pendant la veille. */
    var ringStandbyCustom: Rgb?
        get() = RingLook.parseMix(p.getString("ringStandbyCustom", null))
        set(v) { p.edit().putString("ringStandbyCustom", v?.toString()).apply() }

    /** Les réglages de l'anneau réunis, tels que l'anneau les applique. */
    val ring: RingLook.Settings
        get() = RingLook.Settings(knobColours, ringLevel, RingLook.Tint.of(ringTint), ringStandbyLevel, RingLook.Tint.of(ringStandbyTint),
            ringCustom, ringStandbyCustom)
    /** Fuseau horaire : « auto » (d'après la connexion Internet) ou un identifiant IANA choisi à la main. */
    var timeZone: String by stringPref("timeZone", "auto")
    /** Dernier fuseau appliqué avec succès (affiché dans les réglages). */
    var lastZone: String by stringPref("lastZone", "")

    var autoSync: Boolean by boolPref("autoSync", true)
    var lastSyncMs: Long by longPref("lastSyncMs", 0L)

    /** Résultat de la dernière synchro, écrit dans la langue d'alors (lastSyncLang). */
    var lastSyncResult: String
        get() = p.getString("lastSyncResult", "") ?: ""
        set(v) { p.edit().putString("lastSyncResult", v).putString("lastSyncLang", I18n.code).apply() }

    val lastSyncLang: String get() = p.getString("lastSyncLang", "") ?: ""

    var vendorSession: Boolean by boolPref("vendorSession", false)
    /** Bouton « Retour à Toque » à afficher : Toque a ouvert une autre appli (robot sans bouton Accueil). */
    var returnBubble: Boolean by boolPref("returnBubble", false)
    /** Ouvrir Toque au démarrage du robot (désactivé par « Revenir à l'appli d'origine »). */
    var bootLaunch: Boolean by boolPref("bootLaunch", true)

    var knobLeft: Int by intPref("knobLeft", -1)
    var knobRight: Int by intPref("knobRight", -1)
    var knobPress: Int by intPref("knobPress", -1)
    /** Vrai une fois le bouton rotatif réglé, vu en marche, ou déclaré absent de l'appareil. */
    var knobSetupDone: Boolean by boolPref("knobSetupDone", false)
    /** Le réglage guidé n'est proposé d'office qu'une fois ; ensuite, l'accueil le rappelle. */
    var knobSetupShown: Boolean by boolPref("knobSetupShown", false)
    /** Faux tant que rien ne dit que le bouton rotatif fonctionne : l'accueil affiche alors un rappel. */
    val knobReady: Boolean get() = knobSetupDone || knobLeft >= 0 || knobRight >= 0 || knobPress >= 0

    var welcomeDone: Boolean by boolPref("welcomeDone", false)
    var builtinVersion: Int by intPref("builtinVersion", 0)

    /** Recette guidée en cours (id + étape), pour reprendre après un redémarrage. */
    var guidedRecipe: String by stringPref("guidedRecipe", "")
    var guidedStep: Int by intPref("guidedStep", 0)
    /** Titre connu de la recette guidée, « id⇥titre » : l'accueil le montre avant le chargement du catalogue. */
    var guidedTitle: String by stringPref("guidedTitle", "")

    /** Réglages importés déjà vérifiés (« J'ai vérifié ce réglage »), clé = recette, étape et réglage. */
    var reviewedSteps: Set<String> by stringSetPref("reviewedSteps")
    /**
     * Réglages refusés remplacés d'un appui par le réglage permis le plus proche (« Utiliser … »),
     * clé = recette, étape, réglage de la recette et réglage adopté.
     */
    var adaptedSteps: Set<String> by stringSetPref("adaptedSteps")

    /** Son joué quand la quantité à peser est atteinte (voir Sounds.WEIGH_SOUNDS). */
    var weighSound: String by stringPref("weighSound", "ding")
    /** Prévenir quand une nouvelle version de Toque est publiée (au démarrage et au réveil). */
    var updateAlerts: Boolean by boolPref("updateAlerts", true)
    var lastUpdateCheckMs: Long by longPref("lastUpdateCheckMs", 0L)
    /** Dernière version proposée par l'alerte, et quand (« Plus tard » ne la repropose pas tout de suite). */
    var updateAskedVersion: String by stringPref("updateAskedVersion", "")
    var updateAskedMs: Long by longPref("updateAskedMs", 0L)
    /** Ajouter les recettes en ligne publiées seulement dans une autre langue. */
    var foreignRecipes: Boolean by boolPref("foreignRecipes", false)
    /**
     * Langue du catalogue Lidl : « auto » (d'après l'interface ; le catalan → espagnol) ou un code
     * du site (es-ES, fr-FR, …). Indépendant de la langue de l'interface.
     */
    var recipeLanguage: String by stringPref("recipeLanguage", "auto")

    // ------------------------------------------------------------------ toqueapp.org

    /** Clé de ce robot sur toqueapp.org (vide : pas relié à un compte). Jamais affichée. */
    var siteToken: String by stringPref("siteToken", "")
    /** Pseudo du compte Toque relié. */
    var siteUser: String by stringPref("siteUser", "")
    val siteLinked: Boolean get() = siteToken.isNotBlank()
    /** Proposition de relier un compte Toque déjà faite (une seule fois, acceptée ou non). */
    var siteOffered: Boolean by boolPref("siteOffered", false)
    /** Recevoir les recettes de la communauté (sans compte), avec la synchro. */
    var siteCommunity: Boolean by boolPref("siteCommunity", true)
    /** Date (ISO, celle du site) de la recette la plus récente reçue ; vide : tout redemander. */
    var siteSince: String by stringPref("siteSince", "")
    /** Langue des traductions demandée lors de la dernière synchro (autre langue : tout redemander). */
    var siteLang: String by stringPref("siteLang", "")
    /**
     * Version de ce que l'appli garde des recettes du site : plus petite que SiteAccount.FORMAT, la
     * prochaine synchro redemande toutes les recettes une fois (ex. pour recevoir leurs photos).
     */
    var siteFormat: Int by intPref("siteFormat", 0)
    /** Dernière liste complète (recettes retirées du site enlevées), en ms. */
    var siteFullMs: Long by longPref("siteFullMs", 0L)
    var siteLastMs: Long by longPref("siteLastMs", 0L)

    /** Résultat de la dernière synchro avec toqueapp.org, écrit dans la langue d'alors (siteLastLang). */
    var siteLastResult: String
        get() = p.getString("siteLastResult", "") ?: ""
        set(v) { p.edit().putString("siteLastResult", v).putString("siteLastLang", I18n.code).apply() }

    val siteLastLang: String get() = p.getString("siteLastLang", "") ?: ""

    /** Favoris changés ici, pas encore envoyés au site : « toqueapp-12=1 », « toqueapp-7=0 ». */
    var sitePendingFavorites: Map<String, Boolean>
        get() = (p.getString("sitePendingFavorites", "") ?: "").split('\n').mapNotNull { line ->
            val k = line.substringBefore('=', "")
            if (k.isEmpty()) null else k to (line.substringAfter('=') == "1")
        }.toMap()
        set(v) { p.edit().putString("sitePendingFavorites", v.entries.joinToString("\n") { "${it.key}=${if (it.value) 1 else 0}" }).apply() }

    /** Recettes perso mises sur le site : identifiant perso → numéro sur le site. */
    var siteLinks: Map<String, Long>
        get() = (p.getString("siteLinks", "") ?: "").split('\n').mapNotNull { line ->
            val id = line.substringAfter('=', "").toLongOrNull() ?: return@mapNotNull null
            line.substringBefore('=') to id
        }.toMap()
        set(v) { p.edit().putString("siteLinks", v.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply() }

    /** Recettes perso publiées pour tous (les autres de [siteLinks] sont privées, sur le compte seulement). */
    var sitePublic: Set<String> by stringSetPref("sitePublic")
    /** Recettes perso pour lesquelles on a répondu « Garder pour moi » : ne plus demander. */
    var siteKeepLocal: Set<String> by stringSetPref("siteKeepLocal")

    /** Oublie le compte relié (les recettes reçues restent). */
    fun forgetSite() {
        p.edit().remove("siteToken").remove("siteUser").remove("sitePendingFavorites").apply()
    }

    // ------------------------------------------------------------------ Mealie (serveur de recettes auto-hébergé)

    /** Adresse du serveur Mealie relié (« http://mealie.lan:9000 ») ; vide : aucun. */
    var mealieUrl: String by stringPref("mealieUrl", "")
    /** Jeton d'API du robot sur ce serveur (jamais affiché ni écrit dans le journal). */
    var mealieToken: String by stringPref("mealieToken", "")
    /** Numéro du jeton créé par le robot (retiré du serveur en déliant) ; 0 : jeton collé à la main. */
    var mealieTokenId: Long by longPref("mealieTokenId", 0L)
    val mealieLinked: Boolean get() = mealieUrl.isNotBlank() && mealieToken.isNotBlank()
    var mealieLastMs: Long by longPref("mealieLastMs", 0L)

    /** Résultat de la dernière synchro avec Mealie, écrit dans la langue d'alors (mealieLastLang). */
    var mealieLastResult: String
        get() = p.getString("mealieLastResult", "") ?: ""
        set(v) { p.edit().putString("mealieLastResult", v).putString("mealieLastLang", I18n.code).apply() }

    val mealieLastLang: String get() = p.getString("mealieLastLang", "") ?: ""

    /** Oublie le serveur Mealie relié. */
    fun forgetMealie() {
        p.edit().remove("mealieUrl").remove("mealieToken").remove("mealieTokenId").remove("mealieLastResult").remove("mealieLastMs").apply()
    }
}
