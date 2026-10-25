# Toque setup for the Monsieur Cuisine Connect (SKMC 1200), step by step, from a Windows PC.
# Written to be driven by an AI agent (see AGENTS.md) or by hand. Every step is safe to re-run.
#
#   powershell -ExecutionPolicy Bypass -File tools\setup\toque-setup.ps1 -Step status
#
# Steps, in order: status, prepare, driver, backup, flash-twrp, root, flash-root, android,
# install, wifi, cleanup. Lines starting with "HUMAN:" are actions only a person can do
# (unscrewing, cables, power switch, Windows security prompts, a tap on the robot's screen).
# A step that needs one stops and says so; run it again once the person is done.
param(
    [ValidateSet("status", "prepare", "driver", "backup", "flash-twrp", "root", "flash-root", "android", "install", "wifi", "cleanup")]
    [string]$Step = "status",
    [string]$Work = "C:\Toque",
    [string]$Apk = "",
    [string]$Ip = "",
    [int]$WaitSeconds = 180
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Repo = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$Downloads = Join-Path $Work "downloads"
$Backup = Join-Path $Work "backup"
$StateFile = Join-Path $Work "state.json"
$Log = Join-Path $Work "setup-log.txt"

# Known-good files (SHA-256). The scatter and TWRP come from github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack.
$Known = @{
    Magisk     = @{ Url = "https://github.com/topjohnwu/Magisk/releases/download/v31.0/Magisk-v31.0.apk"; Sha = "2c8a488b9a5293e578e95ae4f07e3c57aba4feec4a52ca4dd852a2692d6dd4e8" }
    HackZip    = @{ Url = "https://github.com/EliasKotlyar/Monsieur-Cuisine-Connect-Hack/archive/refs/heads/master.zip" }
    Twrp       = @{ Sha = "fc9ac999fc3b0a6c0cb563f39359084a6fcc211aeb42e7b754990afa4b543d3e"; Size = 11452416 }
    Scatter    = @{ Sha = "13b8260dba9e4efba874c09e35803af43cc69556ba0f1937226240b0ab318364" }
    Tools      = @{ Url = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip" }
    DriverCab  = @{ Name = "20896845_fdc6bb5aa9a9bac99adf85d931d6c21d1130a96e.cab"; Sha = "ce72e97c07582ad41debfaeee6f6284ecd28285df5f9caa05193653fb236561f" }
    FlashTool  = @{ Sha = "67e9eb48161087d43eca845b2dc0e9beacac3c0381bd1ce7989d48b17e2084a7" }
    FlashDA    = @{ Sha = "9cbf39f3925fe88d18bd4c4e45dd1c01741bf7a31a4f69252dfb318d229c0346" }
    Releases   = "https://api.github.com/repos/Wuki60/MrCuisineVibeConnect/releases/latest"
}
$PartitionSize = 16777216   # boot and recovery: 16 MiB each
$ByName = "/dev/block/platform/mtk-msdc.0/11120000.msdc0/by-name"

# ---------------------------------------------------------------- helpers

function Say([string]$text) {
    Write-Host $text
    try { Add-Content -Path $Log -Value ("{0:yyyy-MM-dd HH:mm:ss} {1}" -f (Get-Date), $text) -Encoding UTF8 } catch {}
}
function Human([string]$text) { Say ("HUMAN: " + $text) }
function Ok([string]$text) { Say ("OK: " + $text) }
function Fail([string]$text) { Say ("STOP: " + $text); exit 2 }
function NeedHuman([string]$text) { Human $text; Say "Run this step again when it is done."; exit 3 }

function Get-State {
    if (Test-Path $StateFile) {
        $o = Get-Content $StateFile -Raw | ConvertFrom-Json
        $h = @{}
        foreach ($p in $o.PSObject.Properties) { $h[$p.Name] = $p.Value }
        return $h
    }
    return @{}
}
function Set-State([string]$key, $value) {
    $s = Get-State
    $s[$key] = $value
    ($s | ConvertTo-Json -Depth 5) | Set-Content -Path $StateFile -Encoding UTF8
}
function Sha([string]$path) { (Get-FileHash -Algorithm SHA256 -Path $path).Hash.ToLowerInvariant() }

function Fetch([string]$url, [string]$dest) {
    Say ("Downloading " + $url)
    Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $dest -Headers @{ "User-Agent" = "toque-setup" }
}

function Get-Adb {
    $candidates = @(
        (Join-Path $Work "platform-tools\adb.exe"),
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe")
    )
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    Fail "adb not found: run the 'prepare' step first."
}

function Adb {
    $adb = Get-Adb
    $out = & $adb @args 2>&1 | Out-String
    return $out.Trim()
}

# State of the robot as adb sees it: "recovery" (TWRP), "device" (Android), "unauthorized", or "".
function Get-AdbState {
    $lines = (Adb devices) -split "`r?`n" | Where-Object { $_ -match "`t" }
    if ($Ip -ne "") { $lines = $lines | Where-Object { $_ -like "$Ip*" } }
    $first = $lines | Select-Object -First 1
    if (-not $first) { return "" }
    return ($first -split "`t")[1].Trim()
}

function Wait-AdbState([string[]]$wanted, [int]$seconds) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $st = Get-AdbState
        if ($wanted -contains $st) { return $st }
        Start-Sleep -Seconds 3
    }
    return Get-AdbState
}

