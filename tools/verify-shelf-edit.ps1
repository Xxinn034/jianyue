# Verify the shelf's batch-edit mode, the local page's add-to-shelf action, and the single-store
# refresh, on a REAL early device.
#
#   & 'G:\jianyue-src\tools\verify-shelf-edit.ps1' [-Dev <serial>] [-SkipInstall] [-SkipLibrary]
#
# What it proves (one PASS/FAIL line each):
#   1. baseline  - the shelf starts, reports its book count, and the layout is intact
#   2. import    - importing a local file does NOT put it on the shelf: the count is unchanged
#                  and the file is listed on the local page instead
#   3. refresh   - the add-to-shelf action on the local page shows up on the shelf in the SAME
#                  process, with no app restart. This is the reported bug: adding to the shelf
#                  seemed to take minutes and only appeared after restarting the app, because
#                  every activity kept its own in-memory copy of progress.json.
#   4. edit mode - a long press enters selection mode: the batch bar appears with its
#                  select-all / delete actions and the row grows a checkbox, ticked for the
#                  row that was pressed
#   5. select all- select-all ticks every row and the bar counts them
#   6. local page- the local page has the same long-press mode, and its own shelf action
#   7. shots     - PNGs of the shelf and of selection mode, taken off the framebuffer
#   8. delete    - batch delete really drops the shelf entries and really removes an imported
#                  file (and its cached text)
#
# THE USER'S LIBRARY IS PARKED FOR THE WHOLE RUN (-SkipLibrary turns that off). progress.json is
# copied aside and files/local is moved aside, the run works on a synthetic shelf of three books
# plus one file the script imports itself, and a finally block puts everything back. That matters
# on a real phone: without it every run would leave another imported test book behind, and a crash
# mid-run could leave the phone with no library at all.
#
# DEVICE NOTES (Coolpad 8056, Android 2.3.5 - the device the user tests on)
#   - there is no `adb shell input tap` (this `input` only knows text/keyevent), so the long
#     press cannot be injected. It is driven through the `editMode` extra, which calls exactly
#     what the long-press listener calls; the gesture itself is the one the book-source page has
#     been using all along.
#   - there is no screencap binary. The screen is read off /dev/graphics/fb0 (32 bpp BGRA, two
#     320x480 pages) and decoded by tools/fb_to_png.py --bpp 4 --order bgra --rotate none.
#   - starting the task root needs FLAG_ACTIVITY_CLEAR_TOP (0x04000000), otherwise the system
#     only brings the existing task forward and the new extras are ignored (the previous intent
#     is replayed instead, which can run an import twice - hence the count-tolerant checks).
#   - a fresh process is forced with the TEST_EXIT broadcast: this `am` has no force-stop, and
#     restarting an activity that is already on top does not re-run onCreate, so a host hook
#     would never fire twice.
#   - the library is written back with `cat >`, never `cp`: a root-owned progress.json makes
#     every later save fail with "Permission denied" (measured), which looks exactly like the
#     refresh bug this script is about.
#   - a focused search box plus a device stuck in non-touch mode (which a host that cannot inject
#     touches leaves it in) opens the IME at startup and covers the page, so shots are taken
#     after an ESCAPE key press, which closes the IME and nothing else.
#
# ASCII only: Windows PowerShell 5.1 reads a BOM-less UTF-8 .ps1 as ANSI, which turns every
# non-ASCII character in this file (comments included) into mojibake AND silently breaks the
# string literals below. The Chinese words that have to be matched are therefore built from code
# points, exactly as tools/verify-tabs.ps1 does.
param(
    [string]$Dev = '',
    [switch]$SkipInstall,
    [switch]$SkipLibrary,
    [switch]$ShowLogs
)
$ErrorActionPreference = 'Continue'

$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }
$pkg = 'com.jianyue.reader'
$apk = 'G:\jianyue-src\out\reader.apk'
$proj = 'G:\jianyue-src'
$outDir = "$proj\out"
$py = 'C:\Users\LX\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
$CLEAR_TOP = '0x04000000'
$cardsDir = '/sdcard/jy-verify'
$testBook = "$cardsDir/shelf-test.txt"
$filesDir = "/data/data/$pkg/files"

