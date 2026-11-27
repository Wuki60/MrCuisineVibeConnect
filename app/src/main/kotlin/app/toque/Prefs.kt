package app.toque

import android.content.Context
import android.content.SharedPreferences
import app.toque.core.i18n.I18n
import app.toque.core.i18n.tr
import app.toque.core.i18n.trKey
import app.toque.core.robot.RobotProfile
import app.toque.core.robot.RobotProfiles

/**
 * Réglages persistants, lisibles par les deux processus (interface et machine).
 * Écriture synchrone (commit) : le processus machine relit le fichier juste après.
 */
class Prefs(context: Context) {
    @Suppress("DEPRECATION")
    private val p: SharedPreferences = context.getSharedPreferences("toque", Context.MODE_MULTI_PROCESS)

    enum class Mode(val key: String, private val fr: String) {
        DEMO("demo", trKey("Démo (base simulée)")),
        TWIN("twin", trKey("Jumeau numérique (PC)")),
        HARDWARE("hardware", trKey("Machine réelle"));

        val label: String get() = tr(fr)

        companion object {
            fun of(k: String?) = values().firstOrNull { it.key == k } ?: DEMO
        }
    }

    var mode: Mode
        get() = Mode.of(p.getString("mode", null))
        set(v) {
            p.edit().putString("mode", v.key).commit()
        }

    /** Langue de l'interface : « auto » (celle du système), « fr » ou « en ». */
    var language: String
        get() = p.getString("language", I18n.AUTO) ?: I18n.AUTO
        set(v) {
            p.edit().putString("language", v).commit()
        }

    /** Couleur d'accent de l'interface (une de Ui.ACCENTS, ou une couleur de la roue que Ui.accepts garde), 0 = celle d'origine. */
    var accentColor: Int
        get() = p.getInt("accentColor", 0)
        set(v) {
            p.edit().putInt("accentColor", v).commit()
        }

    /** Thème clair (fond clair) ; faux = bleu nuit d'origine. */
    var lightTheme: Boolean
        get() = p.getBoolean("lightTheme", false)
        set(v) {
            p.edit().putBoolean("lightTheme", v).commit()
        }

    var twinHost: String
        get() = p.getString("twinHost", "127.0.0.1") ?: "127.0.0.1"
        set(v) {
            p.edit().putString("twinHost", v).commit()
        }

    var twinPort: Int
        get() = p.getInt("twinPort", 8765)
        set(v) {
            p.edit().putInt("twinPort", v).commit()
        }

    /**
     * Robot confirmé par la personne (identifiant de [RobotProfiles]) ; vide tant qu'elle ne l'a pas
     * confirmé : la liaison avec la base reste alors fermée en « Machine réelle ».
     */
    var robotId: String
        get() = p.getString("robotId", "") ?: ""
        set(v) {
            p.edit().putString("robotId", v).commit()
        }

    val robotConfirmed: Boolean get() = RobotProfiles.byId(robotId) != null

    /** Fiche du robot choisi (le Connect tant que rien n'est confirmé, comme avant les fiches). */
    val robot: RobotProfile get() = RobotProfiles.byId(robotId) ?: RobotProfiles.MC_CONNECT

    /** Port série : réglage manuel sur le Connect (ancien réglage), sinon celui de la fiche. */
    var serialDevice: String
        get() = (if (robot === RobotProfiles.MC_CONNECT) p.getString("serialDevice", null) else null) ?: robot.serialDevice
        set(v) {
            p.edit().putString("serialDevice", v).commit()
        }

    /** Appli d'origine : réglage manuel sur le Connect (ancien réglage), sinon celle de la fiche. */
    var vendorPackage: String
        get() = (if (robot === RobotProfiles.MC_CONNECT) p.getString("vendorPackage", null) else null) ?: robot.vendorPackage
        set(v) {
            p.edit().putString("vendorPackage", v).commit()
        }

    var requireBowl: Boolean
        get() = p.getBoolean("requireBowl", false)
        set(v) {
            p.edit().putBoolean("requireBowl", v).commit()
        }

    /** Un téléphone appairé peut lancer une cuisson manuelle (Réglages › Compagnon). */
    var remoteStart: Boolean
        get() = p.getBoolean("remoteStart", true)
        set(v) {
            p.edit().putBoolean("remoteStart", v).commit()
        }

