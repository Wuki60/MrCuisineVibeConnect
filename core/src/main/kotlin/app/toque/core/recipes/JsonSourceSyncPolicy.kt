package app.toque.core.recipes

/** Import policy shared by automatic/manual sync and source removal. */
object JsonSourceSyncPolicy {
    fun canChangeCatalog(connected: Boolean, busy: Boolean, guidedRecipe: String): Boolean =
        connected && !busy && guidedRecipe.isBlank()

    fun stillConfigured(requested: JsonRecipeSource, current: JsonRecipeSource?, automatic: Boolean): Boolean =
        current != null && requested.id == current.id && requested.url == current.url && (!automatic || current.autoSync)
}
