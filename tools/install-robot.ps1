# Installs Toque on the Monsieur Cuisine Connect (rooted, ADB enabled).
# Usage: powershell -ExecutionPolicy Bypass -File tools\install-robot.ps1 [-Apk path.apk] [-Ip 192.168.1.x]
param(
    [string]$Apk = "app\build\outputs\apk\debug\app-debug.apk",
    [string]$Ip = ""
)
$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
if (-not (Test-Path $Apk)) { Write-Host "APK not found: $Apk (Build > Build APK in Android Studio, or download it from GitHub Releases)"; exit 1 }
if ($Ip -ne "") { & $adb connect "${Ip}:5555" | Out-Host }
& $adb devices | Out-Host

$root = (& $adb shell "su -c 'id -u'" 2>$null | Out-String).Trim()
if ($root -ne "0") { Write-Host "No root access: install Magisk / Kitsune Mask first (see docs\INSTALLATION.md)."; exit 1 }

Write-Host "Installing Toque..."
& $adb install -r $Apk | Out-Host
& $adb push tools\robot\restore.sh /data/local/tmp/toque-restore.sh | Out-Null
& $adb shell am start -n app.toque/.ui.MainActivity | Out-Null
Write-Host ""
Write-Host "Toque is running. On the robot:"
Write-Host "  1. Settings > Machine > Real machine > Apply (first test: EMPTY bowl)."
Write-Host "  2. Home button > choose Toque > Always."
Write-Host "  3. Settings > Lidl recipes > Sync."
Write-Host "To undo everything: tools\restore-robot.ps1"
