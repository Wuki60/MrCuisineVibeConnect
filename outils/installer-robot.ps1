# Installe Toque sur le Monsieur Cuisine Connect (rooté, ADB actif).
# Usage : powershell -ExecutionPolicy Bypass -File outils\installer-robot.ps1 [-Apk chemin.apk] [-Ip 192.168.1.x]
param(
    [string]$Apk = "app\build\outputs\apk\debug\app-debug.apk",
    [string]$Ip = ""
)
$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
if (-not (Test-Path $Apk)) { Write-Host "APK introuvable : $Apk (Build > Build APK dans Android Studio)"; exit 1 }
if ($Ip -ne "") { & $adb connect "${Ip}:5555" | Out-Host }
& $adb devices | Out-Host

$root = (& $adb shell "su -c 'id -u'" 2>$null | Out-String).Trim()
if ($root -ne "0") { Write-Host "Root indisponible : installez d'abord Magisk / Kitsune Mask (voir docs\INSTALLATION.md)."; exit 1 }

Write-Host "Installation de Toque…"
& $adb install -r $Apk | Out-Host
& $adb push outils\robot\restaurer.sh /data/local/tmp/toque-restaurer.sh | Out-Null
& $adb shell am start -n app.toque/.ui.MainActivity | Out-Null
Write-Host ""
Write-Host "Toque est lancé. Sur le robot :"
Write-Host "  1. Réglages > Machine > « Machine réelle » > Appliquer (premier essai : bol VIDE)."
Write-Host "  2. Bouton Accueil > choisir Toque > « Toujours »."
Write-Host "  3. Réglages > Recettes Lidl > Synchroniser."
Write-Host "Pour tout annuler : outils\restaurer-robot.ps1"
