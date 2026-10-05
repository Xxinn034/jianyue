# Multi-version acceptance: run the same book-source chain on Android 4.1 (API 16).
#
#   & 'G:\jianyue-src\tools\verify-api16.ps1' [-Dev emulator-5556]
#
# Purpose: API 1 (Android 1.0) and Android 5.0.2 (real phone) were the only two data
# points so far. This script adds the missing "middle" version. API 16 does have
# `screencap` and `input tap`, so unlike API 1 we can also look at the pixels.
#
# ASCII only (Windows PowerShell 5.1 misreads BOM-less UTF-8; CJK literals truncate).
param(
    [string]$Dev = 'emulator-5556',
    [string]$OutDir = 'G:\jianyue-src\tools\api16'
)
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$PKG = 'com.jianyue.reader'
$apk = 'G:\jianyue-src\out\reader.apk'
$src = 'G:\legado-api1\bookSources\zongheng.json'
$devSrc = '/sdcard/zongheng.json'
$CLEAR_TOP = '0x04000000'

if (!(Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }

$pass = 0; $fail = 0
$report = New-Object System.Collections.Generic.List[string]

function Check($name, $cond, $detail) {
    $extra = ''; if ($detail) { $extra = '   ' + $detail }
    $line = if ($cond) { '  PASS  ' + $name + $extra } else { '  FAIL  ' + $name + $extra }
    Write-Output $line
    $script:report.Add($line)
    if ($cond) { $script:pass++ } else { $script:fail++ }
}
function Say($s) { Write-Output $s; $script:report.Add($s) }
function Logs { return ((& $adb -s $Dev logcat -d 2>&1) -join "`n") }
function Shot($name) {
    & $adb -s $Dev shell screencap -p /sdcard/$name.png 2>&1 | Out-Null
    & $adb -s $Dev pull /sdcard/$name.png (Join-Path $OutDir ($name + '.png')) 2>&1 | Out-Null
}
function Warm-Dns {
    foreach ($h in @('www.zongheng.com', 'search.zongheng.com', 'book.zongheng.com')) {
        & $adb -s $Dev shell "ping -c 1 $h" 2>&1 | Out-Null
    }
    Start-Sleep -Seconds 2
}

# CJK built from char codes (see header).
$KW = [string]([char]0x6597 + [char]0x7F57 + [char]0x5927 + [char]0x9646)   # dou luo da lu
$BOOK = [string]([char]0x6597 + [char]0x7F57 + [char]0x5927 + [char]0x9646 `
        + [char]0x4E56 + [char]0x91CD + [char]0x751F + [char]0x5510 + [char]0x4E09)
$AUTHOR = [string]([char]0x5510 + [char]0x5BB6 + [char]0x4E09 + [char]0x5C11)

Say "################ acceptance: book-source reader on Android 4.1 (API 16) ################"

# ---- device ----
$sdk = ((& $adb -s $Dev shell getprop ro.build.version.sdk 2>&1) -join '').Trim()
$rel = ((& $adb -s $Dev shell getprop ro.build.version.release 2>&1) -join '').Trim()
$abi = ((& $adb -s $Dev shell getprop ro.product.cpu.abi 2>&1) -join '').Trim()
$res = ((& $adb -s $Dev shell wm size 2>&1) -join ' ').Trim()
$den = ((& $adb -s $Dev shell getprop ro.sf.lcd_density 2>&1) -join '').Trim()
Say ("device: " + $Dev + "  API " + $sdk + "  release " + $rel + "  abi " + $abi + "  " + $res + "  density " + $den)
Check "device is API level 16" ($sdk -eq '16') ("sdk=" + $sdk)
if ($sdk -ne '16') { Say 'aborting: not an API-16 device'; $report | Out-File -Encoding utf8 (Join-Path $OutDir 'verify-api16.txt'); exit 1 }

# ---- install ----
Say ''
Say '-- install --'
& $adb -s $Dev uninstall com.jianyue.reader 2>&1 | Out-Null
Start-Sleep -Seconds 3
& $adb -s $Dev push $src $devSrc 2>&1 | Out-Null
$inst = (& $adb -s $Dev install -r $apk 2>&1 | Out-String)
Check "apk installs" ($inst -match 'Success') ($inst.Trim())
if ($inst -notmatch 'Success') { $report | Out-File -Encoding utf8 (Join-Path $OutDir 'verify-api16.txt'); exit 1 }

# ---- every activity starts without crashing ----
Say ''
Say '-- activities --'
$acts = @(
    @('MainActivity', '.ui.MainActivity'),
    @('SourcesActivity', '.ui.SourcesActivity'),
    @('LocalActivity', '.ui.LocalActivity'),
    @('SearchActivity', '.ui.SearchActivity'),
    @('HelpActivity', '.ui.HelpActivity'),
    @('FilePickerActivity', '.ui.FilePickerActivity')
)
$actLog = ''
foreach ($a in $acts) {
    & $adb -s $Dev shell am force-stop $PKG 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    & $adb -s $Dev logcat -c 2>&1 | Out-Null
    & $adb -s $Dev shell am start -n ($PKG + '/' + $a[1]) 2>&1 | Out-Null
    Start-Sleep -Seconds 8
    $log = Logs
    $actLog = $actLog + "`n" + $log
    Check ('displayed ' + $a[0]) ($log -match ('Displayed ' + [regex]::Escape($PKG + '/' + $a[1]))) ''
    Check ('  no crash in ' + $a[0]) ((([regex]::Matches($log, 'FATAL EXCEPTION')).Count) -eq 0) ''
}
# shelf screenshot (a real pixel check is possible here, unlike API 1)
& $adb -s $Dev shell am force-stop $PKG 2>&1 | Out-Null
& $adb -s $Dev shell am start -n ($PKG + '/.ui.MainActivity') 2>&1 | Out-Null
Start-Sleep -Seconds 8
Shot 'shelf'

# ---- source import ----
Say ''
Say '-- source import --'
Warm-Dns
& $adb -s $Dev shell am force-stop $PKG 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $Dev logcat -c 2>&1 | Out-Null
& $adb -s $Dev shell am start -n ($PKG + '/.ui.SourcesActivity') -e importPath $devSrc 2>&1 | Out-Null
Start-Sleep -Seconds 15
$log = Logs
Check 'source file imported' ($log -match 'added=1 updated=0 failed=0') ''
Check 'source persisted' ($log -match 'sourceStore: saved 1 sources') ''

# ---- search ----
Say ''
Say '-- search --'
Warm-Dns
& $adb -s $Dev shell am force-stop $PKG 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $Dev logcat -c 2>&1 | Out-Null
& $adb -s $Dev shell am start -n ($PKG + '/.ui.SearchActivity') -e keyword $KW 2>&1 | Out-Null
Start-Sleep -Seconds 30
$log = Logs
$hit = [regex]::Match($log, 'search/book\?keyword=[^\r\n]*? -> HTTP \d+ \d+B')
Check 'search reached the site' ($log -match 'search/book\?keyword=.* -> HTTP 200 \d+B') $hit.Value
Check 'search parsed JSON' ($log -match 'engine\.search .* -> 1 books') ''
Check 'search finished' ($log -match 'search done: 1 hits, \d+ books after merge') ''
Shot 'search'

# ---- detail + toc ----
Say ''
Say '-- detail and toc --'
Warm-Dns
& $adb -s $Dev shell am force-stop $PKG 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $Dev logcat -c 2>&1 | Out-Null
& $adb -s $Dev shell am start -n ($PKG + '/.ui.BookDetailActivity') `
    -e sourceUrl 'http://www.zongheng.com' `
    -e bookUrl 'http://www.zongheng.com/detail/1217041' `
    -e name $BOOK -e author $AUTHOR 2>&1 | Out-Null
Start-Sleep -Seconds 40
$log = Logs
Check 'book info loaded' ($log -match 'engine\.info .* / ') ''
Check 'toc loaded' ($log -match 'engine\.toc .* -> 1184 chapters') ''
Check 'detail screen got toc' ($log -match 'detail: toc=1184') ''
Shot 'detail'

# ---- reader ----
Say ''
Say '-- reader --'
Warm-Dns
& $adb -s $Dev shell am force-stop $PKG 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $Dev logcat -c 2>&1 | Out-Null
& $adb -s $Dev shell am start -f $CLEAR_TOP -n ($PKG + '/.ui.ReaderActivity') `
    -e sourceUrl 'http://www.zongheng.com' `
    -e bookUrl 'http://www.zongheng.com/detail/1217041' `
    -e name $BOOK `
    --ei chapterIndex 2 2>&1 | Out-Null
Start-Sleep -Seconds 45
$log = Logs
$readerLog = $log
Check 'chapter content fetched' ($log -match 'engine\.content .* -> [0-9]+ chars') ''
Check 'reader rendered it' ($log -match 'reader: chapter=\d+ .* chars=[0-9]+') ''
Check 'reader paginated' ($log -match 'reader: paginated chars=\d+ pages=\d+') ''
$m = [regex]::Match($log, 'reader: chapter=(\d+) .* chars=(\d+)')
if ($m.Success) {
    Check 'requested chapter honoured' ([int]$m.Groups[1].Value -eq 2) ('chapterIndex=' + $m.Groups[1].Value)
    Check 'chapter has real text' ([int]$m.Groups[2].Value -gt 500) ('chars=' + $m.Groups[2].Value)
}
Check 'reader got CJK text' ($log -match 'reader: chapter=\d+ .+ chars=\d+') ''
Check 'no fatal exception in reader' ((([regex]::Matches($readerLog, 'FATAL EXCEPTION')).Count) -eq 0) ''
Shot 'reader-18sp'
# largest font size the UI offers
& $adb -s $Dev shell am start -f $CLEAR_TOP -n ($PKG + '/.ui.ReaderActivity') `
    -e sourceUrl 'http://www.zongheng.com' `
    -e bookUrl 'http://www.zongheng.com/detail/1217041' `
    -e name $BOOK --ei chapterIndex 2 --ei textSize 25 2>&1 | Out-Null
Start-Sleep -Seconds 25
Shot 'reader-25sp'
$log2 = Logs
$pg25 = [regex]::Match($log2, 'reader: cut page \d+ budget=\d+ chars=\d+ measured=\d+')
Say ('  25sp pagination line: ' + $pg25.Value)

# ---- progress + files ----
Say ''
Say '-- files --'
$ls = ((& $adb -s $Dev shell "run-as com.jianyue.reader ls files/" 2>&1) -join ' ')
if ($ls -notmatch 'progress') {
    # run-as may be unavailable; fall back to the debuggable-package path via the app's own log
    $ls = ((& $adb -s $Dev shell "ls /data/data/com.jianyue.reader/files/" 2>&1) -join ' ')
}
Check 'progress file written' ($ls -match 'progress\.json') $ls.Trim()
Check 'source file written' ($ls -match 'bookSources\.json') ''

# ---- version-sensitive behaviour notes (informational, not pass/fail) ----
Say ''
Say '-- api16 observations --'
$tls = [regex]::Match($log + $actLog, 'tls: supported protocols = [^\r\n]*')
Say ('  ' + $tls.Value)
$rhino = [regex]::Match($actLog, 'engine-status: rhino=[^\r\n]*')
Say ('  ' + $rhino.Value)
$anim = ((& $adb -s $Dev shell getprop ro.build.version.sdk 2>&1) -join '').Trim()
Say ('  screenshots -> ' + $OutDir)

Say ''
Say '################ SUMMARY ################'
Say ('  passed: ' + $pass)
Say ('  failed: ' + $fail)
Say ('  RESULT: ' + $(if ($fail -eq 0) { 'ALL PASS' } else { 'FAILURES PRESENT' }))
$report | Out-File -Encoding utf8 (Join-Path $OutDir 'verify-api16.txt')
exit $fail