# Chinese words the log lines contain, built from code points (see the header).
$DI = [string][char]0x7B2C                                  # "di"    (chapter prefix)
$ZHANG = [string][char]0x7AE0                               # "zhang" (chapter suffix)
$YIXUAN = -join ([int[]]@(0x5DF2, 0x9009) | ForEach-Object { [char]$_ })   # yi-xuan
$BEN = [string][char]0x672C                                 # "ben"   (counter for books)
$SHANCHU = -join ([int[]]@(0x5220, 0x9664) | ForEach-Object { [char]$_ })  # shan-chu

$script:pass = 0
$script:fail = 0
function Check($name, $ok, $detail) {
    if ($ok) { $script:pass++; Write-Output ("PASS  " + $name + "  " + $detail) }
    else     { $script:fail++; Write-Output ("FAIL  " + $name + "  " + $detail) }
}

function Note($msg) { Write-Output ("      " + $msg) }

# -ShowLogs prints what each step's page actually logged, which is what a failing check has to be
# read against (the details on the FAIL line are only the matched groups).
function Dump-Log($text, $tag) {
    if (-not $ShowLogs) { return }
    Note ("---- raw log: " + $tag + " ----")
    foreach ($line in ($text -split "`n")) {
        $t = $line.Trim()
        if ($t.Length -gt 0) { Note $t }
    }
}

# The log can hold several hits (a page may be started twice, a hook logs before and after), so
# checks that care about the final state look at the LAST hit.
function LastMatch($text, $pattern) {
    $ms = [regex]::Matches($text, $pattern)
    if ($ms.Count -eq 0) { return [regex]::Match('', $pattern) }
    return $ms[$ms.Count - 1]
}

# Safe group access: a failed match must not blow up while a detail string is built.
function G($m, $i) {
    if (-not $m.Success) { return '-' }
    if ($i -ge $m.Groups.Count) { return '-' }
    return $m.Groups[$i].Value
}

function Logs() {
    return (& $adb -s $Dev logcat -d -s JianYue:I 2>&1) -join "`n"
}

function Shell($cmd) {
    return ((& $adb -s $Dev shell $cmd 2>&1) -join "`n").Trim()
}

function Su($cmd) {
    # quoting matters: the remote shell has to see su -c '<one argument>'
    return ((& $adb -s $Dev shell "su -c '$cmd'" 2>&1) -join "`n").Trim()
}

function Reset-App() {
    & $adb -s $Dev logcat -c 2>&1 | Out-Null
    & $adb -s $Dev shell am broadcast -a "$pkg.TEST_EXIT" 2>&1 | Out-Null
    Start-Sleep -Seconds 3
    & $adb -s $Dev logcat -c 2>&1 | Out-Null
}

function Start-Page($activity, $extras, $wait) {
    Reset-App
    if ($extras) {
        & $adb -s $Dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.$activity" $extras 2>&1 | Out-Null
    } else {
        & $adb -s $Dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.$activity" 2>&1 | Out-Null
    }
    Start-Sleep -Seconds $wait
    return Logs
}

# Push one more screen on top WITHOUT killing the process: the checks that matter here are the
# ones where a page started earlier has to notice a write made on another page.
function Start-OnTop($activity, $extras, $wait) {
    & $adb -s $Dev logcat -c 2>&1 | Out-Null
    if ($extras) {
        & $adb -s $Dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.$activity" $extras 2>&1 | Out-Null
    } else {
        & $adb -s $Dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.$activity" 2>&1 | Out-Null
    }
    Start-Sleep -Seconds $wait
    return Logs
}

function Back-Key() { & $adb -s $Dev shell input keyevent 4 2>&1 | Out-Null }

function ShelfCount($text) {
    $m = LastMatch $text "shelf: (\d+) books"
    if (-not $m.Success) { return -1 }
    return [int]$m.Groups[1].Value
}

