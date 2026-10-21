# Installer Toque sur le Monsieur Cuisine Connect

Durée : 1 à 2 heures la première fois. Les étapes 1 et 2 (root) sont les plus délicates : une
erreur peut rendre la tablette du robot inutilisable. Lisez tout avant de commencer.

## Ce qu'il faut

- Un PC Windows avec Android Studio (pour ADB) et [SP Flash Tool](https://spflashtool.com/).
- Un câble **USB-A mâle / USB-A mâle** (+ rallonge), un petit tournevis Torx.
- Le guide de référence : <https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack>
  (fichier de partition `l706_dfbh_v_695scatter.txt` et recovery TWRP `hacks/recovery.img`).

## 1. Accès ADB et TWRP (guide EliasKotlyar)

1. Robot éteint (interrupteur arrière). Dévisser la trappe de maintenance sous le robot.
2. Brancher le PC **uniquement sur le port marqué « Android USB »** (l'autre peut détruire la carte).
3. SP Flash Tool avec le fichier scatter : **sauvegarder la partition `boot`**
   (adresse `0x1d20000`, longueur `0x1000000`) **et la garder précieusement**.
   *Ne sautez pas cette étape : c'est votre seule voie de retour.*
4. Flasher `hacks/recovery.img` dans `boot`, redémarrer : TWRP démarre.
5. Dans `adb shell` : monter `/system`, ajouter à `/system/build.prop`
   `persist.service.adb.enable=1`, `persist.service.debuggable=1`, `persist.sys.usb.config=mtp,adb`,
   puis supprimer `/system/recovery-from-boot.p`.
6. Remettre votre `boot` d'origine dans `boot` et `recovery.img` dans `recovery` (depuis TWRP).
7. Redémarrer : l'appli d'origine démarre, `adb devices` voit le robot.

## 2. Root (Magisk / Kitsune Mask)

Installer Kitsune Mask (fork de Magisk compatible Android 6) en suivant
l'[issue #38](https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack/issues/38) du même dépôt.
Vérifier : `adb shell su -c id` doit afficher `uid=0`. Toque a besoin de la **busybox** fournie par
Magisk (`/data/adb/magisk/busybox`).

## 3. Installer Toque

1. Compiler l'APK dans Android Studio (voir README).
2. `powershell -ExecutionPolicy Bypass -File outils\installer-robot.ps1`
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
   `outils\restaurer-robot.ps1`.

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
- Depuis le PC : `powershell -ExecutionPolicy Bypass -File outils\restaurer-robot.ps1 -Desinstaller`

Toque ne modifie ni le système ni l'appli d'origine : il désactive seulement son service moteur,
ce que ces deux méthodes annulent.
