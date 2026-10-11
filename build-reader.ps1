# Build the API-1 book-source reader (engine + UI).
#
# Toolchain notes (all learned the hard way, see docs/API1-能力实测记录.md):
#   - aapt v0.2 from the 2008 archived SDK packages resources AND generates R.java, so
#     the ids in the dex always match the ids in the APK. aapt.exe needs mgwz.dll
#     next to it or it exits with 0xC0000135 (STATUS_DLL_NOT_FOUND).
#   - javac compiles against the API-1 android.jar as bootclasspath. That is what turns
#     runtime VerifyErrors into compile errors.
#   - d8 needs JDK 11+ and rejects directory inputs, so .class files are enumerated.
#   - aapt cannot take .dex input; the dex is injected into the APK afterwards.
#   - keytool defaults to PKCS12 while this apksigner invocation pins JKS.
#
# Every tool path is probed, so the same script runs on the dev machine and on a GitHub
# runner (where the SDK/JDKs live in environment variables instead of on G:):
#   vendored first  ->  tools\sdk\          (committed, see tools/sdk/README.md)
#   then env vars   ->  JAVA_HOME_8_X64 / JAVA_HOME_17_X64 / ANDROID_SDK_ROOT
#   then this box   ->  the original absolute paths
#
# ASCII-only on purpose (Windows PowerShell 5.1 misreads BOM-less UTF-8).
param(
    [string]$Proj,
    [string]$Aapt,
    [string]$AndroidJar,
    [string]$Rhino,
    [string]$Jdk8,
    [string]$Jdk17,
    [string]$BuildTools
)
$ErrorActionPreference = 'Continue'

if (-not $Proj) {
    if ($PSScriptRoot) { $Proj = $PSScriptRoot } else { $Proj = 'G:\jianyue-src' }
}
$Proj = (Resolve-Path -LiteralPath $Proj).Path
$sdkDir = "$Proj\tools\sdk"

$out = "$Proj\out"
# The APK's targetSdkVersion, stamped into the compiled manifest by tools\axml-patch.ps1.
# 28 is measured, not guessed: < 24 is refused at install on Android 14+, < 28 makes
# Android 17 show the "built for an older version of Android" dialog, and >= 29 forces
# scoped storage, which breaks reading /sdcard for book-source and local-book import.
# 28 also needs usesCleartextTraffic (Android 9+ blocks plain HTTP from targetSdk 28 on).
$targetSdk = 28
$attrTargetSdk = 0x01010270
function Step($n, $msg) { Write-Output ""; Write-Output ("########## " + $n + " : " + $msg + " ##########") }
function Fail($msg) { Write-Output ("!! " + $msg); exit 1 }

function FirstFile($candidates, $leaf) {
    foreach ($c in $candidates) {
        if (-not $c) { continue }
        $p = if ($leaf) { Join-Path $c $leaf } else { $c }
        if (Test-Path -LiteralPath $p) { return $p }
    }
    return $null
}

# Newest build-tools that actually ships apksigner (the runner has several).
function FindBuildTools($roots) {
    foreach ($r in $roots) {
        if (-not $r) { continue }
        $btRoot = Join-Path $r 'build-tools'
        if (-not (Test-Path -LiteralPath $btRoot)) { continue }
        $cand = Get-ChildItem -LiteralPath $btRoot -Directory -EA SilentlyContinue |
                Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat') }
        if (-not $cand) { continue }
        $best = $cand | Sort-Object @{ Expression = { try { [version]$_.Name } catch { [version]'0.0' } } } -Descending |
                Select-Object -First 1
        return $best.FullName
    }
    return $null
}

# ------------------------------------------------ 0. toolchain
$aapt       = FirstFile @($Aapt, (Join-Path $sdkDir 'aapt.exe'), 'G:\legado-api1\tools\aapt.exe')
$androidJar = FirstFile @($AndroidJar, (Join-Path $sdkDir 'android.jar'),
                          'G:\android-legacy\sdk-1.0_r2\android-sdk-windows-1.0_r2\android.jar')
$rhino      = FirstFile @($Rhino, (Join-Path $sdkDir 'rhino-api1.jar'), 'G:\api1-probe2\lib\rhino-api1.jar')
$jdk8       = FirstFile @($Jdk8, $env:JAVA_HOME_8_X64, 'G:\android studio\jbr', $env:JAVA_HOME) 'bin\javac.exe'
$jdk        = FirstFile @($Jdk17, $env:JAVA_HOME_17_X64, "$env:USERPROFILE\.jdks\jbr-17.0.14", $env:JAVA_HOME) 'bin\java.exe'
$bt         = FindBuildTools @($BuildTools, $env:ANDROID_SDK_ROOT, $env:ANDROID_HOME,
                               'C:\Users\LX\AppData\Local\Android\Sdk')

