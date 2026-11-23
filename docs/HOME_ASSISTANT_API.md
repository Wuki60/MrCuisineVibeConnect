# Toque local API for Home Assistant (specification, v1)

Status: **implemented** (Settings › Home Assistant, off by default). Decisions taken: HTTP + SSE transport announced
over mDNS, **read-only**, the Home Assistant (HA) integration lives in a **separate repository**
(`toque-homeassistant`, domain `toque`, distributed through HACS).

## 1. Principles

- The server lives in Toque's **main process**, attached to `ToqueApp` (not to the activity: it keeps running when the
  screen changes). The machine service runs in another process and knows neither the recipes, nor the cooking session,
  nor the preferences; the server reads the machine state through `MachineClient`, like the user interface does.
- No dependency: `ServerSocket` + the in-house JSON code (`core/json`). One listener thread, at most 8 simultaneous
  connections (`503` beyond), one response per connection (`Connection: close`), except the SSE stream.
- The code is in `core` (routing, JSON, cadence, server: testable without Android); only the glue with the app
  (recipes, preferences, mDNS) is in `app`.
- **Read-only**: every method other than `GET` returns `405`. No endpoint touches the motor, the heater or the
  session. The rules in `Safety.kt` are therefore not involved.
- Off by default. Settings › Home Assistant: switch, port, token.
- Integer API version (`api`). **Additive** changes only within a version; an unknown field is ignored.

## 2. Discovery and access

- mDNS service `_toque._tcp`, default port **8765**. TXT records: `api=1`, `id=<stable id>`,
  `version=<Toque version>`, `name=<robot name>`.
- Base: `http://<ip>:8765/api/v1`.
- `GET /info` is public (used for discovery and to validate the address). Everything else requires
  `Authorization: Bearer <token>`.
- Token: 32 random bytes generated on activation, shown on the robot's screen, regenerable. It is entered once in the
  HA config flow.
- Plain HTTP on the local network: an accepted limit, like ADB on 5555. The token does not protect against someone
  listening to the network, only against accidental access. To be documented.
- Errors: JSON `{"error":"<code>","message":"…"}` with `401`, `404`, `405`, `503` (machine or base not ready).

## 3. Endpoints

### `GET /info`
```json
{
  "api": 1, "id": "toque-3f9a…", "name": "Monsieur Cuisine",
  "model": "SKMC 1200",
  "toque": {"version": "0.5.5", "build": 55},
  "board": {"firmware": 0},
  "android": {"release": "6.0", "sdk": 23},
  "ip": "192.168.1.40", "uptimeS": 86400,
  "lang": "fr", "recipeCount": 312, "recipesRev": 7
}
```

### `GET /state`
Full snapshot. `status` is computed on the robot:
`fault` (active fault) › `recipe` (guided session active) › `running` (job or Turbo active, paused included) › `standby`.
There is no "unavailable" state on the robot: HA deduces it when the robot stops answering.

**Freshness.** `snapshotAgeMs` is the age (ms) of the last snapshot received from the motor base (`-1`: none received).
The machine service emits at least one per second; after 3 s of silence the application sets `link` to `OFFLINE` and
publishes a `state` immediately, then keeps the heartbeat going (5 s during a job, 30 s otherwise, with a growing
`snapshotAgeMs`). HA must therefore treat the state as unusable if `link` is `OFFLINE` or `snapshotAgeMs` exceeds 5 s,
and the robot as unavailable if no `state` is received within twice the heartbeat.
```json
{
  "ts": 1763700000, "snapshotAgeMs": 120, "status": "recipe", "screenOn": true,
  "link": "READY", "fault": null,
  "machine": {
    "tempC": 82, "targetTempC": 90, "rpm": 1200, "speed": 4, "targetSpeed": 4, "weightG": 350,
    "scaleStable": true, "lidOpen": false, "bowlDetected": true, "motorOn": true, "heaterOn": true
  },
  "job": {
    "kind": "MANUAL", "label": "Étape 3", "state": "RUNNING", "pauseReason": "",
    "phase": 1, "phaseCount": 1, "elapsedS": 40, "totalS": 300, "remainingS": 260,
    "speed": 4, "reverse": false, "accessory": "BLADE"
  },
  "sessionId": "c1d2…"
}
```
`job` is `null` when no job is running, **including once the job has finished or been stopped** (the robot keeps its end
screen displayed until it is closed, which the API does not show): the end is announced once by the `job_done` event
(§3, `/events`). A job already finished when the application starts produces no event.
`fault` is `{"code":"…","message":"…"}` when there is a fault; `sessionId` is `null` outside a recipe. `screenOn` is
`false` when the screen is off or Toque is in standby ("standby" for the user = `status: standby` and
`screenOn: false`; see §7).

