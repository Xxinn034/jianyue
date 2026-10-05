# Test the filtered book sources in batches on the API-1 device.
#
# WHY BATCHES
# -----------
# Importing all 141 at once made the app hang inside SourceStore: parsing a ~1 MB
# bookSources.json is very slow on API 1's tiny heap. 25 per batch stays comfortable.
#
# The goal is the only result that matters: which sources actually return search hits.
# Everything before this (type check, syntax check, host reachability) narrows the field but
# cannot prove a source works against the live site.
#
# ASCII-only on purpose (Windows PowerShell 5.1 misreads BOM-less UTF-8).
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$PKG = 'com.jianyue.reader'
$dir = 'G:\legado-api1\source-analysis\batches'
$kw = [string]([char]0x6597 + [char]0x7F57 + [char]0x5927 + [char]0x9646)   # 斗罗大陆
$outFile = 'G:\legado-api1\source-analysis\search-hits.txt'

function Restart-App {
    & $adb -s emulator-5554 shell am broadcast -a "$PKG.TEST_EXIT" 2>&1 | Out-Null
    Start-Sleep -Seconds 5
}
function Warm-Dns {
    & $adb -s emulator-5554 shell "ping -c 1 search.zongheng.com" 2>&1 | Out-Null
    Start-Sleep -Seconds 1
}

if (Test-Path $outFile) { Remove-Item $outFile -Force }

$batches = Get-ChildItem $dir -Filter 'batch-*.json' | Sort-Object Name
Write-Output ("batches: " + $batches.Count)

foreach ($b in $batches) {
    Write-Output ""
    Write-Output ("===== " + $b.Name + " =====")

    Restart-App
    $dev = "/sdcard/" + $b.Name
    & $adb -s emulator-5554 push $b.FullName $dev 2>&1 | Out-Null

    # import this batch, replacing whatever was loaded before
    & $adb -s emulator-5554 shell am start -n "$PKG/.ui.SourcesActivity" -e importPath $dev 2>&1 | Out-Null
    Start-Sleep -Seconds 25

    # search with it
    Restart-App
    Warm-Dns
    & $adb -s emulator-5554 logcat -c 2>&1 | Out-Null
    & $adb -s emulator-5554 shell am start -n "$PKG/.ui.SearchActivity" -e keyword $kw 2>&1 | Out-Null

    # wait for the search to finish, polling rather than guessing
    $log = ''
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Seconds 10
        $log = (& $adb -s emulator-5554 logcat -d 2>&1 | Out-String)
        if ($log -match 'search done') { break }
    }

    $lines = $log -split "`n"
    foreach ($l in $lines) {
        if ($l -match 'engine\.search (.+?) -> (\d+) books' -and [int]$matches[2] -gt 0) {
            $entry = $b.Name + "`t" + $matches[1] + "`t" + $matches[2]
            Add-Content -Path $outFile -Value $entry -Encoding UTF8
            Write-Output ("  HIT  " + $matches[1] + "  -> " + $matches[2] + " books")
        }
    }
    $done = ($lines | Select-String -Pattern 'search done')
    if ($done) { Write-Output ("  " + ($done.Line -replace '.*JianYue\(\s*\d+\): ','')) }
    else { Write-Output "  search did not finish in time" }
}

Write-Output ""
Write-Output "################ hits ################"
if (Test-Path $outFile) {
    $hits = Get-Content $outFile -Encoding UTF8
    Write-Output ("total sources with hits: " + $hits.Count)
    $hits | ForEach-Object { Write-Output ("  " + $_) }
} else {
    Write-Output "no hits recorded"
}
