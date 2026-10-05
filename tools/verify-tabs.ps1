# Verify the three-page bottom tab bar (书架 / 书源 / 本地导入) on the API-1 emulator.
#
#   & 'G:\jianyue-src\tools\verify-tabs.ps1' [-SkipInstall]
#
# What it proves (one PASS/FAIL line each):
#   1. shelf   - search box, list, and the three-item bar sitting under the list
#   2. sources - the action row (粘贴导入 / 从文件导入 / 格式说明) is ABOVE the list, not below it
#   3. local   - the + is in the top-right corner and the empty wording is the requested one
#   4. active  - each page marks itself current in the bar and only itself
#   5. switch  - tapping a tab really lands on the other page (driven by the -e tabTap hook)
#   6. no-op   - tapping the tab you are already on does not start anything
#   7. stack   - 书架 -> 书源 -> 本地导入 -> 书架 leaves ONE activity (no duplicate copies)
#   8. import  - the + path (LocalImport) still imports, and the book lands on the local page
#
# API-1 quirks that shaped this script:
#   - there is no `adb shell input tap`, so a tab is tapped through the tabTap extra, which
#     runs the same listener a finger would hit;
#   - int extras need `--ei` (`-e` delivers a String and getIntExtra then returns 0);
#   - starting the task root (MainActivity) needs FLAG_ACTIVITY_CLEAR_TOP, otherwise the
#     system replays the old intent and the new extras are ignored;
#   - the replayed intent is logged BEFORE the requested one, so checks look at the LAST
#     match in the log, never the first;
#   - this AVD is 480x320 LANDSCAPE, so "the bar is at the bottom" means its top is below
#     the list, not below y=400.
#
# ASCII only: Windows PowerShell 5.1 misreads BOM-less UTF-8 and then cannot parse the file.
param(
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Continue'

$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$dev = 'emulator-5554'
$pkg = 'com.jianyue.reader'
$apk = 'G:\jianyue-src\out\reader.apk'
$CLEAR_TOP = '0x04000000'

# "可以点击加号来添加本地图书" - the empty-state wording the user asked for, built from code
# points to keep this file ASCII.
$EMPTY_TEXT = -join ([int[]]@(
    0x53EF, 0x4EE5, 0x70B9, 0x51FB, 0x52A0, 0x53F7, 0x6765,
    0x6DFB, 0x52A0, 0x672C, 0x5730, 0x56FE, 0x4E66) | ForEach-Object { [char]$_ })

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

function FirstMatch($text, $pattern) {
    return [regex]::Match($text, $pattern)
}

# Safe group access: a failed match must not blow up while a detail string is built.
function G($m, $i) {
    if (-not $m.Success) { return '-' }
    if ($i -ge $m.Groups.Count) { return '-' }
    return $m.Groups[$i].Value
}

function Reset-App() {
    & $adb -s $dev logcat -c 2>&1 | Out-Null
    & $adb -s $dev shell am broadcast -a "$pkg.TEST_EXIT" 2>&1 | Out-Null
    Start-Sleep -Seconds 3
}

# Start one page directly with its layout dump and return what it logged.
function Dump-Page($activity) {
    Reset-App
    & $adb -s $dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.$activity" -e layoutDump 1 2>&1 | Out-Null
    Start-Sleep -Seconds 8
    return Logs
}

# Tap a tab on a page through the host hook and return what it logged.
function Tap-Tab($activity, $index) {
    Reset-App
    & $adb -s $dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.$activity" --ei tabTap $index 2>&1 | Out-Null
    Start-Sleep -Seconds 8
    return Logs
}

# Which tabs report themselves current in a dump.
function Active-Tabs($text) {
    $on = New-Object System.Collections.ArrayList
    foreach ($t in @('shelf', 'source', 'local')) {
        $m = LastMatch $text ("layout: tab_" + $t + " top=\d+ left=\d+ w=\d+ h=\d+ icon=\d+x\d+ label=\S+ on=(\w+)")
        if ($m.Success -and $m.Groups[1].Value -eq 'true') { [void]$on.Add($t) }
    }
    return , $on.ToArray()
}

$TABRE = "top=(\d+) left=(\d+) w=(\d+) h=(\d+) icon=(\d+)x(\d+) label=(\S+) on=(\w+)"

Write-Output "########## device ##########"
$sdk = (& $adb -s $dev shell getprop ro.build.version.sdk 2>&1) -join ''
Check "device is API 1" ($sdk.Trim() -eq '1') ("ro.build.version.sdk=" + $sdk.Trim())

if (-not $SkipInstall) {
    Write-Output ""
    Write-Output "########## install ##########"
    $res = (& $adb -s $dev install -r $apk 2>&1) -join ' '
    Check "apk installed" ($res -match 'Success') $res.Trim()
}

# ---------------------------------------------------------------- 1. shelf
Write-Output ""
Write-Output "########## 1. shelf (tab 1) ##########"
$out = Dump-Page 'MainActivity'
$search = LastMatch $out "layout: search_input top=(\d+) left=(\d+) w=(\d+) h=(\d+) vis=(\w)"
Check "search box has width" ($search.Success -and [int](G $search 3) -gt 100) $search.Value
$list = LastMatch $out "layout: list top=(\d+) left=(\d+) w=(\d+) h=(\d+) vis=(\w)"
Check "book list has height" ($list.Success -and [int](G $list 4) -gt 50) $list.Value
$ts = LastMatch $out "layout: tab_shelf top=(\d+) left=(\d+) w=(\d+) h=(\d+) icon=(\d+)x(\d+) label=(\S+) on=(\w+)"
$tc = LastMatch $out "layout: tab_source top=\d+ left=\d+ w=(\d+) h=(\d+)"
$tl = LastMatch $out "layout: tab_local top=\d+ left=\d+ w=(\d+) h=(\d+)"
Check "bar has three items" ($ts.Success -and $tc.Success -and $tl.Success) `
    ("w shelf=" + (G $ts 3) + " source=" + (G $tc 1) + " local=" + (G $tl 1))
Check "item is big enough to tap" ($ts.Success -and [int](G $ts 3) -gt 100 -and [int](G $ts 4) -gt 40) `
    ("w=" + (G $ts 3) + " h=" + (G $ts 4))
Check "icon bitmap loaded" ($ts.Success -and (G $ts 5) -eq '22' -and (G $ts 6) -eq '22') `
    ("icon=" + (G $ts 5) + "x" + (G $ts 6))
if ($list.Success -and $ts.Success) {
    $listBottom = [int](G $list 1) + [int](G $list 4)
    Check "bar sits under the list" ([int](G $ts 1) -ge $listBottom) `
        ("tab top=" + (G $ts 1) + " list bottom=" + $listBottom)
} else {
    Check "bar sits under the list" $false "missing list or tab bounds"
}
Check "no missing view on the shelf" ($out -notmatch 'layout: \S+ MISSING') ""

