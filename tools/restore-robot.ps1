# Gives control back to the stock Monsieur Cuisine app and removes Toque.
# Usage: powershell -ExecutionPolicy Bypass -File tools\restore-robot.ps1 [-Ip 192.168.1.x] [-Uninstall]
param([string]$Ip = "", [switch]$Uninstall)
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
if ($Ip -ne "") { & $adb connect "${Ip}:5555" | Out-Host }
& $adb push tools\robot\restore.sh /data/local/tmp/toque-restore.sh | Out-Null
& $adb shell "su -c 'sh /data/local/tmp/toque-restore.sh'" | Out-Host
if ($Uninstall) { & $adb uninstall app.toque | Out-Host }
& $adb reboot
Write-Host "Robot restarted on the stock app."