    /** Capteur du bol lu à l'envers (bases qui signalent « bol » quand il est retiré). */
    var invertBowl: Boolean
        get() = p.getBoolean("invertBowl", false)
        set(v) {
            p.edit().putBoolean("invertBowl", v).commit()
        }

    /** Montée progressive de la vitesse quand le contenu est chaud (≥ 60 °C). */
    var hotRamp: Boolean
        get() = p.getBoolean("hotRamp", true)
        set(v) {
            p.edit().putBoolean("hotRamp", v).commit()
        }

    /** Recettes : tare automatique en arrivant sur une étape de pesée. */
    var autoTareSteps: Boolean
        get() = p.getBoolean("autoTareSteps", true)
        set(v) {
            p.edit().putBoolean("autoTareSteps", v).commit()
        }

    /** Balance remise à zéro quand le bol vide est replacé sur le socle. */
    var bowlAutoZero: Boolean
        get() = p.getBoolean("bowlAutoZero", true)
        set(v) {
            p.edit().putBoolean("bowlAutoZero", v).commit()
        }

    /** Masse du bol vide apprise (g), 0 = inconnue. */
    var emptyBowlG: Int
        get() = p.getInt("emptyBowlG", 0)
        set(v) {
            p.edit().putInt("emptyBowlG", v).commit()
        }

    /** Recettes : enchaîner sans appui les étapes machine qui se suivent sans geste à faire. */
    var chainSteps: Boolean
        get() = p.getBoolean("chainSteps", true)
        set(v) {
            p.edit().putBoolean("chainSteps", v).commit()
        }

    /** Connect only: emulate Smart automatic programs using Connect's temperature table. */
    var emulateSmartPrograms: Boolean
        get() = p.getBoolean("emulateSmartPrograms", false)
        set(v) {
            p.edit().putBoolean("emulateSmartPrograms", v).commit()
        }

    /** Feature names hidden from ProgramsScreen. */
    var hiddenPrograms: Set<String>
        get() = p.getStringSet("hiddenPrograms", emptySet())?.toSet() ?: emptySet()
        set(v) {
            p.edit().putStringSet("hiddenPrograms", v).commit()
        }

    var sound: Boolean
        get() = p.getBoolean("sound", true)
        set(v) {
            p.edit().putBoolean("sound", v).commit()
        }

    /** Mise en veille de l'écran après ce nombre de minutes sans geste (0 : jamais). */
    var standbyMinutes: Int
        get() = p.getInt("standbyMinutes", 10)
        set(v) {
            p.edit().putInt("standbyMinutes", v).commit()
        }

    /**
     * Molette pendant la veille : « normal » (inchangée), « dim » (veilleuse blanche faible) ou
     * « off » (éteinte). Reprend l'ancien interrupteur « standbyKnobLight » s'il a été réglé.
     */
    var standbyKnob: String
        get() = p.getString("standbyKnob", null)
            ?: if (p.getBoolean("standbyKnobLight", true)) "dim" else "normal"
        set(v) {
            p.edit().putString("standbyKnob", v).commit()
        }

    /** Code couleur sur l'anneau de la molette (problème, pause, à vous, en cours). */
    var knobColours: Boolean
        get() = p.getBoolean("knobColours", true)
        set(v) {
            p.edit().putBoolean("knobColours", v).commit()
        }

    /** Fuseau horaire : « auto » (d'après la connexion Internet) ou un identifiant IANA choisi à la main. */
    var timeZone: String
        get() = p.getString("timeZone", "auto") ?: "auto"
        set(v) {
            p.edit().putString("timeZone", v).commit()
        }

    /** Dernier fuseau appliqué avec succès (affiché dans les réglages). */
    var lastZone: String
        get() = p.getString("lastZone", "") ?: ""
        set(v) {
            p.edit().putString("lastZone", v).commit()
        }

    var autoSync: Boolean
        get() = p.getBoolean("autoSync", true)
        set(v) {
            p.edit().putBoolean("autoSync", v).commit()
        }

    var lastSyncMs: Long
        get() = p.getLong("lastSyncMs", 0)
        set(v) {
            p.edit().putLong("lastSyncMs", v).commit()
        }

