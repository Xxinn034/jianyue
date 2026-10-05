# Does the APK actually RUN on a very new Android once the targetSdk block is bypassed?
#
#   & 'G:\jianyue-src\tools\probe-api37-runtime.ps1'
#
# The plain install is refused with INSTALL_FAILED_DEPRECATED_SDK_VERSION (targetSdk=1 < 24),
# and `--bypass-low-target-sdk-block` proves the package itself is acceptable. This script
# answers the next question: with the block out of the way, does the app come up?
#
# ASCII only (Windows PowerShell 5.1 misreads BOM-less UTF-8).
param(
    [string]$Dev = 'emulator-5558',
    [string]$OutDir = 'G:\jianyue-src\tools\api37',
    [int]$WaitSec = 90
)
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$PKG = 'com.jianyue.reader'
$apk = 'G:\jianyue-src\out\reader.apk'
if (!(Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
$report = New-Object System.Collections.Generic.List[string]
function Say($s) { Write-Output $s; $report.Add("$s") }
function Sh($c) { return ((& $adb -s $Dev shell $c 2>&1) -join "`n").Trim() }
function Shot($name) {
    & $adb -s $Dev shell screencap -p /sdcard/$name.png 2>&1 | Out-Null
    & $adb -s $Dev pull /sdcard/$name.png (Join-Path $OutDir ($name + '.png')) 2>&1 | Out-Null
    $f = Join-Path $OutDir ($name + '.png')
    if (Test-Path $f) { Say ('  shot ' + $name + '.png ' + (Get-Item $f).Length + ' bytes') }
}

Say '################ runtime probe: plain Android 17 (API 37, 16KB pages) ################'
Say ('boot_completed=' + (Sh 'getprop sys.boot_completed'))
Say ('release=' + (Sh 'getprop ro.build.version.release') + ' api=' + (Sh 'getprop ro.build.version.sdk') + ' PAGE_SIZE=' + (Sh 'getconf PAGE_SIZE'))

Say ''
Say '-- install (bypass) --'
& $adb -s $Dev uninstall $PKG 2>&1 | Out-Null
$inst = (& $adb -s $Dev install -r --bypass-low-target-sdk-block $apk 2>&1 | Out-String).Trim()
Say ('  ' + ($inst -replace "`r?`n", ' | '))

Say ''
Say '-- launch MainActivity (shelf) --'
& $adb -s $Dev logcat -c 2>&1 | Out-Null
& $adb -s $Dev shell am start -n ($PKG + '/.ui.MainActivity') 2>&1 | Out-Null
Start-Sleep -Seconds $WaitSec
$pid_ = Sh ('pidof ' + $PKG)
Say ('  pid=' + $pid_)
& $adb -s $Dev logcat -d -s JianYue:* 2>&1 | Out-File -Encoding utf8 (Join-Path $OutDir 'mainactivity-app.log')
$applog = ((& $adb -s $Dev logcat -d -s JianYue:* 2>&1) -join "`n")
Say ('  app log lines=' + (($applog -split "`n" | Where-Object { $_ -match 'JianYue' }).Count))
foreach ($l in ($applog -split "`n" | Where-Object { $_ -match 'JianYue' } | Select-Object -First 10)) { Say ('  app> ' + ($l -replace '^\S+\s+\S+\s+\d+\s+\d+\s+I JianYue\s*:\s*', '')) }
$a = (Sh 'dumpsys activity activities | grep -m 3 -E "mResumedActivity|mFocusedApp|topResumedActivity"')
Say ('  resumed: ' + ($a -replace "`r?`n", ' | '))
Shot 'runtime-shelf'

Say ''
Say '-- launch ReaderActivity (needs the shelf entry, so expect it to add the book) --'
& $adb -s $Dev logcat -c 2>&1 | Out-Null
& $adb -s $Dev shell am start -f 0x04000000 -n ($PKG + '/.ui.ReaderActivity') `
    -e sourceUrl 'http://www.zongheng.com' `
    -e bookUrl 'http://www.zongheng.com/detail/1217041' `
    -e name 'zh' --ei chapterIndex 2 2>&1 | Out-Null
Start-Sleep -Seconds $WaitSec
$applog2 = ((& $adb -s $Dev logcat -d -s JianYue:* 2>&1) -join "`n")
$applog2 | Out-File -Encoding utf8 (Join-Path $OutDir 'reader-app.log')
foreach ($l in ($applog2 -split "`n" | Where-Object { $_ -match 'JianYue' } | Select-Object -First 14)) { Say ('  app> ' + ($l -replace '^\S+\s+\S+\s+\d+\s+\d+\s+I JianYue\s*:\s*', '')) }
$fatalAny = ((& $adb -s $Dev logcat -d 2>&1) -join "`n")
Say ('  FATAL EXCEPTION (whole log)=' + ([regex]::Matches($fatalAny, 'FATAL EXCEPTION')).Count)
Shot 'runtime-reader'

Say ''
Say ('report -> ' + (Join-Path $OutDir 'runtime-api37.txt'))
$report | Out-File -Encoding utf8 (Join-Path $OutDir 'runtime-api37.txt')
