# The app's identity, read from the files that actually define it.
#
#   $AppId  <- AndroidManifest.xml   package="..."
#   $AppTag <- the TAG constant the Java sources log under (all classes use the same one)
#
# Why this exists: the application id is named in the manifest, in the build script and in
# a dozen acceptance scripts, and the logcat tag is named in the Java sources AND in the
# scripts that assert on logcat. Renaming one and missing another produces either a compile
# error or scripts that silently assert on log lines nobody logs. Dot-source this file for
# the value, and run tools\check-appid.ps1 to find every place that still disagrees.
#
# Usage:  . "$PSScriptRoot\appid.ps1"      # tools\*.ps1
#         . "$PSScriptRoot\tools\appid.ps1" # repo root scripts
#
# ASCII-only on purpose (Windows PowerShell 5.1 misreads BOM-less UTF-8).
$appRoot = Split-Path -Parent $PSScriptRoot
$mfPath  = Join-Path $appRoot 'AndroidManifest.xml'
if (-not (Test-Path -LiteralPath $mfPath)) { throw ("appid.ps1: AndroidManifest.xml not found at " + $mfPath) }
$mfText = [System.IO.File]::ReadAllText($mfPath, (New-Object System.Text.UTF8Encoding($false)))
if ($mfText -notmatch 'package="([^"]+)"') { throw 'appid.ps1: package="..." not found in AndroidManifest.xml' }
$AppId = $Matches[1]

$tagHit = Get-ChildItem (Join-Path $appRoot 'src') -Recurse -Filter '*.java' |
          Select-String -Pattern 'TAG\s*=\s*"([^"]+)"' | Select-Object -First 1
if (-not $tagHit) { throw 'appid.ps1: no TAG constant found under src' }
$AppTag = $tagHit.Matches[0].Groups[1].Value
