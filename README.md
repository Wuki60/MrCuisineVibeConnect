# MrCuisineVibeConnect

🇫🇷 **[Version française](README.fr.md)**

**Toque** is a fast, modern replacement interface for the Lidl **Monsieur Cuisine** kitchen robots:
the **Connect**, and the **Smart** in test. It runs on the robot's own tablet instead of the stock app
and still uses **the official Lidl recipes**.

## Supported robots

| Robot | Label under the robot | Status | How to install |
|---|---|---|---|
| Monsieur Cuisine **Connect** | SKMC 1200 (A1, B2, C3) | Supported | [AI agent](AGENTS.md) or [install guide](docs/INSTALLATION.md), then `Toque-x.y.z.apk` |
| Monsieur Cuisine **Connect** | SKMC 1100 A1 | Supported | Same, with one more step, `own-twrp`: TWRP rebuilt around the robot's own kernel ([#10](https://github.com/Wuki60/MrCuisineVibeConnect/issues/10)) |
| Monsieur Cuisine **Smart** | SKMS 1200 | **In test**: tested by Smart owners, reports in [#59](https://github.com/Wuki60/MrCuisineVibeConnect/issues/59) | [Smart guide](docs/SMART.en.md), then `Toque-smart-x.y.z.apk` |

Each [release](https://github.com/Wuki60/MrCuisineVibeConnect/releases/latest) has two APKs built from
the same code: **`Toque-x.y.z.apk` for the Connect** and **`Toque-smart-x.y.z.apk` for the Smart**
(its Android 8.1 needs the virtual machine's safe mode, which would slow the Connect down). Once Toque
is installed, *Settings › Updates* takes the right one by itself. On first start, Toque recognises the
robot without sending anything to its motor base and asks you to confirm it.

> Independent project, not affiliated with Lidl, SilverCrest or Hoyer. Motor control is based on
> a public analysis of the serial protocol. Since October 2026 Toque has been running on SKMC 1200
> robots (revisions A1, B2 and C3) in France and Spain, and on SKMC 1100 A1 robots. The Smart support
> comes from Flav974's and Tidusneo24150's ports; the project does not own a Smart. Use at your own
> risk and follow the first-run procedure.
>
> The interface is available in **English**, **French**, **German**, **Spanish**, **Catalan** and
> **Ukrainian** (*Settings › Langue · Language*), and other languages can be added with a single file
> (see below).

## Install with an AI agent (Connect)

The easiest way: let an AI agent that can operate your Windows PC (Claude, ChatGPT, Copilot…) do
the install. Give it this message:

> Install Toque on my Monsieur Cuisine Connect by following AGENTS.md in
> https://github.com/Wuki60/MrCuisineVibeConnect

[AGENTS.md](AGENTS.md) describes the whole procedure to the agent, and
[`tools/setup/toque-setup.ps1`](tools/setup/toque-setup.ps1) does the work step by step (verified
downloads, driver, backup, root, install, ADB over Wi-Fi). The agent only asks you for what it
cannot do itself:

- have a **USB-A male ↔ USB-A male** cable and a small Torx screwdriver;
- unscrew the cover under the robot, plug the cable into the **"Android USB"** port, switch the
  robot off and on when told;
- answer Windows security prompts (and, if needed, turn memory integrity off during the install);
- tap **Grant** once on the robot's screen.

Allow 1 to 2 hours. Without an agent: [docs/INSTALLATION.md](docs/INSTALLATION.md).

## Features

- **Dark night-blue interface** designed for the robot's 7" screen and rotary knob, and smooth on its
  original tablet (1 GB RAM, Android 6): no heavy libraries, downsampled images, recycled views.
  Ten accent colours and a light theme in *Settings › Appearance*.
- **One app, several robots**: each robot has its profile (port, protocol, programs, temperatures)
  and only shows the programs and recipes it can make. On the **Smart**: manual cooking with its 21
  temperatures (40 °C included), Knead, Steam, Sauté, Turbo, cleaning, and the Smart programs (blend,
  smoothie, eggs, sous-vide, slow cooking, ferment, rice, hot water, pre-rinse, long cleaning), with
  the Lidl recipes made for it.
- **Lidl recipes, straight from monsieur-cuisine.com.** Toque downloads the official Monsieur
  Cuisine connect recipes (about 1,700 in French, with each step's machine settings) from the
  site's public API, nightly or on demand: no account, no server to run, no need for the stock app.
  Requests are spaced out and only new or changed recipes are downloaded again. The recipes already
  in the stock app can still be imported instead.
- **Community recipes from [toqueapp.org](https://toqueapp.org)**: recipes shared by Toque users
  arrive on the robot (*Settings › Community recipes*), no account needed. With a free Toque account
  (optional, offered once and never again if declined; *Settings › Toque account*), the site's
  favourites come to the robot and your own recipes can be published from the robot. The site is in
  French, English, German, Spanish, Catalan and Ukrainian; only a revocable key of the robot is kept,
  never the password.
- **Author, ratings and reviews**: each recipe shows who published it and its rating on the site,
  and the recipe page loads the users' comments (with Monsieur Cuisine's replies).
- **Search that works**: ignores accents, plurals and typos ("bechamle", "risoto"), in any alphabet
  (Cyrillic, Polish…), and the list shows every recipe.
  Search by ingredients (e.g. "chicken, curry"), filters, favorites. **Recent** (last cooked first)
  and **Frequent** (most cooked first) lists, also one tap away under the home search bar.
- **Guided cooking**: each step starts with one tap, the next step shows up by itself, built-in
  weighing, resume after a power cut. Settings that follow each other with nothing to do in
  between (rinsing, blending at several speeds…) start together with a single tap: the blade goes
  straight from one speed and temperature to the next without stopping. Recipe steps that use the
  site's **progressive speed ramp** become one machine step per stage. A recipe in progress shows
  on the home screen and can be **abandoned** there (✕), except while a program is running.
- **Your own notes and quantities**: a free note on each recipe, and tapping an ingredient's
  quantity changes it (1000 g of water → 800 g); the weighings and mentions in the steps that use
  it follow in proportion (numeric keypad, fractions included). Favourites, notes and adjusted quantities follow the recipe when a sync
  replaces it with the same recipe from the other source (site or stock app).
- **For more or fewer people**: − / + above the ingredients, from half to double the recipe. Every
  weighed or measured quantity follows (list, weighings, "500 g of water" in the steps), rounded the
  way you would write it by hand, with the same rounding everywhere; eggs and spoons are rounded to
  the half. Times, speeds and temperatures stay those of the tested recipe, with a reminder to keep
  an eye on the cooking, and a warning when the bowl may be too full (2.5 L of liquid at most).
- **My recipes**: write your own (title, people, time, one line per ingredient, steps with their
  robot setting and weighing, chosen with the same safety limits as manual cooking), or make **your
  version** of a Lidl recipe with the pencil. Syncs never touch them. *Settings › My recipes* exports
  them to, or imports them from, `Download/toque-mes-recettes.json` (to back them up or pass them to
  another robot).
- **Recipes from other languages** (option in *Settings › Lidl recipes*): the recipes that don't
  exist in your language are added from English, French, German, Spanish…, each one only once, with
  its language on the thumbnail, and their categories take the names of your language. The language
  of the Lidl catalogue can also be chosen (for example Spanish recipes with a Catalan interface).
- **Sounds**: a bell rings when the weight to add is reached (sound to choose in *Settings*), and a
  loud tune plays when the recipe's last program ends. A setting you confirmed ("I checked this
  setting") is remembered for the next time you cook the recipe.
- **Manual cooking**, **Knead / Steam / Sauté / Cleaning** programs, **Turbo**, **scale**. Cleaning:
  1 L of hot water and a drop of washing-up liquid, speed rising from 4 to 10, no heat. Manual
  cooking can also **heat without stirring**: with a temperature set (120 °C at most), the speed
  goes down to 0.
- **Offline**: everything works without a network (no more "server unreachable" at startup).
- **Always the right time**: the time zone comes from the Internet connection and the time from
  the network (the robot has no clock battery). Right after power-on, the clock is set from the
  first server that answers, so the time is right within seconds instead of staying on 01:00. Can
  be set by hand in *Settings › Date and time*. The clock only shows once it is right.
- **Standby**, like the stock app: after a few minutes without a touch or a turn of the knob
  (2 to 30 min, or never, in *Settings › Screen*), a dimmed black screen shows the time, and the
  bowl temperature when it is hot. Never during a program or on the scale. The gesture that wakes
  the screen does nothing else. During standby, the knob ring stays normal, dims to a faint white
  night-light, or turns off (*Settings › Screen*).
- **Bowl sensor that reads the wrong way round**: on some robots (seen on an SKMC 1200 C3) the
  base reports the bowl as present when it is removed; *Settings › Safety › Invert the bowl
  sensor* fixes it.
- **Made for a robot without Android buttons**: Toque opens at startup, and a **Back to Toque**
  button stays on top of the stock app when you open it.
- **Updates from GitHub**: *Settings › Updates* checks this repository's
  [Releases](https://github.com/Wuki60/MrCuisineVibeConnect/releases) and installs the new version
  (silently on a rooted robot, then Toque restarts). Refused while a program is running. Recent
  root certificates missing from Android 6 (ISRG Root X1/X2 for GitHub's download server, Sectigo,
  Google) are bundled, so downloads over HTTPS work on the robot. When a new version is out, Toque
  says so when it starts or wakes from standby (at most every few hours, never during a program);
  "Later" waits until the next day.

## Safety: what changes compared to the stock app

All original limits are kept (speed 3 max when heating and in reverse, maximum durations,
Turbo conditions…). Several flaws found while analysing the protocol are fixed:

| Stock app flaw | Toque |
|---|---|
| Start was requested before checking the lid | Lid, errors and data freshness are checked **before** any start |
| No detection of a silent motor board | No reply for 1.5 s: everything stops and an alert is shown |
| Serial write errors ignored | Stop and alert |
| A STOP followed by a START could never send the stop | Any restart or direction change waits for a sample confirming **motor stopped** |
| Serial reading without resynchronisation | Fragmented, merged or corrupted frames are handled |
| The blade sometimes turned in steam mode | Steam at speed 0 |

Also added: confirmation (and a gradual ramp-up) before blending contents at 60 °C or more, a
speed limit with the whisk, alerts that stay latched until acknowledged, nothing restarts on its
own after a pause or an alert. When cold, the blade goes straight to the requested speed. Motor control runs in **its own process**: if the interface crashes, a cooking run is not
cut off and finishes normally.

## Project layout

```
core/   Pure Kotlin logic, tested without Android
        protocol/  serial frames: Connect (15 bytes sent, 27 received, 4800 baud),
                   Smart (38 bytes sent, 53 received, 9600 baud)
        robot/     one profile per robot (port, protocol, programs, temperatures, Lidl recipes)
        machine/   safety rules, controller, 100 ms loop, links (root serial, TCP, demo)
        recipes/   model, stock database import, search
app/    Android app (minSdk 23), no AndroidX
        machine/   motor control service (":machine" process) and IPC
        recipes/   local catalogue, Lidl sync
        ui/        screens
tools/  setup/toque-setup.ps1 (full install, can be driven by an AI agent), root (TWRP), install,
        restore and release scripts, twin.py (digital twin for testing on a PC)
docs/   INSTALLATION.md, INSTALLATION.fr.md (Connect), SMART.en.md, SMART.md (Smart)
AGENTS.md  install procedure for an AI agent
```

## Build

Open the folder in **Android Studio**, wait for the Gradle sync, then
**Build › Build App Bundle(s) / APK(s) › Build APK(s)**. The APK is in
`app/build/outputs/apk/debug/`.

From the command line: `gradlew assembleDebug` (and `gradlew :core:test` for the tests).

## Try it without the robot

1. **Demo mode** (default): install the APK on any Android device (or the Android Studio
   emulator, in landscape). The motor board is simulated; *Settings › Tools › Motor base
   diagnostics* lets you open the lid or trigger an error to see the safety
   features. *Settings › Lidl recipes › Sync* downloads the recipes on any device too.
2. **Digital twin**: the twin from the [Monsieur Cuisine Developer SDK](https://github.com/antrea/monsieur-cuisine-developer-sdk)
   reproduces the real protocol independently of Toque.
   Run `python tools/twin.py --sdk <SDK folder>`, then in Toque choose *Settings › Machine ›
   Twin (PC)* (emulator: host `10.0.2.2`; real device: `adb reverse tcp:8765 tcp:8765` and host
   `127.0.0.1`). `python tools/twin.py --send "lid 1"` opens the lid, etc.

## Add a language

The app is written in French; each other language is one file in
[`app/src/main/assets/lang/`](app/src/main/assets/lang) (`en.json` for English).

1. Copy `en.json` to `<code>.json` (for example `de.json`), set `"_name"` (for example `"Deutsch"`)
   and translate the values. Keys are the French texts; keep the `%s` / `%d` placeholders.
   Missing texts stay in French.
2. Rebuild the app, or, without rebuilding, copy the file to `Android/data/app.toque/files/lang/`
   on the device and restart Toque.

The language then appears in *Settings › Langue · Language*. `gradlew :core:test` checks
every language file.

## Publish a new version

1. Increase `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit and push to `main`.

The [release workflow](.github/workflows/release.yml) runs the tests, builds both APKs and publishes
the `vX.Y.Z` release with them, the Connect APK first (older Toque versions take the first APK).
Robots then find it in *Settings › Updates*.

All builds are signed with the project key in `app/signature/` (public on purpose), so an APK built
on any PC or by GitHub installs over the previous version. Only install APKs from this repository's
releases.

Without GitHub Actions: `gradlew assembleRelease` (copy `app-release.apk` to `Toque-X.Y.Z.apk`), then
`gradlew assembleRelease -PtoqueSmart=true` (copy it to `Toque-smart-X.Y.Z.apk`), then
`powershell -ExecutionPolicy Bypass -File tools\publish-release.ps1 -Apk Toque-X.Y.Z.apk -SmartApk Toque-smart-X.Y.Z.apk`
creates the `vX.Y.Z` release and attaches both APKs (GitHub token: `GH_TOKEN`, `gh`, or the credentials
already stored for git).

## Install on the robot

- **Connect**, with an AI agent: **[AGENTS.md](AGENTS.md)** (see above). By hand:
  **[docs/INSTALLATION.md](docs/INSTALLATION.md)**: root, install, first run with an empty
  bowl, recipe sync, going back to the stock app.
- **Smart**: **[docs/SMART.en.md](docs/SMART.en.md)**: community root guide, Smart APK, tests to do
  and how to report back.

## Credits

- Protocol: [Monsieur Cuisine Developer SDK](https://github.com/antrea/monsieur-cuisine-developer-sdk) (MIT).
- Robot access: [Monsieur-Cuisine-Connect-Hack](https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack).
- Contributions: @pierok13 (standby, notes and quantities, speed stages, sounds, recent recipes,
  Android 6 roots, bowl sensor), @lucasbraud, @OUTSIDE2k8 (Spanish), @Locodice67 (German, install
  script), @baptiste-gislot, @florentdestremau (rotary knob), @maximehuran (knob light in standby),
  @badrbelhaj1 (Magisk at restart, SKMC 1100 A1 TWRP, heat without stirring, interface fixes),
  @meuca1 (Catalan), @Flav974 (Smart port: protocol, programs, captures), @Tidusneo24150 (Camybrith
  Cooking: Smart temperature ranges and recipe modes, review on a real Smart), @pierrearnou (accent
  colours, switches), @oscarsanchezdm (recipe language, light theme), @phanex (Ukrainian, search in
  any alphabet, scale stability), @McSoda (robot tests and fixes), and everyone who tested their
  robot and reported bugs.

MIT License. This repository contains no Lidl recipes, images or code: each robot downloads the
recipes itself from monsieur-cuisine.com, for its owner's personal use.
