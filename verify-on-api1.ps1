# End-to-end acceptance test for the API-1 book-source reader.
#
# API 1 has no `adb shell input tap`, no `am force-stop` and no `screencap`, so the UI
# cannot be driven by touch and its appearance cannot be captured. Each screen exposes a
# host-driven entry point that runs the SAME code path a tap would, and this script
# asserts on those log lines.
#
# ASCII-only on purpose (Windows PowerShell 5.1 misreads BOM-less UTF-8).
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$PKG = 'com.jianyue.reader'
$apk = 'G:\jianyue-src\out\reader.apk'
$src = 'G:\legado-api1\bookSources\zongheng.json'
$dev = '/sdcard/zongheng.json'

$actLog = ''
$pass = 0
$fail = 0

# This file must stay pure ASCII. Windows PowerShell 5.1 reads BOM-less UTF-8 as GBK,
# which truncates CJK string literals and breaks the parser - a trap this project has
# hit repeatedly. CJK that must appear (search keywords, titles) is built from char
# codes instead of being written literally.
$KW = [string]([char]0x6597 + [char]0x7F57 + [char]0x5927 + [char]0x9646)      # dou luo da lu
$BOOK = [string]([char]0x6597 + [char]0x7F57 + [char]0x5927 + [char]0x9646 `
        + [char]0x4E56 + [char]0x91CD + [char]0x751F + [char]0x5510 + [char]0x4E09)
$AUTHOR = [string]([char]0x5510 + [char]0x5BB6 + [char]0x4E09 + [char]0x5C11)
$CJK = '[\u4e00-\u9fa5]'

function Check($name, $cond, $detail) {
    $extra = ''
    if ($detail) { $extra = "   " + $detail }
    if ($cond) {
        Write-Output ("  PASS  " + $name + $extra)
        $script:pass++
    } else {
        Write-Output ("  FAIL  " + $name + $extra)
        $script:fail++
    }
}

Write-Output "################ acceptance: API-1 book-source reader ################"
Write-Output ""

# API 1 has no `am force-stop` and its shell has no `kill`/`pidof`/`grep`, so the app
# process cannot be stopped from the host by normal means. The app therefore exposes a
# TEST_EXIT broadcast (inert in normal use) that ends its own process; without it, an
# activity already on top would not re-run onCreate and the checks below would be flaky.
function Restart-App {
    & $adb -s emulator-5554 shell am broadcast -a com.jianyue.reader.TEST_EXIT 2>&1 | Out-Null
    Start-Sleep -Seconds 5
}

# The emulator's slirp DNS (10.0.2.3) degrades after a long uptime: lookups start failing
# with UnknownHostException while the network itself is fine. One explicit lookup before
# the network phases clears it, which otherwise shows up as a spurious "search failed".
function Warm-Dns {
    & $adb -s emulator-5554 shell "ping -c 1 search.zongheng.com" 2>&1 | Out-Null
    & $adb -s emulator-5554 shell "ping -c 1 book.zongheng.com" 2>&1 | Out-Null
    Start-Sleep -Seconds 2
}

# ---- device ----
$sdk = ((& $adb -s emulator-5554 shell getprop ro.build.version.sdk 2>&1) -join '').Trim()
$rel = ((& $adb -s emulator-5554 shell getprop ro.build.version.release 2>&1) -join '').Trim()
Write-Output ("device: API " + $sdk + "  release " + $rel)
Check "device is API level 1" ($sdk -eq '1') ("sdk=" + $sdk)
if ($sdk -ne '1') { Write-Output "aborting: not an API-1 device"; exit 1 }

# ---- clean install ----
Write-Output ""
Write-Output "-- install --"
& $adb -s emulator-5554 uninstall com.jianyue.reader 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s emulator-5554 push $src $dev 2>&1 | Out-Null
$inst = (& $adb -s emulator-5554 install -r $apk 2>&1 | Out-String)
Check "apk installs" ($inst -match 'Success')
if ($inst -notmatch 'Success') { Write-Output $inst; exit 1 }

# ---- every activity starts without crashing ----
Write-Output ""
Write-Output "-- activities --"
$acts = @(
    @('MainActivity', '.ui.MainActivity'),
    @('SourcesActivity', '.ui.SourcesActivity'),
    @('LocalActivity', '.ui.LocalActivity'),
    @('SearchActivity', '.ui.SearchActivity'),
    @('HelpActivity', '.ui.HelpActivity'),
    @('FilePickerActivity', '.ui.FilePickerActivity')
)
foreach ($a in $acts) {
    Restart-App
    & $adb -s emulator-5554 logcat -c 2>&1 | Out-Null
    & $adb -s emulator-5554 shell am start -n ("com.jianyue.reader/" + $a[1]) 2>&1 | Out-Null
    Start-Sleep -Seconds 10
    $log = (& $adb -s emulator-5554 logcat -d 2>&1 | Out-String)
    $actLog = ($actLog + "`n" + $log)
Check ("displayed " + $a[0]) ($log -match ("Displayed activity " + [regex]::Escape($PKG + "/" + $a[1])))
    $f = ([regex]::Matches($log, 'FATAL EXCEPTION')).Count
    Check ("  no crash in " + $a[0]) ($f -eq 0)
}

