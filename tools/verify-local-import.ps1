# Verify the local-book import path end to end on the API-1 emulator.
#
#   & 'G:\jianyue-src\tools\verify-local-import.ps1'
#
# What it proves (one PASS/FAIL line each):
#   1. utf-8 .txt  -> imported, chapters found from the heading lines
#   2. gbk .txt    -> same chapter count as the utf-8 copy (encoding detection works)
#   3. .txt with no headings -> fallback chunking, numbered sections
#   4. .xhtml      -> markup stripped, <h2> headings become chapters
#   5. .epub       -> container/OPF/spine followed, one chapter per document
#   6. shelf       -> the book list still has height and the bottom tab bar is present, and
#                     the local page (tab 3) lists the imported books
#   7. reader      -> opens a local book at an explicit chapter and paginates
#   8. gestures    -> centre tap toggles the bars, long press opens the menu
#
# API-1 quirks that shaped this script:
#   - there is no `adb shell input tap`, so every screen is driven through the host
#     entries (importBook / chapterIndex / gesture) that call the real handlers;
#   - starting MainActivity (the task's root) must use FLAG_ACTIVITY_CLEAR_TOP, otherwise
#     the system only brings the existing task forward and REPLAYS the root activity's
#     original intent, so the new extras are ignored and the previous book is imported
#     again; the same flag is used everywhere so a stale intent can never win;
#   - int extras need `--ei` (`-e` delivers a String and getIntExtra then returns 0);
#   - the replayed intent is logged BEFORE the requested one, so checks look at the LAST
#     match in the log, never the first.
#
# ASCII only: Windows PowerShell 5.1 misreads BOM-less UTF-8 and then cannot parse the file.
$ErrorActionPreference = 'Continue'

$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$dev = 'emulator-5554'
$pkg = 'com.jianyue.reader'
$data = '/sdcard/local-test'
$CLEAR_TOP = '0x04000000'

# chapter-section characters, built from code points to keep this file ASCII
$DI = [string][char]0x7B2C      # "di"  (section prefix)
$JIE = [string][char]0x8282     # "jie" (section suffix)

$script:pass = 0
$script:fail = 0
function Check($name, $ok, $detail) {
    if ($ok) { $script:pass++; Write-Output ("PASS  " + $name + "  " + $detail) }
    else     { $script:fail++; Write-Output ("FAIL  " + $name + "  " + $detail) }
}

function Logs() {
    return (& $adb -s $dev logcat -d -s JianYue:I 2>&1) -join "`n"
}

# The replayed root intent is logged first, so the requested one is always the LAST match.
function LastMatch($text, $pattern) {
    $ms = [regex]::Matches($text, $pattern)
    if ($ms.Count -eq 0) { return [regex]::Match('', $pattern) }
    return $ms[$ms.Count - 1]
}

function Reset-App() {
    & $adb -s $dev logcat -c 2>&1 | Out-Null
    & $adb -s $dev shell am broadcast -a "$pkg.TEST_EXIT" 2>&1 | Out-Null
    Start-Sleep -Seconds 3
}

function Start-Main($extras) {
    Reset-App
    & $adb -s $dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.MainActivity" $extras 2>&1 | Out-Null
}

function Import-Book($path) {
    Start-Main @('-e', 'importBook', $path)
    Start-Sleep -Seconds 12
    return Logs
}

function Start-Reader($bookId, $chapterArgs) {
    Reset-App
    & $adb -s $dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.ReaderActivity" `
        -e sourceUrl local://local -e bookUrl $bookId $chapterArgs 2>&1 | Out-Null
    Start-Sleep -Seconds 12
    return Logs
}

Write-Output "########## device ##########"
$sdk = (& $adb -s $dev shell getprop ro.build.version.sdk 2>&1) -join ''
Check "device is API 1" ($sdk.Trim() -eq '1') ("ro.build.version.sdk=" + $sdk.Trim())

$IMPORTED = "local: imported (\S+) '([^']*)' format=(\w+) chapters=(\d+)"

# Each step looks for a pattern that can ONLY come from that step's file (the emulator
# replays the task root's previous intent on some starts, so "the last import line" is not
# a reliable attribution).
Write-Output ""
Write-Output "########## 1. utf-8 txt ##########"
$out = Import-Book "$data/novel-utf8.txt"
$m = LastMatch $out "local: imported \S+ 'novel-utf8' format=txt chapters=(\d+)"
Check "txt/utf-8 imported" $m.Success $m.Value
$utf8Chapters = 0
if ($m.Success) { $utf8Chapters = [int]$m.Groups[1].Value }
Check "txt/utf-8 chapter count" ($utf8Chapters -ge 10) ("chapters=" + $utf8Chapters)