# ---------------------------------------------------------------- 2. sources
Write-Output ""
Write-Output "########## 2. sources (tab 2) ##########"
$out = Dump-Page 'SourcesActivity'
$paste = LastMatch $out "layout: btn_paste top=(\d+) left=(\d+) w=(\d+) h=(\d+) vis=(\w)"
$file = LastMatch $out "layout: btn_file top=(\d+) left=(\d+) w=(\d+) h=(\d+) vis=(\w)"
$help = LastMatch $out "layout: btn_src_help top=(\d+) left=(\d+) w=(\d+) h=(\d+) vis=(\w)"
$srclist = LastMatch $out "layout: list top=(\d+) left=(\d+) w=(\d+) h=(\d+) vis=(\w)"
Check "three action buttons present" ($paste.Success -and $file.Success -and $help.Success) `
    ("w paste=" + (G $paste 3) + " file=" + (G $file 3) + " help=" + (G $help 3))
if ($paste.Success -and $srclist.Success) {
    Check "action row moved to the top" ([int](G $paste 1) -lt [int](G $srclist 1)) `
        ("action top=" + (G $paste 1) + " list top=" + (G $srclist 1))
} else {
    Check "action row moved to the top" $false "missing action or list bounds"
}
Check "action row is near the top" ($paste.Success -and [int](G $paste 1) -lt 120) `
    ("top=" + (G $paste 1))
Check "no missing view on the sources page" ($out -notmatch 'layout: \S+ MISSING') ""

# ---------------------------------------------------------------- 3. local
Write-Output ""
Write-Output "########## 3. local imports (tab 3) ##########"
$out = Dump-Page 'LocalActivity'
$add = LastMatch $out "layout: local_add top=(\d+) left=(\d+) w=(\d+) h=(\d+) vis=(\w)"
Check "add button has size" ($add.Success -and [int](G $add 3) -ge 36 -and [int](G $add 4) -ge 36) `
    ("w=" + (G $add 3) + " h=" + (G $add 4))
Check "add button is in the top-right corner" ($add.Success -and [int](G $add 2) -ge 400 -and [int](G $add 1) -lt 70) `
    ("left=" + (G $add 2) + " top=" + (G $add 1))
$etext = LastMatch $out "layout: local_empty text=(.+)"
Check "empty wording is the requested one" ($etext.Success -and $etext.Groups[1].Value.Trim() -match [regex]::Escape($EMPTY_TEXT)) `
    $etext.Groups[1].Value.Trim()
Check "no missing view on the local page" ($out -notmatch 'layout: \S+ MISSING') ""
$on = Active-Tabs $out
Check "only local is marked current" (($on.Count -eq 1) -and ($on[0] -eq 'local')) ("on=" + ($on -join ','))

