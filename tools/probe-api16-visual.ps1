# Visual evidence on Android 4.1 (API 16): the earlier run proved the chain works in the
# logs, but screencap came back blank because the emulator ran with -gpu swiftshader_indirect
# and no window. This script re-checks rendering with the guest's own software GL
# (-gpu guest) and, independently of the GPU, dumps the view hierarchy with uiautomator so
# the on-screen text and its bounds can be asserted without trusting any framebuffer.
#
#   & 'G:\jianyue-src\tools\probe-api16-visual.ps1'
#
# ASCII only (Windows PowerShell 5.1 misreads BOM-less UTF-8).
param(
    [string]$Dev = 'emulator-5556',
    [string]$OutDir = 'G:\jianyue-src\tools\api16'
)
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$PKG = 'com.jianyue.reader'
$apk = 'G:\jianyue-src\out\reader.apk'
if (!(Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
$report = New-Object System.Collections.Generic.List[string]
function Say($s) { Write-Output $s; $report.Add("$s") }
function Sh($c) { return ((& $adb -s $Dev shell $c 2>&1) -join "`n").Trim() }
function Logs { return ((& $adb -s $Dev logcat -d 2>&1) -join "`n") }
function Shot($name) {
    & $adb -s $Dev shell screencap -p /sdcard/$name.png 2>&1 | Out-Null
    & $adb -s $Dev pull /sdcard/$name.png (Join-Path $OutDir ($name + '.png')) 2>&1 | Out-Null
    $f = Join-Path $OutDir ($name + '.png')
    if (Test-Path $f) { Say ('  shot ' + $name + '.png ' + (Get-Item $f).Length + ' bytes') }
}
function UiDump($name) {
    & $adb -s $Dev shell uiautomator dump /sdcard/$name.xml 2>&1 | Out-Null
    $dest = Join-Path $OutDir ($name + '.xml')
    & $adb -s $Dev pull /sdcard/$name.xml $dest 2>&1 | Out-Null
    if (!(Test-Path $dest)) { Say ('  ui dump ' + $name + ': FAILED'); return }
    try {
        [xml]$x = Get-Content $dest -Encoding UTF8
        $nodes = $x.SelectNodes('//node')
        Say ('  ui dump ' + $name + ': nodes=' + $nodes.Count)
        $tv = $nodes | Where-Object { $_.text -and $_.text.Trim().Length -gt 0 }
        Say ('  nodes with text: ' + $tv.Count)
        foreach ($n in ($tv | Select-Object -First 8)) {
            $t = $n.text
            if ($t.Length -gt 24) { $t = $t.Substring(0, 24) + '...' }
            Say ('    [' + $n.bounds + '] len=' + $n.text.Length + ' "' + $t + '"')
        }
    } catch { Say ('  ui dump ' + $name + ': parse failed ' + $_.Exception.Message) }
}

$KW = [string]([char]0x6597 + [char]0x7F57 + [char]0x5927 + [char]0x9646)
$BOOK = [string]([char]0x6597 + [char]0x7F57 + [char]0x5927 + [char]0x9646 `
        + [char]0x4E56 + [char]0x91CD + [char]0x751F + [char]0x5510 + [char]0x4E09)

Say '################ API16 visual check (-gpu guest) ################'

$booted = $false
foreach ($i in 1..24) {
    if ((Sh 'getprop sys.boot_completed') -eq '1') { $booted = $true; break }
    Start-Sleep -Seconds 5
}
Say ('boot_completed=' + $booted)
if (-not $booted) { $report | Out-File -Encoding utf8 (Join-Path $OutDir 'visual-api16.txt'); exit 1 }

& $adb -s $Dev install -r $apk 2>&1 | Out-Null
& $adb -s $Dev push 'G:\legado-api1\bookSources\zongheng.json' /sdcard/zongheng.json 2>&1 | Out-Null
& $adb -s $Dev shell am force-stop $PKG 2>&1 | Out-Null
& $adb -s $Dev shell am start -n ($PKG + '/.ui.SourcesActivity') -e importPath /sdcard/zongheng.json 2>&1 | Out-Null
Start-Sleep -Seconds 12

Say ''
Say '-- shelf --'
& $adb -s $Dev shell am start -n ($PKG + '/.ui.MainActivity') 2>&1 | Out-Null
Start-Sleep -Seconds 10
Shot 'gpu-shelf'
UiDump 'gpu-shelf'

Say ''
Say '-- reader (zongheng chapter 2) --'
& $adb -s $Dev logcat -c 2>&1 | Out-Null
& $adb -s $Dev shell am start -f 0x04000000 -n ($PKG + '/.ui.ReaderActivity') `
    -e sourceUrl 'http://www.zongheng.com' `
    -e bookUrl 'http://www.zongheng.com/detail/1217041' `
    -e name $BOOK --ei chapterIndex 2 2>&1 | Out-Null
Start-Sleep -Seconds 45
$log = Logs
$m = [regex]::Match($log, 'reader: paginated chars=(\d+) pages=(\d+)')
Say ('  ' + $m.Value)
Shot 'gpu-reader'
UiDump 'gpu-reader'
$fatal = ([regex]::Matches($log, 'FATAL EXCEPTION')).Count
Say ('  FATAL=' + $fatal)

Say ''
Say '-- sources page --'
& $adb -s $Dev shell am start -n ($PKG + '/.ui.SourcesActivity') 2>&1 | Out-Null
Start-Sleep -Seconds 8
Shot 'gpu-sources'
UiDump 'gpu-sources'

$report | Out-File -Encoding utf8 (Join-Path $OutDir 'visual-api16.txt')
Say ''
Say ('report -> ' + (Join-Path $OutDir 'visual-api16.txt'))