Write-Output ""
Write-Output "########## 2. gbk txt ##########"
$out = Import-Book "$data/novel-gbk.txt"
$m = LastMatch $out "local: imported \S+ 'novel' format=txt chapters=(\d+)"
Check "txt/gbk imported" $m.Success $m.Value
$gbkChapters = 0
if ($m.Success) { $gbkChapters = [int]$m.Groups[1].Value }
Check "gbk == utf8 chapter count" ($gbkChapters -eq $utf8Chapters -and $gbkChapters -gt 0) `
    ("gbk=" + $gbkChapters + " utf8=" + $utf8Chapters)

Write-Output ""
Write-Output "########## 3. txt without headings ##########"
$out = Import-Book "$data/nohead.txt"
$m = LastMatch $out "local: imported \S+ 'nohead' format=txt chapters=(\d+)"
Check "noheading txt imported" $m.Success $m.Value
Check "fallback chunk count" ($m.Success -and [int]$m.Groups[1].Value -ge 2) ("chapters=" + $m.Groups[1].Value)
# The reader is opened EXPLICITLY on the book this step imported. The import flow opens it by
# itself, but that start races with the replayed task-root intent (see the header) and with the
# books earlier steps left on the shelf, so the auto-open is not a dependable signal. The id is
# taken from this step's own import line.
$noheadId = ''
if ($m.Success) { $noheadId = $m.Groups[1].Value }
if ($noheadId.Length -gt 0) {
    $out = Start-Reader $noheadId @('--ei', 'chapterIndex', '0')
}
Check "fallback chapter numbered" ($out -match ("local chapter=0 " + $DI + " 1 " + $JIE)) `
    (LastMatch $out "reader: local chapter=0[^\r\n]*").Value

Write-Output ""
Write-Output "########## 4. xhtml ##########"
$out = Import-Book "$data/single.xhtml"
$m = LastMatch $out "local: imported \S+ '([^']*)' format=(xhtml|html) chapters=(\d+)"
Check "html imported" $m.Success $m.Value
Check "html chapter count" ($m.Success -and [int]$m.Groups[3].Value -ge 4) ("chapters=" + $m.Groups[3].Value)

Write-Output ""
Write-Output "########## 5. epub ##########"
$out = Import-Book "$data/book.epub"
$m = LastMatch $out $IMPORTED
Check "epub imported" ($m.Success -and $m.Groups[3].Value -eq 'epub') $m.Value
Check "epub chapter count" ($m.Success -and [int]$m.Groups[4].Value -eq 4) ("chapters=" + $m.Groups[4].Value)
$epubId = ''
if ($m.Success) { $epubId = $m.Groups[1].Value }
Check "epub title from OPF metadata" ($m.Success -and $m.Groups[2].Value.Length -gt 6) `
    ("title=" + $m.Groups[2].Value)

Write-Output ""
Write-Output "########## 6. shelf layout + tab bar ##########"
Start-Main @('-e', 'layoutDump', '1')
Start-Sleep -Seconds 10
$out = Logs
$m3 = LastMatch $out "layout: list top=(\d+) left=(\d+) w=(\d+) h=(\d+)"
Check "book list still has height" ($m3.Success -and [int]$m3.Groups[4].Value -gt 50) $m3.Value
$t = LastMatch $out "layout: tab_local top=(\d+) left=(\d+) w=(\d+) h=(\d+)"
Check "bottom tab bar is on the shelf" ($t.Success -and [int]$t.Groups[3].Value -gt 100 -and [int]$t.Groups[4].Value -gt 40) $t.Value
if ($m3.Success -and $t.Success) {
    Check "tab bar sits under the list" ([int]$t.Groups[1].Value -ge ([int]$m3.Groups[1].Value + [int]$m3.Groups[4].Value)) `
        ("tab top=" + $t.Groups[1].Value + " list bottom=" + ([int]$m3.Groups[1].Value + [int]$m3.Groups[4].Value))
}
# the imports above must be visible on their own page, not only on the shelf
& $adb -s $dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.LocalActivity" -e layoutDump 1 2>&1 | Out-Null
Start-Sleep -Seconds 10
$out = Logs
$m = LastMatch $out "page: local books=(\d+)"
Check "local page lists the imports" ($m.Success -and [int]$m.Groups[1].Value -ge 1) $m.Value
$e = LastMatch $out "layout: local_empty text=(.+)"
Check "empty-state wording logged" $e.Success $e.Value

Write-Output ""
Write-Output "########## 7. reader: local chapter + pagination ##########"
if ($epubId.Length -gt 0) {
    $out = Start-Reader $epubId @('--ei', 'chapterIndex', '2')
    $line = (LastMatch $out "reader: local chapter=2[^\r\n]*").Value
    Check "explicit local chapter opened" ($line.Length -gt 0) $line
    $pg = (LastMatch $out "reader: paginated[^\r\n]*").Value
    Check "local chapter paginated" ($pg -match "pages=[1-9]\d*") $pg
} else {
    Check "reader: local chapter + pagination" $false "no epub id captured"
}

Write-Output ""
Write-Output "########## 8. reader gestures ##########"
if ($epubId.Length -gt 0) {
    $out = Start-Reader $epubId @('-e', 'gesture', 'center')
    $line = (LastMatch $out "reader: chrome [^\r\n]*").Value
    Check "centre tap toggles bars" ($line -match "chrome shown") $line
    $inset = (LastMatch $out "reader: safe bottom inset[^\r\n]*").Value
    Check "bars re-cut the page" ($inset -match "areaH=[1-9]") $inset

    $out = Start-Reader $epubId @('-e', 'gesture', 'menu')
    $line = (LastMatch $out "reader: long press[^\r\n]*").Value
    Check "long press opens the menu" ($line.Length -gt 0) $line
} else {
    Check "reader gestures" $false "no epub id captured"
}

Write-Output ""
Write-Output ("########## RESULT: pass=" + $script:pass + " fail=" + $script:fail + " ##########")
if ($script:fail -gt 0) { exit 1 }
exit 0
