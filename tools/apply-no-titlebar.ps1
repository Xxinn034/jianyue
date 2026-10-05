# Apply the shared window setup (no title bar + matching window background) to every
# activity, and stop calling setTitle().
#
# Why a script: the grey strip the user sees above each screen is the framework TITLE BAR.
# Removing it means calling Ui.setup(activity, bgColor) as the first statement of onCreate
# and dropping setTitle(). Doing that by hand across 7 files invites a missed screen.
#
# ASCII-only on purpose (Windows PowerShell 5.1 misreads BOM-less UTF-8).
$ErrorActionPreference = 'Stop'

$dir = 'G:\jianyue-src\src\com\jianyue\reader\ui'

# activity file -> background colour used by its layout (so no grey band is left behind)
$targets = @{
    'MainActivity.java'       = '0xFFF7F7F7'   # act_shelf   bg
    'SourcesActivity.java'    = '0xFFF7F7F7'   # act_sources bg
    'SearchActivity.java'     = '0xFFF7F7F7'   # act_search  bg
    'FilePickerActivity.java' = '0xFFF7F7F7'   # act_browser bg
    'BookDetailActivity.java' = '0xFFF7F7F7'   # act_detail  bg
    'HelpActivity.java'       = '0xFFF7F7F7'   # help is a plain scroll on bg
    'ReaderActivity.java'     = '0xFFF6F1E7'   # reading paper; ReaderActivity also calls
                                               # its own setFullscreen(), which is fine
}

foreach ($name in $targets.Keys) {
    $path = Join-Path $dir $name
    if (-not (Test-Path -LiteralPath $path)) {
        Write-Output ("SKIP (missing): " + $name)
        continue
    }
    $txt = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
    $orig = $txt
    $bg = $targets[$name]

    # 1. drop setTitle("...") lines
    $txt = [regex]::Replace($txt, '^\s*setTitle\([^;]*\);\s*\r?\n', '', 'Multiline')

    # 2. insert Ui.setup right after super.onCreate(...)
    $anchor = 'super.onCreate(savedInstanceState);'
    if ($txt.Contains($anchor) -and -not $txt.Contains('Ui.setup(')) {
        $inject = $anchor + "`r`n" +
                  "        // no title bar and no grey band above the content; see Ui`r`n" +
                  "        Ui.setup(this, $bg);"
        $txt = $txt.Replace($anchor, $inject)
    }

    if ($txt -ne $orig) {
        [System.IO.File]::WriteAllText($path, $txt, (New-Object System.Text.UTF8Encoding($false)))
        Write-Output ("updated: " + $name)
    } else {
        Write-Output ("unchanged: " + $name)
    }
}

Write-Output ""
Write-Output "---- remaining setTitle calls ----"
$left = Get-ChildItem $dir -Filter '*.java' | Select-String -Pattern 'setTitle\('
if ($left) { $left | ForEach-Object { Write-Output ("  " + $_.Filename + ":" + $_.LineNumber) } }
else { Write-Output "  none" }

Write-Output ""
Write-Output "---- Ui.setup calls ----"
Get-ChildItem $dir -Filter '*.java' | Select-String -Pattern 'Ui\.setup\(' |
    ForEach-Object { Write-Output ("  " + $_.Filename) }