**Quirks of the SKMC 1200 base** (observed on a real robot, firmware 4922):
- `targetTempC` is the temperature that **Toque asks for** for the current job (the step's or the setting's), 0 without
  heating or without a job. It is not the base's echo: the base adjusts its target during the rise (observed: step at
  95 °C, target returned 103 then 97; at 45 °C, 51; at 65 °C, 72) and keeps the last one after stopping or during a step
  without heating (51 °C stayed during a 0 °C step).
- `heaterOn` is `true` only if the base announces heating **and** the job asks for a temperature (`targetTempC > 0`).
  The base answers "motor + heater" to every standard program, even without heating requested (command
  `STANDARD v3 t0`, cold bowl bottom).
- `tempC` has a low floor: 0 as long as the contents are under 37 °C, then the measurement follows (observed: 37 → 55 °C
  in 1 min 30 with 600 g of water, at 65 °C requested).
- `weightG` is `null` while the motor runs: the base then returns 0, and the value comes back at standstill.
- `rpm` (measured) and `speed` (level deduced from the measured speed) stay at 0 while running, and so does `tempC`
  without heating (observed, blades checked to be turning): the base does not return these measurements in this mode.
  The behaviour of `tempC` during a real heating phase has not been recorded yet. Use `targetSpeed`, the speed level the
  base says it received (0 at standstill), to know how fast the motor is turning.

### `GET /diagnostics`
For development, not for Home Assistant: what the base returns **raw** (`base`: `tempC`, `targetTempC`, `rpm`, `speed`,
`targetSpeed`, `weightG`, `motorOn`, `heaterOn`, `rxHex` frame…), what Toque sends it (`sent`: operation, speed,
temperature level, `txHex`), the job (`job`) and the frame counters. Nothing is corrected there: `/state` gives the
interpreted reading (heating, weight, target). Used to compare two versions of the robot or of the base. Its content may
change without changing the API version; token required, read-only like the rest.

`server` describes the server's connections (at most 8; beyond that, 503 `too_many_connections`): `open` and `max`,
`connections` (remote address, age in ms, requested path, `stream` for the event stream), and `refused`: the number of
refusals since start-up, the age of the last one (`lastAgoMs`) and the list of connections that held the slots at that
moment. When the server is full nothing answers: `refused` can be read once a slot is free again.

A single client (IP address) keeps at most 3 `/events` streams: when it opens one more, the robot closes the oldest.
A client that reconnects without closing its previous stream therefore cannot fill up the server.

### `GET /session`
`{"active": false}` outside a recipe. Otherwise:
```json
{
  "active": true, "id": "c1d2…", "rev": 12,
  "recipe": {"id": "veloute-de-potiron", "title": "Velouté de potiron", "category": "Soupe", "servings": 4},
  "stepIndex": 2, "stepCount": 8,
  "awaiting": "user",
  "step": {"index": 2, "kind": "manual", "text": "Ajouter la crème.", "machine": null,
           "weigh": {"label": "Crème", "grams": 200}},
  "lastManual": {"index": 0, "text": "Éplucher et couper le potiron."},
  "nextManual": {"index": 5, "text": "Verser dans un bol."},
  "ingredients": [{"group": "", "items": [{"qty": "800", "unit": "g", "name": "potiron", "note": ""}]}],
  "startedAt": 1763699000
}
```
- A **manual** step is a step without a machine setting (`Step.machine == null`): adding, weighing, a gesture. A step with
  a setting is `kind: "machine"` (`machine` contains `program`, `seconds`, `speed`, `tempC`, `reverse`, `accessory`,
  `pulses`).
