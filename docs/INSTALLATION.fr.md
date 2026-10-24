# Installer Toque sur le Monsieur Cuisine Connect

🇬🇧 **[English version](INSTALLATION.md)**

Durée : 1 à 2 heures la première fois. Les étapes 1 et 2 (root) sont les plus délicates : une
erreur peut rendre la tablette du robot inutilisable. Lisez tout avant de commencer.

## Ce qu'il faut

- Un PC Windows avec Android Studio (pour ADB) et [SP Flash Tool](https://spflashtool.com/)
  (v5.1924 testée).
- Un câble de données **USB-A mâle / USB-A mâle** et un petit tournevis Torx. Un câble USB-A ↔ USB-C
  ne marche **pas** : le port du robot est un port « appareil », le port USB-C du PC ne le détecte
  jamais.
- Le pilote **MediaTek PreLoader USB VCOM** : le chercher sur le
  [catalogue Microsoft Update](https://www.catalog.update.microsoft.com/), extraire le `.cab` (en
  gardant le dossier `x64`) et l'installer en administrateur : `pnputil /add-driver cdc-acm.inf /install`.
  Sous Windows 11, si Windows affiche « Impossible de charger un pilote sur cet appareil :
  usb2ser.sys », désactiver **l'intégrité de la mémoire** (Sécurité Windows › Sécurité de
  l'appareil › Isolation du noyau) et redémarrer. La réactiver à la fin (retirer d'abord le pilote :
  `pnputil /enum-drivers` pour trouver son `oemXX.inf`, puis `pnputil /delete-driver oemXX.inf /uninstall`).
- Le guide de référence : <https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack>
  (fichier de partition `l706_dfbh_v_695scatter.txt` et recovery TWRP `hacks/recovery.img`).
- L'APK officiel de [Magisk](https://github.com/topjohnwu/Magisk/releases) (v31.0 testée ; Android 6
  est pris en charge).

> **Allumer le robot câble débranché.** Allumé avec le câble branché, il reste bloqué sur un écran
> de batterie (charge). Brancher le câble une fois qu'il a démarré. Seule exception : SP Flash Tool,
> où il faut au contraire allumer le robot *avec* le câble branché.

## 1. Sauvegarde et TWRP

1. Robot éteint (interrupteur arrière). Dévisser la trappe de maintenance sous le robot.
2. Brancher le PC **uniquement sur le port de droite, marqué « Android USB »** (l'autre peut
   détruire la carte).
3. SP Flash Tool, fichier scatter chargé, onglet **Readback** : ajouter `boot` (début `0x1d20000`,
   longueur `0x1000000`, région `EMMC_USER`) et `recovery` (début `0x2d20000`, longueur
   `0x1000000`), cliquer sur **Read Back**, puis allumer le robot. On obtient `boot-original.img`
   et `recovery-original.img` (16 Mo chacun) : **à garder précieusement, c'est votre seule voie
   de retour.**
4. Onglet **Download**, *Download Only* : cocher uniquement `boot`, avec `hacks/recovery.img`
   (TWRP). Cliquer sur **Download**, puis éteindre et rallumer le robot.
5. Éteindre le robot, débrancher le câble, rallumer : **TWRP** démarre. Rebrancher le câble :
   `adb devices` affiche le robot en mode `recovery`.

## 2. ADB et root (Magisk)

