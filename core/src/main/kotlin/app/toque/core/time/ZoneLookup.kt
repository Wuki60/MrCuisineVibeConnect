package app.toque.core.time

import app.toque.core.json.Json

/**
 * Fuseau horaire d'après la connexion Internet : des services publics de géolocalisation
 * de l'adresse IP (sans compte) renvoient le fuseau IANA, par exemple « Europe/Paris ».
 * Le robot n'a ni GPS ni réseau mobile : c'est le seul moyen de régler son heure tout seul.
 */
object ZoneLookup {
    const val AUTO = "auto"

    class Provider(val name: String, val url: String)

    /** Essayés dans l'ordre ; le dernier, en HTTP simple, sert si les certificats posent problème. */
    val PROVIDERS = listOf(
        Provider("ipwho.is", "https://ipwho.is/?fields=success,timezone"),
        Provider("geojs.io", "https://get.geojs.io/v1/ip/geo.json"),
        Provider("ipapi.co", "https://ipapi.co/json/"),
        Provider("ip-api.com", "http://ip-api.com/json/?fields=status,timezone"),
    )

    /** Fuseaux proposés au choix manuel (pays où le robot a été vendu, DOM-TOM compris). */
    val COMMON = listOf(
        "Europe/Paris", "Europe/Brussels", "Europe/Luxembourg", "Europe/Zurich", "Europe/Monaco",
        "Europe/Berlin", "Europe/Vienna", "Europe/Amsterdam", "Europe/Madrid", "Europe/Lisbon",
        "Europe/Rome", "Europe/London", "Europe/Dublin", "Europe/Warsaw", "Europe/Prague",
        "Europe/Budapest", "Europe/Bucharest", "Europe/Athens", "Europe/Helsinki", "Europe/Stockholm",
        "Atlantic/Canary", "Africa/Casablanca", "Africa/Algiers", "Africa/Tunis", "Africa/Dakar",
        "Indian/Reunion", "Indian/Mayotte", "Indian/Mauritius", "America/Guadeloupe", "America/Martinique",
        "America/Cayenne", "America/Miquelon", "America/Montreal", "Pacific/Noumea", "Pacific/Tahiti", "UTC",
    )

    private val ID = Regex("^(UTC|[A-Z][A-Za-z_]+(/[A-Za-z0-9_+\\-]+){1,2})$")

    fun isZoneId(text: String?): Boolean = text != null && ID.matches(text)

    /** Fuseau trouvé dans la réponse d'un des services, sinon null. */
    fun zoneOf(json: String): String? {
        val root = try {
            Json.parse(json)
        } catch (_: Exception) {
            return null
        } as? Map<*, *> ?: return null
        if (root["success"] == false || root["status"] == "fail") return null
        val candidate = when (val tz = root["timezone"] ?: root["time_zone"]) {
            is String -> tz
            is Map<*, *> -> (tz["id"] ?: tz["name"]) as? String
            else -> null
        }?.trim()
        return candidate?.takeIf { isZoneId(it) }
    }
}