    /** Résultat de la dernière synchro, écrit dans la langue d'alors (lastSyncLang). */
    var lastSyncResult: String
        get() = p.getString("lastSyncResult", "") ?: ""
        set(v) {
            p.edit().putString("lastSyncResult", v).putString("lastSyncLang", I18n.code).commit()
        }

    val lastSyncLang: String get() = p.getString("lastSyncLang", "") ?: ""

    var vendorSession: Boolean
        get() = p.getBoolean("vendorSession", false)
        set(v) {
            p.edit().putBoolean("vendorSession", v).commit()
        }

    /** Bouton « Retour à Toque » à afficher : Toque a ouvert une autre appli (robot sans bouton Accueil). */
    var returnBubble: Boolean
        get() = p.getBoolean("returnBubble", false)
        set(v) {
            p.edit().putBoolean("returnBubble", v).commit()
        }

    /** Ouvrir Toque au démarrage du robot (désactivé par « Revenir à l'appli d'origine »). */
    var bootLaunch: Boolean
        get() = p.getBoolean("bootLaunch", true)
        set(v) {
            p.edit().putBoolean("bootLaunch", v).commit()
        }

    var knobLeft: Int
        get() = p.getInt("knobLeft", -1)
        set(v) {
            p.edit().putInt("knobLeft", v).commit()
        }

    var knobRight: Int
        get() = p.getInt("knobRight", -1)
        set(v) {
            p.edit().putInt("knobRight", v).commit()
        }

    var knobPress: Int
        get() = p.getInt("knobPress", -1)
        set(v) {
            p.edit().putInt("knobPress", v).commit()
        }

    /** Vrai une fois le bouton rotatif réglé, vu en marche, ou déclaré absent de l'appareil. */
    var knobSetupDone: Boolean
        get() = p.getBoolean("knobSetupDone", false)
        set(v) {
            p.edit().putBoolean("knobSetupDone", v).commit()
        }

    /** Le réglage guidé n'est proposé d'office qu'une fois ; ensuite, l'accueil le rappelle. */
    var knobSetupShown: Boolean
        get() = p.getBoolean("knobSetupShown", false)
        set(v) {
            p.edit().putBoolean("knobSetupShown", v).commit()
        }

    /** Faux tant que rien ne dit que le bouton rotatif fonctionne : l'accueil affiche alors un rappel. */
    val knobReady: Boolean get() = knobSetupDone || knobLeft >= 0 || knobRight >= 0 || knobPress >= 0

    var lastCrashMs: Long
        get() = p.getLong("lastCrashMs", 0)
        set(v) {
            p.edit().putLong("lastCrashMs", v).commit()
        }

    var welcomeDone: Boolean
        get() = p.getBoolean("welcomeDone", false)
        set(v) {
            p.edit().putBoolean("welcomeDone", v).commit()
        }

    var builtinVersion: Int
        get() = p.getInt("builtinVersion", 0)
        set(v) {
            p.edit().putInt("builtinVersion", v).commit()
        }

    /** Recette guidée en cours (id + étape), pour reprendre après un redémarrage. */
    var guidedRecipe: String
        get() = p.getString("guidedRecipe", "") ?: ""
        set(v) {
            p.edit().putString("guidedRecipe", v).commit()
        }

    var guidedStep: Int
        get() = p.getInt("guidedStep", 0)
        set(v) {
            p.edit().putInt("guidedStep", v).commit()
        }

    /** Titre connu de la recette guidée, « id⇥titre » : l'accueil le montre avant le chargement du catalogue. */
    var guidedTitle: String
        get() = p.getString("guidedTitle", "") ?: ""
        set(v) {
            p.edit().putString("guidedTitle", v).apply()
        }

    /** Réglages importés déjà vérifiés (« J'ai vérifié ce réglage »), clé = recette, étape et réglage. */
    var reviewedSteps: Set<String>
        get() = HashSet(p.getStringSet("reviewedSteps", emptySet()) ?: emptySet())
        set(v) {
            p.edit().putStringSet("reviewedSteps", HashSet(v)).commit()
        }

    /**
     * Réglages refusés remplacés d'un appui par le réglage permis le plus proche (« Utiliser … »),
     * clé = recette, étape, réglage de la recette et réglage adopté.
     */
    var adaptedSteps: Set<String>
        get() = HashSet(p.getStringSet("adaptedSteps", emptySet()) ?: emptySet())
        set(v) {
            p.edit().putStringSet("adaptedSteps", HashSet(v)).commit()
        }

