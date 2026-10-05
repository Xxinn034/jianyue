# Focused end-to-end check: 黑岩 (API-based) and 新笔趣阁 (multi-page 目录).
# ASCII-only on purpose (PowerShell 5.1 reads BOM-less UTF-8 as GBK).
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$PKG = 'com.jianyue.reader'
$DEV = 'emulator-5554'

function Restart-App {
    & $adb -s $DEV shell am broadcast -a "$PKG.TEST_EXIT" 2>&1 | Out-Null
    Start-Sleep -Seconds 5
}
function LogDump { (& $adb -s $DEV logcat -d 2>&1 | Out-String) }

Write-Output "########## import ##########"
& $adb -s $DEV install -r 'G:\jianyue-src\out\reader.apk' 2>&1 | Out-Null
Restart-App
& $adb -s $DEV push 'G:\legado-api1\source-analysis\sources-v2.json' /sdcard/v2.json 2>&1 | Out-Null
& $adb -s $DEV logcat -c 2>&1 | Out-Null
& $adb -s $DEV shell am start -n "$PKG/.ui.SourcesActivity" -e importPath /sdcard/v2.json 2>&1 | Out-Null
Start-Sleep -Seconds 20
LogDump | Select-String 'importPath result' | ForEach-Object { $_.Line }

Write-Output ""
Write-Output "########## 黑岩: reader (search-api toc + content-api text) ##########"
Restart-App
& $adb -s $DEV logcat -c 2>&1 | Out-Null
& $adb -s $DEV shell am start -n "$PKG/.ui.ReaderActivity" `
    -e sourceUrl 'https://www.heiyan.com' `
    -e bookUrl 'https://www.heiyan.com/book/161350' `
    -e name 'heiyan' --ei chapterIndex 0 2>&1 | Out-Null
Start-Sleep -Seconds 75
LogDump | Select-String 'engine\.info|engine\.toc|engine\.content|reader: chapter|reader: paginated' |
    ForEach-Object { $_.Line } | Select-Object -First 16

Write-Output ""
Write-Output "########## 新笔趣阁: reader (multi-page toc) ##########"
Restart-App
& $adb -s $DEV logcat -c 2>&1 | Out-Null
& $adb -s $DEV shell am start -n "$PKG/.ui.ReaderActivity" `
    -e sourceUrl 'http://www.xbiquge.info/' `
    -e bookUrl 'http://www.xbiquge.info/112/112570/' `
    -e name 'xbiquge' --ei chapterIndex 0 2>&1 | Out-Null
Start-Sleep -Seconds 75
LogDump | Select-String 'engine\.toc|engine\.content|reader: chapter|reader: paginated' |
    ForEach-Object { $_.Line } | Select-Object -First 16