function Test-BootImage([string]$path, [string]$what) {
    if (-not (Test-Path $path)) { Fail "$what missing: $path" }
    $len = (Get-Item $path).Length
    if ($len -ne $PartitionSize) { Fail ("$what has the wrong size ({0} bytes, expected {1})." -f $len, $PartitionSize) }
    $fs = [IO.File]::OpenRead($path)
    try {
        $head = New-Object byte[] 8
        [void]$fs.Read($head, 0, 8)
    } finally { $fs.Close() }
    if ([Text.Encoding]::ASCII.GetString($head) -ne "ANDROID!") { Fail "$what is not an Android boot image (no ANDROID! header): do not continue." }
}

function Get-FlashTool {
    $exe = Get-ChildItem -Path $Work -Filter flash_tool.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $exe) { return $null }
    return $exe.FullName
}

function Get-MemoryIntegrity {
    try {
        $v = Get-ItemPropertyValue -Path "HKLM:\SYSTEM\CurrentControlSet\Control\DeviceGuard\Scenarios\HypervisorEnforcedCodeIntegrity" -Name Enabled -ErrorAction Stop
        return [int]$v -eq 1
    } catch { return $false }
}

function Get-MtkPort {
    try {
        return Get-PnpDevice -PresentOnly -ErrorAction Stop | Where-Object { $_.InstanceId -match "VID_0E8D" }
    } catch { return $null }
}

function Test-MtkDriver {
    $out = pnputil /enum-drivers | Out-String
    return $out -match "cdc-acm\.inf"
}

# SP Flash Tool console configuration (flash_tool.exe -i file.xml).
function Write-FlashConfig([string]$name, [string]$commands, [string]$romList) {
    $tool = Get-FlashTool
    $da = Join-Path (Split-Path $tool) "MTK_AllInOne_DA.bin"
    $scatter = Join-Path $Work "hack\l706_dfbh_v_695scatter.txt"
    $xml = @"
<?xml version="1.0" encoding="UTF-8" ?>
<flashtool-config version="2.0">
    <general>
        <chip-name>MT6580</chip-name>
        <storage-type>EMMC</storage-type>
        <download-agent>$da</download-agent>
        <scatter>$scatter</scatter>
$romList
        <connection type="BromUSB" high-speed="true" power="AutoDetect" timeout-count="3600000" com-port="" />
    </general>
    <commands>
$commands
    </commands>
</flashtool-config>
"@
    $path = Join-Path $Work "spft-$name.xml"
    Set-Content -Path $path -Value $xml -Encoding UTF8
    return $path
}

# Download Only of boot (and optionally recovery); every other downloadable partition explicitly off.
function Write-DownloadConfig([string]$name, [string]$bootImage, [string]$recoveryImage) {
    $roms = @("            <rom index=""0"" enable=""false""></rom>", "            <rom index=""7"" enable=""false""></rom>")
    $roms += "            <rom index=""8"" enable=""true"">$bootImage</rom>"
    if ($recoveryImage) { $roms += "            <rom index=""9"" enable=""true"">$recoveryImage</rom>" }
    else { $roms += "            <rom index=""9"" enable=""false""></rom>" }
    foreach ($i in 11, 17, 19, 20, 21) { $roms += "            <rom index=""$i"" enable=""false""></rom>" }
    $romList = "        <rom-list>`r`n" + ($roms -join "`r`n") + "`r`n        </rom-list>"
    return Write-FlashConfig $name "        <download-only />" $romList
}

