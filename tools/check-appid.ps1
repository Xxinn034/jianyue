# Guard against a half-done rename.
#
# Run this before a release (and in CI, before the build): it fails if any script, doc or
# resource still names a different application id than AndroidManifest.xml, or logs/asserts
# on a different logcat tag than the Java sources use.
#
# Exit code 0 = everything agrees, 1 = something disagrees (details are printed).
# ASCII-only on purpose (Windows PowerShell 5.1 misreads BOM-less UTF-8).
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\appid.ps1"

$proj = Split-Path -Parent $PSScriptRoot
$exts = @('.ps1', '.md', '.xml', '.yml', '.txt')
$skip = @('\\\.git\\', '\\out\\', '\\tools-tmp\\', '\\tools\\sdk\\', '\\_backup-before-ui-changes\\',
          '\\tools\\api16\\', '\\tools\\api37\\', '\\tools\\probe-reader\\',
          # these two name the patterns themselves, so they are not identity claims
          '\\tools\\check-appid\.ps1$', '\\tools\\appid\.ps1$')

# Anything shaped like "com.<something>.<something>". Only our own two house names are
# treated as identity claims: unrelated packages (the API-1 probe apps, for instance) are
# legitimately different and must not fail the check.
$idPattern  = 'com\.(legado|jianyue)\.[a-z0-9_]+'
$tagPattern = '\b(LegadoApi1|JianYue[A-Za-z0-9]*)\b'

$bad = 0
$files = Get-ChildItem -LiteralPath $proj -Recurse -File |
         Where-Object { $exts -contains $_.Extension -and ($_.FullName -notmatch ($skip -join '|')) }
foreach ($f in $files) {
    $rel = $f.FullName.Replace($proj + '\', '')
    $t = [System.IO.File]::ReadAllText($f.FullName, (New-Object System.Text.UTF8Encoding($false)))
    foreach ($m in [regex]::Matches($t, $idPattern)) {
        if ($m.Value -ne $AppId) {
            Write-Output ("  BAD ID   " + $rel + " : " + $m.Value + "  (expected " + $AppId + ")")
            $bad++
        }
    }
    foreach ($m in [regex]::Matches($t, $tagPattern)) {
        if ($m.Value -ne $AppTag -and $m.Value -notlike ($AppTag + '*')) {
            Write-Output ("  BAD TAG  " + $rel + " : " + $m.Value + "  (expected " + $AppTag + ")")
            $bad++
        }
    }
}

Write-Output ("appid: " + $AppId + "   tag: " + $AppTag + "   files scanned: " + $files.Count)
if ($bad -gt 0) { Write-Output ("!! " + $bad + " disagreeing reference(s)"); exit 1 }
Write-Output "OK - every reference agrees"
exit 0
