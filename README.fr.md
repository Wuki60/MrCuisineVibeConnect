# MrCuisineVibeConnect

*[English version](README.md)*

**Toque**, une interface moderne et rapide pour le robot **Monsieur Cuisine Connect** (SKMC 1200),
qui remplace l'appli d'origine sur la tablette du robot tout en gardant **les recettes Lidl**.

> Projet indépendant, non affilié à Lidl, SilverCrest ni Hoyer. Le pilotage du moteur repose
> sur une analyse publique du protocole ; il n'a **pas** encore été validé sur une vraie machine.
> À utiliser à vos risques, en suivant la procédure de premier essai.

## Ce que fait Toque

- **Interface sombre**, pensée pour l'écran 7" et le bouton rotatif du robot, fluide sur sa
  tablette d'origine (1 Go de RAM, Android 6) : pas de bibliothèque lourde, images
  sous-échantillonnées, vues recyclées.
- **Recettes Lidl uniquement**. L'appli d'origine reste seule à dialoguer avec les serveurs
  Lidl (avec l'identité de l'appareil, comme d'habitude). Toque copie ensuite sa base, chaque
  nuit ou sur demande. Aucun serveur à gérer, aucun compte.
- **Recherche qui marche** : insensible aux accents, aux pluriels et aux fautes de frappe
  (« bechamle », « risoto »). Recherche par ingrédients (« poulet, curry »), filtres, favoris.
- **Cuisine pas à pas** : chaque étape se lance d'un appui, l'étape suivante s'affiche toute
  seule, pesée intégrée, reprise après coupure.
- **Cuisson manuelle**, programmes **Pétrir / Vapeur / Rissoler**, **Turbo**, **balance**.
- **Hors ligne** : tout fonctionne sans réseau (plus de « serveur injoignable » au démarrage).
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
tout seul. Le pilotage tourne dans **son propre processus** : un plantage de l'interface ne coupe
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
outils/ jumeau.py (jumeau numérique pour tester sur PC), scripts d'installation/restauration
docs/   INSTALLATION.md (EN), INSTALLATION.fr.md (FR)
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
   L'écran Recettes reste vide : les recettes Lidl n'existent que sur le robot.
2. **Jumeau numérique** : le jumeau du [Monsieur Cuisine Developer SDK](https://github.com/antrea/monsieur-cuisine-developer-sdk)
   reproduit le protocole réel indépendamment de Toque.
   `python outils/jumeau.py --sdk <dossier du SDK>` puis, dans Toque, Réglages › Machine ›
   Jumeau (émulateur : hôte `10.0.2.2` ; appareil réel : `adb reverse tcp:8765 tcp:8765` et
   hôte `127.0.0.1`). `python outils/jumeau.py --send "lid 1"` ouvre le couvercle, etc.

## Publier une nouvelle version

1. Augmenter `versionCode` et `versionName` dans `app/build.gradle.kts`.
2. Committer et pousser sur `main`.

Le [workflow de publication](.github/workflows/release.yml) lance les tests, compile l'APK et publie la
version `vX.Y.Z` avec. Les robots la trouvent ensuite dans Réglages › Mises à jour.

Toutes les compilations sont signées avec la clé du projet (`app/signature/`, publique exprès) : un APK
compilé sur n'importe quel PC ou par GitHub s'installe par-dessus la version précédente. N'installez que
les APK publiés sur ce dépôt.

## Installer sur le robot

Voir **[docs/INSTALLATION.fr.md](docs/INSTALLATION.fr.md)** : root, installation, premier essai à vide,
synchronisation des recettes, retour à l'appli d'origine.

## Crédits

- Protocole : [Monsieur Cuisine Developer SDK](https://github.com/antrea/monsieur-cuisine-developer-sdk) (MIT).
- Accès au robot : [Monsieur-Cuisine-Connect-Hack](https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack).

Licence MIT. Ce dépôt ne contient aucune recette, image ni code de Lidl : les recettes sont lues
uniquement sur le robot de l'utilisateur.
