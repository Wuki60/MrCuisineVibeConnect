# Installing Toque on the Monsieur Cuisine Connect

🇫🇷 **[Version française](INSTALLATION.fr.md)**

Time: 1 to 2 hours the first time. Steps 1 and 2 (root) are the tricky ones: a mistake can leave
the robot's tablet unusable. Read everything before you start.

Menu names below are those of the English interface (*Settings › Langue · Language › English*).

## What you need

- A Windows PC with Android Studio (for ADB) and [SP Flash Tool](https://spflashtool.com/)
  (v5.1924 tested).
- A **USB-A male / USB-A male** data cable and a small Torx screwdriver. A USB-A ↔ USB-C cable
  does **not** work: the robot's port is a device port, so the PC's USB-C port never detects it.
- The **MediaTek PreLoader USB VCOM** driver: search for it on the
  [Microsoft Update Catalog](https://www.catalog.update.microsoft.com/), extract the `.cab` (keep the
  `x64` folder) and install it as administrator: `pnputil /add-driver cdc-acm.inf /install`.
  On Windows 11, if Windows says *"A driver can't load on this device: usb2ser.sys"*, turn off
  **Memory integrity** (Windows Security › Device security › Core isolation) and restart. Turn it
  back on when you are done (remove the driver first: `pnputil /enum-drivers` to find its
  `oemXX.inf`, then `pnputil /delete-driver oemXX.inf /uninstall`).
- The reference guide: <https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack>
  (partition file `l706_dfbh_v_695scatter.txt` and TWRP recovery `hacks/recovery.img`).
- The official [Magisk](https://github.com/topjohnwu/Magisk/releases) APK (v31.0 tested; Android 6
  is supported).

> **Switch the robot on with the cable unplugged.** If it is switched on with the cable plugged in,
> it stops on a battery (charging) screen. Plug the cable in once it has started. SP Flash Tool is
> the exception: there, the robot must be switched on *with* the cable plugged in.

## 1. Backup and TWRP

1. Robot off (rear switch). Unscrew the maintenance cover under the robot.
2. Connect the PC **only to the right-hand port, labelled "Android USB"** (the other one can
   destroy the board).
3. SP Flash Tool, scatter file loaded, **Readback** tab: add `boot` (start `0x1d20000`, length
   `0x1000000`, region `EMMC_USER`) and `recovery` (start `0x2d20000`, length `0x1000000`), click
   **Read Back**, then switch the robot on. You get `boot-original.img` and
   `recovery-original.img` (16 MB each): **keep them safe, they are your only way back.**
4. **Download** tab, *Download Only*: tick only `boot`, with `hacks/recovery.img` (TWRP). Click
   **Download**, then switch the robot off and on again.
5. Switch the robot off, unplug the cable, switch it on: **TWRP** starts. Plug the cable back in:
   `adb devices` shows the robot in `recovery` mode.

## 2. ADB and root (Magisk)

1. From the repository folder, with TWRP running:
   ```
   adb push Magisk-v31.0.apk /tmp/magisk.apk
   adb push boot-original.img /tmp/boot.img
   adb push %USERPROFILE%\.android\adbkey.pub /tmp/adbkey.pub
   adb push tools\robot\twrp-root.sh /tmp/
   adb shell sh /tmp/twrp-root.sh
   adb pull /tmp/magisk/new-boot.img boot-magisk.img
   ```
   [`twrp-root.sh`](../tools/robot/twrp-root.sh) patches your original `boot` with Magisk
   (`boot-magisk.img`), enables ADB in Android (`/system/build.prop`), deletes
   `/system/recovery-from-boot.p` (otherwise Android puts the stock recovery back over TWRP) and
   authorizes your PC's ADB key (no prompt on the robot's screen).
2. SP Flash Tool, *Download Only*: tick `boot` = `boot-magisk.img` and `recovery` =
   `hacks/recovery.img`, click **Download**, switch the robot off and on again.
3. Switch the robot off, unplug the cable, switch it on: the stock app starts. Plug the cable in.
4. `adb install Magisk-v31.0.apk`, then `adb shell su -c id` and tap **Grant** on the robot: it
   must print `uid=0`. Toque uses the **busybox** shipped with Magisk (`/data/adb/magisk/busybox`).

To undo the root: flash `boot-original.img` into `boot` with SP Flash Tool (and
`recovery-original.img` into `recovery` to remove TWRP).

Optional, so you no longer need the cable: enable ADB over Wi-Fi.

```
adb shell su -c "setprop persist.adb.tcp.port 5555"
adb reboot
```

Only do this on your home network: anyone on it could then connect to the robot.

## 3. Install Toque

1. Download the latest `Toque-X.Y.Z.apk` from the
   [Releases page](https://github.com/Wuki60/MrCuisineVibeConnect/releases), or build it in
   Android Studio (see the README). With a downloaded APK, add `-Apk <path to the APK>` below.
2. `powershell -ExecutionPolicy Bypass -File tools\install-robot.ps1`
   (over Wi-Fi: add `-Ip <robot IP address>`).
3. The script also allows Toque in Magisk (no prompt on the robot: Magisk's prompt closes by
   itself after 10 s and then refuses for good) and checks that Magisk's busybox, which Toque
   needs for the serial port, is in place.

## 4. First run — EMPTY bowl

1. In Toque: **Settings › Machine › Real machine › Apply**. Toque turns off the stock app's motor service (reversible) and opens the
   serial port.
2. The badge at the top right must switch to **Ready**. If not: Settings › Tools › Motor base
   diagnostics (are frames received?) and Log.
3. Empty bowl, lid closed, **hand near the main switch**:
   - Manual cooking: 10 s, speed 2 → the motor runs, then stops on its own.
   - Open the lid during a run → immediate stop, "Paused: lid open".
   - 30 s at 37 °C, speed 1 → the displayed temperature rises.
   - Scale: put an object of known weight on it, Tare.
   - Diagnostics: remove the bowl and watch "Bowl detected". If it changes correctly, you can
     enable Settings › Safety › Require bowl detection.
4. At the slightest abnormal behaviour: **main switch**, then `tools\restore-robot.ps1`.

## 5. Toque at startup

The robot has no Android Home or Back button. Toque opens by itself when the robot starts, and
whenever it opens another app (the stock app, the Android settings), a **Back to Toque** button
stays on top of it (you can drag it). Hidden access to the Android settings: tap the clock at the
top right 5 times quickly.

To open Toque from the PC: `adb shell am start -n app.toque/.ui.MainActivity`.

## 6. Lidl recipes

**Settings › Lidl recipes › Sync** (the robot needs Wi-Fi). Toque downloads the official
Monsieur Cuisine connect recipes from monsieur-cuisine.com, then their photos: about 15 minutes the
first time, then only new or changed recipes. After that, the sync runs every night at 3:30 am.
Recipes are in the interface language (French by default).

Other source: **Import from the stock app** copies the recipes already in the stock app (they
replace the website's). If the stock app has none yet, **Open the stock app** lets it sync with
Lidl's servers; come back with the **Back to Toque** button. If that import fails, the file
`Android/data/app.toque/files/diagnostic-recettes.txt` describes the stock database.

## Going back to the stock app

- From Toque: Settings › Tools › Back to the stock app.
- From the PC: `powershell -ExecutionPolicy Bypass -File tools\restore-robot.ps1 -Uninstall`

Toque changes neither the system nor the stock app: it only disables its motor service, which
both methods undo. Toque then no longer opens at startup.