# The uid the app runs as, so files written as root can be handed back (see the header).
function App-Uid {
    $ps = Shell "ps"
    $line = ($ps -split "`n") | Where-Object { $_ -match 'com\.jianyue\.reader\s*$' } | Select-Object -Last 1
    if (-not $line) { return '' }
    $parts = @($line -split '\s+')
    $st = Su "cat /proc/$($parts[1])/status"
    if ($st -match 'Uid:\s+(\d+)') { return $Matches[1] }
    return ''
}

# Write a file into the app's private dir as root, keeping the target's owner: `cp` would replace
# the inode with a root-owned one and the app could not save again (measured).
#
# NOTE: a source path that starts with "/" is ALREADY ON THE DEVICE (the parked backup) and is
# copied with `cat` on the device. `adb push` cannot read a device path - an earlier version of
# this helper passed one and silently pushed nothing, so the restore wrote back whatever payload
# was still on the card, i.e. the synthetic library instead of the user's. That is exactly the
# kind of mistake a "restore" helper must not make.
function Put-AppFile($source, $appPath) {
    if ($source.StartsWith('/')) {
        Su "cat $source > $appPath" | Out-Null
    } else {
        & $adb -s $Dev push $source "$cardsDir/payload" 2>&1 | Out-Null
        Su "cat $cardsDir/payload > $appPath" | Out-Null
    }
    if ($uid) { Su "chown $uid.$uid $appPath" | Out-Null }
}

# Read the live framebuffer page as a PNG. No screencap on this device; see the header.
function Save-Shot($file) {
    & $adb -s $Dev shell input keyevent 111 2>&1 | Out-Null   # close the IME, if any
    Start-Sleep -Seconds 1
    $fb = Shell "cat /sys/class/graphics/fb0/virtual_size"
    $bpp = Shell "cat /sys/class/graphics/fb0/bits_per_pixel"
    $pan = Shell "cat /sys/class/graphics/fb0/pan"
    if ($fb -notmatch '(\d+),(\d+)') { Note "no framebuffer info, shot skipped"; return $false }
    $rw = [int]$Matches[1]
    $rh = [int]$Matches[2]
    $panelH = $rh
    if ($rh -gt 480 -and ($rh % 480) -eq 0) { $panelH = 480 }   # two 480 line pages
    $page = 0
    if ($pan -match '^\s*\d+\s*,\s*(\d+)') { $page = [int]([int]$Matches[1] / $panelH) }
    $size = "$rw" + "x" + "$panelH"
    & $adb -s $Dev shell "su -c 'dd if=/dev/graphics/fb0 of=$cardsDir/fb.raw bs=4096 2>/dev/null'" 2>&1 | Out-Null
    $raw = "$outDir\verify-fb.raw"
    Remove-Item $raw -Force -ErrorAction SilentlyContinue
    & $adb -s $Dev pull "$cardsDir/fb.raw" $raw 2>&1 | Out-Null
    if (-not (Test-Path $raw)) { Note "framebuffer pull failed, shot skipped"; return $false }
    $bppArg = '4'
    if ([int]$bpp -eq 2) { $bppArg = '2' }
    & $py "$proj\tools\fb_to_png.py" $raw "$outDir\verify-shot" --size $size --rotate none --bpp $bppArg --order bgra 2>&1 | Out-Null
    $live = "$outDir\verify-shot-page$page.png"
    if (-not (Test-Path $live)) { Note "decode failed, shot skipped"; return $false }
    Copy-Item $live $file -Force
    Note ("shot: " + $file + "  (raw " + (Get-Item $raw).Length + " bytes, bpp=" + $bpp + ", page " + $page + ")")
    return $true
}

# ------------------------------------------------------------------- device