- `lastManual`: the largest manual index **strictly lower** than `stepIndex`. `nextManual`: the smallest manual index
  **strictly higher**. Both are `null` if there is none. If the current step is manual, it is `step`.
- `awaiting`: `"user"` (a human action is awaited), `"machine"` (setting ready to start or running), `null`.
- `rev` increases on every change of the session; `ingredients` uses the shape of `RecipeCodec` (quantities possibly
  adapted by the user).

### `GET /recipes`
Parameters: `q` (search, same normalisation as the app), `category`, `favorite=1`, `limit`, `offset`.
Returns summaries and `rev` (changes when the catalogue changes, usable as an `ETag`).
```json
{"rev": 7, "total": 312, "recipes": [
  {"id": "veloute-de-potiron", "title": "Velouté de potiron", "category": "Soupe", "tags": ["automne"],
   "servings": 4, "prepMinutes": 15, "totalMinutes": 40, "difficulty": 1,
   "rating": 4.5, "ratingCount": 120, "favorite": false, "image": "", "source": "imported"}
]}
```

### `GET /recipes/{id}`
`RecipeCodec.toMap` format (grouped ingredients, steps with `machine` and `weigh`, `notes`, `author`), plus
`machineSteps`, `ingredientCount` and `imageUrl` (see §8). `404` if unknown. `difficulty`: 1 easy, 2 medium, 3 hard.
"Type" (drink, dessert…) = `category` + `tags`.

### `GET /events` (SSE)
`text/event-stream` stream, a `:` keep-alive comment every 15 s, increasing `id:`, resume with `Last-Event-ID` (replay of
at most the last 50 events). On connection the stream first sends the current state (`state` then `session`, without
`id:`), so that the client does not need to query the API as well.

The `id:` values restart at 1 when the robot's application restarts (update, stop). A `Last-Event-ID` greater than the
last identifier emitted therefore predates that restart: the robot ignores it (no replay) and sends all the following
events. The client has nothing more to do.

| `event:` | When | `data:` |
|---|---|---|
| `state` | change of `status`, job or fault; otherwise reduced cadence (see §4) | same shape as `/state` |
| `session` | start, end, change of step or of `awaiting` | same shape as `/session` |
| `step` | move to a new step | `{"sessionId","index","kind","text"}` |
| `done` | end of recipe or abandon | `{"sessionId","recipeId","result":"finished|aborted"}` |
| `job_done` | a running machine job ends or is stopped (once) | `{"kind","label","result":"finished|aborted","reason","totalS"}` |
| `fault` | fault raised or cleared | `{"code","message","active"}` |
| `recipes` | catalogue modified (sync, import) | `{"rev":8,"total":315}` |

## 4. Cadence (protecting HA's database)

The internal `Snapshot` is produced at ~10 Hz; the API does not expose that detail.
- Temperature: only if it moves by ≥ 1 °C; rpm: change ≥ 50; weight: change ≥ 2 g. At most one `state` per second for
  these variations.
- Weight at rest (no job and no recipe in progress): the scale drifts by a few grams, so the change must be ≥ 5 g and
  there is at most one `state` every 10 s for weight alone. During a job or a recipe the threshold stays at 2 g.
- Heartbeat: a `state` at least every 5 s during a job (the remaining time moves), every 30 s otherwise.
- Changes of `status`, lid, bowl, motor, heater, fault, job, phase, `targetSpeed`, `targetTempC`, session and screen go out immediately (the base confirms a new speed about 3 s after a step change, so the speed of a chained step arrives in its own `state`).

