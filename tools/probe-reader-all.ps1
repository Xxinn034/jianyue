# Probe: drive the READER for every source in sources-v4.json on the API-1 emulator and
# capture ANY fatal exception / ANR, plus how long the chapter took to appear.
#
#   & 'G:\jianyue-src\tools\probe-reader-all.ps1'
#
# This exists because the reported symptom is "进入阅读界面一直加载不出文字，最终停止apk":
# that is either (a) the worker thread never posts back, or (b) the process dies while the
# screen still says 正在加载. Both look the same from the outside, so the log is dumped whole.
#
# ASCII only (Windows PowerShell 5.1 misreads BOM-less UTF-8).
$ErrorActionPreference = 'Continue'

$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$dev = 'emulator-5554'
$pkg = 'com.jianyue.reader'
$CLEAR_TOP = '0x04000000'
$sourcesFile = 'G:\legado-api1\bookSources\sources-v4.json'
$outDir = 'G:\jianyue-src\tools\probe-reader'
if (!(Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

function Logs() {
    return (& $adb -s $dev logcat -d 2>&1) -join "`n"
}

# one real book per source (sourceUrl, bookUrl, label, min chapters, min content chars)
$books = @(
    @{ n = 'zongheng'; src = 'http://www.zongheng.com';  book = 'http://www.zongheng.com/detail/1217041';  minCh = 1000; minTx = 500 },
    @{ n = 'suixkan';  src = 'http://m.suixkan.com/';    book = 'http://m.suixkan.com/c/229093.html';      minCh = 1600; minTx = 2500 },
    @{ n = 'xbiquge';  src = 'http://www.xbiquge.info/'; book = 'http://www.xbiquge.info/112/112570/';     minCh = 120;  minTx = 4000 },
    @{ n = 'heiyan';   src = 'http://www.heiyan.com';    book = 'http://www.heiyan.com/book/161350';       minCh = 5;    minTx = 2000 },
    @{ n = 'maojiuxs'; src = 'http://www.maojiuxs.com';  book = 'http://www.maojiuxs.com/book/77569.html'; minCh = 600;  minTx = 1500 }
)

Write-Output "########## import sources-v4.json ##########"
& $adb -s $dev push $sourcesFile /sdcard/v4.json 2>&1 | Out-Null
& $adb -s $dev shell am broadcast -a "$pkg.TEST_EXIT" 2>&1 | Out-Null
Start-Sleep -Seconds 3
& $adb -s $dev logcat -c 2>&1 | Out-Null
& $adb -s $dev shell am start -n "$pkg/.ui.SourcesActivity" -e importPath /sdcard/v4.json 2>&1 | Out-Null
Start-Sleep -Seconds 15
(Logs) -split "`n" | Select-String 'importPath result' | ForEach-Object { $_.Line }

foreach ($b in $books) {
    Write-Output ""
    Write-Output ("########## reader: " + $b.n + " ##########")
    & $adb -s $dev shell am broadcast -a "$pkg.TEST_EXIT" 2>&1 | Out-Null
    Start-Sleep -Seconds 3
    & $adb -s $dev shell "ping -c 1 x" 2>&1 | Out-Null
    & $adb -s $dev logcat -c 2>&1 | Out-Null
    $t0 = Get-Date
    & $adb -s $dev shell am start -f $CLEAR_TOP -n "$pkg/.ui.ReaderActivity" `
        -e sourceUrl $b.src -e bookUrl $b.book -e name $b.n --ei chapterIndex 0 2>&1 | Out-Null

    # poll until the chapter text shows up (or give up after 200s)
    $seen = $false
    $waited = 0
    while ($waited -lt 200) {
        Start-Sleep -Seconds 5
        $waited += 5
        $l = Logs
        if ($l -match 'reader: paginated') { $seen = $true; break }
        if ($l -match 'FATAL EXCEPTION') { break }
    }
    $secs = [int]((Get-Date) - $t0).TotalSeconds
    Start-Sleep -Seconds 5
    $l = Logs
    $l | Out-File -Encoding utf8 (Join-Path $outDir ($b.n + ".log"))

    $toc = [regex]::Match($l, 'engine\.toc[^\r\n]*?-> (\d+) chapters')
    $cnt = [regex]::Match($l, 'engine\.content ([^\r\n]*?) -> (\d+) chars')
    $pg = [regex]::Match($l, 'reader: paginated chars=(\d+) pages=(\d+)')
    $fat = [regex]::Matches($l, 'FATAL EXCEPTION')
    $anr = [regex]::Matches($l, 'ANR ')
    Write-Output ("  paginated=" + $seen + " after=" + $secs + "s")
    Write-Output ("  toc=" + $toc.Value)
    Write-Output ("  content=" + $cnt.Value)
    Write-Output ("  paginated line=" + $pg.Value)
    Write-Output ("  FATAL=" + $fat.Count + "  ANR=" + $anr.Count)
    if ($fat.Count -gt 0) {
        $idx = $l.IndexOf('FATAL EXCEPTION')
        Write-Output ("  ---- fatal trace ----")
        Write-Output $l.Substring($idx, [Math]::Min(2500, $l.Length - $idx))
    }
    Write-Output ("  log -> " + (Join-Path $outDir ($b.n + ".log")))
}

Write-Output ""
Write-Output "########## done ##########"
