# Compatibility ceiling: what exactly happens when the APK meets a very new Android.
#
#   & 'G:\jianyue-src\tools\check-install-block.ps1' [-Dev emulator-5558]
#
# The manifest has minSdkVersion=1 and no targetSdkVersion (so targetSdk = 1).
# Android 14+ refuses to install packages with targetSdk < 23. This script turns that
# documentation claim into measured data:
#   A) plain `adb install`        -> expected Failure [INSTALL_FAILED_DEPRECATED_SDK_VERSION]
#   B) install with the documented bypass flag -> expected Success
#   C) if B installed it, launch MainActivity and report whether the app actually runs
#
# ASCII only (Windows PowerShell 5.1 misreads BOM-less UTF-8).
param(
    [string]$Dev = 'emulator-5558',
    [string]$OutDir = 'G:\jianyue-src\tools\api37'
)
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$PKG = 'com.jianyue.reader'
$apk = 'G:\jianyue-src\out\reader.apk'
if (!(Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
$report = New-Object System.Collections.Generic.List[string]
function Say($s) { Write-Output $s; $report.Add("$s") }
function Sh($cmd) { return ((& $adb -s $Dev shell $cmd 2>&1) -join "`n").Trim() }

Say "################ install-block probe: very new Android ################"

# ---- wait for boot ----
# First boot of a brand-new API-37 image AOT-compiles everything (dex2oat runs for
# minutes per package under software rendering), so allow up to 30 minutes.
$booted = $false
foreach ($i in 1..180) {
    $b = Sh 'getprop sys.boot_completed'
    if ($b -eq '1') { $booted = $true; break }
    if ($i % 6 -eq 0) { Say ("  ...still booting after " + ($i * 10) + "s") }
    Start-Sleep -Seconds 10
}
Say ("boot_completed=" + $booted)

$sdk = Sh 'getprop ro.build.version.sdk'
$rel = Sh 'getprop ro.build.version.release'
$code = Sh 'getprop ro.build.version.codename'
$abi = Sh 'getprop ro.product.cpu.abi'
$page = Sh 'getconf PAGE_SIZE'
$fp = Sh 'getprop ro.build.fingerprint'
Say ("device: " + $Dev)
Say ("  api=" + $sdk + "  release=" + $rel + "  codename=" + $code + "  abi=" + $abi + "  PAGE_SIZE=" + $page)
Say ("  fingerprint=" + $fp)
if (-not $booted) { Say 'aborting: device never booted'; $report | Out-File -Encoding utf8 (Join-Path $OutDir 'install-block.txt'); exit 1 }

# ---- manifest facts, read back from the device-side package manager after install ----
Say ''
Say '-- A) plain install (no bypass) --'
& $adb -s $Dev uninstall $PKG 2>&1 | Out-Null
$a = (& $adb -s $Dev install -r $apk 2>&1 | Out-String).Trim()
Say $a
$blocked = ($a -match 'INSTALL_FAILED_DEPRECATED_SDK_VERSION') -or ($a -match 'DEPRECATED_SDK') -or ($a -match 'Failure')
Say ("  => blocked=" + $blocked)

Say ''
Say '-- B) install with --bypass-low-target-sdk-block --'
$b = (& $adb -s $Dev install -r --bypass-low-target-sdk-block $apk 2>&1 | Out-String).Trim()
Say $b
$bypassed = ($b -match 'Success')
Say ("  => bypassed_install_ok=" + $bypassed)

Say ''
Say '-- C) if installed: does it run? --'
if ($bypassed) {
    $dump = Sh ("dumpsys package " + $PKG + " | grep -E 'versionName|targetSdk|minSdk'")
    Say ('  package: ' + ($dump -replace "`r?`n", ' | '))
    & $adb -s $Dev logcat -c 2>&1 | Out-Null
    & $adb -s $Dev shell am start -n ($PKG + '/.ui.MainActivity') 2>&1 | Out-Null
    Start-Sleep -Seconds 20
    $log = ((& $adb -s $Dev logcat -d 2>&1) -join "`n")
    $log | Out-File -Encoding utf8 (Join-Path $OutDir 'mainactivity.log')
    $fatal = ([regex]::Matches($log, 'FATAL EXCEPTION')).Count
    $disp = [regex]::Match($log, 'Displayed ' + [regex]::Escape($PKG) + '/[^\r\n]*')
    $err = [regex]::Matches($log, 'E (AndroidRuntime|ActivityManager|dalvikvm)[^\r\n]*')
    Say ('  displayed: ' + $disp.Value)
    Say ('  FATAL EXCEPTION count: ' + $fatal)
    $n = 0
    foreach ($e in $err) { if ($n -lt 6) { Say ('  err> ' + $e.Value) }; $n++ }
    & $adb -s $Dev shell screencap -p /sdcard/api37-shelf.png 2>&1 | Out-Null
    & $adb -s $Dev pull /sdcard/api37-shelf.png (Join-Path $OutDir 'api37-shelf.png') 2>&1 | Out-Null
    # second screen, to see whether the tab bar renders at all
    & $adb -s $Dev shell am start -n ($PKG + '/.ui.SourcesActivity') 2>&1 | Out-Null
    Start-Sleep -Seconds 8
    & $adb -s $Dev shell screencap -p /sdcard/api37-sources.png 2>&1 | Out-Null
    & $adb -s $Dev pull /sdcard/api37-sources.png (Join-Path $OutDir 'api37-sources.png') 2>&1 | Out-Null
    # cleanup so a later real install is clean
    & $adb -s $Dev uninstall $PKG 2>&1 | Out-Null
} else {
    Say '  skipped (bypass install failed too)'
}

Say ''
Say '################ SUMMARY ################'
Say ('  install blocked without bypass: ' + $blocked)
Say ('  installs with bypass flag: ' + $bypassed)
$report | Out-File -Encoding utf8 (Join-Path $OutDir 'install-block.txt')
