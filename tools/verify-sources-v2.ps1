# Targeted verification for the new/fixed book sources and the merge+switch features.
# ASCII-only on purpose (PowerShell 5.1 reads BOM-less UTF-8 as GBK).
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$PKG = 'com.jianyue.reader'
$apk = 'G:\jianyue-src\out\reader.apk'
$DEV = 'emulator-5554'

$KW  = [string]([char]0x6597 + [char]0x7F57 + [char]0x5927 + [char]0x9646)   # dou luo da lu

function Restart-App {
    & $adb -s $DEV shell am broadcast -a "$PKG.TEST_EXIT" 2>&1 | Out-Null
    Start-Sleep -Seconds 5
}
function LogDump { (& $adb -s $DEV logcat -d 2>&1 | Out-String) }

Write-Output "########## install ##########"
& $adb -s $DEV install -r $apk 2>&1 | Out-String | Write-Output

Write-Output "########## import sources-v2.json ##########"
Restart-App
& $adb -s $DEV push 'G:\legado-api1\source-analysis\sources-v2.json' /sdcard/v2.json 2>&1 | Out-Null
& $adb -s $DEV logcat -c 2>&1 | Out-Null
& $adb -s $DEV shell am start -n "$PKG/.ui.SourcesActivity" -e importPath /sdcard/v2.json 2>&1 | Out-Null
Start-Sleep -Seconds 25
LogDump | Select-String 'sourceStore: import|sourceStore: saved|check:|unsupported' | ForEach-Object { $_.Line }

Write-Output ""
Write-Output "########## search (merge check) ##########"
Restart-App
& $adb -s $DEV logcat -c 2>&1 | Out-Null
& $adb -s $DEV shell am start -n "$PKG/.ui.SearchActivity" -e keyword $KW 2>&1 | Out-Null
Start-Sleep -Seconds 60
$log = LogDump
$log | Select-String 'search: kw=|engine\.search .* -> \d+ books|search done' | ForEach-Object { $_.Line }

Write-Output ""
Write-Output "########## per-source detail + toc ##########"
$cases = @(
    @('xbiquge',  'http://www.xbiquge.info/',   'http://www.xbiquge.info/112/112570/'),
    @('heiyan',   'https://www.heiyan.com',     'http://www.heiyan.com/book/161350'),
    @('heiyan1',  'http://www.heiyan.com/',     'http://www.heiyan.com/book/161350'),
    @('suixkan',  'http://m.suixkan.com/',      'http://m.suixkan.com/b/229093.html')
)
foreach ($c in $cases) {
    Restart-App
    & $adb -s $DEV logcat -c 2>&1 | Out-Null
    & $adb -s $DEV shell am start -n "$PKG/.ui.BookDetailActivity" `
        -e sourceUrl $c[1] -e bookUrl $c[2] -e name $c[0] 2>&1 | Out-Null
    Start-Sleep -Seconds 70
    $l = LogDump
    Write-Output ("---- " + $c[0] + " ----")
    $l | Select-String 'engine\.toc|engine\.info|detail: toc=|engine\.content|reader: chapter' |
        ForEach-Object { $_.Line } | Select-Object -First 16
}

Write-Output ""
Write-Output "########## reader on a new source (xbiquge chapter 1) ##########"
Restart-App
& $adb -s $DEV logcat -c 2>&1 | Out-Null
& $adb -s $DEV shell am start -n "$PKG/.ui.ReaderActivity" `
    -e sourceUrl 'http://www.xbiquge.info/' `
    -e bookUrl 'http://www.xbiquge.info/112/112570/' `
    -e name 'xbiquge' --ei chapterIndex 0 2>&1 | Out-Null
Start-Sleep -Seconds 70
LogDump | Select-String 'engine\.toc|engine\.content|reader: chapter|reader: paginated' |
    ForEach-Object { $_.Line } | Select-Object -First 12

Write-Output ""
Write-Output "########## reader on 黑岩 (heiyan) ##########"
Restart-App
& $adb -s $DEV logcat -c 2>&1 | Out-Null
& $adb -s $DEV shell am start -n "$PKG/.ui.ReaderActivity" `
    -e sourceUrl 'https://www.heiyan.com' `
    -e bookUrl 'http://www.heiyan.com/book/161350' `
    -e name 'heiyan' --ei chapterIndex 0 2>&1 | Out-Null
Start-Sleep -Seconds 70
LogDump | Select-String 'engine\.toc|engine\.content|reader: chapter|reader: paginated|engine\.info' |
    ForEach-Object { $_.Line } | Select-Object -First 14
