package app.toque.core.recipes

/** Shared SQL contract, exercised with SQLite by tools/test_recipe_storage.py. */
object RecipeStorageSchema {
    const val VERSION = 2
    const val LIDL = "lidl"
    const val CREATE_RECIPES = "CREATE TABLE recipes (id TEXT PRIMARY KEY, title TEXT NOT NULL, json TEXT NOT NULL, updated INTEGER NOT NULL, source_id TEXT NOT NULL DEFAULT 'lidl')"
    const val CREATE_FAVORITES = "CREATE TABLE favorites (id TEXT PRIMARY KEY)"
    const val UPGRADE_1_2 = "ALTER TABLE recipes ADD COLUMN source_id TEXT NOT NULL DEFAULT 'lidl'"
    const val SOURCE_INDEX = "CREATE INDEX recipes_source_id ON recipes(source_id)"
    const val SOURCE_IDS = "SELECT id FROM recipes WHERE source_id = ?"
    const val SOURCE_COUNT = "SELECT COUNT(*) FROM recipes WHERE source_id = ?"
    const val DELETE_SOURCE = "DELETE FROM recipes WHERE source_id = ?"
    const val DELETE_RECIPE = "DELETE FROM recipes WHERE id = ? AND source_id = ?"
    const val DELETE_UNFAVORITED_RECIPE = "DELETE FROM recipes WHERE id = ? AND source_id = ? AND id NOT IN (SELECT id FROM favorites)"
    const val DELETE_UNFAVORITED_SOURCE = "DELETE FROM recipes WHERE source_id = ? AND id NOT IN (SELECT id FROM favorites)"
}