function Invoke-FlashTool([string]$config, [string]$what) {
    $tool = Get-FlashTool
    if (-not $tool) { Fail "SP Flash Tool missing: run 'prepare'." }
    Human "Robot switched OFF with its rear switch, USB A-A cable in the right-hand port marked 'Android USB'. Switch the robot ON as soon as the next line appears (the tool waits up to 1 hour)."
    Say ("Running SP Flash Tool (console mode): " + $what)
    $out = Join-Path $Work ("spft-" + [IO.Path]::GetFileNameWithoutExtension($config) + "-out.txt")
    $p = Start-Process -FilePath $tool -ArgumentList @("-i", "`"$config`"") -WorkingDirectory (Split-Path $tool) -Wait -PassThru -NoNewWindow -RedirectStandardOutput $out
    Say ("flash_tool exit code: " + $p.ExitCode + " (output: " + $out + ")")
    return $p.ExitCode
}

# ---------------------------------------------------------------- steps

function Step-Status {
    $s = Get-State
    Say ("Work folder: " + $Work)
    Say ("Done steps: " + (($s.Keys | Where-Object { $_ -like "done-*" } | ForEach-Object { $_.Substring(5) }) -join ", "))
    Say ("Memory integrity (blocks the MediaTek driver): " + $(if (Get-MemoryIntegrity) { "ON" } else { "off" }))
    Say ("MediaTek driver installed: " + $(if (Test-MtkDriver) { "yes" } else { "no" }))
    $tool = Get-FlashTool
    Say ("SP Flash Tool: " + $(if ($tool) { $tool } else { "missing" }))
    $adb = $null
    try { $adb = Get-Adb } catch {}
    if ($adb) { Say ("adb state: " + $(Get-AdbState)) }
    $order = "prepare", "driver", "backup", "flash-twrp", "root", "flash-root", "android", "install", "wifi"
    foreach ($o in $order) { if (-not $s.ContainsKey("done-$o")) { Say ("Next step: " + $o); return } }
    Say "Everything is done. Optional: 'cleanup' (removes the MediaTek driver so memory integrity can be turned back on)."
}

function Step-Prepare {
    New-Item -ItemType Directory -Force -Path $Work, $Downloads, $Backup | Out-Null

    # Android platform tools (adb)
    if (-not (Test-Path (Join-Path $Work "platform-tools\adb.exe"))) {
        $zip = Join-Path $Downloads "platform-tools.zip"
        Fetch $Known.Tools.Url $zip
        Expand-Archive -Path $zip -DestinationPath $Work -Force
    }
    Ok ("adb: " + (Get-Adb))

    # Magisk
    $magisk = Join-Path $Downloads "Magisk-v31.0.apk"
    if (-not (Test-Path $magisk) -or (Sha $magisk) -ne $Known.Magisk.Sha) { Fetch $Known.Magisk.Url $magisk }
    if ((Sha $magisk) -ne $Known.Magisk.Sha) { Fail "Magisk-v31.0.apk checksum mismatch." }
    Ok "Magisk v31.0"

    # Scatter file and TWRP
    $hack = Join-Path $Work "hack"
    $twrp = Join-Path $hack "recovery.img"
    $scatter = Join-Path $hack "l706_dfbh_v_695scatter.txt"
    if (-not (Test-Path $twrp) -or -not (Test-Path $scatter)) {
        $zip = Join-Path $Downloads "Monsieur-Cuisine-Connect-Hack.zip"
        Fetch $Known.HackZip.Url $zip
        $tmp = Join-Path $Downloads "hack-tmp"
        if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
        Expand-Archive -Path $zip -DestinationPath $tmp -Force
        New-Item -ItemType Directory -Force -Path $hack | Out-Null
        Copy-Item (Get-ChildItem $tmp -Recurse -Filter recovery.img | Select-Object -First 1).FullName $twrp -Force
        Copy-Item (Get-ChildItem $tmp -Recurse -Filter l706_dfbh_v_695scatter.txt | Select-Object -First 1).FullName $scatter -Force
        Remove-Item -Recurse -Force $tmp
    }
    if ((Sha $twrp) -ne $Known.Twrp.Sha) { Fail "TWRP (recovery.img) checksum mismatch: do not flash it." }
    if ((Sha $scatter) -ne $Known.Scatter.Sha) { Fail "Scatter file checksum mismatch: this guide is only for the SKMC 1200." }
    Ok "TWRP and scatter file"

    # Toque APK (latest release)
    if ($Apk -eq "") {
        try {
            $rel = Invoke-RestMethod -Uri $Known.Releases -Headers @{ "User-Agent" = "toque-setup" }
            $asset = $rel.assets | Where-Object { $_.name -like "Toque-*.apk" } | Select-Object -First 1
            if ($asset) {
                $dest = Join-Path $Downloads $asset.name
                if (-not (Test-Path $dest)) { Fetch $asset.browser_download_url $dest }
                Ok ("Toque " + $rel.tag_name + ": " + $dest)
            } else { Say "No Toque APK in the latest release: build it (see README) or pass -Apk later." }
        } catch { Say ("GitHub releases unreachable: " + $_.Exception.Message) }
    }

    # Things that need a browser: the MediaTek driver and SP Flash Tool.
    $cab = Join-Path $Downloads $Known.DriverCab.Name
    $missing = $false
    if (-not (Test-Path $cab)) {
        $missing = $true
        Say ("MISSING: MediaTek PreLoader USB VCOM driver. In a browser, open https://www.catalog.update.microsoft.com/Search.aspx?q=MediaTek%20PreLoader%20USB%20VCOM , download the update whose file is named " + $Known.DriverCab.Name + " and save it as " + $cab)
    } elseif ((Sha $cab) -ne $Known.DriverCab.Sha) { Fail "Driver .cab checksum mismatch." }
    else { Ok "MediaTek driver package" }
    $tool = Get-FlashTool
    if (-not $tool) {
        $missing = $true
        Say ("MISSING: SP Flash Tool v5.1924 for Windows. Download it from https://spflashtool.com/download (file SP_Flash_Tool_v5.1924_Win.zip) and extract it into " + (Join-Path $Work "SP_Flash_Tool"))
    } else {
        $da = Join-Path (Split-Path $tool) "MTK_AllInOne_DA.bin"
        if ((Sha $tool) -ne $Known.FlashTool.Sha -or -not (Test-Path $da) -or (Sha $da) -ne $Known.FlashDA.Sha) {
            Fail "SP Flash Tool files differ from v5.1924 (flash_tool.exe or MTK_AllInOne_DA.bin checksum): download the official v5.1924 again."
        }
        Ok ("SP Flash Tool v5.1924: " + $tool)
    }
    if ($missing) { Say "Download the missing files, then run 'prepare' again."; exit 3 }
    Set-State "done-prepare" (Get-Date).ToString("s")
    Ok "prepare done. Next: driver"
}

function Step-Driver {
    if (Test-MtkDriver) { Ok "MediaTek driver already installed." }
    else {
        if (Get-MemoryIntegrity) {
            NeedHuman "Windows memory integrity is ON and blocks the MediaTek driver (usb2ser.sys). Open Windows Security > Device security > Core isolation details, turn 'Memory integrity' OFF, then restart the PC. It can be turned back on at the end (step 'cleanup')."
        }
        $cab = Join-Path $Downloads $Known.DriverCab.Name
        if (-not (Test-Path $cab)) { Fail "Driver package missing: run 'prepare'." }
        $dir = Join-Path $Work "driver"
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
        & expand.exe -F:* $cab $dir | Out-Null
        $inf = Get-ChildItem $dir -Recurse -Filter cdc-acm.inf | Select-Object -First 1
        if (-not $inf) { Fail "cdc-acm.inf not found in the driver package." }
        Human "A Windows prompt (User Account Control) asks to allow 'PnP Utility' to make changes: click Yes."
        $p = Start-Process -FilePath pnputil.exe -ArgumentList @("/add-driver", "`"$($inf.FullName)`"", "/install") -Verb RunAs -Wait -PassThru
        Say ("pnputil exit code: " + $p.ExitCode)
        if (-not (Test-MtkDriver)) { Fail "The driver was not installed (prompt refused, or blocked by Windows)." }
        Ok "MediaTek driver installed."
    }
    Set-State "done-driver" (Get-Date).ToString("s")
    Ok "driver done. Next: backup"
}

