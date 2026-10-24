# MrCuisineVibeConnect

🇫🇷 **[Version française](README.fr.md)**

**Toque** is a fast, modern replacement interface for the Lidl **Monsieur Cuisine Connect**
kitchen robot (SKMC 1200). It runs on the robot's own tablet instead of the stock app and still
uses **the official Lidl recipes**.

> Independent project, not affiliated with Lidl, SilverCrest or Hoyer. Motor control is based on
> a public analysis of the serial protocol and has **not** been validated on a real machine yet.
> Use at your own risk and follow the first-run procedure.
>
> The interface is available in **English** and **French** (*Settings › Langue · Language*), and
> other languages can be added with a single file (see below).

## Features

- **Dark interface** designed for the robot's 7" screen and rotary knob, and smooth on its
  original tablet (1 GB RAM, Android 6): no heavy libraries, downsampled images, recycled views.
- **Lidl recipes only.** The stock app remains the only thing talking to Lidl's servers (with the
  device's own identity, as usual). Toque then copies its recipe database, nightly or on demand.
  No server to run, no account.
- **Search that works**: ignores accents, plurals and typos ("bechamle", "risoto").
  Search by ingredients (e.g. "chicken, curry"), filters, favorites.
- **Guided cooking**: each step starts with one tap, the next step shows up by itself, built-in
  weighing, resume after a power cut.
- **Manual cooking**, **Knead / Steam / Sauté** programs, **Turbo**, **scale**.
- **Offline**: everything works without a network (no more "server unreachable" at startup).
- **Made for a robot without Android buttons**: Toque opens at startup, and a **Back to Toque**
  button stays on top of the stock app when you open it.
- **Updates from GitHub**: *Settings › Updates* checks this repository's
  [Releases](https://github.com/Wuki60/MrCuisineVibeConnect/releases) and installs the new version
  (silently on a rooted robot, then Toque restarts). Refused while a program is running.

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
own. Motor control runs in **its own process**: if the interface crashes, a cooking run is not
cut off and finishes normally.

## Project layout

```
core/   Pure Kotlin logic, tested without Android
        protocol/  serial frames (15 bytes sent, 27 received, 4800 baud)
        machine/   safety rules, controller, 100 ms loop, links (root serial, TCP, demo)
        recipes/   model, stock database import, search
app/    Android app (minSdk 23), no AndroidX
        machine/   motor control service (":machine" process) and IPC
        recipes/   local catalogue, Lidl sync
        ui/        screens
tools/  twin.py (digital twin for testing on a PC), root (TWRP), install and restore scripts
docs/   INSTALLATION.md
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
   features. The recipe screen stays empty: Lidl recipes only exist on the robot.
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

The [release workflow](.github/workflows/release.yml) runs the tests, builds the APK and publishes
the `vX.Y.Z` release with it. Robots then find it in *Settings › Updates*.

All builds are signed with the project key in `app/signature/` (public on purpose), so an APK built
on any PC or by GitHub installs over the previous version. Only install APKs from this repository's
releases.

Without GitHub Actions: run `gradlew assembleRelease`, then create a release named `vX.Y.Z` on GitHub
and attach `app/build/outputs/apk/release/app-release.apk` (renamed `Toque-X.Y.Z.apk`).

## Install on the robot

See **[docs/INSTALLATION.md](docs/INSTALLATION.md)**: root, install, first run with an empty
bowl, recipe sync, going back to the stock app.

## Credits

- Protocol: [Monsieur Cuisine Developer SDK](https://github.com/antrea/monsieur-cuisine-developer-sdk) (MIT).
- Robot access: [Monsieur-Cuisine-Connect-Hack](https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack).

MIT License. This repository contains no Lidl recipes, images or code: recipes are only read on
the user's own robot.