1. Depuis le dossier du dépôt, TWRP affiché :
   ```
   adb push Magisk-v31.0.apk /tmp/magisk.apk
   adb push boot-original.img /tmp/boot.img
   adb push %USERPROFILE%\.android\adbkey.pub /tmp/adbkey.pub
   adb push tools\robot\twrp-root.sh /tmp/
   adb shell sh /tmp/twrp-root.sh
   adb pull /tmp/magisk/new-boot.img boot-magisk.img
   ```
   [`twrp-root.sh`](../tools/robot/twrp-root.sh) patche votre `boot` d'origine avec Magisk
   (`boot-magisk.img`), active ADB dans Android (`/system/build.prop`), supprime
   `/system/recovery-from-boot.p` (sinon Android remet le recovery d'origine par-dessus TWRP) et
   autorise la clé ADB de votre PC (aucune confirmation à faire sur l'écran du robot).
2. SP Flash Tool, *Download Only* : cocher `boot` = `boot-magisk.img` et `recovery` =
   `hacks/recovery.img`, cliquer sur **Download**, éteindre et rallumer le robot.
3. Éteindre le robot, débrancher le câble, rallumer : l'appli d'origine démarre. Brancher le câble.
4. `adb install Magisk-v31.0.apk`, puis `adb shell su -c id` et appuyer sur **Autoriser** sur le
   robot : la commande doit afficher `uid=0`. Toque utilise la **busybox** fournie par Magisk
   (`/data/adb/magisk/busybox`).

Pour retirer le root : flasher `boot-original.img` dans `boot` avec SP Flash Tool (et
`recovery-original.img` dans `recovery` pour retirer TWRP).

Facultatif, pour ne plus avoir besoin du câble : activer ADB par Wi-Fi.

```
adb shell su -c "setprop persist.adb.tcp.port 5555"
adb reboot
```

À faire uniquement sur le réseau de la maison : n'importe quel appareil connecté dessus
pourrait alors accéder au robot.

## 3. Installer Toque

1. Télécharger le dernier `Toque-X.Y.Z.apk` sur la
   [page des versions](https://github.com/Wuki60/MrCuisineVibeConnect/releases), ou le compiler dans
   Android Studio (voir README). Avec un APK téléchargé, ajouter `-Apk <chemin de l'APK>` ci-dessous.
2. `powershell -ExecutionPolicy Bypass -File tools\install-robot.ps1`
   (par Wi-Fi : ajouter `-Ip <adresse IP du robot>`).
3. Autoriser Toque dans la fenêtre Magisk qui s'affiche sur le robot (« Accorder »).

## 4. Premier essai — bol VIDE

1. Dans Toque : **Réglages › Machine › Machine réelle › Appliquer**. Toque coupe l'appli
   d'origine du moteur (réversible) et ouvre le port série.
2. La pastille en haut à droite doit passer à **« Prête »**. Sinon : Réglages › Outils ›
   Diagnostic (trames reçues ?) et Journal.
3. Bol vide, couvercle fermé, **main près de l'interrupteur principal** :
   - Cuisson manuelle : 10 s, vitesse 2 → le moteur tourne puis s'arrête seul.
   - Ouvrir le couvercle pendant un essai → arrêt immédiat, « Pause : couvercle ouvert ».
   - 30 s à 37 °C, vitesse 1 → la température affichée monte.
   - Balance : poser un objet de poids connu, Tare.
   - Diagnostic : retirer le bol et regarder « Bol détecté ». S'il change bien, vous pouvez
     activer Réglages › Sécurité › « Exiger la détection du bol ».
4. Au moindre comportement anormal : **interrupteur principal**, puis
   `tools\restore-robot.ps1`.

## 5. En faire l'écran d'accueil

Bouton Accueil → choisir **Toque** → **Toujours**. (Accès caché aux paramètres Android :
5 appuis rapides sur l'heure en haut à droite.)

## 6. Recettes Lidl

**Réglages › Recettes Lidl › Synchroniser**. L'appli d'origine se synchronise avec les serveurs
Lidl en arrière-plan, puis Toque importe ses recettes et photos (quelques minutes). Ensuite, la
synchro se fait chaque nuit à 3 h 30.

Si rien n'arrive : **« Ouvrir l'appli d'origine »**. Toque lui rend le moteur, elle s'ouvre
et se synchronise comme d'habitude. Revenez avec le bouton Accueil : Toque reprend la main et
importe tout.

Si l'import échoue, le fichier `Android/data/app.toque/files/diagnostic-recettes.txt` décrit
la structure de la base d'origine, de quoi adapter l'import.

## Revenir à l'appli d'origine

- Depuis Toque : Réglages › Outils › « Revenir à l'appli d'origine ».
- Depuis le PC : `powershell -ExecutionPolicy Bypass -File tools\restore-robot.ps1 -Uninstall`

Toque ne modifie ni le système ni l'appli d'origine : il désactive seulement son service moteur,
ce que ces deux méthodes annulent.
