# Installing Toque on the Monsieur Cuisine Connect

*[Version française](INSTALLATION.fr.md)*

Time: 1 to 2 hours the first time. Steps 1 and 2 (root) are the tricky ones: a mistake can leave
the robot's tablet unusable. Read everything before you start.

The app's interface is in French; menu names are given below in French with a translation.

## What you need

- A Windows PC with Android Studio (for ADB) and [SP Flash Tool](https://spflashtool.com/).
- A **USB-A male / USB-A male** cable (+ extension cable), a small Torx screwdriver.
  If your PC has a USB-C port, a USB-C ↔ USB-A data cable also works.
- The reference guide: <https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack>
  (partition file `l706_dfbh_v_695scatter.txt` and TWRP recovery `hacks/recovery.img`).

## 1. ADB access and TWRP (EliasKotlyar guide)

1. Robot off (rear switch). Unscrew the maintenance cover under the robot.
2. Connect the PC **only to the right-hand port, labelled "Android USB"** (the other one can
   destroy the board).
3. With SP Flash Tool and the scatter file, **back up the `boot` partition**
   (address `0x1d20000`, length `0x1000000`) **and keep it safe**.
   *Do not skip this step: it is your only way back.*
4. Flash `hacks/recovery.img` into `boot` and reboot: TWRP starts.
5. In `adb shell`: mount `/system`, add to `/system/build.prop`
   `persist.service.adb.enable=1`, `persist.service.debuggable=1`, `persist.sys.usb.config=mtp,adb`,
   then delete `/system/recovery-from-boot.p`.
6. Put your original `boot` back into `boot` and `recovery.img` into `recovery` (from TWRP).
7. Reboot: the stock app starts and `adb devices` sees the robot.

## 2. Root (Magisk / Kitsune Mask)

Install Kitsune Mask (a Magisk fork that works on Android 6) following
[issue #38](https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack/issues/38) of the same
repository. Check: `adb shell su -c id` must print `uid=0`. Toque needs the **busybox** shipped
with Magisk (`/data/adb/magisk/busybox`).

Optional, so you no longer need the cable: enable ADB over Wi-Fi.

```
adb shell su -c "setprop persist.adb.tcp.port 5555"
adb reboot
```

Only do this on your home network: anyone on it could then connect to the robot.

## 3. Install Toque

1. Build the APK in Android Studio (see the README).
2. `powershell -ExecutionPolicy Bypass -File outils\installer-robot.ps1`
   (over Wi-Fi: add `-Ip <robot IP address>`).
3. Allow Toque in the Magisk prompt shown on the robot ("Grant").

## 4. First run — EMPTY bowl

1. In Toque: **Réglages › Machine › Machine réelle › Appliquer** (Settings › Machine › Real
   machine › Apply). Toque turns off the stock app's motor service (reversible) and opens the
   serial port.
2. The badge at the top right must switch to **« Prête »** (Ready). If not: Réglages › Outils ›
   Diagnostic (Settings › Tools › Diagnostics: are frames received?) and Journal (Log).
3. Empty bowl, lid closed, **hand near the main switch**:
   - Manual cooking: 10 s, speed 2 → the motor runs, then stops on its own.
   - Open the lid during a run → immediate stop, « Pause : couvercle ouvert » (lid open).
   - 30 s at 37 °C, speed 1 → the displayed temperature rises.
   - Scale: put an object of known weight on it, Tare.
   - Diagnostics: remove the bowl and watch « Bol détecté » (bowl detected). If it changes
     correctly, you can enable Réglages › Sécurité › « Exiger la détection du bol »
     (Settings › Safety › Require bowl detection).
4. At the slightest abnormal behaviour: **main switch**, then `outils\restaurer-robot.ps1`.

## 5. Make it the home screen

Home button → choose **Toque** → **Always**. (Hidden access to the Android settings: tap the
clock at the top right 5 times quickly.)

## 6. Lidl recipes

**Réglages › Recettes Lidl › Synchroniser** (Settings › Lidl recipes › Sync). The stock app syncs
with Lidl's servers in the background, then Toque imports its recipes and photos (a few
minutes). After that, the sync runs every night at 3:30 am.

If nothing arrives: **« Ouvrir l'appli d'origine »** (Open the stock app). Toque hands the motor
back to it, it opens and syncs as usual. Come back with the Home button: Toque takes over again
and imports everything.

If the import fails, the file `Android/data/app.toque/files/diagnostic-recettes.txt` describes the
structure of the stock database, which is what's needed to adapt the import.

## Going back to the stock app

- From Toque: Réglages › Outils › « Revenir à l'appli d'origine » (Settings › Tools › Back to
  the stock app).
- From the PC: `powershell -ExecutionPolicy Bypass -File outils\restaurer-robot.ps1 -Desinstaller`

Toque changes neither the system nor the stock app: it only disables its motor service, which
both methods undo.
