package app.toque.core.i18n

import java.util.Locale

/** Langues de l'interface. Le texte source est en français ; l'anglais vient de [En]. */
enum class Lang(val code: String, val locale: Locale) {
    FR("fr", Locale.FRANCE),
    EN("en", Locale.UK);

    companion object {
        fun of(code: String?): Lang = values().firstOrNull { it.code == code } ?: FR
    }
}

object I18n {
    /** Valeur du réglage : langue du système. */
    const val AUTO = "auto"

    @Volatile var lang: Lang = Lang.FR

    val locale: Locale get() = lang.locale

    /** Langue effective d'après le réglage (« auto », « fr », « en ») et la langue du système. */
    fun resolve(setting: String?, systemLanguage: String): Lang = when (setting) {
        Lang.FR.code -> Lang.FR
        Lang.EN.code -> Lang.EN
        else -> if (systemLanguage.lowercase(Locale.ROOT) == "fr") Lang.FR else Lang.EN
    }
}

/** Traduit un texte écrit en français dans la langue de l'interface. */
fun tr(fr: String): String = if (I18n.lang == Lang.EN) En.map[fr] ?: fr else fr

/** Comme [tr], avec des paramètres au format `String.format` (%s, %d…). */
fun tr(fr: String, vararg args: Any?): String = String.format(I18n.locale, tr(fr), *args)

/** Marque un texte à traduire plus tard avec [tr] (sans le traduire tout de suite). */
fun trKey(fr: String): String = fr
