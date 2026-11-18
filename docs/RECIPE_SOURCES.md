# Public JSON recipe sources

Toque can read additional public HTTPS catalogues in its existing `toque-recettes-1` format. This adds recipes and guided settings; it does not download code, start cooking, install updates or change the motor controller.

## On the robot

Open **Settings → JSON recipe sources → Manage personal catalogues**. Add a name and an HTTPS catalogue URL, then tap **Sync**. Each source has its own automatic-sync switch, last successful sync and error message. Names and URLs can be edited without changing the source's identity or losing its favorites.

There are no external sources configured by default. Public catalogues need no account or GitHub token. This first version supports up to 20 sources, 500 recipes and 2 MiB per catalogue. Authentication and downloading custom recipe photos are outside this change. The catalogue host must use a certificate trusted by the device; TLS verification is never bypassed.

Automatic synchronization checks each source at startup and daily while Toque is running, online and idle. Applying a catalogue or removing a source waits until any guided recipe has ended, including paused cooking. Failed downloads and invalid catalogues keep the previous data. ETags avoid importing an unchanged catalogue again.

Removing a source deletes only that source's non-favorite recipes. Favorites remain available offline. Likewise, a favorited recipe withdrawn from its catalogue is retained locally. Turn automatic synchronization off instead of removing a source if you want to keep its configuration for later.

## Example: a GitHub repository

[maximehuran/monsieur-cuisine-recipes](https://github.com/maximehuran/monsieur-cuisine-recipes) is a working public example containing a personal cleaning/prewash recipe and a generated catalogue. Configure this URL:

```text
https://api.github.com/repos/maximehuran/monsieur-cuisine-recipes/contents/catalog.json
```

The client requests raw JSON from GitHub's Contents API, so no personal token is needed. Public GitHub API rate limits still apply. Other HTTPS hosts work with the same catalogue format.

The example's cleaning recipe is a user-authored adaptation, not a manufacturer-certified program. Its first device test should only check import and display; machine safety checks and a supervised physical test remain separate.

## Catalogue format

```json
{
  "format": "toque-recettes-1",
  "recipes": [
    {
      "id": "my-stable-recipe-id",
      "title": "My recipe",
      "ingredients": [
        {"group": "Ingredients", "items": [{"qty": "100", "unit": "g", "name": "ingredient"}]}
      ],
      "steps": [{"text": "A preparation step."}]
    }
  ]
}
```

Use the same recipe ID for updates, and a different ID for a different recipe. IDs are scoped to the configured source: two sources can both publish `my-stable-recipe-id` without overwriting each other or a Lidl recipe. See `RecipeCodec` in `core/.../recipes/Recipe.kt` for ingredient groups, weighing, notes and machine fields. Any machine settings are validated with Toque's existing limits and always require user review before execution, even if the remote file claims otherwise.

The entire catalogue is validated before any changes. Missing or duplicate IDs, unknown machine programs, invalid numeric settings, excessive nesting or an unsupported format reject the whole import. An explicit valid empty `recipes` array removes the source's non-favorite recipes. A 404, invalid JSON or a partial download never acts as an empty catalogue.

## Storage and compatibility

Database version 2 adds `source_id`, defaulting all existing rows to `lidl`; existing IDs, recipe JSON and favorites are preserved. Lidl synchronization and stock-app import replace only this built-in source. JSON sources receive stable opaque source IDs and namespaced recipe IDs, independently of their editable names and URLs. Each successful replacement is one SQLite transaction.

Application update URLs, package version and signing configuration are unchanged. Adding a new catalogue of this format does not require rebuilding Toque. Supporting a different provider protocol in the future would require another importer, with the same source-ownership rules.

## Validation

```sh
python3 tools/test_recipe_storage.py
./gradlew :core:test :app:assembleRelease
```

The SQLite tests execute the actual schema/query constants used by `RecipeRepo`, including migration from version 1, source isolation, rollback and favorite retention. Unit tests cover catalogue validation, stable namespacing, HTTPS redirects, size limits, conditional requests and idle/configuration checks. No network or physical robot is required for these checks.