    /** Son joué quand la quantité à peser est atteinte (voir Sounds.WEIGH_SOUNDS). */
    var weighSound: String
        get() = p.getString("weighSound", "ding") ?: "ding"
        set(v) {
            p.edit().putString("weighSound", v).commit()
        }
    /** Prévenir quand une nouvelle version de Toque est publiée (au démarrage et au réveil). */
    var updateAlerts: Boolean
        get() = p.getBoolean("updateAlerts", true)
        set(v) {
            p.edit().putBoolean("updateAlerts", v).commit()
        }

    var lastUpdateCheckMs: Long
        get() = p.getLong("lastUpdateCheckMs", 0)
        set(v) {
            p.edit().putLong("lastUpdateCheckMs", v).commit()
        }

    /** Dernière version proposée par l'alerte, et quand (« Plus tard » ne la repropose pas tout de suite). */
    var updateAskedVersion: String
        get() = p.getString("updateAskedVersion", "") ?: ""
        set(v) {
            p.edit().putString("updateAskedVersion", v).commit()
        }

    var updateAskedMs: Long
        get() = p.getLong("updateAskedMs", 0)
        set(v) {
            p.edit().putLong("updateAskedMs", v).commit()
        }

    /** Ajouter les recettes en ligne publiées seulement dans une autre langue. */
    var foreignRecipes: Boolean
        get() = p.getBoolean("foreignRecipes", false)
        set(v) {
            p.edit().putBoolean("foreignRecipes", v).commit()
        }

    /**
     * Langue du catalogue Lidl : « auto » (d'après l'interface ; le catalan → espagnol) ou un code
     * du site (es-ES, fr-FR, …). Indépendant de la langue de l'interface.
     */
    var recipeLanguage: String
        get() = p.getString("recipeLanguage", "auto") ?: "auto"
        set(v) {
            p.edit().putString("recipeLanguage", v).commit()
        }

    // ------------------------------------------------------------------ toqueapp.org

    /** Clé de ce robot sur toqueapp.org (vide : pas relié à un compte). Jamais affichée. */
    var siteToken: String
        get() = p.getString("siteToken", "") ?: ""
        set(v) {
            p.edit().putString("siteToken", v).commit()
        }

    /** Pseudo du compte Toque relié. */
    var siteUser: String
        get() = p.getString("siteUser", "") ?: ""
        set(v) {
            p.edit().putString("siteUser", v).commit()
        }

    val siteLinked: Boolean get() = siteToken.isNotBlank()

    /** Proposition de relier un compte Toque déjà faite (une seule fois, acceptée ou non). */
    var siteOffered: Boolean
        get() = p.getBoolean("siteOffered", false)
        set(v) {
            p.edit().putBoolean("siteOffered", v).commit()
        }

    /** Recevoir les recettes de la communauté (sans compte), avec la synchro. */
    var siteCommunity: Boolean
        get() = p.getBoolean("siteCommunity", true)
        set(v) {
            p.edit().putBoolean("siteCommunity", v).commit()
        }

    /** Date (ISO, celle du site) de la recette la plus récente reçue ; vide : tout redemander. */
    var siteSince: String
        get() = p.getString("siteSince", "") ?: ""
        set(v) {
            p.edit().putString("siteSince", v).commit()
        }

    /** Langue des traductions demandée lors de la dernière synchro (autre langue : tout redemander). */
    var siteLang: String
        get() = p.getString("siteLang", "") ?: ""
        set(v) {
            p.edit().putString("siteLang", v).commit()
        }

    /**
     * Version de ce que l'appli garde des recettes du site : plus petite que SiteAccount.FORMAT, la
     * prochaine synchro redemande toutes les recettes une fois (ex. pour recevoir leurs photos).
     */
    var siteFormat: Int
        get() = p.getInt("siteFormat", 0)
        set(v) {
            p.edit().putInt("siteFormat", v).commit()
        }

    /** Dernière liste complète (recettes retirées du site enlevées), en ms. */
    var siteFullMs: Long
        get() = p.getLong("siteFullMs", 0)
        set(v) {
            p.edit().putLong("siteFullMs", v).commit()
        }

    var siteLastMs: Long
        get() = p.getLong("siteLastMs", 0)
        set(v) {
            p.edit().putLong("siteLastMs", v).commit()
        }

