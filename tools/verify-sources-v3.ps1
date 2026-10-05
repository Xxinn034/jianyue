# End-to-end verification of the book sources in sources-v3.json, on the API-1 emulator.
#
#   & 'G:\jianyue-src\tools\verify-sources-v3.ps1'
#
# Steps: import the sources -> search -> open one real book per source and check that the
# table of contents loads AND that the chapter text is fetched with a sane character count.
#
# The character counts are the point. "The search returned hits" proves nothing about a
# source: the TOC and the chapter text use different rules, and a rule that returns a
# *partial* chapter still looks like success (the suixkan source returned 600 of 3004
# characters before the engine learned to join every CSS match the way Legado does).
#
# ASCII only: Windows PowerShell 5.1 misreads BOM-less UTF-8 and then cannot parse the file.
$ErrorActionPreference = 'Continue'

$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$dev = 'emulator-5554'
$pkg = 'com.jianyue.reader'
$CLEAR_TOP = '0x04000000'
$sourcesFile = 'G:\legado-api1\bookSources\sources-v3.json'

# the search keyword (Dou Lu Da Lu) and the source names, all built from code points so
# this file stays pure ASCII
$KW = [string]([char]0x6597 + [char]0x7F57 + [char]0x5927 + [char]0x9646)
# source names as they appear in the log, also built from code points
$S_ZH = [string]([char]0x7EB5 + [char]0x6A2A + [char]0x4E2D + [char]0x6587 + [char]0x7F51)
$S_SX = [string]([char]0x9605 + [char]0x53CB + [char]0x5C0F + [char]0x8BF4)
$S_XB = [string]([char]0x65B0 + [char]0x7B14 + [char]0x8DA3 + [char]0x9601) + 'xbiquge'
$S_HY = [string]([char]0x9ED1 + [char]0x5CA9 + [char]0x9605 + [char]0x8BFB) + 'heiyan'

$script:pass = 0
$script:fail = 0
function Check($name, $ok, $detail) {
    if ($ok) { $script:pass++; Write-Output ("PASS  " + $name + "  " + $detail) }
    else     { $script:fail++; Write-Output ("FAIL  " + $name + "  " + $detail) }
}

function Logs() {
    return (& $adb -s $dev logcat -d -s JianYue:I 2>&1) -join "`n"
}

function LMatch($text, $pattern) {
    $ms = [regex]::Matches($text, $pattern)
    if ($ms.Count -eq 0) { return [regex]::Match('', $pattern) }
    return $ms[$ms.Count - 1]
}

function Reset-App() {
    & $adb -s $dev logcat -c 2>&1 | Out-Null
    & $adb -s $dev shell am broadcast -a "$pkg.TEST_EXIT" 2>&1 | Out-Null
    Start-Sleep -Seconds 3
}

# Poll the log until the pattern shows up (or give up): the emulator is slow and a fixed
# sleep is either wasteful or, worse, flaky.
function Wait-Log($pattern, $timeoutSec) {
    $waited = 0
    while ($waited -lt $timeoutSec) {
        Start-Sleep -Seconds 5
        $waited += 5
        if ((Logs) -match $pattern) { return $true }
    }
    return $false
}

Write-Output "########## device ##########"
$sdk = (& $adb -s $dev shell getprop ro.build.version.sdk 2>&1) -join ''
Check "device is API 1" ($sdk.Trim() -eq '1') ("ro.build.version.sdk=" + $sdk.Trim())

Write-Output ""
Write-Output "########## import sources-v3.json ##########"
& $adb -s $dev push $sourcesFile /sdcard/v3.json 2>&1 | Out-Null
Reset-App
& $adb -s $dev shell am start -n "$pkg/.ui.SourcesActivity" -e importPath /sdcard/v3.json 2>&1 | Out-Null
Start-Sleep -Seconds 15
$out = Logs
$m = LMatch $out "importPath result: added=(\d+) updated=(\d+) failed=(\d+)"
Check "sources imported" ($m.Success -and [int]$m.Groups[3].Value -eq 0 -and
    (([int]$m.Groups[1].Value + [int]$m.Groups[2].Value) -eq 4)) $m.Value

Write-Output ""
Write-Output "########## search ##########"
Reset-App
& $adb -s $dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.SearchActivity" -e keyword $KW 2>&1 | Out-Null
Wait-Log "search done:" 180 | Out-Null
Start-Sleep -Seconds 5
$out = Logs
$m = LMatch $out "engine.searchAll sources=(\d+) threads=(\d+) books=(\d+)"
Check "sources answered" ($m.Success -and [int]$m.Groups[3].Value -gt 0) $m.Value
$m = LMatch $out "search done: (\d+) hits, (\d+) books after merge, failures=(\d+)"
Check "results survived the merge" ($m.Success -and [int]$m.Groups[2].Value -gt 0) $m.Value
foreach ($src in @($S_ZH, $S_SX, $S_XB, $S_HY)) {
    # names are matched by their ASCII part where possible
    if ($out -match ("engine\.search " + $src + " -> \d+ books")) {
        Check ("search hit: " + $src) $true ([regex]::Match($out, "engine\.search " + $src + " -> \d+ books").Value)
    } else {
        Check ("search hit: " + $src) $false "no hits (site down or rule broke)"
    }
}

# source name -> sourceUrl, bookUrl, minimum chapters, minimum content chars
$books = @(
    @{ n = 'zongheng'; src = 'http://www.zongheng.com';  book = 'http://www.zongheng.com/detail/1217041';  minCh = 1000; minTx = 500 },
    @{ n = 'suixkan';  src = 'http://m.suixkan.com/';    book = 'http://m.suixkan.com/c/229093.html';      minCh = 1600; minTx = 2500 },
    @{ n = 'xbiquge';  src = 'http://www.xbiquge.info/'; book = 'http://www.xbiquge.info/112/112570/';     minCh = 120;  minTx = 4000 },
    @{ n = 'heiyan';   src = 'http://www.heiyan.com';    book = 'http://www.heiyan.com/book/161350';       minCh = 5;    minTx = 2000 }
)

foreach ($b in $books) {
    Write-Output ""
    Write-Output ("########## reader: " + $b.n + " ##########")
    Reset-App
    & $adb -s $dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.ReaderActivity" `
        -e sourceUrl $b.src -e bookUrl $b.book -e name $b.n --ei chapterIndex 0 2>&1 | Out-Null
    Wait-Log "engine.content" 240 | Out-Null
    Start-Sleep -Seconds 8
    $out = Logs
    # the book name can be empty when the book is not on the shelf yet, so it is not captured
    $m = LMatch $out "engine\.toc[^\r\n]*?-> (\d+) chapters"
    Check ($b.n + " toc") ($m.Success -and [int]$m.Groups[1].Value -ge $b.minCh) $m.Value
    $c = LMatch $out "engine\.content ([^\r\n]*?) -> (\d+) chars"
    Check ($b.n + " content") ($c.Success -and [int]$c.Groups[2].Value -ge $b.minTx) $c.Value
    $p = LMatch $out "reader: paginated chars=(\d+) pages=(\d+)"
    Check ($b.n + " paginated") ($p.Success -and [int]$p.Groups[2].Value -ge 2) $p.Value
}

Write-Output ""
Write-Output ("########## RESULT: pass=" + $script:pass + " fail=" + $script:fail + " ##########")
if ($script:fail -gt 0) { exit 1 }
exit 0