function Step-Backup {
    $boot = Join-Path $Backup "boot-original.img"
    $rec = Join-Path $Backup "recovery-original.img"
    if (-not ((Test-Path $boot) -and (Test-Path $rec))) {
        Human "Switch the robot OFF with its rear switch (it can stay plugged into the mains). Unscrew the maintenance cover under the robot (small Torx). Plug the USB A-A cable into the RIGHT-HAND port marked 'Android USB' (never the other one: it can destroy the board) and into a USB-A port of the PC."
        $rb = @"
        <readback>
            <physical-readback is-physical-readback="false" />
            <readback-list>
                <readback-rom-item readback-index="0" readback-enable="true" readback-flag="NUTL_READ_PAGE_ONLY" start-address="0x0000000001d20000" readback-length="0x01000000" addr-flag="NUTL_ADDR_LOGICAL" part-id="8">$boot</readback-rom-item>
                <readback-rom-item readback-index="1" readback-enable="true" readback-flag="NUTL_READ_PAGE_ONLY" start-address="0x0000000002d20000" readback-length="0x01000000" addr-flag="NUTL_ADDR_LOGICAL" part-id="8">$rec</readback-rom-item>
            </readback-list>
        </readback>
"@
        $cfg = Write-FlashConfig "readback" $rb ""
        $code = Invoke-FlashTool $cfg "read back boot and recovery"
        if (-not ((Test-Path $boot) -and (Test-Path $rec))) {
            Fail ("Readback produced no files (exit code $code). Use the SP Flash Tool window instead: see AGENTS.md, 'GUI fallback: readback'. Save the files as $boot and $rec, then run 'backup' again.")
        }
    }
    Test-BootImage $boot "boot-original.img"
    Test-BootImage $rec "recovery-original.img"
    if ((Sha $boot) -eq (Sha $rec)) { Fail "boot and recovery backups are identical: the readback went wrong." }
    foreach ($f in $boot, $rec) { Set-ItemProperty -Path $f -Name IsReadOnly -Value $true }
    Set-State "boot-original-sha256" (Sha $boot)
    Set-State "recovery-original-sha256" (Sha $rec)
    Set-State "done-backup" (Get-Date).ToString("s")
    Ok "Backups verified (16 MiB Android boot images). Keep $Backup safe: it is the only way back."
    Human "Copy the folder $Backup to a second place (USB stick, cloud)."
    Ok "backup done. Next: flash-twrp"
}

