# MrCuisineVibeConnect

🇬🇧 **[English version](README.md)**

**Toque**, une interface moderne et rapide pour le robot **Monsieur Cuisine Connect** (SKMC 1200),
qui remplace l'appli d'origine sur la tablette du robot tout en gardant **les recettes Lidl**.

> Projet indépendant, non affilié à Lidl, SilverCrest ni Hoyer. Le pilotage du moteur repose
> sur une analyse publique du protocole. Depuis octobre 2026, Toque tourne sur des SKMC 1200
> (révisions A1, B2 et C3) en France et en Espagne, et sur un SKMC 1100 A1 (avec un recovery
> adapté, voir #10). À utiliser à vos risques, en suivant la procédure de premier essai.
>
> L'interface existe en **français**, **anglais**, **allemand** et **espagnol** (*Réglages › Langue · Language*).

## Installer avec un agent IA

Le plus simple : confiez l'installation à un agent IA qui peut piloter votre PC Windows (Claude,
ChatGPT, Copilot…). Donnez-lui ce message :

> Installe Toque sur mon Monsieur Cuisine Connect en suivant le fichier AGENTS.md de
> https://github.com/barjola/MrCuisineVibeConnectES

Le fichier [AGENTS.md](AGENTS.md) lui décrit toute la procédure, et le script
[`tools/setup/toque-setup.ps1`](tools/setup/toque-setup.ps1) fait le travail étape par étape
(téléchargements vérifiés, pilote, sauvegarde, root, installation, ADB par Wi-Fi). L'agent ne vous
demande que ce qu'il ne peut pas faire lui-même :

- avoir un câble **USB-A mâle ↔ USB-A mâle** et un petit tournevis Torx ;
- dévisser la trappe sous le robot, brancher le câble sur le port **« Android USB »**, éteindre et
  rallumer le robot quand il le dit ;
- répondre aux fenêtres de sécurité de Windows (et, si besoin, désactiver l'intégrité de la mémoire
  le temps de l'installation) ;
- appuyer une fois sur **Autoriser** sur l'écran du robot.

Comptez 1 à 2 heures. Sans agent : [docs/INSTALLATION.fr.md](docs/INSTALLATION.fr.md).

## Ce que fait Toque

- **Interface sombre, bleu nuit**, pensée pour l'écran 7" et le bouton rotatif du robot, fluide sur sa
  tablette d'origine (1 Go de RAM, Android 6) : pas de bibliothèque lourde, images
  sous-échantillonnées, vues recyclées.
- **Recettes Lidl, directement depuis monsieur-cuisine.com**. Toque télécharge les recettes
  officielles du Monsieur Cuisine connect (environ 1 700 en français, avec les réglages machine de
  chaque étape) par l'API publique du site, chaque nuit ou sur demande : aucun compte, aucun
  serveur à gérer, plus besoin de l'appli d'origine. Requêtes espacées, et seules les recettes
  nouvelles ou modifiées sont retéléchargées. On peut toujours importer à la place celles de
  l'appli d'origine.
- **Auteur, notes et avis** : chaque recette affiche qui l'a publiée et sa note sur le site, et la
  fiche charge les commentaires des utilisateurs (avec les réponses de Monsieur Cuisine).
- **Recherche qui marche** : insensible aux accents, aux pluriels et aux fautes de frappe
  (« bechamle », « risoto »). Recherche par ingrédients (« poulet, curry »), filtres, favoris.
  Listes **Récentes** (dernières recettes cuisinées) et **Fréquentes** (les plus cuisinées), aussi
  accessibles d'un appui sous la barre de recherche de l'accueil.
- **Cuisine pas à pas** : chaque étape se lance d'un appui, l'étape suivante s'affiche toute
  seule, pesée intégrée, reprise après coupure. Les réglages qui se suivent sans geste à faire
  entre eux (rinçage, mixages en plusieurs vitesses…) partent ensemble d'un seul appui : la lame
  passe directement d'une vitesse et d'une température à l'autre, sans s'arrêter. Les étapes du
  site en **montée progressive de vitesse** (« ramp ») deviennent une étape machine par palier.
  Une recette en cours s'affiche sur l'accueil et peut y être **abandonnée** (✕), sauf pendant un
  programme.
- **Notes et quantités personnelles** : une note libre sur chaque recette, et un appui sur la
  quantité d'un ingrédient la change (1000 g d'eau → 800 g) ; les pesées et les mentions des étapes
  qui l'utilisent suivent en proportion (pavé numérique, fractions comprises). Favoris, notes et quantités corrigées suivent la recette
  quand une synchronisation la remplace par la même recette de l'autre source (site ou appli
  d'origine).
- **Pour plus ou moins de personnes** : − / + au-dessus des ingrédients, de la moitié au double de la
  recette. Tout ce qui se pèse ou se mesure suit (liste, pesées, « 500 g d'eau » dans les étapes),
  arrondi comme on l'écrirait à la main, avec le même arrondi partout ; œufs et cuillerées sont
  arrondis au demi. Temps, vitesses et températures restent ceux de la recette testée, avec un rappel
  de surveiller la cuisson, et une alerte quand le bol risque d'être trop plein (2,5 L de liquide au
  maximum).
