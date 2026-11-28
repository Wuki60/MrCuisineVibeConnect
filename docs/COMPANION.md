# Toque Compagnon: robot-side contract

Toque Compagnon lets a phone on the home Wi-Fi follow what the robot does and act on it within fixed
limits. The phone runs a native Android app (its own repository,
[Toque Compagnon](https://github.com/Wuki60/ToqueCompagnon), package `app.toque.companion`) that
talks to a small HTTP server inside Toque, the robot's app. This document is the contract between the
two. It is kept in sync with the code in `core/src/main/kotlin/app/toque/core/companion/`; when they
disagree, the code is right and this file has a bug.

Protocol version: **1** (`COMPANION_PROTOCOL`).

## 1. Architecture

```
 phone (companion app)                         robot (Toque, UI process)
 ┌──────────────────────────┐   HTTP/1.1, LAN  ┌───────────────────────────────────────────┐
 │ CompanionClient          │ ───────────────► │ HttpServer (ServerSocket, bounded pool)   │
 │ StateFollower (long-poll)│ ◄─────────────── │   └ CompanionApi (routes, auth)           │
 │ RobotFinder (discovery)  │   JSON           │       └ CompanionHub (state, policy,      │
 │ wire data classes        │                  │           journal, revision, waiting)     │
 └──────────────────────────┘                  │             └ CompanionHost (interface)   │
         same classes from core                │                 implemented by the app:    │
                                               │                 MachineClient, RecipeRepo… │
                                               └───────────────────────────────────────────┘
```

- **Everything is in `core`** (pure Kotlin, Android API 23 safe, no dependency). The robot app and the
  phone app both depend on it; every JSON structure is a data class in `Wire.kt` with `toMap()` /
  `fromMap()`, written with `app.toque.core.json.Json`. Decoding is lenient: a missing field takes its
  default, an unknown field is ignored, so an older phone can read a newer robot.
- **Robot side.** `CompanionHub` lives in the UI process. The app *pushes* to it:
  `updateMachine(Snapshot)` on every snapshot (about 10 per second), `onMachineEvent(MachineEvent)`,
  `updateSession(CookSession)` / `endSession(completed)` from the cooking screen, `updateRobot(RobotInfo)`.
  These are cheap, thread-safe and never block. The server threads *pull* from the hub and send the
  allowed commands to the app through `CompanionHost`.
- **`CompanionHost`** (the app implements it) has exactly: `pause`, `resume(stillAllowed)`, `stop`,
  `adjust(prepare)`, `tare`, `start(prepare)`, `clear`, `catalog`, `recipe`, `image`, the servings methods (`baseRecipe`,
  `baseServings`, `setServings`), `setFavorite` and `stopRecipe` (with the flags `setsFavorites` and `stopsRecipes`), and
  `storedRecipe` / `writeRecipe` (flag `writesRecipes`, recipes written on the phone, see §3). It is
  called on a server thread and must return within `CompanionHub.HOST_TIMEOUT_MS` (5 s).
  - **Only the commands** (`pause`, `resume`, `stop`, `adjust`, `tare`, `start`, `clear`, `setServings`, `setFavorite`, `stopRecipe`) hop to the Android main
    thread, with `HostCalls.command(post, timeout) { done -> machine.pause { v -> done(CommandResult.of(v)) } }`
    (`post` = `Handler(Looper.getMainLooper())::post`). A block that has not started when the timeout
    fires is **skipped, never run later**: `command` then returns `CommandResult.timeout()` (nothing
    done). A block that started but whose answer did not come gives `CommandResult.noAnswer()` (it may
    have been applied). `HostCalls.call`/`await` have the same skipping rule and return `null`.
  - **`catalog`, `recipe`, `image` run on the server thread, off the main thread** (database and file
    reads); never post them to the main thread.
  - `resume` must call `stillAllowed()` **on the main thread, right before**
    `MachineClient.resume(hot = false)` and refuse if it returns false.
  - `adjust` must call `prepare()` **on the main thread, right before acting**. It returns an
    `AdjustCommand`: if `refusal != null`, return it and do nothing; otherwise call
    `MachineClient.adjust(c.speed, temp = c.temperatureLevel, reverse = c.reverse, totalMs = c.totalMs, hot = false)`.
    `prepare` re-checks the rule against the freshest state, refuses if the job changed since the
    request (`changed`), and computes an added duration (`addMs`) from the latest total, counting a
    total already sent but not yet seen in a snapshot, so two "+1 min" make two minutes.
  - `start` is the only method that starts anything: a **manual cooking**, nothing else (no recipe,
    step, program, Turbo or hot-blend confirmation). It must call `prepare()` **on the main thread,
    right before acting**. It returns a `StartCommand`: if `refusal != null`, return it and do
    nothing; otherwise call `MachineClient.start(c.spec)` and answer `CommandResult.of(verdict)`: any
    violation from the machine (hot blend included) is a refusal and nothing starts; never retry with
    a confirmation. `c.phoneName` names the phone (the robot shows « Cuisson lancée depuis … »).
    `prepare` re-checks the whole start rule (§6) against the freshest state. Its default
    implementation refuses (`unsupported`).
  - `setFavorite(id, on)` does what the star of the robot's recipe page does (`RecipeRepo.setFavorite`), on the main
    thread; `not_found` for an unknown recipe. `setsFavorites` true says the host can (`state.can.favorite`). Defaults:
    false, and a refusal (`unsupported`).
  - `stopRecipe()` does what « Arrêter la recette » of the guided-cooking screen does, without its dialog (the phone
    asked first), on the main thread: the program the recipe started stops, the session ends as not completed
    (`endSession(false)`), the robot's « Reprendre » card goes and the screen closes. `no_recipe` when no guided recipe
    is open. `stopsRecipes` true says the host can (`state.can.stopRecipe`). Defaults: false, and `unsupported`.
  - `setAppearance(accent, theme)` applies a colour (one of `RobotLooks.ACCENTS`, or, when `setsAnyAccent` is true, any
    colour the wheel's guard accepts: see `POST /api/robot/appearance`) and/or a theme (`dark`, `light`;
    null: unchanged) the way the robot's Settings › Screen does, on the main thread, but stays on Home (the
    Settings are not opened). `busy` when rebuilding the interface would cost something (section 6 and `POST /api/robot/appearance`).
    `setsAppearance` true says the host can (`state.can.setAppearance`). Defaults: false, and `unsupported`.
    `setsAnyAccent` true says it takes any guarded colour, not only the ten (`robot.anyAccent`); default false, and the
    hub then answers `400 bad_request` « Couleur inconnue » to a colour outside the ten without calling the host.
  - `storedRecipe(id)` reads a recipe as stored (not adjusted, not corrected) on the server thread.
    `writeRecipe(id, decide)` runs on the main thread in one block: read the stored recipe (null if absent), say
    whether the robot's recipe editor has it open (`RecipeEditScreen.isEditing(id)`), call `decide(stored, inEditor)`,
    and when the answer carries a recipe (`RecipeWrite.saved`) save it as the robot's editor does
    (`RecipeRepo.saveUser`, the chosen number of people reset to the recipe's). The hub's `decide` checks everything
    again there (session open on that recipe, editor open, version, the editor's limits). A block that could not
    start in time is never run (`504 timeout`). `writesRecipes` true says the host can (`state.can.editRecipes`).
    Defaults: false, and a refusal (`unsupported`).
    `writesMachineSteps` true (with `writesRecipes`) says the host also takes a step's machine setting typed on the phone
    (lot B: `settings` in the editor's replies). The host then promises, in the same `writeRecipe` block that saves,
    to forget for every rank of `RecipeWrite.forget` (a setting typed or changed, `typed`; a step with a setting that
    came from another rank, `moved`) any remembered confirmation or adoption that would make it pass as already
    checked (the robot: `CookScreen.forgetConfirmations`, see « Own recipes written on the phone »), and never to
    chain into the previous step a setting typed on a phone and not yet checked (`Step.reviewAlone`).
    `RecipeWrite.phone` is the phone's name, for the host's log. Default false: a `machine` member is `400`, as before.
  - `takesOwnSteps` true says the host takes the owner's setting for a step of a received recipe (Mealie) typed on a
    phone (`state.can.ownSettings`, `RecipeDetail.ownSettings`; see « The owner's setting for a step of a Mealie
    recipe »). `ownSteps(id)` reads, on the server thread, the recipe as received (`OwnStepsRead.source`, the one the
    step keys come from), as shown (`shown`: owner's settings, corrections and number of people), and the owner's
    settings by key (`own`); null when unknown or not cookable by the robot chosen. `writeOwnStep(id, decide)` runs on
    the main thread in one block: read (`ownSteps`), call `decide(read)`, and when the answer is a change
    (`OwnStepWrite.changed`) store it as the robot's recipe page does, as typed on a phone (`clear`:
    `RecipeRepo.clearStepSetting`; else `RecipeRepo.setStepSetting(id, index, machine, SettingAuthor.PHONE)`, which
    checks again and may still refuse: `OwnStepWrite.failed`), forget every remembered confirmation or adoption at
    that rank (`CookScreen.forgetConfirmations`), then answer with the recipe read again (`OwnStepWrite.done`).
    `OwnStepWrite.phone` is the phone's name, for the host's log. Defaults: false, null, and `409 unsupported`.
  - `clear` does what the robot's **OK** button does: `MachineClient.clearFinished()`, which removes
    a finished or stopped job from the display and nothing else (the controller ignores a job that has
    not ended). Its default implementation refuses (`unsupported`).
  - **Start switch.** `hub.setRemoteStart(on)` gives the robot setting « Lancement depuis le
    téléphone » (Réglages › Téléphone, on by default, stored in `Prefs.remoteStart`); the hub starts
    with it **off** until the app gives it. A change bumps the revision.
- **Revision.** The hub keeps a revision number that grows on every visible change: job state, pause,
  lid, bowl, link, fault, settings, session, waiting recipe, journal line, robot info. Temperature,
  weight and scale stability bump it at most every 300 ms. The remaining time does **not** bump it
  while it counts down normally (the phone extrapolates); it bumps when the real value differs from
  the extrapolation by more than 1.5 s. The phone long-polls `GET /api/state?since=<revision>`.
- **Server life.** The app calls `hub.start(port)` / `hub.stop()`. `hub.wanted` is true while at least
  one phone is paired or the pairing screen is open: listen only then. Default port **8088**.
- **Listeners for the robot UI** (called on a server thread; hop to the main thread):
  `hub.onWaitingChanged`, `hub.onPhonesChanged` (add/remove, not "last seen"), `hub.onPairingChanged`
  (code opened, replaced, consumed, killed by wrong attempts, closed). Each returns an unsubscribe
  lambda. Code expiry fires nothing: the pairing screen runs its own timer from
  `PairingOffer.expiresMono` / `expiresAtWall`. `hub.pairing.status` tells the screen why there is no
  usable code: `CLOSED`, `OPEN`, `EXPIRED` (2 minutes passed), `LOCKED` (5 wrong attempts), `USED`
  (a phone just paired).
- **Robot UI calls:** `hub.pairing.open()` (screen opened, or "Nouveau code"), `hub.pairing.close()`,
  `hub.pairing.current`, `hub.pairingPayload(ipv4)` (QR text), `hub.pairing.list` / `remove(id)`
  (paired phones list), `hub.waitingRecipe`, `hub.clearWaiting(WaitingEnd.LAUNCHED | REMOVED)`
  ("Cuisiner" or "Retirer" on the robot).
- **Storage.** `CompanionStore { load(): String?; save(text) }` keeps one JSON document: the robot id
  and the phones (id, number, SHA-256 of the token, dates, method). The robot app keeps it in a file of
  its own (`files/compagnon/telephones.json`, UI process only). "Last seen" is written at most every 10
  minutes and on `stop()`. The waiting recipe is kept by the app too (`WaitingCodec`,
  `files/compagnon/attente.json`) and put back with `hub.restoreWaiting(w)` after a restart of the UI
  process; the journal and the revision are in memory only.

### Threading on the phone

Every `CompanionClient` call blocks: never on the main thread. `StateFollower` and `RobotFinder` run
their own daemon threads and call back on them.

## 2. Finding the robot and pairing

### 2.1 QR code (`PairingPayload`)

The robot shows a QR code whose text is:

```
toque://pair?v=1&h=192.168.0.12&p=8088&s=0123456789abcdef0123456789abcdef&r=a1b2c3d4e5f6
```

| key | meaning | format |
|---|---|---|
| `v` | protocol version | integer ≥ 1 |
| `h` | robot IPv4 address on the LAN | dotted quad |
| `p` | port | 1–65535 |
| `s` | one-time pairing secret, 128 bits | 32 lowercase hex |
| `r` | stable robot id | 12 lowercase hex |
| `n` | robot name, ≤ 32 characters, optional | `%XX` UTF-8 (RFC 3986 unreserved kept) |

The robot leaves `n` out (the phone gets the name in the pairing reply): about 90 bytes (at most 94),
encoded at error-correction level L (a code on a screen is never damaged), which gives a version 5
code, 37 modules plus the 4-module quiet zone. The robot draws it with whole-pixel modules, as large as
its card allows (6 px per module, 222 px on the 1024 × 600 screen). `parse()` still accepts `n`.
`format()` refuses more than 200, `parse()` refuses more than 200 and returns `null`
for anything that is not a valid Toque pairing text. The phone scans it with its camera; the secret
travels only inside the LAN request that consumes it.

### 2.2 Pairing rules

- The robot opens pairing (`hub.pairing.open()`): a 6-digit code and a secret, both valid **2 minutes**.
  "Nouveau code" calls `open()` again and replaces both.
- **5 wrong attempts** (code or secret) kill the current code (`code_dead`) until a new one is opened.
- A successful pairing **consumes** the code and the secret and closes pairing; open it again for
  another phone.
- Each phone gets its own random **256-bit token** (64 lowercase hex), sent once in the pairing reply.
  The robot keeps only its SHA-256; comparison is constant-time. At most **8** phones.
- Phones are named "Téléphone N" (lowest free N, translated) and listed on the robot with pairing date
  and last seen. The owner can give one another name on the robot (`hub.pairing.rename(id, label)`: one
  line, at most 24 characters, empty to go back to "Téléphone N"); that name is the one the journal, the
  robot's messages and `PhoneWire.name` then carry, with no change to the protocol. A phone can also
  name itself (`POST /api/phones/me`, same cleaning, when `state.can.renamePhone` says so); either
  way the name given is also sent alone as `PhoneWire.label`. The robot removes a
  phone with `hub.pairing.remove(id)`; a phone removes itself with
  `DELETE /api/phones/me` ("Oublier"). A removed phone gets `401` on every call.
- Authentication: `Authorization: Bearer <token>` on every call except `/api/hello`, `/api/pair`
  and `/api/prove`. No cookies, no CORS headers, ever: a web page from another site can neither send
  this header nor read a response.
- **The token only goes to a proven robot** (section 2.6): before the first authenticated request to an
  address, and again after any network error, the client runs the proof exchange; if it fails, the
  token is not sent (`RobotProofException`). A long-poll the phone cuts itself (the app comes back to
  the screen) counts as a network error too: the cut request may date from before a long sleep, or
  from another Wi-Fi where that address belongs to another device.

### 2.3 Flow with the QR code

1. Phone parses the text: `PairingPayload.parse(text)` (or `client.pairWithQr(text)`).
2. `POST http://<h>:<p>/api/pair` with `{"secret":"<s>"}`.
3. Reply: token, phone, robot. The client checks `robot.id == r` and refuses otherwise.
4. Phone stores host, port, token, phone id, robot id (`client.phoneId`, `client.robotId` are set by
   the pairing calls).

### 2.4 Flow with the typed code

The phone does not know the address. It scans the LAN:

1. Candidates: `RobotFinder.subnet(ownIpv4)` (the /24 around the phone's address).
2. `RobotFinder.scan(candidates, 8088)` probes `GET /api/hello` in parallel (32 at a time, 800 ms each)
   and returns the hosts that answer `"service":"toque-companion"`; keep those with `pairing: true`.
3. `POST /api/pair` with `{"code":"482913"}` to that host (`client.pairWithCode`). The phone app tries a
   typed code on **one** host only; if several robots have pairing open, it asks the person to choose
   (or to use the QR code).

### 2.5 Finding the robot again (address changed)

The phone keeps the robot id. When the robot stops answering, `RobotFinder.locate(candidates, port,
token, phoneId, robotId)` runs the proof exchange (2.6) against every candidate in parallel (short
timeouts, first valid proof wins) and returns that host. **The token is sent to none of them.** Put
the last known address first. `StateFollower(..., finder = { RobotFinder.locate(...) })` does this by
itself after 3 failures in a row, then every 4 failures.

### 2.5 bis Discovery on the Wi-Fi (mDNS, Toque 0.11.0 or later)

While the server is open (a phone paired, or a pairing in progress), the robot announces it with
DNS-SD: service type `_toque._tcp`, name `Toque <robot name>`, the server port, and TXT records
`id` (the robot id, as in the QR code), `v` (`COMPANION_PROTOCOL`) and `model` (robot profile id,
`mc-connect`, `mc-smart`). Nothing secret: the same as the QR code minus the pairing secret. The
announce is withdrawn when the server closes. Pairing still needs the code or the QR code; a client
may use the announce to fill in the address, or to find the robot again after its address changed
(then confirm with the proof exchange, 2.6).

### 2.6 Proof exchange (`POST /api/prove`, no token)

Before sending its token to an address, the phone checks that the address is its robot:

1. Phone sends `{"phoneId":"1eeddd36","nonce":"<32 lowercase hex, fresh random>"}`.
2. Robot answers `{"proof":"<64 hex>"}` with
   `proof = hex(HMAC-SHA256(key = UTF-8 bytes of sha256Hex(token), message = "toque-prove-1|" + nonce + "|" + robotId + "|" + localAddress))`,
   where `sha256Hex(token)` is the lowercase hex SHA-256 the robot stores, and `localAddress` is the
   robot's IPv4 address on which the connection arrived (dotted quad).
3. Phone computes the same with its token, its robot id and **the address it dialled**
   (`CompanionProof.check`) and compares in constant time.

The robot does not demand the exchange (curl works without it): it is a rule of the phone's client,
which never sends its token otherwise. An unknown phone id gets an answer of the same shape and status, stable for that id, but wrong (keyed
with a random per-start secret), so the endpoint reveals neither the robot id nor which phones exist.
Dial IPv4 literals: the check uses the dialled address as text. A failed proof means another device
answers at that address, or that the robot removed this phone; the client cannot tell which, and
`StateFollower` reports it through `onUnreachable(RobotProofException)` (it would otherwise get `401`
and call `onUnauthorized`, when the address is already proven).

## 3. Endpoints

Base: `http://<robot>:8088`. All bodies are JSON (`Content-Type: application/json`), UTF-8. Every
response has `Connection: close`, `Cache-Control: no-store` (images: `private, max-age=3600`),
`X-Content-Type-Options: nosniff`.

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/api/hello` | no | is there a Toque companion here, is pairing open |
| POST | `/api/pair` | no | pair with `code` or `secret` |
| POST | `/api/prove` | no | proof that this robot knows a phone (2.6) |
| GET | `/api/robot` | yes | robot id and this phone (discovery check) |
| GET | `/api/state` | yes | full state; `?since=N&wait=S` long-poll |
| GET | `/api/journal` | yes | activity journal; `?after=SEQ&limit=N` |
| GET | `/api/recipes` | yes | catalogue; `?q=&filter=&category=&offset=&limit=` |
| GET | `/api/recipes/{id}` | yes | recipe detail; `?base=1` at its own number of people |
| GET | `/api/recipes/{id}/image` | yes | recipe image bytes |
| POST | `/api/recipes/{id}/servings` | yes | number of people of a recipe, saved on the robot |
| POST | `/api/recipes/{id}/favorite` | yes | favourite of a recipe, saved on the robot |
| POST | `/api/recipes` | yes | a new own recipe written on the phone; the robot names it |
| GET | `/api/recipes/{id}/edit` | yes | an own recipe as stored, with its version, for the phone's editor |
| POST | `/api/recipes/{id}/edit` | yes | save an own recipe written on the phone |
| POST | `/api/recipes/{id}/copy` | yes | « Ma version » of a Lidl recipe: its own copy, saved in « Mes recettes » |
| GET | `/api/editor` | yes | the editor's bounds for a new recipe, and the step settings it offers (lot B) |
| GET | `/api/recipes/{id}/steps` | yes | the steps of a Mealie recipe, with their keys and owner's settings, and the step editor |
| POST | `/api/recipes/{id}/steps/{rank}` | yes | the owner's robot setting for one step of a Mealie recipe, without a copy |
| DELETE | `/api/recipes/{id}/steps/{rank}` | yes | that step back to the recipe's own setting (`?key=`) |
| POST | `/api/waiting` | yes | send a recipe to the robot (it waits there) |
| DELETE | `/api/waiting` | yes | remove the waiting recipe |
| POST | `/api/machine/pause` | yes | pause |
| POST | `/api/machine/resume` | yes | resume (see policy) |
| POST | `/api/machine/stop` | yes | stop |
| POST | `/api/machine/tare` | yes | tare the scale |
| POST | `/api/machine/adjust` | yes | change duration, temperature, speed |
| POST | `/api/machine/start` | yes | start a manual cooking (see policy) |
| POST | `/api/machine/clear` | yes | close the ended job shown on the robot (its OK button) |
| POST | `/api/session/stop` | yes | stop the guided recipe open on the robot (its « Arrêter la recette ») |
| POST | `/api/robot/appearance` | yes | set the robot's colour and/or theme (Settings › Screen) |
| POST | `/api/phones/me` | yes | rename this phone (its own name only) |
| DELETE | `/api/phones/me` | yes | forget this phone |

Without a valid token every path except the first three answers `401 unauthorized`, including unknown
paths (an unpaired client cannot even tell which routes exist). `HEAD` is accepted wherever `GET` is.
Routing works on path segments split **before** percent-decoding: `%2F` stays inside its segment
(`/api%2Fstate` is not `/api/state`; a recipe id may contain an encoded `/`).

### GET /api/hello

```json
{"service":"toque-companion","protocol":1,"pairing":true}
```

Nothing else about the robot. `pairing` is true while a live code exists.

### POST /api/pair

Request: exactly one of
```json
{"code":"482913"}
{"secret":"0123456789abcdef0123456789abcdef"}
```
Reply `200`:
```json
{"token":"6f1c…64 hex…","phone":{"id":"1eeddd36","number":1,"name":"Téléphone 1","pairedAt":1795340083523,"lastSeenAt":1795340083523,"method":"code"},
 "robot":{"id":"a1b2c3d4e5f6","name":"Monsieur Cuisine Connect","appVersion":"0.7.0","language":"fr","protocol":1}}
```
Errors: `403 wrong_code`, `410 code_dead` (5 wrong attempts), `410 code_expired`, `410 pairing_closed`
(no code open, or already consumed), `409 too_many_phones`, `400 bad_request` (both or neither field).

### POST /api/prove

Request `{"phoneId":"1eeddd36","nonce":"0123456789abcdef0123456789abcdef"}`, reply
`{"proof":"9f…64 hex…"}`. `400 bad_request` unless `phoneId` is 8 lowercase hex and `nonce` 32
lowercase hex. See 2.6.

### GET /api/robot

```json
{"robot":{"id":"a1b2c3d4e5f6","name":"Monsieur Cuisine Connect","appVersion":"0.7.0","language":"fr","protocol":1},
 "phone":{"id":"1eeddd36","number":1,"name":"Téléphone 1","pairedAt":1795340083523,"lastSeenAt":1795340090000,"method":"qr"}}
```

### GET /api/state

Without `since`: immediate. With `since=N`: waits while the revision equals `N`, at most `wait`
seconds (default 20, clamped to 0–120; robots before this change clamp to 25, which phones must
accept), then answers. With `quiet=1`, pure telemetry changes (temperature, weight, scale stability,
remaining-time drift) do not end the wait: only a change of the job, session, waiting recipe, journal,
pairing, permissions or robot info does; a `since` older than the last such change, or unknown to the
robot (robot app restarted), answers at once. Phones use it whenever the app is not on screen (120 s
wait with the robot idle, 20 s while something is in progress) so a temperature or a jittering scale
does not wake them; robots before this change ignore the parameter. With `replace=1`, the long-poll
replaces the one the same phone may have left waiting: the older one answers at once (current state,
written to a socket the phone has usually closed) and frees its connection slot (8 per address). A
phone sends it only from its single follower; one phone never has two long-polls it wants at once.
Without `replace=1` (older phone apps) nothing changes; robots before this change ignore it (the cut
long-poll then waits until its end). The reply is the current state (same revision on timeout). A
`since` different from the current revision (for example after the robot app restarted) answers at
once. Reply: `StateWire`, see section 4. Errors: `400` for a non-numeric `since` or `wait`.

### GET /api/journal

`after` (default 0): only lines with `seq > after`. `limit` (default 100, 1–300). Lines newest first.
With `after = 0`, all kept activities; otherwise only the activities of the returned lines. See
section 5.

### GET /api/recipes

| param | default | values |
|---|---|---|
| `q` | "" | free text, ≤ 100 characters (tolerant search: accents, plurals, typos) |
| `filter` | `all` | `all`, `recent`, `frequent`, `favorites`, `user`, `community`, `mealie`, `quick`, `category` |
| `category` | — | with `filter=category` only (required then): a category name, 1–100 characters, no control character |
| `offset` | 0 | 0–100000 |
| `limit` | 20 | clamped to 1–50 (the phone asks 48) |

The filters are the chips of the robot's recipe list, and the selection is the robot's own code
(`RecipeChip.select` in `core/.../recipes/Chips.kt`, used by `RecipesScreen` and `CatalogSearch`):
the same chip and text give the same recipes on the phone as on the robot, in the same order. The
robot's screen stops at 500 recipes; the API pages through all of them.

- `all`, `favorites`, `user` (the robot owner's own recipes, "Mes recettes"), `community` (recipes
  received from toqueapp.org, "Communauté"; a robot based on Toque 0.8.1 or later), `mealie` (recipes
  from the owner's Mealie server, read-only; Toque 0.11.0 or later: an older phone skips the chip,
  `CatalogChipWire.toChip` returns null), `quick` (total time
  known and ≤ 30 min, "30 min max"), `category`: with no text, favourites first, then by title;
  with text, by relevance (tolerant search; text with commas: by ingredients, most matched first).
- `recent` / `frequent`: the robot's order (last cooked / most cooked, 60 at most), filtered by the
  text if any.

A category that no longer exists gives an empty page, not an error. Reply:
```json
{"total":25,"offset":0,"items":[{"id":"perso-veloute-de-potimarron","title":"Velouté de potimarron","category":"Soupes",
 "totalMinutes":35,"favorite":true,"user":true,"hasImage":true,"machineSteps":3,"rating":0}],
 "filters":[{"filter":"all"},{"filter":"recent"},{"filter":"frequent"},{"filter":"favorites"},{"filter":"user"},
  {"filter":"quick"},{"filter":"category","category":"Desserts"},{"filter":"category","category":"Soupes"}]}
```
`user`: the robot owner's own recipe ("Ma recette"). `rating`: average out of 5, 0 if none. Errors:
`400 bad_request` for a bad parameter (unknown `filter`, `category` missing, empty or too long with
`filter=category`, `category` given with another filter).

`filters` (first page only, `offset=0`; absent on the next pages): the robot's chips, in the order of
its screen. Always `all`, `recent`, `frequent`, `favorites`; then `user` when the catalogue holds a
personal recipe or `user` is the requested filter; `community` likewise, for recipes received from
toqueapp.org (a phone that does not know this filter drops the entry: those recipes stay under `all` and
in the search); `quick`; then up to 30 categories, alphabetical
(accents ignored). Each entry is exactly the `filter` (and `category`) to send back. The phone shows
the fixed ones with its own translations (same French keys as the robot) and category names as
they are, and skips a `filter` it does not know. Why on the first page rather than a route of its
own: no second request, the list always matches the catalogue the page was cut from, it is fresh on
every reload, and `/api/recipes/filters` would collide with `/api/recipes/{id}`. A robot from before
this field sends no `filters` and refuses the new filters with `400`: the phone then keeps the four
fixed chips.

### GET /api/recipes/{id}

`RecipeDetail`: `id, title, category, servings, servingUnit, prepMinutes, totalMinutes, difficulty`
(1 easy, 2 medium, 3 hard)`, favorite, user, author, rating, ratingCount, hasImage, ingredients, steps, language, source`.
```json
{"id":"perso-veloute-de-potimarron","title":"Velouté de potimarron","category":"Soupes","servings":4,"servingUnit":"",
 "prepMinutes":10,"totalMinutes":35,"difficulty":1,"favorite":true,"user":true,"author":"","rating":0,"ratingCount":0,"hasImage":true,
 "ingredients":[{"title":"","items":[{"qty":"20","unit":"g","name":"Beurre","note":"","display":"20 g Beurre"}]}],
 "steps":[{"index":2,"text":"Faire revenir l'oignon.","machine":{"program":"manuel","seconds":180,"speed":1,"tempC":100,
   "reverse":false,"accessory":"BLADE","pulses":0,"summary":"3 min · 100 °C · vitesse 1"},"weigh":null,"needsReview":false,"hotBlendOnRobot":false}]}
```
Quantities are those the robot would show (servings adjusted on the robot included). `404 not_found`.

Number of people (the stepper above the ingredients, as on the robot's recipe page):
`baseServings` is the recipe's own number, `servings` the one chosen on the robot, `minServings` /
`maxServings` the allowed range (half to double, `Portions.range`), `totalGrams` the estimated weight
of the weighed or measured ingredients at `servings`, `bowlTooFull` when the recipe is enlarged and
`totalGrams` is above 2500 g (the robot's warning). `baseServings` is 0 when the recipe has no number
of people; a robot from before these fields sends none of them: no stepper, only « N pers. ».

`language` is the language the robot writes this recipe's numbers in: the recipe's own, or the
robot's interface language when the recipe has none (decimal separator, French agreement of counted
names). With it, the phone computes the sheet for another number itself with the robot's code
(`Wires.forServings(sheet, n)`, from a sheet at `servings == baseServings`) and shows it during the
tap; the robot's answer to `POST …/servings` then confirms it (same values). A robot from before
this field sends none: the phone uses its own interface language.

`source` says where the recipe comes from, the robot's `RecipeSource` in lower case: `imported` (Lidl),
`user` (« Ma recette », same as `user` true), `community` (toqueapp.org) or `mealie` (a self-hosted Mealie
server, read-only on the robot: its page says « Depuis Mealie », and « Ma version » makes an own copy,
`POST /api/recipes/{id}/copy`, the Mealie recipe staying as it is). Absent on a robot from before this
field: the phone then knows only `user`. A value it does not know (a newer robot) reads as `imported`.

`ownSettings` (written only when true) says the owner may give a step of this recipe their own robot setting
from the phone, without a copy (a Mealie recipe, on a robot whose host has `takesOwnSteps`; see « The owner's
setting for a step of a Mealie recipe »). Each step then also carries `key`, the fingerprint of its text in the
recipe as received (`OwnStepSettings.keys`, 16 hex, `-2`… for a second step with the same text), and `own`:
`robot` when the step carries a setting the owner typed on the robot (trusted), `phone` when it came from a phone
(`needsReview` is then true: still to check on the robot), absent otherwise. `machine` is always the setting that
will run (the owner's one when there is one). Steps of other recipes, and every step of a robot from before these
fields, have neither `key` nor `own`; an older phone ignores them. The same fields come with `?base=1` and in the
reply of `POST …/servings`.

`?base=1`: the recipe with the user's corrections but at its own number of people
(`Recipe.corrected(adjustments)`, `servings == baseServings`), whatever number is chosen on the
robot; nothing is saved or journaled. `base=0` or absent: the answer above. Any other value: `400
bad_request`. The phone asks for it when a sheet arrives at another number, so that the next taps are
computed at once. A robot from before this parameter ignores it and answers at the chosen number:
the phone recognises that by `servings != baseServings` and waits for the robot's answers instead.

### POST /api/recipes/{id}/servings

Body `{"servings": n}`, strictly: that one field, an integer 1–99 (`400 bad_request` otherwise). The
robot saves the choice exactly as its recipe page does (`RecipeAdjustments.withServings`, on its main
thread), so its recipe page, the phone and a recipe sent to the robot always agree; the robot page
showing that recipe redraws at once. `n` equal to `baseServings` returns to the recipe's own number.
Reply: the `RecipeDetail` at the new number. Errors: `404 not_found` (unknown recipe), `400
servings_range` (outside `minServings`–`maxServings`), `409 no_servings` (the recipe has no number of
people), `409 cooking` « Recette en cours sur le robot » (a guided session is open on that recipe;
checked again on the robot's main thread just before saving), `504` as for commands. When that
recipe is the waiting one, `state.waiting.servings` follows and the revision moves (also when the
number is changed on the robot's page). Journal: one `servings` line, with the phone as `origin`.

### POST /api/recipes/{id}/favorite

Body `{"favorite": true}`, strictly: that one field, a boolean (`400 bad_request` otherwise). The robot saves it as the
star of its recipe page does (`RecipeRepo.setFavorite`, on its main thread): its recipe list, its « Favoris » chip and
the phone's catalogue (`favorite` of `GET /api/recipes`, the `favorites` filter, `GET /api/recipes/{id}`) agree at once.
Any paired phone may do it, no confirmation; nothing is journaled and the revision does not move (nothing of the state
changes). Reply `{"id":"perso-veloute-de-potimarron","favorite":true}`. Errors: `404 not_found` (unknown recipe),
`409 unsupported` (a host that cannot), `504` as for commands. A robot from before this route has no `can.favorite`:
the phone keeps its « ★ Favori » label and never calls it.

### Own recipes written on the phone (`/api/recipes`, `…/edit`, `…/copy`)

The phone is a keyboard, the robot keeps the recipe and judges it. Lot A carries text: the title, the number
of people, the total time, the difficulty, the ingredient lines, and each step's text and weighing. Each step
sent says which stored step it comes from (`from`), and the robot gives it back that step's machine setting and
review flag; a new step has none. Lot B adds, for a robot that says so (`settings`, below), the step's machine
setting typed on the phone (`machine`): the robot judges it with its own step editor's rules and has it checked
once on the robot before its first start. A setting the phone did not touch is never judged again.

Names. `POST /api/recipes` creates in the collection the catalogue reads (`GET /api/recipes`), so a new route
name is not needed and it cannot collide with a recipe id. `/api/recipes/{id}/edit` is the recipe's edit form,
read with GET and submitted with POST, like `servings` and `favorite` are named after what they change; it is not
`/api/recipes/{id}` because that one is the sheet as the robot shows it (number of people chosen, the owner's
corrections), and editing from it would save scaled quantities. `/api/recipes/{id}/copy` names what « Ma version »
does on the robot. The server only accepts GET, POST, DELETE and HEAD, so there is no PUT or PATCH.

`GET /api/recipes/{id}/edit` (own recipes only, `perso-` ids) answers `EditableRecipe`:
```json
{"id":"perso-soupe-de-legumes-lx3k2a","version":"9f2c41d07a5be318","title":"Soupe de légumes","servings":4,"totalMinutes":40,
 "difficulty":1,"ingredients":[{"title":"","items":["2 carottes","500 g eau"]},{"title":"Pour servir","items":["10 cl crème"]}],
 "steps":[{"index":0,"text":"Couper les légumes.","grams":0,"machine":null,"needsReview":false},
  {"index":1,"text":"Cuire.","grams":0,"machine":{"program":"manuel","seconds":1200,"speed":1,"tempC":100,"reverse":false,
   "accessory":"BLADE","pulses":0,"summary":"20 min · 100 °C · vitesse 1"},"needsReview":true}],
 "limits":{"minServings":1,"maxServings":20,"maxTotalMinutes":600,"maxGrams":5000,"maxTitle":200,"maxLine":300,
  "maxStepText":1500,"maxIngredients":100,"maxSteps":60,"maxBodyBytes":196608},
 "settings":{"kinds":[
   {"program":"manuel","label":"Cuisson manuelle","params":["seconds","tempC","speed","reverse","accessory"],
    "default":{"program":"manuel","seconds":60,"speed":2,"tempC":0,"reverse":false,"accessory":"BLADE","pulses":0},
    "minSeconds":5,"maxSeconds":5940,"secondsStep":0,"minTempC":0,"maxTempC":0,"minPulses":0,"maxPulses":0,"hint":""},
   {"program":"petrir","label":"Pétrir","params":["seconds"],
    "default":{"program":"petrir","seconds":90,"speed":4,"tempC":0,"reverse":false,"accessory":"BLADE","pulses":0},
    "minSeconds":15,"maxSeconds":120,"secondsStep":15,"minTempC":0,"maxTempC":0,"minPulses":0,"maxPulses":0,
    "hint":"Pâtes à pain, pizza, brioche. Alterne sens inverse et normal à vitesse 4."},
   …],
  "manual":{"temperaturesC":[37,45,50,55,60,65,70,75,80,85,90,95,100,105,110,115,120,125,130],"maxSpeed":10,"maxSpeedHeated":3,
   "maxSpeedReverse":3,"whiskMaxSpeed":4,"maxTempNoStirC":120,"heatNoStir":true,"reverse":true,"accessories":["BLADE","WHISK"],
   "minSeconds":5,"maxSeconds":5940,"maxSecondsHighSpeed":900,"highSpeedAbove":6}}}
```
The recipe as stored: not adjusted to the number of people chosen, without the owner's corrections. Ingredient
lines are `Ingredient.display`; `machine` is the stored setting, in full and with its `summary` (in the robot's
language), and `needsReview` its review flag; both are read-only (the phone never sends them back as such). `version` is the first 16
hex of the SHA-256 of the stored recipe (`RecipeCodec`, favourite left out: a star changes nothing here): any
change on the robot (its editor, an import, another phone) gives another version. `limits` lets the phone show
the bounds; the robot judges. Errors: `404 not_found`, `409 not_user` (a Lidl recipe: « Ma version »).

**`settings`** (lot B, `StepSettingsWire`, core `companion/RecipeWrite.kt`) is the flag and the description at once:
written only by a robot that takes a step setting typed on the phone (`CompanionHost.writesMachineSteps`), absent on
an older robot, which answers `400` to any `machine` member (an older phone ignores it). It is the robot's step
editor (`StepEditScreen`) for the robot chosen (`RobotProfiles.active`), from one core source (`StepSettings`):
- `kinds`: the kinds the editor offers on this robot, in its order (manual, knead, steam, toast, Turbo, then the
  Smart's programs on a Smart), each with `label` (robot's language), `params` (the fields set with this kind;
  every other field keeps the value of `default`), `default` (the setting a kind starts from when chosen, as on
  the robot), and its bounds as the editor presents them: `minSeconds`/`maxSeconds`/`secondsStep` (knead 15 s to
  2 min by 15 s, steam 1 to 99 min by 1 min, toast 30 s to 14 min by 30 s, Smart programs their original app's
  range; `secondsStep` 0: the manual cooking's steps, `ManualSettings.nextSeconds`), `minTempC`/`maxTempC` (Smart
  heated programs, by 1 °C), `minPulses`/`maxPulses` (Turbo 1 to 10), `hint` (the editor's line under it; `""`:
  none). 0 means not applicable. A phone ignores a kind it does not know.
- `manual`: the manual cooking screen's bounds (`ManualLimits`, read back with `ManualRangesWire.limits()`): the
  robot's temperatures (the Smart's 40 °C included), the top speeds when heating, in reverse and with the whisk,
  heating without stirring (`heatNoStir`, up to `maxTempNoStirC`), durations (5 s to 99 min, 15 min above speed
  6), and what the robot has (`maxSpeed`, `reverse`, `accessories`). The phone sets a manual setting with core's
  `ManualSettings` and these limits, the very rules and messages of the robot's screen
  (`StepSettings.manualStart`, `StepSettings.manualPicked`), so that most refusals never happen.

`GET /api/editor` answers `{"limits":{…EditLimitsWire},"settings":{…}}` for a recipe not yet created (`settings`
again only on a robot that takes them; `409 unsupported` on a host without `writesRecipes`). A robot from before
lot B answers `404` to it: the phone then offers no setting.

`POST /api/recipes/{id}/edit`, body (strict):
```json
{"version":"9f2c41d07a5be318","title":"Soupe de légumes","servings":4,"totalMinutes":40,"difficulty":1,
 "ingredients":[{"title":"","items":["2 carottes","500 g d'eau"]},{"title":"Pour servir","items":["10 cl crème"]}],
 "steps":[{"from":1,"text":"Cuire 20 minutes.","grams":0},{"from":0,"text":"Couper les légumes.","grams":0},
  {"text":"Mixer.","grams":0}]}
```
- Every field required, no other field. `servings` 0–1000, `totalMinutes` 0–100000, `difficulty` 0–10 and `grams`
  0–1000000 are reading bounds (integers; `4.0` accepted, `2.5` not); the editor's limits come after.
- `ingredients`: groups with `title` and `items` (strings). A group title must already exist in the stored recipe
  (a copied Lidl recipe's groups are kept, never created). A line equal to a stored ingredient's display keeps that
  ingredient as stored; another line is read like a line typed on the robot (`UserRecipes.ingredient`). Blank lines
  are ignored; groups left empty go.
- `steps`, in their new order: `from` (absent or null: a new step) is the `index` of the stored step it comes from,
  at most once each; a stored step left out is removed. `grams` 0: no weighing; equal to the stored step's: the
  stored weighing (its label) stays. A step object carrying `machine`, `needsReview`, `weigh`, `hotBlendOnRobot` or a
  machine field at the step's top level (`program`, `seconds`, `speed`, `tempC`, `reverse`, `accessory`, `pulses`)
  is `400 bad_request` « Machine settings are set on the robot, never sent by the phone », with `field` `steps` and
  `step`. On a robot that takes settings (`settings` present) the one exception is the member `machine` (lot B):
  - absent: keep the stored step's setting and review flag (`from`), exactly as lot A; a new step has none;
  - `null`: no setting (a stored one is removed; nothing is judged);
  - an object `{"program":"manuel","seconds":300,"speed":1,"tempC":90,"reverse":false,"accessory":"BLADE","pulses":0}`:
    this setting. Strict: the seven fields, each of its type, nothing else (no `summary`, no `needsReview`):
    `program` a `StepProgram.key`, `accessory` `BLADE` or `WHISK`, `reverse` a boolean, `seconds` 0–1000000,
    `speed` 0–100, `tempC` 0–1000, `pulses` 0–1000 (reading bounds, integers). Otherwise `400 bad_request`
    « Invalid machine setting », `field` `steps`, `step` its rank. An object equal to the stored step's setting
    (`from`), field by field, counts as unchanged: kept, not judged, its review flag as it was.
- Then, on the robot's main thread, just before writing: `404 not_found`, `409 not_user`, `409 cooking` « Recette en
  cours sur le robot » (a guided session is open on that recipe; also checked when the request arrives), `409 editing`
  « Recette ouverte dans l'éditeur du robot », `409 stale` « La recette a changé sur le robot » (the version is not
  the stored one: the phone reloads, or rebases its draft and saves again), then the editor's limits (`422`), then
  each setting typed or changed on the phone (`422 step_machine`, below).

Reply: `EditableRecipe` of the saved recipe (its new version). Nothing is journaled in the phones' journal; the
robot's own log says which phone saved it and, with lot B, at which steps a setting was typed. The revision moves
only when the recipe is the waiting one (`state.waiting` takes its new title, step count and time).

**A setting typed or changed on the phone** (a `machine` object that differs from the stored step's, or any
object on a new step) is judged by `StepSettings.refusal(m, RobotProfiles.active)`, never throwing: first what
the step editor does not let one set (a kind this robot lacks, « X : absent de ce robot »; a field outside the
kind's `params` that differs from its `default`, « Réglage non proposé par l'éditeur du robot »; the kind's
bounds: the manual screen's (`ManualSettings.outOfBounds`, the robot's temperature table), « Durée : de 15 s à
2 min », « Impulsions : de 1 à 10 », « Sous-vide : de 40 à 85 °C »), then the safety rules
(`StepMachine.violations`), then what the robot can do (`RecipeCompat.stepReason`); a setting core cannot
describe (a Smart program out of its range makes `toJob` throw) is refused, « Réglage impossible sur ce robot :
… ». A refusal is `422 step_machine`, `field` `steps`, `step` its rank, message « Étape N : reason »; nothing is
saved. There is no « nearest permitted setting » here (that is for imported recipes, at cooking). An accepted
typed setting is saved with `needsReview` and `reviewAlone` true (`Step`, written to the recipe file as
`"reviewAlone":true`), whatever the phone thinks: the robot asks for it once (« Réglage à vérifier », « J'ai
vérifié ce réglage ») before its first start, on its own card. Until then it is never chained into the previous
step's program (« Puis, enchaînés automatiquement », `CookScreen.continues`); an imported setting to check
chains as before, listed and checked with the first step of its series. A step whose text is only the summary of
its stored setting (the chained settings of a Lidl recipe) takes the summary of the new one. In the same
main-thread block as the save, the robot forgets every remembered confirmation (`Prefs.reviewedSteps`, keys
`<id>|<rank>|<summary>`) and every adopted nearest setting (`Prefs.adaptedSteps`, keys
`<id>|<rank>|<from>><to>`) at the ranks of `RecipeWrite.forget`, whatever their summary: the ranks where a
setting was typed or changed (one with the same summary as one confirmed before at that rank is asked again), and
the ranks where a step with an untouched setting arrived from another rank (confirmations are kept by rank: the
one of the step that stood there is not its own). Other steps keep theirs. Changing only the text or the weighing
of a step whose stored setting breaks a rule still saves (that setting stays blocked at cooking, as for a Lidl
recipe).

The phone keeps to what the editor offers: as soon as a program setting is touched there, `SettingKindWire.fit`
brings it to the kind's `default` for the fields the kind does not set and into its bounds for the others (a
Lidl kneading has no speed and a Lidl Turbo counts seconds: sent as they are, the robot would refuse them), and
a setting whose `params` have the stored values is not sent at all (`SettingKindWire.same`).

`POST /api/recipes`: the same body with `draft` (32 lowercase hex, the phone's draft id) instead of `version`, and
no `from` (nothing is stored yet). The robot names it (`UserRecipes.newId(title)`; an id already taken moves to the
next millisecond) and judges the whole recipe. A second creation with the same `draft` from the same phone (the
reply was lost) saves onto the recipe it created, as long as that recipe has not changed on the robot since (else
`409 stale`); the robot remembers its last 32 creations, in memory.

`POST /api/recipes/{id}/copy`, empty body: « Ma version » of a Lidl recipe, as the button of the robot's recipe page
(`UserRecipes.copyOf` of the recipe as shown: number of people chosen, corrections), saved at once in « Mes
recettes »; machine settings and review flags come with it. The Lidl recipe never changes. Reply: `EditableRecipe`
of the copy. Errors: `404 not_found`, `409 already_user`, `409 busy` (id taken four times in a row).

**Refusals of the content (`422`)** carry where (`ApiError.field`, `ApiError.step`, absent from other errors, ignored
by older phones) and the message the robot's editor shows. One core check, `RecipeRules.check` (core
`recipes/Recipe.kt`), used by the robot's editor and by these routes:

| code | field | rule (robot editor) | message |
|---|---|---|---|
| `title_missing` | `title` | a title | « Donnez un titre à la recette » |
| `servings_range` | `servings` | 1 to 20 people | « De 1 à 20 personnes » |
| `total_time_range` | `totalMinutes` | 0 to 600 min | « Durée totale de 0 à 600 min » |
| `difficulty_range` | `difficulty` | 1, 2 or 3 | « Difficulté : Facile, Moyen ou Difficile » |
| `content_empty` | `content` | an ingredient or a step | « Ajoutez au moins un ingrédient ou une étape » |
| `step_empty` | `steps` + `step` | text, a setting or a weighing | « Étape N : Écrivez ce qu'il faut faire » |
| `weigh_range` | `steps` + `step` | 1 to 5000 g (`RecipeAdjustments.MAX_GRAMS`) | « Étape N : Entrez un poids entre 1 et 5000 g » |
| `too_long` | `title`, `ingredients`, `steps` (+ `step`) | 200 / 300 / 1500 characters | « Titre trop long : 200 caractères au plus »… |
| `too_many_ingredients`, `too_many_steps` | `ingredients`, `steps` | 100 lines, 60 steps | « Trop d'étapes : 60 au plus »… |
| `step_machine` | `steps` + `step` | a setting typed or changed on the phone, judged as in the step editor (lot B) | « Étape N : En chauffe, vitesse 3 maximum »… |

A value already stored and left unchanged is not judged again, as on the robot (a copy at 24 people stays at 24
until the number is touched; an unchanged step keeps a 6 kg weighing; a stored text longer than the caps can be
kept). A stored machine setting is not judged here (`machines = false`; the robot's editor still checks them):
only a setting typed or changed on the phone is (`step_machine`), and a stored setting the safety rules refuse
stays blocked at cooking, as for a Lidl recipe.

**Size.** These two routes (`POST /api/recipes`, `POST /api/recipes/{id}/edit`) accept a body of 192 KiB
(`RecipeEdits.MAX_BODY_BYTES`) when the request has an `Authorization: Bearer` header; every other request keeps the
16 KiB bound. Why 192 KiB: the caps above make at most about 121 000 characters, accents take two bytes in UTF-8,
plus the JSON; 16 connections × 192 KiB is 3 MiB at worst on the robot, and the body is parsed only after the token
is checked. Above: `413 body_too_large`.

Compatibility. A robot from before these routes has no `can.editRecipes` (and answers `401`/`404`/`405` on them):
the phone shows nothing new. A host without `writesRecipes` gives `can.editRecipes` refused (`unsupported`) and `409
unsupported` on every route. An older phone ignores the flag and the new error fields. Lot B: a phone offers a
step setting only to a robot whose editor replies carry `settings` (for a new recipe, `GET /api/editor`); a robot
of lot A has no `settings` and answers `400` « Machine settings are set on the robot… » to a `machine` member. An
older phone sends no `machine` member: every stored setting stays through `from`, exactly as before.

### The owner's setting for a step of a Mealie recipe (`/api/recipes/{id}/steps`)

On the robot, a step of a recipe received from a Mealie server can be given the owner's own robot setting without
making a copy (`RecipeScreen`, « Mon réglage », core `recipes/OwnStepSettings.kt`): the setting is kept beside the
recipe (`RecipeAdjustments.steps`), applied over it at each display (`Recipe.corrected`), and survives the syncs as
long as the step's text does not change. These routes let a phone do the same. The phone is a keyboard; the robot
judges, stores and has it checked on the robot. Only the recipes `OwnStepSettings.appliesTo` accepts take them
(Mealie for now); a step's text, weighing and order never change here.

Names. `steps` is the collection of the recipe's steps as the owner sets them, `steps/{rank}` one of them. A POST on
that one sets it, a DELETE takes the owner's setting away (the server has no PUT or PATCH). The rank is in the path
so that the refusals name it (`step`); the key goes with it so that the robot can tell the step is still the one
the phone read.

`GET /api/recipes/{id}/steps` answers `OwnStepsWire` (core `companion/OwnStepEdit.kt`):
```json
{"id":"mealie-0b1c2d3e","title":"Gâteau du verger",
 "steps":[{"index":0,"key":"3f0a6c9e1b2d4f58","text":"Mettre la farine dans le bol.","machine":null,"recipe":null,"own":"","needsReview":false},
  {"index":1,"key":"a41c07d9e6b35f20","text":"Mélanger.","machine":{"program":"petrir","seconds":90,"speed":4,"tempC":0,"reverse":false,
   "accessory":"BLADE","pulses":0,"summary":"Pétrir 1 min 30 s"},"recipe":{"program":"manuel","seconds":30,"speed":3,"tempC":0,
   "reverse":false,"accessory":"BLADE","pulses":0,"summary":"30 s · vitesse 3"},"own":"phone","needsReview":true}],
 "settings":{"kinds":[…],"manual":{…}}}
```
`key` as in `RecipeDetail`; `text` as the robot shows it; `machine` the setting that will run; `recipe` the
recipe's own setting (what « Réglage de la recette » gives back; read from the step's text by the Mealie sync, so
usually to check); `own` `robot`, `phone` or empty; `needsReview` to check on the robot before it runs; `settings`
the robot's step editor (`StepSettingsWire`, as in « Own recipes written on the phone »), always present here.
Errors: `404 not_found`, `409 own_setting_not_allowed` « Cette recette ne prend pas votre réglage du robot » (not a
Mealie recipe: an own recipe is edited, a Lidl one copied), `409 unsupported` (a host without `takesOwnSteps`).

`POST /api/recipes/{id}/steps/{rank}`, body (strict, exactly these two members):
```json
{"key":"a41c07d9e6b35f20","machine":{"program":"petrir","seconds":90,"speed":4,"tempC":0,"reverse":false,"accessory":"BLADE","pulses":0}}
```
`rank` a decimal step rank 0–10000 (else `400 bad_request` « Invalid parameter: index »); `key` the step's key as the
phone read it (16 hex, optional `-N`); `machine` the seven fields of a lot B setting (`SettingWire`, same reading
bounds) or `null` for « no robot setting at this step ». Another member, a missing one, a bad key: `400 bad_request`
(`field` `steps`, `step` the rank); a bad `machine`: `400` « Invalid machine setting ». Then, on the robot's main
thread just before writing (`OwnStepEdits.decide`):
- `404 not_found`; `409 own_setting_not_allowed`; `409 cooking` « Recette en cours sur le robot » (a guided session
  is open on that recipe; also checked when the request arrives);
- `409 stale` « La recette a changé sur le robot » when the step at `rank` does not have that `key` any more: its
  text changed at the source (a sync), steps moved, or the rank is gone. A setting is never attached to a step whose
  text changed since the phone read it; the phone reads the recipe again;
- a setting equal to the step's current one (the same owner's setting, whoever typed it; or, without one, the
  recipe's own setting when it is not to check) changes nothing and keeps its author: a setting the owner typed on
  the robot stays trusted;
- `422` « Étape N : … » (`field` `steps`, `step` the rank): first the rule of the robot's recipe page,
  `OwnStepSettings.problem` (`RecipeRules.step`: the safety rules, `step_machine`), then what the robot's step editor
  offers, as for every setting typed on a phone (`StepSettings.refusal` for the robot chosen: a kind the robot
  lacks, a field the kind does not set, its bounds, what the robot can do; `step_machine`). Nothing is stored.

Accepted, the setting is stored as typed on a phone (`OwnStepSetting(machine, SettingAuthor.PHONE)`, `"by":"phone"`
in the adjustments) and is **not trusted**: `withOwnSettings` gives that step `needsReview` and `reviewAlone`. On the
robot, its recipe page (redrawn at once through `RecipeRepo.onAdjusted` when it shows that recipe) marks it « Du
téléphone : à vérifier »; the step editor opened from it says « Réglage saisi sur le téléphone : vérifiez-le avant
de l'enregistrer. », and its OK stores it as the owner's own (`SettingAuthor.ROBOT`, « Étape N : réglage du téléphone
vérifié »); guided cooking shows it on its own card (« Réglage saisi sur le téléphone : vérifiez-le avant de
lancer. »), never chained into the previous step's program, « Lancer » disabled until « J'ai vérifié ce réglage »,
which also makes it the owner's own (`RecipeRepo.confirmStepSetting`, only if it is still that setting). In the same
main-thread block as the save, the robot forgets every remembered confirmation and adopted nearest setting at that
rank (`CookScreen.forgetConfirmations`), so that a setting with the same summary as one checked before at that rank
is asked again. `null` (no robot setting) has nothing to check. The commands still go through `MachineController`
with its checks; nothing here starts anything. The robot's own log says which phone set which step.

`DELETE /api/recipes/{id}/steps/{rank}?key=…`: the step goes back to the recipe's own setting (as « Réglage de la
recette » on the robot), the remembered confirmations at that rank are forgotten too. `key` is required (`400
bad_request` « Invalid parameter: key » without a valid one) and checked as above (`409 stale`), as are `404`,
`409 own_setting_not_allowed` and `409 cooking`. Nothing to take away: nothing is written.

Both replies are `OwnStepsWire` read again in the same block. Nothing is journaled in the phones' journal and the
revision does not move (nothing of the state changes); the phone reads the sheet again (`GET /api/recipes/{id}`) for
its marks.

Compatibility. A robot from before these routes has no `can.ownSettings` and no `ownSettings` on its sheets (and
answers `404`/`405` here): the phone shows nothing new. A host without `takesOwnSteps` gives `can.ownSettings`
refused (`unsupported`), no `ownSettings`, and `409 unsupported` on these routes. An older phone ignores the new
fields. The protocol version does not change.

### GET /api/recipes/{id}/image

Bytes, `Content-Type` `image/jpeg`, `image/png` or `image/webp`. `404` when the recipe has no image.
At most 4 MiB. Needs the `Authorization` header like everything else (`client.image(id)`).

### POST /api/waiting

Request `{"recipeId":"perso-veloute-de-potimarron"}`. The recipe waits on the robot's home screen;
nothing starts. Sending another one replaces it. Accepted during a cooking or standby: it interrupts
nothing. Reply `{"ok":true,"revision":84}`. Errors: `400` (no id), `404` (unknown recipe).

### DELETE /api/waiting

Reply `{"ok":true,"revision":86}`; `409 no_waiting` when nothing waits.

### POST /api/machine/{pause|resume|stop|tare}

Empty body. Reply `{"ok":true,"revision":66}`. A refusal is `409` with the rule's code and a
displayable reason (the same as `state.can.<command>`), or a controller refusal code (`state`,
`lid`, `hot_blend`…). `504 timeout` when the robot app did not answer in time.

### POST /api/machine/adjust

Request: at least one field; `totalMs` and `addMs` are exclusive.
```json
{"speed":3,"tempC":90,"addMs":60000,"reverse":true}
```
| field | meaning |
|---|---|
| `speed` | 0–10 (0 = heat without stirring) |
| `tempC` | 0 (no heating) or a value of `limits.temperaturesC` |
| `totalMs` | new total duration (1 s – 99 min) |
| `addMs` | added to the current total ("+1 min" = 60000; negative to shorten) |
| `reverse` | `true` reverse direction, `false` normal direction (`state.job.reverse` tells the current one) |

Numbers must be integers (a JSON `3.0` is accepted, `2.5` is not) within reading bounds (`speed`
0–10, `tempC` 0–300, `totalMs` 0–24 h, `addMs` ±24 h); anything else, or a non-boolean `reverse`, is
`400 bad_request` without being truncated.

Only for a running or paused **manual** job with one phase (`state.can.adjust`). The controller checks
everything again (speed while heating, reverse, accessory, duration already elapsed…) and its
refusal code comes back (`heated_speed`, `reverse_speed`, `past`, `too_long`…). A direction change goes
through a confirmed stop of the blade, as on the robot; the speed in reverse is capped at 3 by the
controller. Always sent without the hot-blend confirmation; on top of that, while the contents are hot
(≥ 60 °C) the phone may neither change the speed to 4 or more nor change the direction at speed 4 or
more (`409 hot_blend`). The rule is checked when the request arrives and again on the robot app's main
thread just before acting; `409 changed` if the job changed in between. `504 timeout` means nothing
was done; `504 no_answer` that the command was sent but not confirmed.

### POST /api/machine/start

Starts a **manual cooking**, the only thing a phone can start. Request, every field required:
```json
{"seconds":600,"tempC":90,"speed":2,"reverse":false,"accessory":"BLADE"}
```
| field | meaning |
|---|---|
| `seconds` | duration, 5 s – 99 min, 15 min at most above speed 6 (the robot's Cuisson manuelle screen) |
| `tempC` | 0 (no heating) or a value of `limits.temperaturesC` |
| `speed` | 0–10; 0 only with heating and at most `limits.maxTempNoStirC` (heat without stirring) |
| `reverse` | `true` reverse direction (not at speed 0) |
| `accessory` | `BLADE` or `WHISK` |

Parsing is strict: a missing or unknown field (`hotBlendConfirmed` included), a non-integer number
(`120.0` is accepted), a number outside the reading bounds (`seconds` 0–24 h, `tempC` 0–300, `speed`
0–10), a non-boolean `reverse` or another accessory is `400 bad_request`; nothing is truncated.

The rule (§6) is checked when the request arrives and again on the robot app's main thread just
before acting. A setting outside the bounds of the robot's screen is refused with its code
(`too_short`, `too_long`, `temp_range`, `speed_min`, `no_stir_temp`, `no_stir_dir`, `heated_speed`,
`reverse_speed`, `accessory_speed`), never clamped; then `SafetyPolicy.checkJob` must find no
violation at all: hot contents (≥ 60 °C) at speed 4 or more is `409 hot_blend` (« Contenu chaud :
lancez sur le robot »), no dialog opens on the robot. One start at a time (`409 busy` while one is
being handled). The job is always sent without the hot-blend confirmation. Reply
`{"ok":true,"revision":…}`, sent once a snapshot shows the job (at most 2 s after the machine
accepted): the state of that revision already contains it. The journal line `job_started` is then
attributed to the phone: « Démarrage depuis <phone> : Cuisson manuelle ».

### POST /api/machine/clear

Empty body. Does what the robot's **OK** button does once a job has ended (finished, stopped,
cancelled after a long pause, aborted by an alert): the job leaves the robot's display, so `state.job`
becomes `null`. Nothing else changes on the machine, and no journal line is written. Allowed while the
robot shows an ended job (`state.job.state` `FINISHED` or `ABORTED`, `state.can.clear`), from any
paired phone, whoever started or stopped the job; otherwise `409 nothing_ended`. Idempotent: a second
OK, or an OK after the robot's own, is that refusal and does nothing. Reply `{"ok":true,"revision":…}`,
sent once a snapshot no longer shows the job (at most 2 s after the robot accepted): the state of that
revision already has `job` `null`. `504 timeout` / `no_answer` as for the other commands.

### POST /api/session/stop

Empty body. Does what « Arrêter la recette » of the robot's guided-cooking screen does once its dialog is confirmed
(the phone shows the same confirmation first): the program this recipe started stops, the session ends as not
completed, the recipe is no longer offered on the robot's home screen (« Reprendre ») and the screen closes. Allowed to
any paired phone while a guided recipe is open (`state.can.stopRecipe`), like the stop of a program; otherwise `409
no_recipe` « Aucune recette en cours sur le robot ». Checked when the request arrives and again on the robot's main
thread (the screen that holds the session; none: `no_recipe`). A second request is that refusal and does nothing.
Reply `{"ok":true,"revision":…}`: the state of that revision already has `session` `null`. Journal: `job_stopped`
« Arrêté depuis Téléphone 1 après … » for the program, then `recipe_abandoned` « Recette arrêtée depuis Téléphone 1 »
with the phone as `origin` (the activity's `endOrigin`). `504 timeout` / `no_answer` as for the other commands. A robot
from before this route has no `can.stopRecipe`: the phone shows no button; an older phone ignores the flag.

### POST /api/robot/appearance

Body `{"accent":"#4FC3F7"}`, `{"theme":"light"}` or both; nothing else (any other key, an empty object, another type:
`400 bad_request`, nothing applied). `accent` is `#RRGGBB` (either case) and must be a colour the robot can wear:
one of its ten colours (`RobotLooks.ACCENTS`, the same as `Ui.ACCENTS`:
`#FF8A3D #FFB547 #E8D44D #8BD85F #3DDCB0 #4FC3F7 #6E9BFF #B48CFF #F2639A #FF6F61`), or any colour that passes the
guard of the colour wheel (`core/.../look/ColourWheel.accepts`): the text drawn on it (12 % of each channel, the
robot's `ON_ACCENT` rule) has 4.5:1 on it, and it has 4.5:1 as text on the dark surface `#131D2E`. Every colour of
the wheel passes; dark colours (navy `#1A237E`, burgundy `#800020`, black) do not: `400 bad_request`, nothing applied.
Colours outside the ten are taken only by a robot that says so with `robot.anyAccent` (section 4, since the colour
wheel); a robot that does not answers `400 bad_request` « Couleur inconnue », nothing applied, and a robot from before
the wheel answers `400` to them too. A phone offers a colour outside the ten only to a robot with `robot.anyAccent`.
`theme` is `dark` or `light`. The server's default body limit applies. Allowed to any paired phone when the robot
can (`state.can.setAppearance`; otherwise `409 unsupported` « Le robot ne change pas son apparence depuis le
téléphone »).

What is already in place is left alone: a request equal to the robot's look answers `{"ok":true,…}` without
rebuilding anything (a phone cannot make the robot rebuild its interface in a loop). Otherwise the robot applies it on
its main thread like its Settings do (the interface is rebuilt) and stays on its Home screen. It accepts only when
nothing would be lost: no program (running, paused, or ended and still shown), Home alone on the screen stack (no
guided recipe, editor, pairing code, settings or other screen above it), no dialog or overlay, no timer alarm; standby
over Home is fine. Otherwise `409 busy` with a French message to show as is: « Un programme est en cours sur le robot :
changez l'apparence une fois qu'il est fini » or « Le robot n'est pas sur son écran d'accueil : revenez-y sur le robot,
puis réessayez ». Reply `{"ok":true,"revision":…}`; the next state's `robot.accent` / `robot.theme` show the new look
once the interface is rebuilt. Journal: `appearance` « Apparence changée depuis Téléphone 1 : couleur #4FC3F7, thème
clair » (no alert). `504 timeout` / `no_answer` as for the other commands. A robot from before this route has no
`can.setAppearance`: the phone offers nothing; an older phone ignores the flag.

### POST /api/phones/me

Gives this phone (the one whose token is sent, never another) a name on the robot, as the owner can
on the robot's list of phones. Body `{"name":"Cuisine de Léa"}`, nothing else (any other key, a name
that is not a string or longer than 200 characters: `400 bad_request`, nothing changed). The robot
cleans it like its own field (`PairingManager.cleanLabel`): one line (control characters and runs of
spaces become one space), trimmed, at most 24 characters (`MAX_LABEL`, longer is cut). An empty or
blank name goes back to the default « Téléphone N ». Reply `200`: this phone's `PhoneWire` as the robot
now knows it (`name` the displayed name; `label` the name given and `defaultName` the name it would take
back without one, in the robot's language, both absent for the default):
```json
{"id":"1eeddd36","number":1,"name":"Cuisine de Léa","pairedAt":1795340083523,"lastSeenAt":1795340090000,"method":"code","label":"Cuisine de Léa","defaultName":"Téléphone 1"}
```
The new name is the one the journal, the robot's messages and `state.me` carry from then on; a change
moves `revision` (a rename on the robot's list too). Nothing is journaled. Allowed to any paired phone
for itself (`state.can.renamePhone`, always allowed by a robot that has this route). A robot from before
this route has no `can.renamePhone` and answers `405 method_not_allowed`: the phone offers no
« Renommer »; an older phone ignores the flag and the `label` field.

### POST /api/phones/me/read-only

Puts this phone in **read-only** (Toque 0.12.0 or later), for a wall screen or a home-automation
client that should never command anything: body `{}` (or none), reply `200` with this phone's
`PhoneWire` (`"readOnly":true`). One way only: a phone cannot leave read-only itself; the owner gives
the controls back on the robot (*Settings › Phone*, « Rendre les commandes »), and can also put any
phone in read-only there. A read-only phone reads everything (`GET` routes, the long poll, the
journal, recipes, images) and keeps what concerns itself only (`POST /api/phones/me`,
`POST /api/phones/me/read-only`, `DELETE /api/phones/me`). Every other `POST` or `DELETE` is answered
`403 read_only`, whatever `can` says; in `state.can` every action but `renamePhone` reads
`{"allowed":false,"code":"read_only",…}`. `state.me.readOnly` is written only when true. An older robot
answers `405 method_not_allowed` and has no `readOnly`.

### DELETE /api/phones/me

Removes this phone from the robot. Reply `{"ok":true,"revision":…}`; the token is dead afterwards.

## 4. State (`StateWire`)

```json
{"protocol":1,"revision":42,"serverTime":1795340084886,
 "robot":{"id":"a1b2c3d4e5f6","name":"Monsieur Cuisine Connect","appVersion":"0.7.0","language":"fr","protocol":1},
 "me":{"id":"1eeddd36","number":1,"name":"Téléphone 1","pairedAt":1795340083523,"lastSeenAt":1795340084885,"method":"code"},
 "machine":{"link":"READY","linkLabel":"Base prête","ready":true,"faultCode":"","faultMessage":"","hasSample":true,"tempC":96,
   "targetTempC":100,"hot":true,"weightG":0,"scaleStable":true,"lidOpen":false,"bowlDetected":true,"bowlRemoved":false,
   "motorOn":true,"heaterOn":true,"turbo":false},
 "job":{"kind":"MANUAL","kindLabel":"Cuisson manuelle","label":"Velouté de potimarron","state":"RUNNING","counting":true,
   "endReason":"","phaseIndex":0,"phaseCount":1,"elapsedMs":440000,"totalMs":1200000,"remainingMs":760000,"speed":1,
   "tempLevel":13,"tempC":100,"reverse":false,"accessory":"BLADE","hotBlendConfirmed":false,"startedAt":1795339644886,
   "waitingStop":false,"pause":null},
 "can":{"pause":{"allowed":true,"code":"","reason":""},
   "resume":{"allowed":false,"code":"not_paused","reason":"Le programme n'est pas en pause"},
   "stop":{"allowed":true,"code":"","reason":""},"adjust":{"allowed":true,"code":"","reason":""},
   "tare":{"allowed":true,"code":"","reason":""},"sendRecipe":{"allowed":true,"code":"","reason":""},
   "removeWaiting":{"allowed":false,"code":"no_waiting","reason":"Aucune recette en attente"},
   "start":{"allowed":false,"code":"busy","reason":"Un programme est déjà en cours sur le robot"},
   "clear":{"allowed":false,"code":"nothing_ended","reason":"Aucun programme terminé à fermer"},
   "favorite":{"allowed":true,"code":"","reason":""},"stopRecipe":{"allowed":true,"code":"","reason":""}},
 "session":{"recipeId":"perso-veloute-de-potimarron","title":"Velouté de potimarron","servings":4,"stepCount":6,"current":4,
   "done":[0,1,2,3],"running":[4],"waitingFor":"nothing","startedAt":1795339100000,"steps":[ …StepWire… ]},
 "waiting":null,
 "last":{"id":3,"kind":"recipe","title":"Pâte à pizza","recipeId":"pate-a-pizza","stepCount":4,"handSteps":0,
   "startedAt":1795250000000,"endedAt":1795250420000,"outcome":"finished","summary":"","totalMs":0,"endOrigin":null},
 "journalSeq":17,
 "limits":{"temperaturesC":[37,45,50,55,60,65,70,75,80,85,90,95,100,105,110,115,120,125,130],"maxSpeed":10,
   "maxSpeedHeated":3,"maxSpeedReverse":3,"whiskMaxSpeed":4,"maxTempNoStirC":120,"hotC":60,"minMs":1000,
   "maxMs":5940000,"maxMsHighSpeed":900000,"pauseMaxMs":600000,"manualMinMs":5000,"highSpeedAbove":6}}
```

Times named `…At` and `serverTime` are the robot's wall clock (ms since 1970); the phone's clock may
differ, so compute countdowns from the moment the response arrived, not from `serverTime`. Texts
(`…Label`, `reason`, `summary`, journal `text`) are in the robot's language (`robot.language`).

**Top level**

| field | meaning |
|---|---|
| `protocol` | protocol version |
| `revision` | grows on every visible change; pass it back as `since` |
| `serverTime` | robot wall clock at the response |
| `robot` | `id` (stable random id, 12 hex), `name`, `appVersion`, `language` (UI language code), `protocol`, and its look so that the phone can match it: `accent` (the colour chosen in Settings › Screen, `#RRGGBB` in upper case) and `theme` (`dark` or `light`). Both are absent on a robot that does not know them (before 0.8.0): the phone then keeps its own colour and theme. A change of colour or theme on the robot moves `revision` like any visible change. `anyAccent`: `true` when `POST /api/robot/appearance` takes any colour of the colour wheel (any colour its guard accepts), not only the ten; written only when true, so absent on an older robot (read as false: offer it only the ten) and an older phone ignores it. `accent` may then be a wheel colour: a phone shows it as is. `model`: the robot model, the id of its profile (`mc-connect` or `mc-smart`); written only when known, so absent on an older robot (the phone then infers it from `limits`: `heatNoStir` false is the Smart) |
| `me` | this phone: `id`, `number`, `name`, `pairedAt`, `lastSeenAt`, `method` (`qr` / `code`); `label`: the name given to it (on the robot or with `POST /api/phones/me`), written only when there is one, so absent for « Téléphone N » and on an older robot; `readOnly`: `true` when this phone is read-only (`POST /api/phones/me/read-only`), written only then; `defaultName`: with a `label`, the name the phone would take back without it (« Téléphone N » in the robot's language), absent otherwise (`name` is then that name) |
| `machine` | the base, always present |
| `job` | the current or last job, `null` when none is shown on the robot; an ended job (`FINISHED`, `ABORTED`) stays until OK is pressed on the robot or sent by a phone (`POST /api/machine/clear`) |
| `can` | what this phone may do now (section 6): `pause`, `resume`, `stop`, `adjust`, `tare`, `sendRecipe`, `removeWaiting`, `start`, `clear`, `favorite`, `stopRecipe`, `editRecipes`, `setAppearance`, `renamePhone`, `ownSettings` |
| `session` | the guided recipe on the robot, `null` when none |
| `suspendedSession` | the guided recipe left on the robot without being finished or stopped, the one its home screen offers to resume (« Reprendre »), same shape as `session`; also after a restart of Toque (then `startedAt` is 0, the steps before `current` are in `done`, `running` is empty). Gone when it is resumed (it becomes `session` again), dropped on the robot, or another recipe is started. Written only when there is one and no `session` (Toque 0.12.0 or later) |
| `waiting` | the recipe waiting on the robot's home screen, `null` when none |
| `last` | the last finished activity (journal), `null` when none ("Dernière activité") |
| `journalSeq` | `seq` of the last journal line (0: empty); fetch `/api/journal?after=` when it moves |
| `limits` | safety limits for drawing the controls (the robot checks everything again); `manualMinMs` (shortest manual duration on the robot's screen) and `highSpeedAbove` (above this speed the maximum is `maxMsHighSpeed`) complete the input rules of a manual cooking (`LimitsWire.manual()`); absent from an older robot, Toque's values apply. `temperaturesC` is the robot's table without the Smart's 40 °C: that one stays on the robot (start and adjust refuse it). A cooking started at 40 °C on a Smart shows `job.tempLevel` 1 (`job.tempC` 37) and `machine.targetTempC` 40; "−" from there goes to 37 °C |

**`machine`**

| field | meaning |
|---|---|
| `link` | `OFFLINE`, `ARMING`, `READY`, `LOST` (link with the motor base) |
| `linkLabel` | displayable link state ("Base prête"…) |
| `ready` | link READY and no fault |
| `faultCode`, `faultMessage` | latched machine alert (`COMM_LOST`, `BOARD_ERROR`…), "" if none; acknowledged on the robot only |
| `hasSample` | at least one measurement received |
| `tempC`, `targetTempC` | bowl temperature, target temperature (°C) |
| `hot` | `tempC ≥ 60` |
| `weightG`, `scaleStable` | scale reading (g, after tare), stable flag |
| `lidOpen` | lid open (or not locked) |
| `bowlDetected` | bowl sensor says present (inversion setting applied) |
| `bowlRemoved` | lid "open" because the bowl is out (not just the lid) |
| `motorOn`, `heaterOn` | reported by the base |
| `turbo` | a Turbo press is running |
| `tempKnown` | the base is measuring the temperature right now. Written only when `false`: absent means `true` (the Connect, an older robot). `false` on the Smart outside a heating (its base measures only while heating, and during a heated job): `tempC` is then not a measurement, do not show it. Read it together with `hasSample` |
| `weightValid` | `weightG` is a real weighing: link `READY`, a sample, scale state normal, motor and Turbo stopped (with the blade turning, the Connect's base sends 0). Written only when `false`: absent means `true` (or an older robot). Toque 0.12.0 or later |
| `heating` | Toque asks the base to heat right now: a job runs (`RUNNING`, not paused) and its setting has a temperature, no fault. Prefer it to `heaterOn`, which the base also reports during programs that do not heat. Always written by Toque 0.12.0 or later; absent: an older robot, fall back on `heaterOn` |

**`job`**

| field | meaning |
|---|---|
| `kind`, `kindLabel` | `MANUAL`, `KNEAD`, `STEAM`, `TOAST` (`TURBO` never appears as a job) |
| `label` | job title (recipe title, "Cuisson manuelle", "Nettoyage"…) |
| `state` | `STARTING`, `RUNNING`, `STOPPING`, `PAUSED`, `FINISHED`, `ABORTED` |
| `counting` | `state == RUNNING`: extrapolate `remainingMs` locally only then |
| `endReason` | why it ended ("Terminé", "Arrêté", "Pause trop longue : programme annulé", fault text) |
| `phaseIndex`, `phaseCount` | current phase; a manual job has 1 phase (only those are adjustable) |
| `ferment` | `true` for a fermentation (the Connect's Yaourt program): a `MANUAL` job with one phase, blade stopped, lukewarm, that lasts hours (up to 12 h) and is not tuned like a manual cooking; show it as a program. Written only when `true`; absent on an older robot, where such a job is the only `MANUAL` one longer than 99 min |
| `elapsedMs`, `totalMs`, `remainingMs` | job timer |
| `speed`, `tempLevel`, `tempC`, `reverse` | current phase setting (level 0–19, °C from the robot's table) |
| `accessory` | `BLADE` or `WHISK` |
| `hotBlendConfirmed` | hot blending was confirmed on the robot |
| `startedAt` | when the companion saw it start (0 if it was already running) |
| `waitingStop` | the machine waits for a confirmed stop (start, direction change, end) |
| `pause` | present when `state == PAUSED`: see below |

**`job.pause`**

| field | meaning |
|---|---|
| `reason` | `USER` (pause pressed), `LID` (lid opened or bowl removed), `SAFETY` (a safety check stopped it) |
| `label` | displayable ("En pause", "Couvercle ouvert", "Bol retiré"…) |
| `since` | start of the pause (0 if not seen) |
| `autoCancelAt`, `autoCancelInMs` | the robot cancels the job after 10 min of pause; -1 / 0 if unknown |
| `origin` | who paused: robot or a phone (`self` for this phone) |
| `lidOpenedDuringPause` | lid opened or bowl removed during the pause, or the pause began unseen |
| `bowlRemoved` | the bowl was removed during this pause |

**`session`** (guided recipe on the robot)

| field | meaning |
|---|---|
| `recipeId`, `title`, `servings`, `stepCount` | the recipe |
| `current` | index of the step shown on the robot |
| `done` | indexes of steps done |
| `running` | steps covered by the running program (several when chained), `[]` if none |
| `waitingFor` | what the robot waits for: `nothing` (a program runs), `weigh`, `hand` (do it by hand, then "Suivant" on the robot), `launch` (machine step ready, start it on the robot), `hot_blend` (confirm hot blending on the robot), `resume` (paused), `finish` (last step done) |
| `startedAt` | when the recipe was started |
| `steps` | `StepWire` list (below) |

**`StepWire`**: `index`, `text`, `machine` (`null` for a hand step; else `program`: `manuel`,
`petrir`, `vapeur`, `rissoler`, `turbo`; `seconds`, `speed`, `tempC`, `reverse`, `accessory`, `pulses`
(Turbo presses), `summary` displayable), `weigh` (`null`, or `label`, `grams` target, `weighedG`
measured when the weighing was done, `null` before), `needsReview` (imported setting to check on the
robot), `hotBlendOnRobot` (hot contents and speed ≥ 4: confirmation on the robot).

In a `session` the steps describe the recipe as it is being cooked: `needsReview` goes back to `false`
once the cook has checked the setting on the robot, and when the recipe's setting is refused by the
safety rules and the cook chose the nearest permitted one offered on the robot ("Utiliser …"),
`machine` is that setting, the one that will start; `text` stays the recipe's. The recipe's own
record (`GET /api/recipes/{id}`) is unchanged.

**`waiting`**: `recipeId`, `title`, `stepCount`, `totalMinutes`, `servings`, `category`, `user`,
`hasImage`, `sentAt` (sending = reception, the robot is the server), `origin` (who sent it).

**`origin`** (everywhere): `{"kind":"robot"|"phone","phoneId":"…","name":"Téléphone 1","self":true}`.

## 5. Journal and alerts

`GET /api/journal` returns `JournalPage`:
```json
{"lastSeq":9,
 "entries":[{"seq":9,"at":1795340098700,"kind":"job_stopped","text":"Arrêté depuis Téléphone 1 après 2 min 10 s","alert":"job_end",
   "origin":{"kind":"phone","phoneId":"1eeddd36","name":"Téléphone 1","self":true},"activityId":1,"stepIndex":-1,"elapsedMs":130000,"detail":""}],
 "activities":[{"id":1,"kind":"manual","title":"Cuisson manuelle","recipeId":"","stepCount":0,"handSteps":0,"startedAt":1795339960000,
   "endedAt":1795340098700,"outcome":"stopped","summary":"3 min · 60 °C · sans mélange","totalMs":180000,
   "endOrigin":{"kind":"phone","phoneId":"1eeddd36","name":"Téléphone 1","self":true}}]}
```

- `seq` grows by exactly 1 per line and never repeats while the robot app runs. The robot keeps the last
  300 lines and 60 activities.
- **Notifications without duplicates:** the phone keeps the last `seq` it notified. When
  `state.journalSeq` moves, it fetches `/api/journal?after=<last notified>` and notifies each line
  whose `alert` is not empty (skipping lines whose `origin.self` is true if it wishes), then stores
  the highest `seq`. If `state.journalSeq` is lower than the stored value, the robot app restarted:
  reset the stored value to 0 and notify nothing older than the restart (compare `at` with the time
  of the last notification).
- **No stale alerts.** The phone follows permanently, so after an outage (phone back home hours later)
  the journal may hold old lines: only lines younger than 2 minutes on the robot's clock
  (`state.serverTime - line.at`) are notified; older ones count as seen.
- "Robot hors ligne" is detected by the phone itself (`StateFollower.onUnreachable`).
- **When the phone notifies** (Toque Compagnon): `FollowService` (foreground service, type `dataSync`)
  follows whenever a robot is paired and "Suivi en arrière-plan" is on: started when the app comes to
  the screen, right after pairing, at boot (`BOOT_COMPLETED`) and after an update
  (`MY_PACKAGE_REPLACED`); stopped only when the phone is forgotten or removed, or the switch goes off.
  It always stays a foreground service: a plain started service of an app that has shown a screen
  gets a cached priority on Android 14 and its process is frozen (cached-app freezer), so it could not
  read the long-poll answer. Its notification follows the same "in progress" rule as the wake locks
  (`LockPolicy`, including the 10-minute grace when the robot vanishes mid-cooking): while busy, the
  "Suivi en cours" channel (`follow`, id 10: the recipe or program in the title, the step and state in the text,
  « Étape 5 sur 6 · Cuisson », « Attend : pesée (Beurre) », « 90 °C · vitesse 2 », a countdown kept by Android; only
  fields that end a `quiet=1` wait, never the temperature or the weight: `CookingNotices`, pure); otherwise a fixed text on its own
  channel "Suivi au repos" (`follow_idle`, id 11, low importance, no badge), posted once when the
  service starts idle and once at each return to idle, never re-posted while idle (a swipe stays).
  Its "Masquer" action and the Robot tab's "Notification au repos" row open Android's page of that
  channel, so the user can switch it off without losing the cooking notification. Two ids because a
  `startForeground` on a blocked channel with the same id would leave the cooking notification shown.
  Alerts are notified with the app on screen too (they then time out after 8 s). Battery: a wake lock
  and a Wi-Fi lock only while something is in progress; whenever the app is not on screen the long-poll is
  `quiet=1` (120 s idle, 20 s while something is in progress: temperature and weight never wake the phone,
  the notification's countdown is re-set at least every 20 s); otherwise the phone sleeps, a long-poll
  answer wakes it and a 10 s wake lock covers the journal read and the notification. An inexact
  `AlarmManager` wake-up (every 15 min, 5 while unreachable) retries and cuts a long-poll left without
  answer while the phone slept. Robot unreachable in the background: retries back off to 5 minutes, and
  the /24 is scanned only when the phone is on the robot's last /24, at most once an hour.
- **Buttons on the cooking notification** (manual cooking only, outside a guided recipe): « Pause » or « Reprendre »
  and « +1 min », each shown only when `state.can` allows it (a resume after the lid was opened stays on the robot,
  `can.resume` refuses it); never a stop button (a mistaken tap on a locked screen would stop the cooking). They are
  broadcasts to `NoticeActionReceiver` (not exported), never an activity (Android 12+ forbids starting one from a
  notification button, and it would need the phone unlocked): the same commands and rules as the app's buttons,
  through the link, off the main thread; buttons hidden while one is on its way; the robot's refusal replaces the text
  for 8 s. `setAuthenticationRequired(false)` (Android 12+): usable on the lock screen without unlocking.
- The Robot tab has a "Diagnostic des alertes" screen: the last 400 lines of what the service, the
  link and the alert centre did (no token, secret or IP address), with a copy button. A received state
  writes a line only when something that matters changes (job kind or state, recipe step, waiting
  recipe, journal number) or after a link change; the others are counted, one line a minute at most
  ("N état(s) sans changement notable en 60 s").

**Activity** (`ActivityWire`): `id`, `kind` (`recipe`, `manual`, `program`), `title`, `recipeId`,
`stepCount`, `handSteps`, `startedAt`, `endedAt` (0 while running), `outcome` (`running`, `finished`,
`stopped`, `cancelled` (pause too long), `fault`, `abandoned` (recipe left before the end)),
`summary`, `totalMs`, `endOrigin`. Jobs started during a guided recipe belong to the recipe's activity. A `recipe`
activity carries its `recipeId` (robots since Toque Compagnon's first version): the phone's journal opens that recipe's
sheet when it is tapped, once it has checked that the recipe still exists (`GET /api/recipes/{id}`, `404` → the card
stays untappable).

**Line kinds** (`kind` → `alert`, `detail`):

| kind | alert | meaning |
|---|---|---|
| `recipe_started` | | guided recipe opened on the robot |
| `waiting` | `waiting` | robot waits for the cook; `detail` = `weigh`, `hand`, `launch`, `hot_blend`, `finish`; `stepIndex` set |
| `step_started` | | a recipe step's program started; `stepIndex` |
| `step_finished` | `step_end` | it finished |
| `recipe_finished` | `job_end` | recipe done ("Bon appétit") |
| `recipe_abandoned` | | recipe left before the end; « Recette arrêtée depuis Téléphone 1 » with the phone as `origin` when a phone stopped it (`POST /api/session/stop`) |
| `appearance` | | colour and/or theme changed from a phone (`POST /api/robot/appearance`); origin = the phone |
| `job_started` | | manual cooking or program started (outside a recipe); origin = the phone for a start from `POST /api/machine/start` |
| `job_finished` | `job_end` | it finished |
| `job_stopped` | `job_end` | stopped (on the robot or from a phone: `origin`); `elapsedMs` |
| `job_cancelled` | `job_end` | cancelled after a pause longer than 10 min |
| `job_fault` | `job_end` | aborted by a machine alert |
| `paused` | `pause` | `detail` = `USER`, `LID`, `BOWL` (bowl removed), `SAFETY`; `origin` for a pause from a phone |
| `resumed` | | resumed (robot or phone) |
| `adjusted` | | settings changed from a phone; `detail` like `speed=3;tempC=90;addMs=60000;reverse=true;newTotalMs=540000` |
| `tare` | | scale tared from a phone |
| `servings` | | number of people of a recipe changed from a phone; `detail` = `servings=6` |
| `recipe_sent` | `waiting` | a phone sent a recipe; it waits to be launched on the robot |
| `recipe_removed` | | waiting recipe removed (robot or phone) |
| `recipe_launched` | | waiting recipe launched on the robot |
| `fault` | `fault` | machine alert (`text` = message) |
| `temp_reached` | `temp_reached` | the bowl came within 2 °C of the requested temperature ("Température atteinte : 100 °C"; 40 °C on a Smart cooking at 40 °C); on the Smart, only from what its base measures while heating; `stepIndex` in a recipe |

**Temperature reached** is the only temperature alert: no other temperature change is ever notified. One
line per job and requested temperature, only for a single-phase manual cooking or a step of a guided
recipe (whatever its program), only while the timer runs and the job heats. Never when the bowl was
already at temperature when the job started or resumed, never for a program started alone from the
Programmes screen. Raising the temperature during the job (bowl below the new target) arms it again;
lowering it below the bowl's temperature does not. The sensor reads 0 below 37 °C, so 0 never counts.
Rule: `TemperatureWatch` (core). Robots before this change never send the line.

Each pause, step end and job end appears once. A job end inside a recipe gives `step_finished` (or
`job_stopped`… if the step was stopped); the recipe's own end is `recipe_finished`.

## 6. Command policy (fixed, the phone only displays it)

| Action | From a paired phone | Condition | Refusal codes |
|---|---|---|---|
| Pause | yes | job `RUNNING` or `STARTING` | `nothing_running` |
| Stop | yes | a job not ended, or Turbo | `nothing_running` |
| Resume | yes | `USER` pause seen from its start; no lid opening or bowl removal since the job started or was last resumed, whatever the order in which events and snapshots arrive (an opening between the pause command and the first paused snapshot counts); no fault; link ready; not hot contents at speed ≥ 4 unconfirmed | `not_paused`, `lid_opened`, `bowl_removed`, `safety`, `pause_unknown`, `lid_opened_during_pause`, `hot_blend`, `fault`, `link` |
| Adjust duration, temperature, speed, direction | yes | running/paused manual one-phase job, no fault, same job as when asked; never with hot-blend confirmation; no change of speed to ≥ 4, and no direction change at speed ≥ 4, while hot | `nothing_running`, `fixed`, `fault`, `hot_blend`, `too_long`, `changed`, controller codes |
| Tare | yes | link `READY` | `link` |
| Send a recipe | yes | always (it waits; nothing starts) | |
| Change a recipe's number of people | yes | the recipe has one, the number is within half to double, no guided session open on that recipe | `servings_range`, `no_servings`, `cooking`, `not_found` |
| Remove the waiting recipe | yes | a recipe waits | `no_waiting` |
| Change a recipe's favourite | yes, any paired phone, no confirmation (`can.favorite`) | the recipe exists | `not_found`, `unsupported` |
| Write an own recipe (create, save, « Ma version ») | yes, any paired phone (`can.editRecipes`); text, ingredients, weighings, and a step's machine setting on a robot that says so (`settings`), never starting anything | own recipe (`perso-`), not cooked, not open in the robot's editor, version current, the editor's limits, typed settings judged by the step editor's rules and checked once on the robot before their first start | `not_user`, `already_user`, `cooking`, `editing`, `stale`, `busy`, limit codes and `step_machine` (422), `unsupported` |
| Give a step of a Mealie recipe the owner's setting | yes, any paired phone (`can.ownSettings`), never starting anything | a recipe that takes them (`RecipeDetail.ownSettings`), not cooked, the step still the one read (`key`), judged by the recipe page's rule and the step editor's; stored as from a phone and checked once on the robot before it runs | `own_setting_not_allowed`, `cooking`, `stale`, `step_machine` (422), `not_found`, `unsupported` |
| Stop the guided recipe (« Arrêter la recette ») | yes, any paired phone, after the phone's own confirmation | a guided recipe is open on the robot (`can.stopRecipe`) | `no_recipe`, `unsupported` |
| Change the robot's colour or theme | yes, any paired phone, after the person said yes on the phone (`can.setAppearance`) | one of the robot's ten colours, `dark` or `light`; no program, Home alone on the robot's screen, no dialog | `bad_request` (400), `busy`, `unsupported` |
| Rename this phone | yes, each phone its own name only (`can.renamePhone`) | the phone is still paired | `bad_request` (400) |
| Put this phone in read-only | yes, each phone itself only, one way (`POST /api/phones/me/read-only`) | the phone is still paired | |
| Anything else, from a read-only phone | **never** (`403 read_only`) | the owner gives the controls back on the robot | `read_only` |
| Clear an ended job (OK) | yes, any paired phone | the robot shows a job `FINISHED` or `ABORTED` (whoever started or stopped it); only removes it from the display | `nothing_ended` |
| Start a manual cooking | yes, if the robot's switch « Lancement depuis le téléphone » is on | (1) switch on; (2) link `READY`, a sample, no fault; (3) no job starting, running, stopping or paused, no Turbo (a finished job still shown is fine); (4) no guided recipe open on the robot; (5) lid closed, bowl in place (detected, not removed); (6) settings inside the bounds of the robot's Cuisson manuelle screen; (7) `SafetyPolicy.checkJob` finds no violation, hot blend included. `state.can.start` reflects (1)–(5). | `disabled`, `link`, `fault`, `busy`, `recipe`, `lid`, `bowl`, bounds codes, `hot_blend`, controller codes |
| Start a recipe, a step, a program | **never** (no endpoint) | | |
| Turbo, confirm a hot blend, acknowledge a fault, any setting | **never** (no endpoint) | | |
| Resume after the lid was opened or the bowl removed, or after a safety pause | **never** (refused) | | |

A phone talking to an older robot finds no `can.start`: it reads as not allowed, and the phone says
« Mettez Toque à jour sur le robot ». It finds no `can.clear` either: that reads as not allowed with an
empty code, and the phone keeps its former behaviour (no end screen, no OK; the job is closed on the
robot).

`state.can` gives one `Permission` per allowed action (`allowed`, `code`, `reason`), computed for the
phone that asks at the moment it asks. The robot re-checks at execution; for resume the app
re-checks on its main thread just before acting.

## 7. Errors

Every error is
```json
{"error":{"code":"lid_opened_during_pause","message":"Le couvercle a été ouvert pendant la pause : reprenez sur le robot"}}
```
`message` is displayable for policy and pairing errors (robot language); protocol errors carry a short
English technical message.

| HTTP | codes |
|---|---|
| 400 | `bad_request`, `bad_json`, and malformed HTTP |
| 401 | `unauthorized` (no token, unknown token, phone removed) |
| 422 | a recipe written on the phone refused by the editor's limits (`title_missing`, `weigh_range`, `step_machine`…), with `field` and `step` |
| 403 | `wrong_code`; `read_only` (a command from a read-only phone) |
| 404 | `not_found` |
| 405 | `method_not_allowed` (also `OPTIONS`, so no CORS preflight can succeed) |
| 408 | `request_timeout` (request too slow) |
| 409 | command refused: policy or controller code; `too_many_phones`; `no_waiting` |
| 410 | `code_dead`, `code_expired`, `pairing_closed` |
| 413, 431 | `body_too_large`, `headers_too_large` |
| 415 | `unsupported_media_type` (a body that is not `application/json`) |
| 421 | `bad_host`: the `Host` header must be an IP literal or `localhost` (DNS rebinding defence) |
| 500 | `internal` |
| 501, 505 | `not_implemented` (`Transfer-Encoding`), `http_version` (other than 1.0/1.1) |
| 504 | `timeout`: the robot app did not take the command within 5 s (nothing done); `no_answer`: sent but not confirmed (may have been applied) |

## 8. Limits

| limit | value |
|---|---|
| request line + headers | 8 KiB, 64 headers |
| body | 16 KiB; 192 KiB for `POST /api/recipes` and `POST /api/recipes/{id}/edit` with a token; `Content-Length` only (no chunked) |
| recipe written on the phone | title 200, ingredient line 300, step text 1500 characters; 100 ingredient lines, 20 groups, 60 steps |
| connections handled at once | 16 (one daemon thread each; beyond: closed at once) |
| reserved for trusted addresses | 4 of the 16: unauthenticated traffic never holds more than 12; an address is trusted for 10 min after an authenticated request |
| connections per client address | 8 |
| read timeout between bytes | 10 s; whole request 15 s, checked on every byte (blank lines before the request line included, and counted in the 8 KiB) and enforced by a reaper thread even while a read blocks |
| request handling | 150 s safety net (long-poll ≤ 120 s, host call 5 s) |
| response write | 10 s + 1 ms per 32 bytes; a client that never reads is cut off |
| JSON nesting | 64 levels (`Json.MAX_DEPTH`), on both sides |
| long-poll | ≤ 120 s (`wait`, default 20; 25 s on robots before Toque Compagnon's permanent following) |
| connection reuse | none (`Connection: close`) |
| pairing code | 6 digits, 2 min, 5 wrong attempts |
| phones | 8 |
| catalogue page | ≤ 50 items; `q` ≤ 100 characters |
| journal | 300 lines, 60 activities kept; ≤ 300 lines per request |
| image | ≤ 4 MiB |
| client response read | ≤ 8 MiB |
| host call | 5 s (`HOST_TIMEOUT_MS`) |

A phone should hold one long-poll at a time, plus short calls.

**Known limit: plain HTTP.** The token travels in clear in every authenticated request. Anyone able to
capture the home Wi-Fi traffic (another device on an open or shared network, a compromised access
point) can copy a phone's token and use it as that phone until it is removed on the robot. The proof
exchange stops the phone from *handing* its token to the wrong device; it does not hide the token from
an eavesdropper. Per-request signing was considered and left out for now.

## 9. Client library (`app.toque.core.companion.client`)

- `CompanionClient(host, port, token, phoneId, robotId)`: `hello()`, `pairWithCode(code)`, `pairWithQr(text)` /
  `pairWithPayload(p)` (takes host and port from the QR code and checks the robot id; both set
  `token`, `phoneId`, `robotId`), `verify()` (proof exchange, 2.6), `robot()`,
  `state(since, waitSeconds)`, `journal(after, limit)`, `recipes(text, filter, offset, limit, category)`,
  `recipe(id, base = false)` (`base`: `?base=1`), `setServings(id, n)`, `image(id)` (null if none), `sendRecipe(id)`, `removeWaiting()`, `pause()`, `resume()`,
  `stop()`, `tare()`, `adjust(AdjustRequest)`, `start(StartRequest)`, `clear()`, `setFavorite(id, on)` (`FavoriteReply`),
  `stopRecipe()`, `setAppearance(accent, theme)`, `editable(id)`, `saveRecipe(id, RecipeEditRequest)`, `createRecipe(RecipeEditRequest)`, `copyRecipe(id)`
  (each answers `EditableRecipe`), `editor()` (`EditorWire`), `ownSteps(id)`, `setOwnStep(id, rank, key, machine)`,
  `clearOwnStep(id, rank, key)` (each answers `OwnStepsWire`), `renameMe(name)` (`PhoneWire`), `forget()` (drops the token even if the reply is lost).
  Every authenticated call first proves the address (once per address, again after a network error).
  Errors: `CompanionException(status, error)` (`unauthorized` when 401), `RobotProofException` (the
  address did not prove it is this phone's robot; the token was not sent), `IOException` for the
  network and for malformed responses.
- `state(since, waitSeconds, quiet, replace)`: `quiet` adds `&quiet=1`, `replace` adds `&replace=1` (see
  GET /api/state). `cancelAll()`: cuts the requests in flight; the proof runs again before the next
  request.
- `StateFollower(client, listener, waitSeconds = 20, finder = null, pacing = null, elapsedMs)`: `start()`, `stop()`,
  `retarget(host)`, `latest`, `nudge(restart)` (retry now; with `restart`, also cut the current
  long-poll without counting a failure), every long-poll with `replace=1` (one
  follower per phone), `cutIfStale()` (a long-poll still waiting well past its
  wait, measured on `elapsedMs`, which must count deep sleep: cut and counted as a failure).
  Callbacks: `onState` (each new revision), `onUnreachable` (once per
  outage), `onReachable`, `onUnauthorized` (then it stops), `onAnswer` (every reply, before anything
  else), `onRetry`. Backoff 1, 2, 4, 8, 15 s, or `Pacing` (`waitSeconds()`, `backoffMs(failures)`,
  `locate(failures)`, `quiet()`) chosen by the app. Nothing kills its
  thread: any `Throwable` from the network, the parser or a listener callback is caught.
- `RobotFinder`: `subnet(ownIpv4)`, `scan(candidates, port)`, `locate(candidates, port, token, phoneId, robotId)`.

## 10. Demo server

A JVM server that never ships in the APK (`core/src/demo/kotlin`, source set `demo`): the real
`MachineController` on `SimBoard`/`SimTransport`, an invented catalogue of 12 recipes in seven categories, one of them personal (so every chip of the
recipe list can be tried) (including
"Velouté de potimarron" with the six steps of the mockup), a simulated guided recipe.

```
export JAVA_HOME=$HOME/toolchains/jdk-17
bash gradlew --no-watch-fs -q :core:companionDemo -PdemoArgs="--port 8088 --fast 5"
```

Options: `--port N` (API; robot controls on N+1), `--lan` (listen on all interfaces instead of
127.0.0.1, to try with a real phone), `--fast F` (machine clock F times faster, 1–20), `--store FILE`
(paired phones, default `core/build/companion-demo/store.json`), `--lang en|es|de|fr`.

Play the robot by typing in the console, or from the same machine with
`curl -X POST --data-binary 'pair' http://127.0.0.1:8089/demo`:

| command | robot action |
|---|---|
| `pair` / `close-pairing` | open pairing (prints the code and the QR text) / close it |
| `phones`, `unpair N\|all` | list / remove paired phones |
| `launch` | open the received recipe (or the velouté) and start it ("Cuisiner") |
| `next` | next: record the weighing, finish a hand step, or start the machine step |
| `confirm` | start the machine step confirming hot blending |
| `finish` | end the running machine step at once |
| `abandon` | leave the recipe |
| `remove` | remove the waiting recipe |
| `lid open\|close`, `bowl out\|in` | lid, bowl (bowl out opens the lid) |
| `weight G`, `tare` | put G grams on the scale, tare |
| `manual V T S` | manual cooking: speed V, T °C, S seconds |
| `phone-start on\|off` | robot setting « Lancement depuis le téléphone » (on in the demo) |
| `program knead\|steam\|toast\|clean` | start a program |
| `pause`, `resume`, `stop`, `clear` | as on the robot screen |
| `status`, `help`, `quit` | |

Try it with curl (pair by code, then follow):
```
curl -s -X POST --data-binary pair http://127.0.0.1:8089/demo
curl -s -X POST -H 'Content-Type: application/json' -d '{"code":"482913"}' http://127.0.0.1:8088/api/pair
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8088/api/state
curl -s -X POST --data-binary 'manual 2 90 480' http://127.0.0.1:8089/demo
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8088/api/machine/pause
```

## 11. Integration on the Android robot side (`app/.../companion/`)

- Create the hub once in the application object with a `CompanionStore` in a file of its own, a host
  using `MachineClient` and `RecipeRepo` through `HostCalls`, and feed it from `MachineClient.onSnapshot`
  / `onEvent`. Start the server when `hub.wanted`, stop it otherwise (re-check after the phone and
  pairing listeners fire).
- `CookScreen` pushes `CookSession.of(recipe, index, done, launchedSteps, waitingFor, weighed, hotBlend)`
  (the recipe with the settings adopted on the robot and without `needsReview` on the steps checked there)
  on each change and calls `hub.endSession(true)` at "Bon appétit", `endSession(false)` when the
  recipe is left.
- `catalog` can use `CatalogSearch.page(repo.index, query, repo.recentIds(), repo.mostCookedIds())`.
- `recipe(id)` should return `repo.getAdjusted(id)`; `image(id)` the bytes of `repo.imageFile(...)`.
- `baseRecipe(id)` (`?base=1`) is `repo.get(id).corrected(repo.adjustmentsOf(id))`, read on the server
  thread like `recipe(id)`. Its default returns `recipe(id)`, which the phone treats as an older robot.
- `ownSteps(id)` is `repo.get(id)` (cookable), its `adjusted(adjustmentsOf(id))` and `adjustmentsOf(id).steps`;
  `writeOwnStep` runs on the main thread through `HostCalls.attempt`, stores with `repo.setStepSetting(…, PHONE)` or
  `repo.clearStepSetting`, then `CookScreen.forgetConfirmations(prefs, id, listOf(rank))`. `RecipeRepo.onAdjusted`
  redraws the recipe page; `RecipeScreen` marks a phone's setting « Du téléphone : à vérifier ».
- `writeRecipe` runs on the main thread through `HostCalls.attempt`; the robot's editor says it has a recipe open
  with `RecipeEditScreen.isEditing(id)` (its open instances, weakly held, still on the activity's stack).
- Import of a recipe file (`RecipeFiles.import`): a file never says what was checked on this robot, so every step
  with a machine setting is marked `needsReview` (confirmed on the robot before its first start), whatever the file
  says; a setting the safety rules refuse is imported too and stays blocked at cooking, and the import's message
  counts those steps (« … ; N étapes ont un réglage à adapter au moment de cuisiner »).
- `setFavorite(id, on)` runs `repo.setFavorite` on the main thread through `HostCalls.command`; `stopRecipe()` calls
  `MainActivity.stopGuidedRecipe()` on the main thread (the `CookScreen` on the stack, `stopRecipe()`); `RobotCompanion`
  keeps a weak reference to the activity through `ActivityLifecycleCallbacks`. `setAppearance` calls
  `MainActivity.appearanceFromPhone(accent, light)` on the main thread (`setsAnyAccent` is true: any colour
  `Ui.accepts` takes, checked again there; the refusals of `POST /api/robot/appearance`,
  then the prefs, `Ui.setTheme`, `Ui.setAccent` and `recreate()` without reopening the Settings); with no activity,
  the prefs are saved for the next one.
- `baseServings(id)` is `repo.get(id).servings`; `setServings(id, n, stillAllowed)` runs on the main
  thread through `HostCalls`: `ServingsChange.check`, then `repo.setAdjustments(id, adjustments.withServings(n, base))`.
  `RecipeRepo.onAdjusted` redraws the recipe page on show and calls `hub.recipeServingsChanged(id, n)`,
  which keeps the waiting recipe's number in step when it is changed on the robot.
