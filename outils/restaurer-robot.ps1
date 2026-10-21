# Rend la main à l'appli Monsieur Cuisine d'origine et retire Toque.
param([string]$Ip = "", [switch]$Desinstaller)
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
if ($Ip -ne "") { & $adb connect "${Ip}:5555" | Out-Host }
& $adb push outils\robot\restaurer.sh /data/local/tmp/toque-restaurer.sh | Out-Null
& $adb shell "su -c 'sh /data/local/tmp/toque-restaurer.sh'" | Out-Host
if ($Desinstaller) { & $adb uninstall app.toque | Out-Host }
& $adb reboot
Write-Host "Robot redémarré sur l'appli d'origine."
