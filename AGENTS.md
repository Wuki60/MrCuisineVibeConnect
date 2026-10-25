# AGENTS.md: install Toque on a Monsieur Cuisine Connect

This file is for an AI agent (Claude, ChatGPT, Copilot…) that a person has pointed at this
repository with a request such as *"Install Toque on my Monsieur Cuisine Connect"*. It is the
complete procedure, written so that the agent does everything it can by itself and asks the person
only for what needs hands, eyes or a Windows security prompt.

Humans: the same procedure by hand is in [docs/INSTALLATION.md](docs/INSTALLATION.md).

## What you are doing

The Lidl **Monsieur Cuisine Connect** (model **SKMC 1200**) is a cooking robot whose screen is an
Android 6 tablet (MediaTek MT6580). Toque replaces its stock app. To get there you will:

1. back up two partitions of the tablet (boot, recovery) — the only way back;
2. boot TWRP (a recovery system) and patch the original boot with **Magisk** (root);
3. flash the patched boot, keep TWRP as recovery;
4. install the Toque APK, enable ADB over Wi-Fi.

Flashing happens over USB with **SP Flash Tool** while the robot is switched off (MediaTek BROM
mode). Everything else goes through **adb**. It was done end to end on a real robot in October 2026
with exactly these files; total time 1–2 hours, most of it waiting for the person.

## Rules for the agent

- **Talk to the person in their language**, in short messages. Before starting, tell them what
  they will need (list below), that it voids the warranty, that a mistake can leave the robot's
  tablet unusable, and get a clear *yes*.
- **Never skip the backup step**, never flash anything other than `boot` and `recovery`, never
  use *Format* or *Firmware Upgrade* in SP Flash Tool, only *Download Only*.
- **Never type passwords, never change Windows security settings yourself.** The person turns
  memory integrity off/on and answers User Account Control prompts.
- Download only from the URLs below, and let the script check the SHA-256 of every file. If a
  checksum does not match, stop.
- Ask before each step that writes to the robot (`flash-twrp`, `root`, `flash-root`) — say what it
  does in one sentence and wait for *yes*.
- If the person is using the PC (a game, a call…), ask before taking over the screen.
- When a step prints `STOP:`, do not improvise a workaround on the robot: read the cause, fix it
  on the PC side or ask the person, then run the step again.
- If the robot is not an SKMC 1200 Monsieur Cuisine **Connect** (for example a Monsieur Cuisine
  **Smart**, SKMS 1200), stop: this procedure does not apply.

## What the person needs

- A Windows 10/11 PC with a **USB-A** port, Internet, and the agent able to run PowerShell.
- A **USB-A male ↔ USB-A male data cable**. USB-A ↔ USB-C does **not** work: the robot's port is a
  device port and the PC's USB-C port never sees it.
- A small Torx screwdriver (maintenance cover under the robot).
- The robot within reach of the PC, connected to the mains, and the home Wi-Fi password (for the
  end).

## The tool

Everything is driven by one script, from the repository folder:

```
powershell -ExecutionPolicy Bypass -File tools\setup\toque-setup.ps1 -Step <step>
```

Work folder: `C:\Toque` (change with `-Work`). Every step can be re-run; `-Step status` says what
is done and what comes next. Output lines:

- `OK:` done and verified;
- `HUMAN:` relay this to the person, word for word in their language, and wait until they say it
  is done;
- `STOP:` the step failed safely (exit code 2) — nothing irreversible was done after the failure;
- exit code 3: the step is waiting for a human action; run it again afterwards.

A log is kept in `C:\Toque\setup-log.txt`, state in `C:\Toque\state.json`.

## Procedure