if (-not $aapt)       { Fail "aapt.exe not found (vendor it into tools\sdk or pass -Aapt)" }
if (-not $androidJar) { Fail "API-1 android.jar not found (vendor it into tools\sdk or pass -AndroidJar)" }
if (-not $rhino)      { Fail "rhino-api1.jar not found (vendor it into tools\sdk or pass -Rhino)" }
if (-not $jdk8)       { Fail "JDK 8 (javac) not found - set JAVA_HOME_8_X64 or pass -Jdk8" }
if (-not $jdk)        { Fail "JDK 17 (java, for d8) not found - set JAVA_HOME_17_X64 or pass -Jdk17" }
if (-not $bt)         { Fail "Android build-tools not found - set ANDROID_SDK_ROOT or pass -BuildTools" }

$jdk8   = Split-Path -Parent (Split-Path -Parent $jdk8)   # ...\bin\javac.exe -> JDK home
$jdk    = Split-Path -Parent (Split-Path -Parent $jdk)
$d8Jar  = "$bt\lib\d8.jar"
$zipAlign  = "$bt\zipalign.exe"
$apkSigner = "$bt\apksigner.bat"
if (-not (Test-Path -LiteralPath $d8Jar)) { Fail "d8.jar missing in $bt" }

# ------------------------------------------------ 0b. signing config
# Env vars win; secrets.local.ps1 (gitignored) fills them in on the dev machine.
$secretsFile = "$Proj\secrets.local.ps1"
if (Test-Path -LiteralPath $secretsFile) { . $secretsFile }
$keyStore = $env:JY_KEYSTORE
$ksPass   = $env:JY_KEYSTORE_PASS
$ksAlias  = $env:JY_KEY_ALIAS
$keyPass  = if ($env:JY_KEY_PASS) { $env:JY_KEY_PASS } else { $ksPass }

# Never auto-generate a key here: a silently different key produces an APK that no
# existing install can upgrade over (users would have to uninstall, losing their books).
if (-not $keyStore -or -not (Test-Path -LiteralPath $keyStore)) {
    Fail "signing keystore not found (JY_KEYSTORE='$keyStore'). Copy secrets.local.ps1.example to secrets.local.ps1 and fill it in."
}
if (-not $ksPass -or -not $ksAlias) {
    Fail "JY_KEYSTORE_PASS / JY_KEY_ALIAS not set. See secrets.local.ps1.example."
}

# ------------------------------------------------ 0c. version (single source of truth)
# version.txt holds "<versionName> [<versionCode>]". aapt 0.2 (2008) has no --version-code
# flag, so the values are injected into a copy of the manifest for aapt instead of being
# kept in AndroidManifest.xml (one source, no chance of the two drifting apart).
$verFile = "$Proj\version.txt"
if (-not (Test-Path -LiteralPath $verFile)) { Fail "version.txt missing" }
$verLine = (Get-Content -LiteralPath $verFile -Encoding UTF8 |
    Where-Object { $_.Trim() -ne '' -and -not $_.Trim().StartsWith('#') } |
    Select-Object -First 1).Trim()
$verFields = $verLine -split '\s+'
$verName = $verFields[0]
if ($verName -notmatch '^(\d+)\.(\d+)\.(\d+)$') {
    Fail ("version.txt: versionName must be MAJOR.MINOR.PATCH, got '" + $verName + "'")
}
$verCode = ([int]$Matches[1] * 10000) + ([int]$Matches[2] * 100) + [int]$Matches[3]
if ($verFields.Count -ge 2) { $verCode = [int]$verFields[1] }

Write-Output ("PROJECT  : " + $Proj)
Write-Output ("aapt     : " + $aapt)
Write-Output ("android  : " + $androidJar)
Write-Output ("rhino    : " + $rhino)
Write-Output ("javac    : " + $jdk8)
Write-Output ("java/d8  : " + $jdk + "   (" + $bt + ")")
Write-Output ("keystore : " + $keyStore + "   alias " + $ksAlias)
Write-Output ("VERSION  : " + $verName + "   (versionCode " + $verCode + ")")

Remove-Item $out -Recurse -Force -EA SilentlyContinue
New-Item -ItemType Directory "$out\gen","$out\classes","$out\dex" -Force | Out-Null

