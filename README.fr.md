# MrCuisineVibeConnect

🇬🇧 **[English version](README.md)**

**Toque**, une interface moderne et rapide pour le robot **Monsieur Cuisine Connect** (SKMC 1200),
qui remplace l'appli d'origine sur la tablette du robot tout en gardant **les recettes Lidl**.

> Projet indépendant, non affilié à Lidl, SilverCrest ni Hoyer. Le pilotage du moteur repose
> sur une analyse publique du protocole ; il tourne au quotidien sur un robot (SKMC 1200, Android 6)
> depuis octobre 2026, mais n'a été essayé que sur celui-là. À utiliser à vos risques, en suivant la
> procédure de premier essai.

## Installer avec un agent IA

Le plus simple : confiez l'installation à un agent IA qui peut piloter votre PC Windows (Claude,
ChatGPT, Copilot…). Donnez-lui ce message :

> Installe Toque sur mon Monsieur Cuisine Connect en suivant le fichier AGENTS.md de
> https://github.com/Wuki60/MrCuisineVibeConnect

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

- **Interface sombre**, pensée pour l'écran 7" et le bouton rotatif du robot, fluide sur sa
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
- **Cuisine pas à pas** : chaque étape se lance d'un appui, l'étape suivante s'affiche toute
  seule, pesée intégrée, reprise après coupure. Les réglages qui se suivent sans geste à faire
  entre eux (rinçage, mixages en plusieurs vitesses…) partent ensemble d'un seul appui : la lame
  passe directement d'une vitesse et d'une température à l'autre, sans s'arrêter.
- **Cuisson manuelle**, programmes **Pétrir / Vapeur / Rissoler**, **Turbo**, **balance**.
- **Hors ligne** : tout fonctionne sans réseau (plus de « serveur injoignable » au démarrage).
- **Heure toujours juste** : le fuseau horaire est trouvé d'après la connexion Internet et l'heure
  vient du réseau (le robot n'a pas de pile d'horloge) ; réglable à la main dans Réglages › Date et heure.
- **Pensé pour un robot sans boutons Android** : Toque s'ouvre au démarrage, et un bouton
  **« Retour à Toque »** reste affiché par-dessus l'appli d'origine quand on l'ouvre.
- **Mises à jour depuis GitHub** : Réglages › Mises à jour vérifie les
  [versions publiées](https://github.com/Wuki60/MrCuisineVibeConnect/releases) de ce dépôt et installe
  la nouvelle (sans rien demander sur un robot rooté, puis Toque redémarre). Refusé pendant un programme.

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

Licence MIT. Ce dépôt ne contient aucune recette, image ni code de Lidl : chaque robot télécharge
lui-même les recettes sur monsieur-cuisine.com, pour l'usage personnel de son propriétaire.
