# Count how many book sources an APK can fully understand (no network needed).
#
# Usage: powershell -NoProfile -File tools-tmp\measure-sources.ps1 -Dev emulator-5554 `
#                     -Apk G:\jianyue-src\out\reader.apk -Label after `
#                     -Sources G:\legado-api1\source-analysis\yicheng-raw.json
#
# SourceCheckActivity parses the file through the real BookSource importer and reports, per
# source, whether every non-empty rule uses syntax the engine implements. That isolates the
# ENGINE from the network, which on API 1 is the other half of the story.
#
# Results are read from /sdcard/source-check.txt rather than logcat: the API-1 log buffer is
# tiny and a 231-source report overflows it.
#
# ASCII-only on purpose.
param(
    [string]$Dev = 'emulator-5554',
    [string]$Apk = 'G:\jianyue-src\out\reader.apk',
    [string]$Sources = 'G:\legado-api1\source-analysis\yicheng-raw.json',
    [string]$Label = 'run',
    [string]$Out = 'G:\legado-api1\source-analysis\engine-gap',
    [int]$TimeoutSec = 600
)
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$PKG = 'com.jianyue.reader'
$dst = '/sdcard/check-sources.json'
$local = Join-Path $Out ("source-check-" + $Label + ".txt")

function Log($m) { Write-Output ("[" + $Label + "] " + $m) }

& $adb -s $Dev uninstall $PKG 2>&1 | Out-Null
Start-Sleep -Seconds 2
$inst = (& $adb -s $Dev install -r $Apk 2>&1 | Out-String)
if ($inst -notmatch 'Success') { Log ("INSTALL FAILED: " + $inst); exit 1 }
Log ("installed " + (Split-Path $Apk -Leaf))

& $adb -s $Dev shell rm /sdcard/source-check.txt 2>&1 | Out-Null
& $adb -s $Dev push $Sources $dst 2>&1 | Out-Null
& $adb -s $Dev shell am start -n "$PKG/.ui.SourceCheckActivity" -e listPath $dst 2>&1 | Out-Null

# Poll the report FILE, not logcat: reading the file is cheap and cannot wedge the
# emulator the way repeated `logcat -d` does on these images.
$t0 = Get-Date
$last = -1
while (((Get-Date) - $t0).TotalSeconds -lt $TimeoutSec) {
    Start-Sleep -Seconds 10
    $out = (& $adb -s $Dev shell cat /sdcard/source-check.txt 2>&1 | Out-String)
    if ($out -match 'SUMMARY') { break }
    $n = ([regex]::Matches($out, "`n")).Count
    if ($n -ne $last) { $last = $n; Log ("progress lines=" + $n) }
}

& $adb -s $Dev pull /sdcard/source-check.txt $local 2>&1 | Out-Null
if (-not (Test-Path $local)) {
    # Older builds only log; the API-1 log buffer is small, so keep the source list short
    # enough that a whole run fits in it.
    Log "no report file (old build) - falling back to logcat"
    $raw = (& $adb -s $Dev logcat -d 2>&1 | Out-String)
    Set-Content -Path ($local + ".log") -Value $raw -Encoding UTF8
    $lines2 = @()
    $idx = -1
    foreach ($ln in ($raw -split "`n")) {
        $m = [regex]::Match($ln, 'check: \[(\d+)\]')
        if ($m.Success) { $idx = [int]$m.Groups[1].Value }
        $m2 = [regex]::Match($ln, 'verdict=(\w+)')
        if ($m2.Success -and $idx -ge 0) {
            $lines2 += ("VERDICT`t" + $idx + "`t" + $m2.Groups[1].Value)
            $idx = -1
        }
        $m3 = [regex]::Match($ln, 'check:   UNSUPPORTED [^:]+: .*?\(([^)]*)\)')
        if ($m3.Success) { $lines2 += ("UNSUPPORTED`t?`t" + $m3.Groups[1].Value + "`t?") }
        $m4 = [regex]::Match($ln, 'check: SUMMARY sources=(\d+) fullyCompatible=(\d+)')
        if ($m4.Success) { $lines2 += ("SUMMARY`tsources=" + $m4.Groups[1].Value + "`tfullyCompatible=" + $m4.Groups[2].Value) }
    }
    Set-Content -Path $local -Value $lines2 -Encoding UTF8
    Log ("logcat lines parsed: " + $lines2.Count)
}
if (-not (Test-Path $local)) { Log "REPORT FILE NOT FOUND"; exit 1 }
$text = Get-Content $local -Raw

$m = [regex]::Match($text, 'SUMMARY\tsources=(\d+)\tfullyCompatible=(\d+)')
if ($m.Success) {
    $n = [int]$m.Groups[1].Value
    $c = [int]$m.Groups[2].Value
    $pct = if ($n -gt 0) { [math]::Round(100.0 * $c / $n, 1) } else { 0 }
    Log ("SUMMARY sources=" + $n + " fullyCompatible=" + $c + " (" + $pct + "%)")
} else {
    Log "SUMMARY LINE NOT FOUND in the report"
}

$reasons = @{}
foreach ($line in ($text -split "`n")) {
    if ($line.StartsWith("UNSUPPORTED`t")) {
        $parts = $line.Split("`t")
        if ($parts.Length -ge 3) {
            $r = $parts[2]
            if ($reasons.ContainsKey($r)) { $reasons[$r] = $reasons[$r] + 1 } else { $reasons[$r] = 1 }
        }
    }
}
foreach ($h in ($reasons.GetEnumerator() | Sort-Object -Property Value -Descending)) {
    Log ("blocked: " + $h.Value + "  " + $h.Name)
}
$comp = ([regex]::Matches($text, "VERDICT`t\d+`tCOMPATIBLE")).Count
$part = ([regex]::Matches($text, "VERDICT`t\d+`tPARTIAL")).Count
Log ("verdicts COMPATIBLE=" + $comp + " PARTIAL=" + $part)
Log ("report saved to " + $local)
exit 0
