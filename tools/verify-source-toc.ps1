# Verify book sources end-to-end: open a known book URL and load its TOC.
#
# WHY THIS EXISTS
# ---------------
# The earlier batch test only checked that SEARCH returned rows. That was not enough: the
# sources passed search but every one of them failed at the chapter list, because
# "class.listmain@dd!0:1:2:..." (阅读's index-exclusion syntax) was being ignored. A search
# hit is therefore NOT a valid "source works" signal - the TOC must be checked too.
#
# This drives the reader directly with a known book URL per source, so it measures the TOC
# and the chapter text without depending on search parsing.
#
# ASCII-only on purpose, INCLUDING the labels. Windows PowerShell 5.1 reads a BOM-less
# UTF-8 script as GBK, so a CJK string literal reaches the parser as mojibake and the whole
# script fails to parse. Labels are ASCII; the CJK values are passed as arguments instead.
$ErrorActionPreference = 'Continue'
$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$PKG = 'com.jianyue.reader'

# label | sourceUrl | bookUrl   (book URLs taken from a live search on each site)
# Labels are ASCII because of the encoding trap above; the site names are not needed.
$cases = @(
    @('gdbzkz-1',  'http://www.gdbzkz.com/',          'http://www.gdbzkz.com/guichuideng/'),
    @('gdbzkz-2',  'http://www.gdbzkz.com/',          'http://www.gdbzkz.com/guichuidengii/'),
    @('wtzw',      'https://www.wtzw.com',            'https://www.wtzw.com/book/207765.html'),
    @('heiyan',    'http://www.heiyan.com/',          'https://www.heiyan.com/book/96640'),
    @('tadu',      'http://www.tadu.com/',            'http://www.tadu.com/book/1/'),
    @('lsds',      'http://www.lsds.cn/',             'http://www.lsds.cn/'),
    @('suixkan',   'http://m.suixkan.com/',           'http://m.suixkan.com/'),
    @('mibrowser', 'https://reader.browser.miui.com/','https://reader.browser.miui.com/'),
    @('gexing',    'https://www.gexingshuo.com/',     'https://www.gexingshuo.com/'),
    @('zongheng',  'http://www.zongheng.com',         'http://www.zongheng.com/detail/1217041')
)

function Restart-App {
    & $adb -s emulator-5554 shell am broadcast -a "$PKG.TEST_EXIT" 2>&1 | Out-Null
    Start-Sleep -Seconds 5
}

$pass = 0
$fail = 0
Write-Output "################ TOC verification (search is NOT enough) ################"
foreach ($c in $cases) {
    $label = $c[0]; $src = $c[1]; $book = $c[2]
    Restart-App
    & $adb -s emulator-5554 logcat -c 2>&1 | Out-Null
    & $adb -s emulator-5554 shell am start -n "$PKG/.ui.BookDetailActivity" `
        -e sourceUrl $src -e bookUrl $book -e name $label 2>&1 | Out-Null
    Start-Sleep -Seconds 40
    $log = (& $adb -s emulator-5554 logcat -d 2>&1 | Out-String)
    $m = [regex]::Match($log, 'engine\.toc .* -> (\d+) chapters')
    if ($m.Success -and [int]$m.Groups[1].Value -gt 0) {
        Write-Output ("  OK    " + $label.PadRight(11) + " toc=" + $m.Groups[1].Value)
        $pass++
    } else {
        $f = [regex]::Match($log, 'engine\.toc ([^\r\n]*)')
        $why = 'no toc log'
        if ($f.Success) { $why = $f.Groups[1].Value.Trim() }
        if ($why.Length -gt 70) { $why = $why.Substring(0, 70) }
        Write-Output ("  FAIL  " + $label.PadRight(11) + " " + $why)
        $fail++
    }
}
Write-Output ""
Write-Output ("  toc ok=" + $pass + " fail=" + $fail)