- **Mes recettes** : écrivez les vôtres (titre, personnes, durée, une ligne par ingrédient, étapes
  avec leur réglage du robot et leur pesée, choisis avec les mêmes limites de sécurité que la cuisson
  manuelle), ou faites **votre version** d'une recette Lidl avec le crayon. Les synchronisations n'y
  touchent jamais. Réglages › Mes recettes les exporte vers `Download/toque-mes-recettes.json`, ou
  les importe (pour les sauvegarder ou les passer à un autre robot).
- **Recettes des autres langues** (option dans Réglages › Recettes Lidl) : les recettes absentes
  dans votre langue sont ajoutées en anglais, français, allemand, espagnol…, une seule fois
  chacune, avec leur langue sur la vignette.
- **Sons** : une cloche sonne quand la quantité à peser est atteinte (son au choix dans les
  Réglages), et une mélodie forte retentit à la fin du dernier programme de la recette. Un réglage
  vérifié (« J'ai vérifié ce réglage ») est mémorisé pour les prochaines fois.
- **Cuisson manuelle**, programmes **Pétrir / Vapeur / Rissoler / Nettoyage**, **Turbo**, **balance**.
  Nettoyage : 1 L d'eau chaude et une goutte de liquide vaisselle, vitesse de 4 à 10, sans chauffe.
- **Hors ligne** : tout fonctionne sans réseau (plus de « serveur injoignable » au démarrage).
- **Heure toujours juste** : le fuseau horaire est trouvé d'après la connexion Internet et l'heure
  vient du réseau (le robot n'a pas de pile d'horloge). Juste après l'allumage, l'horloge est réglée
  d'après le premier serveur qui répond : l'heure est juste en quelques secondes au lieu de rester
  sur 01:00. Réglable à la main dans Réglages › Date et heure.
- **Mise en veille**, comme l'appli d'origine : après quelques minutes sans toucher l'écran ni le
  bouton rotatif (de 2 à 30 min, ou jamais, dans Réglages › Écran), un écran noir assombri affiche
  l'heure, et la température du bol quand il est chaud. Jamais pendant un programme ni sur la
  balance. Le geste qui réveille ne fait rien d'autre. Pendant la veille, l'anneau de la molette
  reste normal, passe en veilleuse blanche très faible ou s'éteint (Réglages › Écran).
- **Capteur du bol inversé** : sur certains robots (vu sur un SKMC 1200 C3), la base signale le bol
  présent quand il est retiré ; Réglages › Sécurité › Inverser le capteur du bol corrige cela.
- **Pensé pour un robot sans boutons Android** : Toque s'ouvre au démarrage, et un bouton
  **« Retour à Toque »** reste affiché par-dessus l'appli d'origine quand on l'ouvre.
- **Mises à jour depuis GitHub** : Réglages › Mises à jour vérifie les
  [versions publiées](https://github.com/barjola/MrCuisineVibeConnectES/releases) de ce dépôt et installe
  la nouvelle (sans rien demander sur un robot rooté, puis Toque redémarre). Refusé pendant un programme.
  Les certificats racines récents absents d'Android 6 (ISRG Root X1/X2 pour le serveur de
  téléchargement de GitHub, Sectigo, Google) sont embarqués : les téléchargements HTTPS marchent sur le robot.
  Quand une nouvelle version sort, Toque le signale au démarrage ou en sortant de veille (au plus
  toutes les quelques heures, jamais pendant un programme) ; « Plus tard » attend le lendemain.

## Sécurité : ce qui change par rapport à l'appli d'origine

Toutes les limites d'origine sont reprises (vitesse 3 maximum en chauffe et en sens inverse,
durées maximales, conditions du Turbo…). Plusieurs défauts relevés par l'analyse du protocole
sont corrigés :

| Défaut de l'appli d'origine | Toque |
|---|---|
| Le démarrage était demandé avant de vérifier le couvercle | Couvercle, erreurs et fraîcheur des mesures sont vérifiés **avant** tout démarrage |
| Aucune détection d'une base moteur muette | Plus de réponse pendant 1,5 s : tout s'arrête et une alerte s'affiche |
| Erreurs d'écriture série ignorées | Arrêt et alerte |
| Un STOP suivi d'un START pouvait ne jamais émettre d'arrêt | Tout redémarrage ou changement de sens attend un échantillon confirmant **moteur arrêté** |
| Lecture série sans resynchronisation | Trames fragmentées, collées ou corrompues gérées |
| La lame tournait parfois en vapeur | Vapeur à vitesse 0 |

Autres ajouts : confirmation (et montée progressive) avant de mixer un contenu à 60 °C ou plus,
limite de vitesse avec le fouet, alertes verrouillées jusqu'à acquittement, rien ne redémarre
tout seul après une pause ou une alerte. À froid, la lame va directement à la vitesse demandée. Le pilotage tourne dans **son propre processus** : un plantage de l'interface ne coupe
pas une cuisson, qui se termine normalement.

## Structure