function Step-FlashTwrp {
    if (-not (Get-State).ContainsKey("done-backup")) { Fail "Run 'backup' first: never flash without the backups." }
    $twrp = Join-Path $Work "hack\recovery.img"
    if ((Sha $twrp) -ne $Known.Twrp.Sha) { Fail "TWRP checksum mismatch." }
    $cfg = Write-DownloadConfig "flash-twrp" $twrp ""
    $code = Invoke-FlashTool $cfg "TWRP into boot"
    if ($code -ne 0) { Fail ("SP Flash Tool reported an error ($code). Use the GUI: AGENTS.md, 'GUI fallback: Download Only' with boot = $twrp only.") }
    Set-State "done-flash-twrp" (Get-Date).ToString("s")
    Human "Switch the robot OFF, UNPLUG the USB cable, switch it ON: TWRP (a blue/black recovery screen) appears. Then plug the cable back in. (Switched on with the cable in, the robot stops on a battery screen.)"
    Ok "flash-twrp done. Next: root (with TWRP on the robot's screen)"
}

function Step-Root {
    $st = Wait-AdbState @("recovery") $WaitSeconds
    if ($st -ne "recovery") { NeedHuman "adb does not see TWRP (state: '$st'). The robot must show TWRP and the cable must be plugged in AFTER it started." }
    $boot = Join-Path $Backup "boot-original.img"
    Test-BootImage $boot "boot-original.img"
    $magisk = Join-Path $Downloads "Magisk-v31.0.apk"
    $key = Join-Path $env:USERPROFILE ".android\adbkey.pub"
    Say (Adb shell "rm -rf /tmp/magisk /tmp/orig /tmp/twrp-root.sh")
    Say (Adb push $magisk /tmp/magisk.apk)
    Say (Adb push $boot /tmp/boot.img)
    if (Test-Path $key) { Say (Adb push $key /tmp/adbkey.pub) }
    $script = Join-Path $Repo "tools\robot\twrp-root.sh"
    Say (Adb push $script /tmp/twrp-root.sh)
    $out = Adb shell "sh /tmp/twrp-root.sh"
    Say $out
    if ($out -notmatch "== Done") { Fail "twrp-root.sh did not finish: nothing was flashed. Read the output above." }
    $patched = Join-Path $Work "boot-magisk.img"
    if (Test-Path $patched) { Remove-Item -Force $patched }
    Say (Adb pull /tmp/magisk/new-boot.img $patched)
    Test-BootImage $patched "boot-magisk.img"
    $orig = Join-Path $Work "system-originals"
    New-Item -ItemType Directory -Force -Path $orig | Out-Null
    Say (Adb pull /tmp/orig $orig)
    Set-State "boot-magisk-sha256" (Sha $patched)
    Set-State "done-root" (Get-Date).ToString("s")
    Ok "Magisk boot image ready: $patched. Next: flash-root"
}