## 5. Internal model in `core`

`CookSession` / `CookSessions` (pure Kotlin: recipe, `stepIndex`, finished steps, `awaiting`, `startedAt`, `rev`), driven
by `CookScreen` and saved in `prefs.cookSession` by `ToqueApp`. Routing, JSON formats, cadence thresholds and the server
(local sockets) are tested in `core`.

## 6. Home Assistant entities (`toque-homeassistant` repository)

One device per robot (`id`); `sw_version` = Toque version, `model` = SKMC 1200, `hw_version` = board firmware.
Config flow: `zeroconf` discovery (`_toque._tcp`) then the token. Updates: SSE, with a fallback poll of `/state` every
60 s; unavailable when there is no more answer.

| Entity | Source |
|---|---|
| `sensor` State (enum: `standby`, `running`, `recipe`, `fault`) | `status` |
| `sensor` Temperature, Target, Speed (rpm), Weight, Remaining time | `machine`, `job` |
| `binary_sensor` Lid open, Bowl present, Motor, Heater, Fault | `machine`, `fault` |
| `sensor` Current recipe (title), Step (`n/N`) | `session` |
| `sensor` Current step, Last manual step, Next manual step | `session`; **state = 255 characters at most, full text as an attribute** |
| `ingredients` attribute of the current recipe | `session.ingredients` (to exclude from the recorder) |
| `event` Cooking (`step`, `action_requise`, `done`, `job_done`, `fault`) | `/events` |
| `sensor` Recipe count (diagnostic) | `recipeCount` |
| Services with a response `toque.list_recipes`, `toque.get_recipe` | `/recipes`, `/recipes/{id}` |
| `todo` action: add a recipe's ingredients to a shopping list | `/recipes/{id}` |

Diagnostics (firmware, Android version, uptime, IP address): `diagnostic` category, disabled by default.

## 7. Decisions

- **`screenOn`**: field of `/state`, false when the Android screen is off **or** Toque is in standby (black screen with
  the clock, backlight at minimum: Android itself stays awake, so `PowerManager.isInteractive` alone never sees this
  standby — observed on the robot). "Standby" for the user = `status: standby` and `screenOn: false`. A `state` event is
  sent each time standby is entered or left.
- **Images**: served by the robot, `GET /recipes/{id}/image`. See §8.
- **Editable name**: Settings › Home Assistant › Robot name (default: "Monsieur Cuisine"). It appears in `/info.name`,
  in the mDNS TXT `name` and as the HA device name. The `id` stays stable and serves as the HA unique identifier:
  renaming the robot does not create a second device.
- **Session identifier kept**: `CookSession.id` is saved with the session (where `guidedRecipe` / `guidedStep` used to
  be). After an application or robot restart, the resumed session keeps its `id` and its `rev` keeps growing. A new `id`
  only appears for a new recipe being started.

## 8. Images

- `recipe.image` (original URL) stays in the JSON; in addition, every summary and detail carries
  `"imageUrl": "/api/v1/recipes/{id}/image"` when the file is already cached, otherwise `null`.
- The endpoint returns **the cached file as is** (`OnlineSync` has already resized and recompressed it): no decoding or
  resizing on the API side, hence a CPU cost close to zero (one file read).
- `ETag` (file size + date) and `Cache-Control: max-age=86400`: HA does not download an unchanged image again.
- Safeguards: only one image sent at a time, reading in 16 KiB blocks, `404` if not cached (a download is never
  triggered by the API), and no image during an active job (`503` with `Retry-After: 5`) so as not to disturb the
  communication loop with the board. The HA integration asks for its images at start-up and on the `recipes` event, not
  continuously.
- To measure during implementation: how long a transfer takes on the MT6580 during a job. If the machine loop suffers,
  the "no image during a job" rule remains the protection.

## 9. Still open

- A recipe started from HA does not exist (read-only): nothing to decide.
- Retention of cached images: no known limit today, to be checked before announcing `imageUrl` for the whole catalogue.
