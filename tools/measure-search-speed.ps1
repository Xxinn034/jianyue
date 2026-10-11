# Bounded before/after comparison of a 200+-source search on the API-1 emulator.
#
# Usage: powershell -NoProfile -File tools-tmp\measure-search-speed.ps1 -Apk <apk> -Label before
#
# Why bounded: the old engine (4 threads, wait for everything) needs many minutes on an API-1
# emulator to finish 120+ sources, and hammering this emulator wedges it. So both builds are
# observed for the SAME fixed window and the metric is how much work each got through:
#
#   attempted   = sources whose search request was actually started
#   parsed      = sources that produced parsed books
#   finished    = whether the whole search completed inside the window
#
# API 1's logcat has no -t option, so the log is read once, at the end, with a plain -d.
param(
    [string]$Dev = 'emulator-5554',
    [string]$Apk = 'G:\jianyue-src\out\reader.apk',
    [string]$Sources = 'G:\legado-api1\source-analysis\engine-gap\yicheng-120.json',
    [string]$Label = 'run',
    [int]$WindowSec = 120,
    [string]$Out = 'G:\legado-api1\source-analysis\engine-gap'
)
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$PKG = 'com.jianyue.reader'
$dst = '/sdcard/big-sources.json'
$KW = [string]([char]0x6597 + [char]0x7F57 + [char]0x5927 + [char]0x9646)
$logFile = Join-Path $Out ("search-" + $Label + ".log")

function Log($m) { Write-Output ("[" + $Label + "] " + $m) }

& $adb -s $Dev uninstall $PKG 2>&1 | Out-Null
Start-Sleep -Seconds 2
$inst = (& $adb -s $Dev install -r $Apk 2>&1 | Out-String)
if ($inst -notmatch 'Success') { Log ("INSTALL FAILED: " + $inst); exit 1 }
Log ("installed " + (Split-Path $Apk -Leaf))

& $adb -s $Dev push $Sources $dst 2>&1 | Out-Null
& $adb -s $Dev shell am start -n "$PKG/.ui.SourcesActivity" -e importPath $dst 2>&1 | Out-Null
Start-Sleep -Seconds 15
$imp = (& $adb -s $Dev logcat -d 2>&1 | Out-String)
$m = [regex]::Match($imp, 'sourceStore: import added=(\d+) updated=(\d+) failed=(\d+)')
if ($m.Success) {
    Log ("imported added=" + $m.Groups[1].Value + " updated=" + $m.Groups[2].Value + " failed=" + $m.Groups[3].Value)
} else {
    Log "IMPORT LINE NOT FOUND - continuing anyway"
}

& $adb -s $Dev shell "ping -c 1 baidu.com" 2>&1 | Out-Null

& $adb -s $Dev logcat -c 2>&1 | Out-Null
$t0 = Get-Date
& $adb -s $Dev shell am start -n "$PKG/.ui.SearchActivity" -e keyword $KW 2>&1 | Out-Null
Log ("search started, observing for " + $WindowSec + "s")
Start-Sleep -Seconds $WindowSec

$log = (& $adb -s $Dev logcat -d 2>&1 | Out-String)
Set-Content -Path $logFile -Value $log -Encoding UTF8

$started = ([regex]::Matches($log, 'engine\.search [^\r\n]*url=')).Count
$parsedHits = 0
foreach ($mm in [regex]::Matches($log, 'engine\.search [^\r\n]*-> (\d+) books')) {
    if ([int]$mm.Groups[1].Value -gt 0) { $parsedHits++ }
}
$done = $log -match 'search done:'
$fin = [regex]::Match($log, 'search done: ([^\r\n]*)')
$sum = [regex]::Match($log, 'engine\.searchAll ([^\r\n]*)')

Log ("window=" + $WindowSec + "s attempted=" + $started + " parsedNonEmpty=" + $parsedHits + " finished=" + $done)
if ($sum.Success) { Log ("engine.searchAll " + $sum.Groups[1].Value) }
if ($fin.Success) { Log ("search done: " + $fin.Groups[1].Value) }
Log ("raw log: " + $logFile)
exit 0