function Step-FlashRoot {
    if (-not (Get-State).ContainsKey("done-root")) { Fail "Run 'root' first." }
    $patched = Join-Path $Work "boot-magisk.img"
    Test-BootImage $patched "boot-magisk.img"
    $twrp = Join-Path $Work "hack\recovery.img"
    $cfg = Write-DownloadConfig "flash-root" $patched $twrp
    $code = Invoke-FlashTool $cfg "Magisk boot into boot, TWRP into recovery"
    if ($code -ne 0) { Fail ("SP Flash Tool reported an error ($code). Use the GUI: AGENTS.md, 'GUI fallback: Download Only' with boot = $patched and recovery = $twrp.") }
    Set-State "done-flash-root" (Get-Date).ToString("s")
    Human "Switch the robot OFF, UNPLUG the USB cable, switch it ON: the stock Monsieur Cuisine app starts. Then plug the cable back in."
    Ok "flash-root done. Next: android"
}

function Step-Android {
    $st = Wait-AdbState @("device", "unauthorized") $WaitSeconds
    if ($st -eq "unauthorized") { NeedHuman "The robot asks to allow USB debugging: tick 'Always allow' and tap OK on its screen." }
    if ($st -ne "device") { NeedHuman "adb does not see Android (state: '$st'). The stock app must be on screen and the cable plugged in after start-up." }
    $magiskV = Adb shell "magisk -v"
    if ($magiskV -notmatch "MAGISK") { Fail "Magisk is not running: the boot partition is not the patched one. Check the flash-root step." }
    Ok ("Magisk " + $magiskV)
    $pkgs = Adb shell "pm list packages com.topjohnwu.magisk"
    if ($pkgs -notmatch "com.topjohnwu.magisk") {
        Say (Adb install -r (Join-Path $Downloads "Magisk-v31.0.apk"))
    }
    $id = Adb shell "su -c 'id -u'"
    if ($id -ne "0") {
        NeedHuman "On the robot's screen, Magisk asks for superuser access for 'Shell': tap GRANT (Autoriser) within 10 seconds. Then run 'android' again."
    }
    $expected = (Get-State)["boot-magisk-sha256"]
    if ($expected) {
        $bb = "/data/adb/magisk/busybox"
        $flashed = Adb shell "su -c '$bb head -c $PartitionSize $ByName/boot | $bb sha256sum'"
        if ($flashed -notmatch $expected) { Say ("WARNING: the boot partition differs from boot-magisk.img (" + $flashed + ")") }
        else { Ok "boot partition = boot-magisk.img" }
    }
    Set-State "done-android" (Get-Date).ToString("s")
    Ok "Root works (uid 0). Next: install"
}