Write-Output "########## device ##########"
if (-not $Dev) {
    $list = @((& $adb devices 2>&1) | Select-String -Pattern "^(\S+)\s+device")
    if ($list.Count -ne 1) {
        Write-Output ("need exactly one connected device (or pass -Dev); found " + $list.Count)
        exit 1
    }
    $Dev = $list[0].Matches[0].Groups[1].Value
}
$sdk = Shell "getprop ro.build.version.sdk"
$model = Shell "getprop ro.product.model"
$rel = Shell "getprop ro.build.version.release"
Write-Output ("device: " + $Dev + "  " + $model + "  Android " + $rel + " (API " + $sdk + ")")
$haveRoot = (Su "id") -match 'uid=0'
$rootDetail = 'no su: the library cannot be parked'
if ($haveRoot) { $rootDetail = 'uid=0' }
Check "device seen by adb" ($sdk -match '^\d+$') ("sdk=" + $sdk)
Check "root available for library park/restore" $haveRoot $rootDetail

if (-not $SkipInstall) {
    Write-Output ""
    Write-Output "########## install ##########"
    $res = (& $adb -s $Dev install -r $apk 2>&1) -join ' '
    Check "apk installed" ($res -match 'Success') $res.Trim()
    Start-Sleep -Seconds 2
}

& $adb -s $Dev shell mkdir -p $cardsDir 2>&1 | Out-Null
$uid = App-Uid
Note ("app uid = " + $uid)

# The shelf the checks run against: three online books, written by this script.
$fake = "["
foreach ($i in 1..3) {
    if ($i -gt 1) { $fake += "," }
    $fake += '{"name":"verify-' + $i + '","author":"","kind":"","intro":"","wordCount":"",'
    $fake += '"bookUrl":"http://verify/book' + $i + '","tocUrl":"","sourceName":"verify",'
    $fake += '"sourceUrl":"http://verify","chapterIndex":0,"chapterName":"","charOffset":0,'
    $fake += '"updatedAt":' + $i + ',"shelf":true}'
}
$fake += "]"
[IO.File]::WriteAllText("$outDir\verify-fake-progress.json", $fake, (New-Object System.Text.UTF8Encoding($false)))

# The book the script imports: four chapters, so the local page has something real to show.
$txt = ""
foreach ($n in 1..4) {
    $txt += ($DI + $n + $ZHANG + " verify section " + $n) + "`n`n"
    $txt += "    body of section " + $n + ", long enough to be a chapter.`n`n"
}
[IO.File]::WriteAllText("$outDir\verify-book.txt", $txt, (New-Object System.Text.UTF8Encoding($false)))
& $adb -s $Dev push "$outDir\verify-book.txt" $testBook 2>&1 | Out-Null

# ------------------------------------------------------------------ park

$parked = $false
$localMoved = $false
if ($haveRoot -and -not $SkipLibrary) {
    Write-Output ""
    Write-Output "########## park the real library ##########"
    & $adb -s $Dev shell am broadcast -a "$pkg.TEST_EXIT" 2>&1 | Out-Null
    Start-Sleep -Seconds 3
    Su "cat $filesDir/progress.json > $cardsDir/progress.bak" | Out-Null
    $localMoved = (Su "ls -d $filesDir/local 2>/dev/null") -match 'local'
    if ($localMoved) {
        Su "mv $filesDir/local $filesDir/local-keep" | Out-Null
    }
    Put-AppFile "$outDir\verify-fake-progress.json" "$filesDir/progress.json"
    $parked = $true
    Note ("parked: progress.json saved, local dir moved=" + $localMoved)
}