    /** Résultat de la dernière synchro avec toqueapp.org, écrit dans la langue d'alors (siteLastLang). */
    var siteLastResult: String
        get() = p.getString("siteLastResult", "") ?: ""
        set(v) {
            p.edit().putString("siteLastResult", v).putString("siteLastLang", I18n.code).commit()
        }

    val siteLastLang: String get() = p.getString("siteLastLang", "") ?: ""

    /** Favoris changés ici, pas encore envoyés au site : « toqueapp-12=1 », « toqueapp-7=0 ». */
    var sitePendingFavorites: Map<String, Boolean>
        get() = (p.getString("sitePendingFavorites", "") ?: "").split('\n').mapNotNull { line ->
            val k = line.substringBefore('=', "")
            if (k.isEmpty()) null else k to (line.substringAfter('=') == "1")
        }.toMap()
        set(v) {
            p.edit().putString("sitePendingFavorites", v.entries.joinToString("\n") { "${it.key}=${if (it.value) 1 else 0}" }).commit()
        }

    /** Recettes perso mises sur le site : identifiant perso → numéro sur le site. */
    var siteLinks: Map<String, Long>
        get() = (p.getString("siteLinks", "") ?: "").split('\n').mapNotNull { line ->
            val id = line.substringAfter('=', "").toLongOrNull() ?: return@mapNotNull null
            line.substringBefore('=') to id
        }.toMap()
        set(v) {
            p.edit().putString("siteLinks", v.entries.joinToString("\n") { "${it.key}=${it.value}" }).commit()
        }

    /** Recettes perso publiées pour tous (les autres de [siteLinks] sont privées, sur le compte seulement). */
    var sitePublic: Set<String>
        get() = p.getStringSet("sitePublic", emptySet())?.toSet() ?: emptySet()
        set(v) {
            p.edit().putStringSet("sitePublic", HashSet(v)).commit()
        }

    /** Recettes perso pour lesquelles on a répondu « Garder pour moi » : ne plus demander. */
    var siteKeepLocal: Set<String>
        get() = p.getStringSet("siteKeepLocal", emptySet())?.toSet() ?: emptySet()
        set(v) {
            p.edit().putStringSet("siteKeepLocal", HashSet(v)).commit()
        }

    /** Oublie le compte relié (les recettes reçues restent). */
    fun forgetSite() {
        p.edit().remove("siteToken").remove("siteUser").remove("sitePendingFavorites").commit()
    }

    // ------------------------------------------------------------------ Mealie (serveur de recettes auto-hébergé)

    /** Adresse du serveur Mealie relié (« http://mealie.lan:9000 ») ; vide : aucun. */
    var mealieUrl: String
        get() = p.getString("mealieUrl", "") ?: ""
        set(v) {
            p.edit().putString("mealieUrl", v).commit()
        }

    /** Jeton d'API du robot sur ce serveur (jamais affiché ni écrit dans le journal). */
    var mealieToken: String
        get() = p.getString("mealieToken", "") ?: ""
        set(v) {
            p.edit().putString("mealieToken", v).commit()
        }

    /** Numéro du jeton créé par le robot (retiré du serveur en déliant) ; 0 : jeton collé à la main. */
    var mealieTokenId: Long
        get() = p.getLong("mealieTokenId", 0)
        set(v) {
            p.edit().putLong("mealieTokenId", v).commit()
        }

    val mealieLinked: Boolean get() = mealieUrl.isNotBlank() && mealieToken.isNotBlank()

    var mealieLastMs: Long
        get() = p.getLong("mealieLastMs", 0)
        set(v) {
            p.edit().putLong("mealieLastMs", v).commit()
        }

    /** Résultat de la dernière synchro avec Mealie, écrit dans la langue d'alors (mealieLastLang). */
    var mealieLastResult: String
        get() = p.getString("mealieLastResult", "") ?: ""
        set(v) {
            p.edit().putString("mealieLastResult", v).putString("mealieLastLang", I18n.code).commit()
        }

    val mealieLastLang: String get() = p.getString("mealieLastLang", "") ?: ""

    /** Oublie le serveur Mealie relié. */
    fun forgetMealie() {
        p.edit().remove("mealieUrl").remove("mealieToken").remove("mealieTokenId").remove("mealieLastResult").remove("mealieLastMs").commit()
    }
}