function Step-Install {
    $apkPath = $Apk
    if ($apkPath -eq "") {
        $latest = Get-ChildItem $Downloads -Filter "Toque-*.apk" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($latest) { $apkPath = $latest.FullName }
    }
    if ($apkPath -eq "" -or -not (Test-Path $apkPath)) { Fail "No Toque APK: run 'prepare' (downloads the latest release) or pass -Apk <file>." }
    $adb = Get-Adb
    $env:Path = (Split-Path $adb) + ";" + $env:Path
    Push-Location $Repo
    try {
        if ($Ip -ne "") { & (Join-Path $Repo "tools\install-robot.ps1") -Apk $apkPath -Ip $Ip }
        else { & (Join-Path $Repo "tools\install-robot.ps1") -Apk $apkPath }
    } finally { Pop-Location }
    $ver = Adb shell "dumpsys package app.toque | grep versionName"
    if ($ver -notmatch "versionName") { Fail "Toque is not installed." }
    Set-State "done-install" (Get-Date).ToString("s")
    Ok ("Toque installed: " + $ver.Trim())
    Human "On the robot, EMPTY bowl, lid closed, hand near the main switch: Toque > Settings > Machine > Real machine > Apply. The badge at the top right must show 'Ready' ('Prete' in French). Then try Manual cooking: 10 s, speed 2."
    Ok "install done. Next: wifi (optional)"
}

function Step-Wifi {
    $st = Get-AdbState
    if ($st -ne "device") { NeedHuman "Connect the robot with the USB cable (Android started), then run 'wifi' again." }
    $addr = Adb shell "ip -f inet addr show wlan0"
    $m = [regex]::Match($addr, "inet (\d+\.\d+\.\d+\.\d+)")
    if (-not $m.Success) {
        NeedHuman "The robot is not on Wi-Fi. In Toque, tap the clock at the top right 5 times quickly to open the Android settings, join the home Wi-Fi, come back with the 'Back to Toque' button."
    }
    $robotIp = $m.Groups[1].Value
    Say (Adb shell "su -c 'setprop persist.adb.tcp.port 5555'")
    Say (Adb tcpip 5555)
    Start-Sleep -Seconds 5
    Say (Adb connect "${robotIp}:5555")
    $check = Adb -s "${robotIp}:5555" shell "su -c 'id -u'"
    if ($check -ne "0") { Fail "Wi-Fi adb does not answer as root yet: unplug and replug the cable, then run 'wifi' again." }
    Set-State "robot-ip" $robotIp
    Set-State "done-wifi" (Get-Date).ToString("s")
    Ok ("ADB over Wi-Fi: ${robotIp}:5555 (kept after restarts). Home network only.")
    Human "The cable is no longer needed: screw the maintenance cover back on."
}

function Step-Cleanup {
    $out = pnputil /enum-drivers | Out-String
    $blocks = $out -split "(\r?\n){2,}"
    $published = @()
    foreach ($b in $blocks) {
        if ($b -match "cdc-acm\.inf") {
            $m = [regex]::Match($b, "(oem\d+\.inf)")
            if ($m.Success) { $published += $m.Groups[1].Value }
        }
    }
    foreach ($oem in $published) {
        Human "A Windows prompt (User Account Control) asks to allow 'PnP Utility': click Yes."
        Start-Process -FilePath pnputil.exe -ArgumentList @("/delete-driver", $oem, "/uninstall", "/force") -Verb RunAs -Wait | Out-Null
        Say ("Removed driver " + $oem)
    }
    Human "You can turn Windows memory integrity back on: Windows Security > Device security > Core isolation details > Memory integrity ON, then restart."
    Set-State "done-cleanup" (Get-Date).ToString("s")
}

# Dot-sourcing (". toque-setup.ps1") only loads the functions, for tests.
if ($MyInvocation.InvocationName -eq ".") { return }
New-Item -ItemType Directory -Force -Path $Work | Out-Null
switch ($Step) {
    "status" { Step-Status }
    "prepare" { Step-Prepare }
    "driver" { Step-Driver }
    "backup" { Step-Backup }
    "flash-twrp" { Step-FlashTwrp }
    "root" { Step-Root }
    "flash-root" { Step-FlashRoot }
    "android" { Step-Android }
    "install" { Step-Install }
    "wifi" { Step-Wifi }
    "cleanup" { Step-Cleanup }
}