# ------------------------------------------------ 1. resources + R.java
Step "1/8" "aapt v0.2 packages resources and generates R.java"
# Inject versionCode/versionName into a build-time copy of the manifest.
$mfText = [System.IO.File]::ReadAllText("$Proj\AndroidManifest.xml", (New-Object System.Text.UTF8Encoding($false)))
if ($mfText -match 'android:versionCode\s*=') {
    Fail "AndroidManifest.xml must not declare versionCode - version.txt owns it"
}
$inject  = 'android:versionCode="' + $verCode + '" android:versionName="' + $verName + '" '
# Injected at the END of the <manifest ...> start tag so the android: prefix is always
# declared before it is used. (Note: an XML comment may not contain a double hyphen; aapt
# 0.2's expat rejects the whole file over it, which is why the manifest comment spells the
# old flag without one.)
$mfBuild = (New-Object System.Text.RegularExpressions.Regex '(?s)<manifest\b([^>]*)>').Replace(
    $mfText, ('<manifest$1 ' + $inject + '>'), 1)
if ($mfBuild -eq $mfText) { Fail "cannot inject version into manifest" }
[System.IO.File]::WriteAllText("$out\AndroidManifest.xml", $mfBuild, (New-Object System.Text.UTF8Encoding($false)))
& $aapt package -f -m -M "$out\AndroidManifest.xml" -S "$Proj\res" `
    -I $androidJar -J "$out\gen" -F "$out\resources.apk" 2>&1 | Select-Object -First 20
if (-not (Test-Path -LiteralPath "$out\resources.apk")) { Fail "aapt failed" }
$rJava = Get-ChildItem "$out\gen" -Recurse -Filter 'R.java' | Select-Object -First 1
Write-Output ("resources.apk = {0:N0} bytes" -f (Get-Item "$out\resources.apk").Length)

Step "2/8" "javac - R.java"
& "$jdk8\bin\javac.exe" -nowarn -source 8 -target 8 -bootclasspath $androidJar `
    -encoding UTF-8 -d "$out\classes" $rJava.FullName 2>&1 | Select-Object -First 10

# ------------------------------------------------ 2. sources
Step "3/8" "javac - reader sources (bootclasspath = API-1 android.jar)"
$src = Get-ChildItem "$Proj\src" -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
Write-Output ("source files: " + $src.Count)
$cp = "$androidJar;$rhino;$out\classes"
& "$jdk8\bin\javac.exe" -nowarn -source 8 -target 8 -bootclasspath $androidJar `
    -classpath $cp -encoding UTF-8 -d "$out\classes" $src 2>&1 | Select-Object -First 40
if (-not (Test-Path -LiteralPath "$out\classes\com\jianyue\reader\ui\MainActivity.class")) {
    Fail "JAVAC FAILED"
}
Write-Output ("javac OK - {0} class files" -f (Get-ChildItem "$out\classes" -Recurse -Filter '*.class').Count)

# ------------------------------------------------ 3. dex
Step "4/8" "d8 --min-api 1 (sources + Rhino)"
$classFiles = Get-ChildItem "$out\classes" -Recurse -Filter '*.class' | Select-Object -ExpandProperty FullName
$inputs = @() + $classFiles + @($rhino)
$d8Args = @('-Xmx1536M','-cp', $d8Jar, 'com.android.tools.r8.D8',
            '--min-api','1','--lib', $androidJar, '--output', "$out\dex") + $inputs
& "$jdk\bin\java.exe" @d8Args 2>&1 | Select-Object -First 30
if (-not (Test-Path -LiteralPath "$out\dex\classes.dex")) { Fail "D8 FAILED" }
$dexBytes = [System.IO.File]::ReadAllBytes("$out\dex\classes.dex")
Write-Output ("classes.dex: {0:N0} bytes  version={1}" -f $dexBytes.Length,
    [System.Text.Encoding]::ASCII.GetString($dexBytes,4,3))

# ------------------------------------------------ 4. assemble
Step "5/8" "inject dex"
Add-Type -AssemblyName System.IO.Compression.FileSystem
Copy-Item "$out\resources.apk" "$out\app.apk" -Force
$zip = [System.IO.Compression.ZipFile]::Open("$out\app.apk", 'Update')
$existing = $zip.Entries | Where-Object { $_.FullName -eq 'classes.dex' }
if ($existing) { $zip.Entries.Remove($existing) }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex', 'Optimal') | Out-Null

# Rhino's message catalog. The vendored rhino-api1.jar was recompiled from sources and its
# resource files were dropped, so ScriptRuntime$DefaultMessageProvider cannot build ANY error
# message: every JavaScript error surfaces as "MissingResourceException: null" instead of
# "java is not defined" / "is not a function". Book-source debugging is impossible without it,
# so the catalog is vendored in tools\sdk\rhino-res and packed into the APK as a class-path
# resource (d8 only rewrites .class files, so a plain file must be added by hand).
$rhinoMsg = Join-Path $Proj 'tools\sdk\rhino-res\org\mozilla\javascript\resources\Messages.properties'
if (Test-Path -LiteralPath $rhinoMsg) {
    $msgEntry = $zip.Entries | Where-Object { $_.FullName -eq 'org/mozilla/javascript/resources/Messages.properties' }
    if ($msgEntry) { $zip.Entries.Remove($msgEntry) }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $rhinoMsg,
        'org/mozilla/javascript/resources/Messages.properties', 'Optimal') | Out-Null
    Write-Output "rhino message catalog packed: 21921 bytes"
} else {
    Fail "rhino message catalog missing: $rhinoMsg"
}
$zip.Dispose()

Step "6/8" "stamp targetSdkVersion + usesCleartextTraffic into the manifest"
# aapt v0.2 cannot express either attribute (see tools\axml-patch.ps1), and without
# targetSdk >= 24 Android 14+ refuses to install the APK at all. The patch runs after the
# dex is in and before signing, and must not invalidate anything else in the APK.
& (Join-Path $Proj 'tools\axml-patch.ps1') -Apk "$out\app.apk" -Out "$out\app-target.apk" `
    -TargetSdk $targetSdk