| # | Step | Who | What happens |
|---|------|-----|--------------|
| 0 | clone | agent | Get this repository on the PC (git clone or the ZIP of `main`). |
| 1 | `prepare` | agent | Downloads adb, Magisk v31.0, TWRP and the scatter file, the latest Toque APK; checks SHA-256. |
| 1b | (browser) | agent + person | Two files need a browser, see *Manual downloads*. Then `prepare` again. |
| 2 | `driver` | agent + person | Installs the MediaTek PreLoader USB VCOM driver (UAC prompt). May ask to turn memory integrity off and restart. |
| 3 | `backup` | person, then agent | Cover off, cable in the **right-hand "Android USB" port**, robot off; the script reads `boot` and `recovery`; the person switches the robot on when told. Verifies two 16 MiB Android images. |
| 4 | `flash-twrp` | agent + person | Writes TWRP into `boot` (Download Only). The person power-cycles: off, **unplug**, on, plug back → TWRP. |
| 5 | `root` | agent | In TWRP: patches the *original* boot with Magisk, enables ADB in Android, authorizes the PC's ADB key, pulls `boot-magisk.img`. |
| 6 | `flash-root` | agent + person | Writes `boot-magisk.img` into `boot` and TWRP into `recovery`. Power cycle as above → stock app. |
| 7 | `android` | agent + person | Checks Magisk, installs its app; the person taps **Grant** on the robot once. Checks the boot partition. |
| 8 | `install` | agent | Installs Toque, allows it in Magisk, starts it. |
| 9 | first run | person | Empty bowl: Settings › Machine › Real machine › Apply, badge **Ready**, manual cooking 10 s speed 2. |
| 10 | `wifi` | agent | ADB over Wi-Fi (port 5555), so the cable can go and the cover go back on. |
| 11 | `cleanup` | agent + person | Removes the MediaTek driver; the person can turn memory integrity back on. |

Then: Toque › Settings › Lidl recipes › **Sync** (about 15 minutes the first time).

### Power-on rules (the source of most confusion)

- **SP Flash Tool steps** (`backup`, `flash-twrp`, `flash-root`): robot **off**, cable **plugged
  in**, start the step, then switch the robot **on**. The tool catches it before Android starts.
- **adb steps** (`root`, `android`, `install`, `wifi`): switch the robot on with the cable
  **unplugged**, plug it in once the screen is up. Switched on with the cable in, the robot stops on
  a **battery / charging screen**: switch off, unplug, switch on, plug.
- Only the right-hand port, labelled **"Android USB"**, ever. The other port can destroy the board.

### Manual downloads (need a browser)