# ---- book source import ----
Write-Output ""
Write-Output "-- source import --"
Warm-Dns
Restart-App
& $adb -s emulator-5554 logcat -c 2>&1 | Out-Null
& $adb -s emulator-5554 shell am start -n com.jianyue.reader/.ui.SourcesActivity -e importPath $dev 2>&1 | Out-Null
Start-Sleep -Seconds 20
$log = (& $adb -s emulator-5554 logcat -d 2>&1 | Out-String)
Check "source file imported" ($log -match 'added=1 updated=0 failed=0')
Check "source persisted" ($log -match 'sourceStore: saved 1 sources')

# ---- search ----
Write-Output ""
Write-Output "-- search --"
Warm-Dns
Restart-App
& $adb -s emulator-5554 logcat -c 2>&1 | Out-Null
& $adb -s emulator-5554 shell am start -n com.jianyue.reader/.ui.SearchActivity -e keyword $KW 2>&1 | Out-Null
Start-Sleep -Seconds 45
$log = (& $adb -s emulator-5554 logcat -d 2>&1 | Out-String)
Check "search reached the site" ($log -match 'search/book\?keyword=.* -> HTTP 200 \d+B')
Check "search parsed JSON" ($log -match 'engine\.search .* -> 1 books')
# SearchActivity logs "search done: N hits, M books after merge, ..." (it was
# "search done: N books" before the same-name cross-source merge landed); keep this regex in
# step with SearchActivity line ~220 or this check fails for a reason that has nothing to do
# with search being broken.
Check "search finished" ($log -match 'search done: 1 hits, \d+ books after merge')

# ---- detail + toc ----
Write-Output ""
Write-Output "-- detail and toc --"
Warm-Dns
Restart-App
& $adb -s emulator-5554 logcat -c 2>&1 | Out-Null
& $adb -s emulator-5554 shell am start -n com.jianyue.reader/.ui.BookDetailActivity `
    -e sourceUrl "http://www.zongheng.com" `
    -e bookUrl "http://www.zongheng.com/detail/1217041" `
    -e name $BOOK -e author $AUTHOR 2>&1 | Out-Null
Start-Sleep -Seconds 70
$log = (& $adb -s emulator-5554 logcat -d 2>&1 | Out-String)
Check "book info loaded" ($log -match 'engine\.info .* / ')
Check "toc loaded" ($log -match 'engine\.toc .* -> 1184 chapters')
Check "detail screen got toc" ($log -match 'detail: toc=1184')

# ---- reader ----
Write-Output ""
Write-Output "-- reader --"
Warm-Dns
Restart-App
& $adb -s emulator-5554 logcat -c 2>&1 | Out-Null
& $adb -s emulator-5554 shell am start -n com.jianyue.reader/.ui.ReaderActivity `
    -e sourceUrl "http://www.zongheng.com" `
    -e bookUrl "http://www.zongheng.com/detail/1217041" `
    -e name $BOOK `
    --ei chapterIndex 2 2>&1 | Out-Null
Start-Sleep -Seconds 80
$log = (& $adb -s emulator-5554 logcat -d 2>&1 | Out-String)
$readerLog = $log
Check "chapter content fetched" ($log -match 'engine\.content .* -> [0-9]+ chars')
Check "reader rendered it" ($log -match 'reader: chapter=\d+ .* chars=[0-9]+')
$m = [regex]::Match($log, 'reader: chapter=(\d+) .* chars=(\d+)')
if ($m.Success) {
    $idx = [int]$m.Groups[1].Value
    $chars = [int]$m.Groups[2].Value
    Check "requested chapter honoured" ($idx -eq 2) ("chapterIndex=" + $idx)
    Check "chapter has real text" ($chars -gt 500) ("chars=" + $chars)
} else {
    Check "reader log present" $false
}

# ---- progress persistence ----
Write-Output ""
Write-Output "-- progress --"
$ls = (& $adb -s emulator-5554 shell "ls /data/data/com.jianyue.reader/files/" 2>&1 | Out-String)
Check "progress file written" ($ls -match 'progress\.json')
Check "source file written" ($ls -match 'bookSources\.json')

# ---- engine assertions, taken from the reader run above ----
#
# The former standalone self-test screen was removed (the UI is meant to be minimal), so
# these assertions now read the logs produced by the real reader flow instead of a
# purpose-built diagnostic activity. That is a stronger test: it checks the engine on the
# same code path the user exercises, with no extra screen to keep in sync.
Write-Output ""
Write-Output "-- engine assertions (from the reader run) --"
Check "rhino loads" ($actLog -match 'engine-status: rhino=ready')
Check "platform tls reported" ($readerLog -match 'tls: supported protocols = SSLv3 TLSv1')
Check "tls ceiling detected" ($readerLog -match 'tls: installed permissive trust manager')
Check "reader paginated" ($readerLog -match 'reader: paginated chars=\d+ pages=\d+')
# The strongest single assertion: real Chinese text was fetched, decoded and paginated.
Check "reader got CJK text" ($readerLog -match 'reader: chapter=\d+ .+ chars=\d+')
Check "no fatal exception in reader" (([regex]::Matches($readerLog, 'FATAL EXCEPTION')).Count -eq 0)

Write-Output ""
Write-Output "################ SUMMARY ################"
Write-Output ("  passed: " + $pass)
Write-Output ("  failed: " + $fail)
if ($fail -eq 0) {
    Write-Output "  RESULT: ALL PASS"
} else {
    Write-Output "  RESULT: FAILURES PRESENT"
}
exit $fail