if (-not (Test-Path -LiteralPath "$out\app-target.apk")) { Fail "manifest patch failed" }
Move-Item -Force "$out\app-target.apk" "$out\app.apk"

Step "7/8" "zipalign + sign"
& $zipAlign -f 4 "$out\app.apk" "$out\app-aligned.apk" 2>&1 | Select-Object -First 3
& $apkSigner sign --ks $keyStore --ks-type JKS --ks-pass ("pass:" + $ksPass) `
    --key-pass ("pass:" + $keyPass) --ks-key-alias $ksAlias --v1-signing-enabled true `
    --out "$out\reader.apk" "$out\app-aligned.apk" 2>&1 | Select-Object -First 6

Step "8/8" "verify"
if (-not (Test-Path -LiteralPath "$out\reader.apk")) { Fail "SIGN FAILED" }
$badging = & $aapt dump badging "$out\reader.apk" 2>&1
$badging | Select-Object -First 3
if (-not (($badging | Select-Object -First 1) -match ("versionCode='" + $verCode + "'"))) {
    Fail ("built APK does not carry versionCode " + $verCode)
}
if (-not (($badging | Select-Object -First 1) -match ("versionName='" + [regex]::Escape($verName) + "'"))) {
    Fail ("built APK does not carry versionName " + $verName)
}
# The manifest patch is the one step whose failure Android only reports at install time on
# a new device, so assert it here: the compiled manifest must carry the attribute id with
# the expected value (aapt v0.2 cannot name the attribute, but it prints the id and value).
$tree = & $aapt dump xmltree "$out\reader.apk" AndroidManifest.xml 2>&1
$want = ("0x" + $attrTargetSdk.ToString('x8') + ")=(type 0x10)0x" + ('{0:x}' -f $targetSdk))
if (-not ($tree | Select-String -Pattern ([regex]::Escape($want)))) {
    Fail ("built APK does not carry targetSdkVersion " + $targetSdk + " (expected " + $want + ")")
}
Write-Output ("targetSdkVersion : " + $targetSdk + " (manifest attribute stamped)")
# Signer fingerprint: the one line that proves a build (local or CI) can overwrite-install
# over the previous one. It must be identical for every release, for ever.
& $apkSigner verify --print-certs "$out\reader.apk" 2>&1 |
    Select-String -Pattern 'certificate SHA-256 digest' | Select-Object -First 1

# GitHub release asset: the file name carries the version so downloads are self-identifying.
# reader.apk stays as-is because the acceptance scripts install that exact path.
$relApk = "$out\jianyue-v$verName.apk"
Copy-Item -LiteralPath "$out\reader.apk" -Destination $relApk -Force
Write-Output ("APK: {0:N0} bytes   version {1} (versionCode {2})" -f `
    (Get-Item "$out\reader.apk").Length, $verName, $verCode)
Write-Output ("APK PATH : $out\reader.apk")
Write-Output ("RELEASE  : $relApk")