1. **MediaTek PreLoader USB VCOM driver** — Microsoft Update Catalog:
   `https://www.catalog.update.microsoft.com/Search.aspx?q=MediaTek%20PreLoader%20USB%20VCOM`.
   Pick the update whose download is named
   `20896845_fdc6bb5aa9a9bac99adf85d931d6c21d1130a96e.cab` (the hex part is its SHA-1; SHA-256
   `ce72e97c07582ad41debfaeee6f6284ecd28285df5f9caa05193653fb236561f`). Save it in
   `C:\Toque\downloads\`.
2. **SP Flash Tool v5.1924 for Windows** — `https://spflashtool.com/download`, file
   `SP_Flash_Tool_v5.1924_Win.zip`. Extract it into `C:\Toque\SP_Flash_Tool\`. The script checks
   `flash_tool.exe` (SHA-256 `67e9eb48161087d43eca845b2dc0e9beacac3c0381bd1ce7989d48b17e2084a7`) and
   `MTK_AllInOne_DA.bin` (`9cbf39f3925fe88d18bd4c4e45dd1c01741bf7a31a4f69252dfb318d229c0346`).

Ask the person before downloading (state the file names, sources and sizes: ~130 KB and ~40 MB).

### Files the script downloads by itself

| File | Source | SHA-256 |
|---|---|---|
| Android platform tools (adb) | `https://dl.google.com/android/repository/platform-tools-latest-windows.zip` | (latest, from Google) |
| Magisk v31.0 | `https://github.com/topjohnwu/Magisk/releases/download/v31.0/Magisk-v31.0.apk` | `2c8a488b9a5293e578e95ae4f07e3c57aba4feec4a52ca4dd852a2692d6dd4e8` |
| TWRP `hacks/recovery.img` | [EliasKotlyar/Monsieur-Cuisine-Connect-Hack](https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack) | `fc9ac999fc3b0a6c0cb563f39359084a6fcc211aeb42e7b754990afa4b543d3e` |
| Scatter `l706_dfbh_v_695scatter.txt` | same repository | `13b8260dba9e4efba874c09e35803af43cc69556ba0f1937226240b0ab318364` |
| Toque `Toque-X.Y.Z.apk` | [latest release](https://github.com/Wuki60/MrCuisineVibeConnect/releases/latest) of this repository | (signed with the project key) |

### SP Flash Tool: console mode, and the GUI fallback

The script runs SP Flash Tool in console mode (`flash_tool.exe -i <config>.xml`, configs written to
`C:\Toque\spft-*.xml`). The reference run in October 2026 used the **SP Flash Tool window** for
these three operations; the console configuration is generated from the settings saved by that run
but has not itself been used on a robot yet. If a console step fails (`STOP:`), do the same
operation in the window — with a computer-use tool, or by guiding the person:

**GUI fallback: readback** (step `backup`)
1. Start `C:\Toque\SP_Flash_Tool\flash_tool.exe`. Tab **Download** › *Scatter-loading File* ›
   choose `C:\Toque\hack\l706_dfbh_v_695scatter.txt`.
2. Tab **Readback** › **Add** twice. Double-click each row and set: *Region* `EMMC_USER`,
   *Start Address* `0x1d20000`, *Length* `0x1000000`, file `C:\Toque\backup\boot-original.img`; then
   `0x2d20000`, `0x1000000`, `C:\Toque\backup\recovery-original.img`.
3. Robot off, cable in. Click **Read Back**, then switch the robot on. Wait for the green tick.
4. Close SP Flash Tool and run `-Step backup` again: it verifies the two files.

**GUI fallback: Download Only** (steps `flash-twrp`, `flash-root`)
1. Tab **Download**, scatter file loaded as above, top drop-down **Download Only**.
2. Untick every row, then tick only:
   - `flash-twrp`: **boot** = `C:\Toque\hack\recovery.img`;
   - `flash-root`: **boot** = `C:\Toque\boot-magisk.img` and **recovery** = `C:\Toque\hack\recovery.img`.
   Double-click the *Location* cell to choose the file. Check that no other row is ticked.
3. Robot off, cable in. Click **Download**, then switch the robot on. Wait for the green tick.
4. Continue with the next step (`root` after `flash-twrp`, `android` after `flash-root`). Those
   steps check the robot itself, so they work the same after a GUI flash.

## Known problems and fixes

| Symptom | Cause | Fix |
|---|---|---|
| SP Flash Tool waits forever, nothing in Device Manager | A↔C cable, wrong port, or USB-C port on the PC | A↔A cable, right-hand "Android USB" port, a USB-A port on the PC |
| Windows: *"A driver can't load on this device: usb2ser.sys"* | Memory integrity on | Person turns it off (Windows Security › Device security › Core isolation), restarts; `driver` again |
| Robot shows a battery picture instead of booting | Switched on with the cable plugged in | Off, unplug, on, plug in after start-up |
| `adb` shows nothing in TWRP | Cable plugged before TWRP was up, or driver missing | Unplug/replug; `status` |
| `root` fails during the Magisk patch | Not the original boot, or a corrupt backup | Nothing was flashed. Check `C:\Toque\backup`, redo `backup` |
| `android`: `su` returns nothing | The Magisk prompt was missed (it closes after 10 s and then refuses) | Person taps **Grant** quickly; if refused for good: Magisk app › Superuser › Shell › allow |
| Toque shows "Base absente" / no motor link | Magisk busybox missing | `install` again (it runs `tools/robot/setup-root.sh`) |
| Stuck in the stock app (no Home/Back buttons on this robot) | Normal | Floating **Back to Toque** button, or `adb shell am start -n app.toque/.ui.MainActivity` |
| `Toque s'est vu refuser les droits` toasts | Magisk policy | `install` again (it allows Toque in Magisk's database) |

## Undo

- Back to the stock app, keeping root: Toque › Settings › Tools › *Back to the stock app*, or
  `powershell -ExecutionPolicy Bypass -File tools\restore-robot.ps1 -Uninstall`.
- Remove root and TWRP: SP Flash Tool *Download Only* with **boot** =
  `C:\Toque\backup\boot-original.img` and **recovery** = `C:\Toque\backup\recovery-original.img`.

## After the install

- Updates: Toque › Settings › Updates installs new releases from this repository.
- Recipes: Settings › Lidl recipes › Sync (monsieur-cuisine.com, no account). Nightly re-sync.
- Clock: set automatically (time zone from the Internet connection); Settings › Date and time.
- To work on the robot later over Wi-Fi: `adb connect <robot IP>:5555`.

## Working on the code

If you were asked to change Toque rather than install it: `core/` is pure Kotlin (no Android) and
holds the protocol, the safety rules, the controller and the recipe parsing; `app/` is the Android
app (minSdk 23, no AndroidX, views built in code). Run `gradlew :core:test` (unit tests, including a
check that every interface text has an English translation in `app/src/main/assets/lang/en.json`)
and `gradlew :app:assembleRelease`. Interface texts are written in French and wrapped in `tr(...)`.
Motor safety rules live in `core/.../machine/Safety.kt` and are enforced by `MachineController`:
keep them strict, and add a test in `ControllerTest` for any behaviour change.