try {
    # ------------------------------------------------------- 1. shelf baseline
    Write-Output ""
    Write-Output "########## 1. shelf baseline ##########"
    $out = Start-Page "MainActivity" @('-e', 'layoutDump', '1') 8
    $n0 = ShelfCount $out
    Check "shelf reports its book count" ($n0 -eq 3) ("shelf: " + $n0 + " books (synthetic library)")
    $m = LastMatch $out "layout: search_input top=(\d+) left=(\d+) w=(\d+) h=(\d+) vis=(\w+)"
    Check "search box has width" ($m.Success -and [int](G $m 3) -gt 100) $m.Value
    $m = LastMatch $out "layout: tab_shelf top=(\d+) left=(\d+) w=(\d+) h=(\d+) icon=(\d+)x(\d+) label=(\S+) on=(\w+)"
    Check "tab bar present and shelf is current" ($m.Success -and (G $m 8) -eq 'true') $m.Value
    $m = LastMatch $out "layout: tab_local top=(\d+) left=(\d+) w=(\d+) h=(\d+) icon=(\d+)x(\d+) label=(\S+) on=(\w+)"
    Check "tab bar carries all three pages" $m.Success $m.Value
    Save-Shot "$outDir\verify-shelf-normal.png" | Out-Null

    # ------------------------------------- 2. an import does not join the shelf
    Write-Output ""
    Write-Output "########## 2. import on the local page: shelf must NOT change ##########"
    # No Reset here: the shelf started in step 1 has to stay alive underneath, because the point
    # is what THAT instance shows afterwards.
    $out = Start-OnTop "LocalActivity" @('-e', 'importBook', $testBook) 14
    $m = LastMatch $out "import: added '([^']*)' id=(\S+) format=(\w+) chapters=(\d+)"
    Check "test book imported on the local page" $m.Success $m.Value
    $m = LastMatch $out "page: local files=(\d+) shelf=(\d+) entries=(\d+)"
    $filesNow = G $m 1
    $shelfNow = G $m 2
    Check "import left the shelf alone" ($m.Success -and [int]$shelfNow -eq $n0) ("files=" + $filesNow + " shelf=" + $shelfNow)
    # The import opens the reader; back out of it and out of the local page, landing on the shelf
    # instance that was started BEFORE the import.
    Back-Key
    Start-Sleep -Seconds 2
    Back-Key
    Start-Sleep -Seconds 3
    $out2 = Logs
    $seen = ShelfCount $out2
    Check "shelf still shows the old count (no auto-add)" ($seen -eq $n0) ("shelf: " + $seen + " books (baseline " + $n0 + ")")
    $m = LastMatch $out2 "shelf: edit active=false selected=0/(\d+)"
    Check "shelf reload really ran on the live instance" ($m.Success -and [int](G $m 1) -eq $n0) $m.Value

    # ---------------------------- 3. add-to-shelf on the local page, seen by the live shelf
    Write-Output ""
    Write-Output "########## 3. add-to-shelf on the local page, shelf sees it without a restart ##########"
    $out = Start-OnTop "LocalActivity" @('-e', 'editMode', '1', '-e', 'testShelf', '1') 10
    $m = LastMatch $out "local: edit active=true selected=1/(\d+)"
    Check "long press selects the imported book" $m.Success $m.Value
    $m = LastMatch $out "local: shelf toggle off=false moved=(\d+)/(\d+)"
    Check "the shelf action ran on the selection" ($m.Success -and [int](G $m 1) -ge 1) $m.Value
    $m = LastMatch $out "local: shelf toggle off=false moved=\d+/\d+ shelfNow=(\d+)"
    Check "store now has the book on the shelf" ($m.Success -and [int](G $m 1) -eq ($n0 + 1)) $m.Value
    Dump-Log $out "3. local page add-to-shelf"
    # TWO backs: the first one only leaves selection mode (that is the new back-key behaviour),
    # the second one leaves the page and brings the shelf instance in front.
    Back-Key
    Start-Sleep -Seconds 1
    Back-Key
    Start-Sleep -Seconds 3
    $out2 = Logs
    $seen = ShelfCount $out2
    Check "the live shelf instance shows the new book" ($seen -eq ($n0 + 1)) ("shelf: " + $seen + " books (was " + $n0 + ")")
    $m = LastMatch $out2 "shelf: edit active=false selected=0/(\d+)"
    Check "and it refreshed in this process" ($m.Success -and [int](G $m 1) -eq ($n0 + 1)) $m.Value
    Dump-Log $out2 "3. shelf after coming back"
    Save-Shot "$outDir\verify-shelf-after-add.png" | Out-Null

    # ------------------------------------------------------ 4. selection mode
    Write-Output ""
    Write-Output "########## 4. long press -> selection mode (editMode hook) ##########"
    $books = $n0 + 1
    $out = Start-Page "MainActivity" @('-e', 'editMode', '1', '-e', 'layoutDump', '1') 9
    $m = LastMatch $out "shelf: edit active=true selected=1/(\d+)"
    Check "row pressed is selected" $m.Success $m.Value
    $m = LastMatch $out "layout: shelf editMode=true batchBarVis=V"
    Check "batch bar shown in edit mode" $m.Success $m.Value
    $m = LastMatch $out "layout: batch_bar top=(\d+) left=(\d+) w=(\d+) h=(\d+) vis=(\w+)"
    Check "batch bar has real size" ($m.Success -and [int](G $m 3) -gt 100 -and (G $m 5) -eq 'V') $m.Value
    $m = LastMatch $out "layout: shelf afterSelectAll selected=(\d+) info=(\S+ \d+ \S+) del=(\S+)\((\d+)\)"
    $infoText = G $m 2
    $delText = G $m 3
    $delCount = G $m 4
    Check "select-all ticks every row" ($m.Success -and [int](G $m 1) -eq $books) $m.Value
    Check "bar counts the selection" ($infoText -eq ($YIXUAN + ' ' + $books + ' ' + $BEN)) $infoText
    Check "delete button carries the count" ($delText -eq $SHANCHU -and [int]$delCount -eq $books) ($delText + "(" + $delCount + ")")
    $m = LastMatch $out "layout: shelf row0 check=V checked=true"
    Check "row checkbox visible and ticked" $m.Success $m.Value
    $m = LastMatch $out "layout: shelf editModeOff=false batchBarVis=G selected=0"
    Check "leaving the mode clears the selection" $m.Success $m.Value
    Dump-Log $out "4. shelf edit mode"

    Write-Output ""
    Write-Output "########## 5. selection mode as the user sees it ##########"
    $out = Start-Page "MainActivity" @('-e', 'editMode', '1') 8
    $m = LastMatch $out "shelf: edit active=true selected=1/(\d+)"
    Check "selection mode stays on screen" $m.Success $m.Value
    Save-Shot "$outDir\verify-shelf-edit.png" | Out-Null

    # ------------------------------------------------------------ 6. local page
    Write-Output ""
    Write-Output "########## 6. the local page has the same mode ##########"
    $out = Start-Page "LocalActivity" @('-e', 'editMode', '1', '-e', 'layoutDump', '1') 9
    $m = LastMatch $out "local: edit active=true selected=1/(\d+)"
    Check "long press selects on the local page" $m.Success $m.Value
    $m = LastMatch $out "layout: local row0 check=V checked=true"
    Check "local row checkbox visible and ticked" $m.Success $m.Value
    $m = LastMatch $out "layout: local_done top=(\d+) left=(\d+) w=(\d+) h=(\d+) vis=(\w+)"
    Check "the local page shows its done button in edit mode" ($m.Success -and (G $m 5) -eq 'V') $m.Value
    $m = LastMatch $out "layout: batch_bar top=(\d+) left=(\d+) w=(\d+) h=(\d+) vis=(\w+)"
    Check "local batch bar has real size" ($m.Success -and (G $m 5) -eq 'V') $m.Value
    $m = LastMatch $out "layout: local afterSelectAll selected=(\d+) info=(\S+ \d+ \S+) shelf=(\S+) del=(\S+)\((\d+)\)"
    $linfo = G $m 2
    $laction = G $m 3
    Check "local bar counts the selection" ($linfo -eq ($YIXUAN + ' ' + (G $m 1) + ' ' + $BEN)) $linfo
    Check "local bar offers a shelf action" ($m.Success -and $laction.Length -gt 0) ("action=" + $laction)
    $m = LastMatch $out "layout: local editModeOff=false selected=0"
    Check "leaving the local mode clears the selection" $m.Success $m.Value
    Dump-Log $out "6. local page edit mode"
    Save-Shot "$outDir\verify-local-edit.png" | Out-Null

    # --------------------------------------------------------- 7. deletions
    Write-Output ""
    Write-Output "########## 7. batch deletions ##########"
    # (a) shelf: back to the three synthetic online books, delete all three
    if ($parked) {
        Put-AppFile "$outDir\verify-fake-progress.json" "$filesDir/progress.json"
    }
    $out = Start-Page "MainActivity" $null 8
    $synth = ShelfCount $out
    Check "shelf test library of 3 books loaded" ($synth -eq 3) ("shelf: " + $synth + " books")
    $out = Start-Page "MainActivity" @('-e', 'testBatch', '1') 10
    $m = LastMatch $out "batch: before=(\d+) selected=(\d+)"
    Check "shelf test selects three books" ($m.Success -and [int](G $m 1) -eq 3 -and [int](G $m 2) -eq 3) $m.Value
    $m = LastMatch $out "shelf: batch deleted (\d+) of (\d+) left=(\d+)"
    Check "shelf batch delete dropped three" ($m.Success -and [int](G $m 1) -eq 3 -and [int](G $m 3) -eq 0) $m.Value
    $m = LastMatch $out "batch: RESULT=(\w+)"
    Check "shelf delete self test" ($m.Success -and (G $m 1) -eq 'PASS') $m.Value

    # (b) local page: an imported file is really removed, cache included
    $out = Start-Page "LocalActivity" @('-e', 'importBook', $testBook) 14
    $m = LastMatch $out "import: added '([^']*)' id=(\S+) format=(\w+) chapters=(\d+)"
    Check "local test book imported" $m.Success $m.Value
    $out = Start-Page "LocalActivity" @('-e', 'testBatch', '1') 10
    $m = LastMatch $out "batch: before=(\d+) selected=(\d+)"
    $before = G $m 1
    $sel = G $m 2
    Check "local test selects what is there" ($m.Success -and [int]$before -ge 1 -and $before -eq $sel) $m.Value
    $m = LastMatch $out "local: batch deleted (\d+) of (\d+) left=(\d+)"
    Check "local batch delete removed every selected file" ($m.Success -and (G $m 1) -eq (G $m 2) -and [int](G $m 3) -eq 0) $m.Value
    $m = LastMatch $out "batch: RESULT=(\w+)"
    Check "local delete self test" ($m.Success -and (G $m 1) -eq 'PASS') $m.Value
    $dats = Su "ls $filesDir/local"
    Check "cached text of the deleted book is gone" ($dats -notmatch '\.dat') ("dir: " + $dats)
} finally {
    if ($parked) {
        Write-Output ""
        Write-Output "########## restore the real library ##########"
        Put-AppFile "$cardsDir/progress.bak" "$filesDir/progress.json"        if ($localMoved) {
            Su "rm -rf $filesDir/local" | Out-Null
            Su "mv $filesDir/local-keep $filesDir/local" | Out-Null
            if ($uid) { Su "chown $uid.$uid $filesDir/local" | Out-Null }
        }
        $back = ShelfCount (Start-Page "MainActivity" $null 8)
        # The expected count is read out of the backup itself: entries written before the shelf
        # flag existed have no "shelf" key and were on the shelf, so only an explicit false is off.
        $want = -1
        try {
            $saved = (Su "cat $cardsDir/progress.bak") | ConvertFrom-Json
            $want = @($saved | Where-Object { $_.shelf -ne $false }).Count
        } catch {
            $want = -1
        }
        Check "user library restored" ($back -eq $want) ("shelf: " + $back + " books (expected " + $want + ")")
        $localOk = Su "ls $filesDir/local/index.json"
        Check "local book index restored" ($localOk -match 'index.json') $localOk
        $owner = Su "ls -l $filesDir/progress.json"
        Check "progress.json is writable by the app again" ($owner -match 'app_') $owner
    }
}

# ---------------------------------------------------------------- summary

Write-Output ""
Write-Output "########## summary ##########"
Write-Output ("PASS " + $script:pass + "   FAIL " + $script:fail)
if ($script:fail -gt 0) { exit 1 }
exit 0