# ---------------------------------------------------------------- 4. active tab
Write-Output ""
Write-Output "########## 4. the current page is the one marked current ##########"
$out = Dump-Page 'MainActivity'
$on = Active-Tabs $out
Check "only shelf is marked current" (($on.Count -eq 1) -and ($on[0] -eq 'shelf')) ("on=" + ($on -join ','))
$out = Dump-Page 'SourcesActivity'
$on = Active-Tabs $out
Check "only source is marked current" (($on.Count -eq 1) -and ($on[0] -eq 'source')) ("on=" + ($on -join ','))

# ---------------------------------------------------------------- 5. switching
Write-Output ""
Write-Output "########## 5. tapping the tabs really switches ##########"
$out = Tap-Tab 'MainActivity' 1
Check "shelf -> sources logged" ($out -match 'tab: switch shelf -> source') (LastMatch $out 'tabtap: [^\r\n]*').Value
Check "sources page really started" ($out -match 'page: sources n=\d+') (LastMatch $out 'page: sources n=\d+').Value
$out = Tap-Tab 'SourcesActivity' 2
Check "sources -> local logged" ($out -match 'tab: switch source -> local') (LastMatch $out 'tabtap: [^\r\n]*').Value
Check "local page really started" ($out -match 'page: local books=\d+') (LastMatch $out 'page: local books=\d+').Value
$out = Tap-Tab 'LocalActivity' 0
Check "local -> shelf logged" ($out -match 'tab: switch local -> shelf') (LastMatch $out 'tabtap: [^\r\n]*').Value
Check "shelf really resumed" ($out -match 'shelf: \d+ books') (LastMatch $out 'shelf: \d+ books').Value

Write-Output ""
Write-Output "########## 6. tapping the current tab is a no-op ##########"
$out = Tap-Tab 'MainActivity' 0
Check "no switch from the current tab" ($out -notmatch 'tab: switch') (LastMatch $out 'tabtap: [^\r\n]*').Value

# ---------------------------------------------------------------- 7. back stack
Write-Output ""
Write-Output "########## 7. the task does not pile up copies ##########"
Reset-App
& $adb -s $dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.MainActivity" --ei tabTap 1 2>&1 | Out-Null
Start-Sleep -Seconds 7
& $adb -s $dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.SourcesActivity" --ei tabTap 2 2>&1 | Out-Null
Start-Sleep -Seconds 7
# NOTE: a host `am start` carries NEW_TASK|CLEAR_TOP, and on API 1 that makes the named
# activity the ROOT of the task, so the stack in the middle of this section is not the stack a
# finger would produce - it is only checked as an upper bound. The assertion that matters is
# the last one: a tab switch back to 书架 must not leave a second copy of it behind.
$ti = ((& $adb -s $dev shell dumpsys activity 2>&1) -join "`n")
$n = LastMatch $ti 'numActivities=(\d+)'
# the FIRST HistoryRecord listed is the top of the stack
$top = FirstMatch $ti 'HistoryRecord\{[0-9a-f]+ \{([\w./]+)\}\}'
Check "at most two activities while on a tab" ($n.Success -and [int](G $n 1) -le 2) `
    ("numActivities=" + (G $n 1) + " top=" + (G $top 1))
Check "the page on top is the local page" ((G $top 1) -match 'LocalActivity') ("top=" + (G $top 1))
& $adb -s $dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.LocalActivity" --ei tabTap 0 2>&1 | Out-Null
Start-Sleep -Seconds 7
$ti = ((& $adb -s $dev shell dumpsys activity 2>&1) -join "`n")
$n = LastMatch $ti 'numActivities=(\d+)'
$top = FirstMatch $ti 'HistoryRecord\{[0-9a-f]+ \{([\w./]+)\}\}'
Check "back to the shelf leaves one activity" ($n.Success -and [int](G $n 1) -eq 1) ("numActivities=" + (G $n 1))
Check "and it is the shelf" ((G $top 1) -match 'MainActivity') ("top=" + (G $top 1))

# ---------------------------------------------------------------- 8. import
Write-Output ""
Write-Output "########## 8. the + path still imports ##########"
$out = Dump-Page 'LocalActivity'
$before = 0
$m = LastMatch $out 'page: local books=(\d+)'
if ($m.Success) { $before = [int](G $m 1) }
Reset-App
& $adb -s $dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.LocalActivity" -e importBook /sdcard/local-test/novel-utf8.txt 2>&1 | Out-Null
Start-Sleep -Seconds 16
$out = Logs
$imp = LastMatch $out "local: imported \S+ 'novel-utf8' format=txt chapters=(\d+)"
Check "import through the local page" $imp.Success $imp.Value
$m = LastMatch $out 'page: local books=(\d+)'
$after = 0
if ($m.Success) { $after = [int](G $m 1) }
Check "the book shows on the local page" ($m.Success -and $after -eq ($before + 1)) `
    ("before=" + $before + " after=" + $after)

Write-Output ""
Write-Output ("########## RESULT: pass=" + $script:pass + " fail=" + $script:fail + " ##########")
if ($script:fail -gt 0) { exit 1 }
exit 0