```
core/   Logique pure Kotlin, testée sans Android
        protocol/  trames série (15 octets émis, 27 reçus, 4800 bauds)
        machine/   règles de sécurité, contrôleur, boucle 100 ms, liaisons (série root, TCP, démo)
        recipes/   modèle, import de la base d'origine, recherche
app/    Appli Android (minSdk 23), sans AndroidX
        machine/   service de pilotage (processus « :machine ») et IPC
        recipes/   catalogue local, synchro Lidl
        ui/        écrans
tools/  setup/toque-setup.ps1 (installation complète, pilotable par un agent IA), scripts de root (TWRP),
        d'installation, de restauration et de publication, twin.py (jumeau numérique pour tester sur PC)
docs/   INSTALLATION.md (EN), INSTALLATION.fr.md (FR)
AGENTS.md  procédure d'installation pour un agent IA
```

## Compiler

Ouvrir le dossier dans **Android Studio**, attendre la synchronisation Gradle, puis
**Build › Build App Bundle(s) / APK(s) › Build APK(s)**. L'APK est dans
`app/build/outputs/apk/debug/`.

En ligne de commande : `gradlew assembleDebug` (et `gradlew :core:test` pour les tests).

## Tester sans le robot

1. **Mode Démo** (par défaut) : installer l'APK sur n'importe quel Android (ou l'émulateur
   d'Android Studio, en paysage). La base moteur est simulée ; Réglages › Outils › Diagnostic
   permet d'ouvrir le couvercle ou de provoquer une erreur pour voir les sécurités.
   Réglages › Recettes Lidl › Synchroniser télécharge les recettes sur n'importe quel appareil.
2. **Jumeau numérique** : le jumeau du [Monsieur Cuisine Developer SDK](https://github.com/antrea/monsieur-cuisine-developer-sdk)
   reproduit le protocole réel indépendamment de Toque.
   `python tools/twin.py --sdk <dossier du SDK>` puis, dans Toque, Réglages › Machine ›
   Jumeau (émulateur : hôte `10.0.2.2` ; appareil réel : `adb reverse tcp:8765 tcp:8765` et
   hôte `127.0.0.1`). `python tools/twin.py --send "lid 1"` ouvre le couvercle, etc.

## Ajouter une langue

L'appli est écrite en français ; chaque autre langue est un fichier dans
[`app/src/main/assets/lang/`](app/src/main/assets/lang) (`en.json` pour l'anglais).

1. Copier `en.json` en `<code>.json` (par exemple `de.json`), mettre `"_name"` (par exemple `"Deutsch"`)
   et traduire les valeurs. Les clés sont les textes français ; garder les `%s` / `%d`.
   Les textes manquants restent en français.
2. Recompiler l'appli, ou, sans recompiler, copier le fichier dans `Android/data/app.toque/files/lang/`
   sur l'appareil puis relancer Toque.

La langue apparaît alors dans Réglages › Langue · Language. `gradlew :core:test` vérifie chaque
fichier de langue.

## Publier une nouvelle version

1. Augmenter `versionCode` et `versionName` dans `app/build.gradle.kts`.
2. Committer et pousser sur `main`.

Le [workflow de publication](.github/workflows/release.yml) lance les tests, compile l'APK et publie la
version `vX.Y.Z` avec. Les robots la trouvent ensuite dans Réglages › Mises à jour.

Toutes les compilations sont signées avec la clé du projet (`app/signature/`, publique exprès) : un APK
compilé sur n'importe quel PC ou par GitHub s'installe par-dessus la version précédente. N'installez que
les APK publiés sur ce dépôt.

Sans GitHub Actions : `gradlew assembleRelease`, puis
`powershell -ExecutionPolicy Bypass -File tools\publish-release.ps1 -Apk app\build\outputs\apk\release\app-release.apk`
crée la version `vX.Y.Z` et y joint l'APK (jeton GitHub : `GH_TOKEN`, `gh`, ou les identifiants déjà
enregistrés pour git).

## Installer sur le robot

Avec un agent IA : **[AGENTS.md](AGENTS.md)** (voir plus haut). À la main :
**[docs/INSTALLATION.fr.md](docs/INSTALLATION.fr.md)** : root, installation, premier essai à vide,
synchronisation des recettes, retour à l'appli d'origine.

## Crédits

- Protocole : [Monsieur Cuisine Developer SDK](https://github.com/antrea/monsieur-cuisine-developer-sdk) (MIT).
- Accès au robot : [Monsieur-Cuisine-Connect-Hack](https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack).
- Contributions : @pierok13 (veille, notes et quantités, paliers, sons, récentes, certificats Android 6,
  capteur du bol), @lucasbraud, @OUTSIDE2k8 (traduction espagnole), @Locodice67 (traduction allemande,
  script d'installation), @baptiste-gislot, @florentdestremau (bouton rotatif), et tous ceux qui ont
  testé leur robot et signalé des bugs.

Licence MIT. Ce dépôt ne contient aucune recette, image ni code de Lidl : chaque robot télécharge
lui-même les recettes sur monsieur-cuisine.com, pour l'usage personnel de son propriétaire.
