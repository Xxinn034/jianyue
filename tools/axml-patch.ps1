# Stamp targetSdkVersion / usesCleartextTraffic into an APK manifest, without changing
# what aapt v0.2 produced anywhere else.
#
#   .\tools\axml-patch.ps1 -Apk out\app.apk -Out out\app-target.apk -TargetSdk 28
#
# Why this exists
# ---------------
# The build uses aapt v0.2 (2008) with the API-1 android.jar, so the manifest cannot
# declare either attribute:
#   * targetSdkVersion is not in the API-1 resource table (aapt: "No resource identifier
#     found for attribute 'targetSdkVersion'"), and pointing aapt at a newer android.jar
#     fails as well - it cannot read the newer resource table ("Bad string block").
#     Without the attribute the APK targets SDK 1 and Android 14+ refuses to install it:
#       INSTALL_FAILED_DEPRECATED_SDK_VERSION: App package must target at least SDK
#       version 24, but found 1
#   * usesCleartextTraffic is API 23+, and the book sources speak plain HTTP.
#
# Both are patched into the compiled manifest afterwards. The resource table, the dex and
# the signing pipeline are untouched: the change is one integer attribute on <uses-sdk>,
# one boolean on <application>, plus their ids in the XML resource-map chunk (which is
# how the framework resolves android:versionCode and friends).
#
# Values, all measured on emulator-5554 (API 1), emulator-5556 (API 16), a real Android 11
# phone and an Android 17 emulator (see docs/多版本兼容性矩阵.md):
#   targetSdk 28  < 24 is refused at install on Android 14+; < 28 makes Android 17 show
#                 the "built for an older version of Android" dialog; >= 29 forces scoped
#                 storage, which breaks reading /sdcard for book-source/local-book import.
#                 28 is installable everywhere, shows no dialog and keeps storage legacy.
#   cleartext    required from 28 on (Android 9+ blocks plain HTTP by default); older
#                frameworks do not know the attribute and ignore it.
#
# Layout facts the parser relies on (verified against the APK aapt v0.2 produces):
#   * the file is one RES_XML_TYPE chunk whose body is a flat list of chunks
#   * a START_ELEMENT chunk is: node header (16 bytes) + attrExt (20 bytes), then the
#     attribute array at  chunk + 16 + attributeStart
#   * attribute.name is an index into the RES_XML_RESOURCE_MAP chunk, not the string pool
#   * a ResXMLTree_attribute is 20 bytes: ns, name, rawValue (12) + Res_value (8)
#
# ASCII only on purpose: Windows PowerShell 5.1 reads BOM-less files as GBK, and this
# script also runs on the CI runner under powershell 5.1.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$Out,
    [int]$TargetSdk = 28,
    [switch]$NoCleartext
)

$ErrorActionPreference = 'Stop'

# .NET file APIs resolve relative paths against the process working directory, which is
# not what Set-Location changes, so anchor both paths to the caller's location first.
$Apk = $PSCmdlet.GetUnresolvedProviderPathFromPSPath($Apk)
$Out = $PSCmdlet.GetUnresolvedProviderPathFromPSPath($Out)

$RES_STRING_POOL_TYPE       = 0x0001
$RES_XML_TYPE               = 0x0003
$RES_XML_RESOURCE_MAP_TYPE  = 0x0180
$RES_XML_START_ELEMENT_TYPE = 0x0102

$TYPE_INT_DEC     = 0x10
$TYPE_INT_BOOLEAN = 0x12
# PowerShell 5.1 parses 0xFFFFFFFF as Int32 -1, so spell the unsigned max out.
$NO_INDEX         = [uint32]4294967295
$TRUE_VALUE       = [uint32]4294967295

$ATTR_TARGET_SDK_VERSION    = [uint32]0x01010270
$ATTR_USES_CLEARTEXT_TRAFFIC = [uint32]0x010104ec

function Get-Chunks {
    param([byte[]]$Data)
    $total = [BitConverter]::ToUInt32($Data, 4)
    $off = 8
    $list = New-Object System.Collections.ArrayList
    while (($off + 8) -le $total) {
        $type = [BitConverter]::ToUInt16($Data, $off)
        $size = [BitConverter]::ToUInt32($Data, $off + 4)
        if ($size -lt 8 -or ($off + $size) -gt $total) {
            throw ("corrupt AXML at offset " + $off)
        }
        [void]$list.Add([pscustomobject]@{
            Off    = $off
            Type   = $type
            Header = [BitConverter]::ToUInt16($Data, $off + 2)
            Size   = $size
        })
        $off += $size
    }
    return $list
}

# AXML string length: one byte for UTF-8 pools, two for UTF-16, high bit = two fields.
function Read-Length {
    param([byte[]]$Data, [int]$Pos, [bool]$Utf8, [ref]$Len)
    if ($Utf8) {
        $v = $Data[$Pos]
        if (($v -band 0x80) -ne 0) {
            $Len.Value = (($v -band 0x7F) -shl 8) -bor $Data[$Pos + 1]
            return $Pos + 2
        }
        $Len.Value = $v
        return $Pos + 1
    }
    $v = $Data[$Pos] -bor ($Data[$Pos + 1] -shl 8)
    if (($v -band 0x8000) -ne 0) {
        $Len.Value = (($v -band 0x7FFF) -shl 8) -bor $Data[$Pos + 2]
        return $Pos + 3
    }
    $Len.Value = $v
    return $Pos + 2
}

function Get-Strings {
    param([byte[]]$Data, $Pool)
    $count      = [BitConverter]::ToUInt32($Data, $Pool.Off + 8)
    $flags      = [BitConverter]::ToUInt32($Data, $Pool.Off + 16)
    $stringsAt  = [BitConverter]::ToUInt32($Data, $Pool.Off + 20)
    $utf8       = (($flags -band 0x100) -ne 0)
    $result = New-Object string[] $count
    for ($i = 0; $i -lt $count; $i++) {
        $rel = [BitConverter]::ToUInt32($Data, $Pool.Off + $Pool.Header + 4 * $i)
        $p = $Pool.Off + $stringsAt + $rel
        if ($utf8) {
            $n1 = 0; $p = Read-Length -Data $Data -Pos $p -Utf8 $true -Len ([ref]$n1)
            $n2 = 0; $p = Read-Length -Data $Data -Pos $p -Utf8 $true -Len ([ref]$n2)
            $result[$i] = [System.Text.Encoding]::UTF8.GetString($Data, $p, $n2)
        } else {
            $n = 0; $p = Read-Length -Data $Data -Pos $p -Utf8 $false -Len ([ref]$n)
            $result[$i] = [System.Text.Encoding]::Unicode.GetString($Data, $p, $n * 2)
        }
    }
    return $result
}

function Find-Element {
    param([byte[]]$Data, [string]$Name)
    $chunks = Get-Chunks -Data $Data
    $pool = $chunks | Where-Object { $_.Type -eq $RES_STRING_POOL_TYPE } | Select-Object -First 1
    if (-not $pool) { throw "manifest has no string pool" }
    $strings = Get-Strings -Data $Data -Pool $pool
    foreach ($c in $chunks) {
        if ($c.Type -eq $RES_XML_START_ELEMENT_TYPE) {
            $idx = [BitConverter]::ToUInt32($Data, $c.Off + 20)
            if ($idx -lt $strings.Length -and $strings[$idx] -eq $Name) { return $c }
        }
    }
    throw ("<" + $Name + "> not found in manifest")
}

function Add-Attribute {
    param([byte[]]$Data, [string]$Element, [uint32]$ResId, [byte]$ValueType, [uint32]$Value)

    $chunks = Get-Chunks -Data $Data
    $map = $chunks | Where-Object { $_.Type -eq $RES_XML_RESOURCE_MAP_TYPE } | Select-Object -First 1
    if (-not $map) { throw "manifest has no resource map chunk" }
    $mapCount = [int](($map.Size - $map.Header) / 4)
    $resIds = New-Object uint32[] $mapCount
    for ($i = 0; $i -lt $mapCount; $i++) {
        $resIds[$i] = [BitConverter]::ToUInt32($Data, $map.Off + $map.Header + 4 * $i)
    }
    if ($resIds -contains $ResId) { throw ("attribute 0x" + $ResId.ToString('x8') + " already present") }

    $el = Find-Element -Data $Data -Name $Element
    $attrStart = [BitConverter]::ToUInt16($Data, $el.Off + 24)
    $attrSize  = [BitConverter]::ToUInt16($Data, $el.Off + 26)
    $attrCount = [BitConverter]::ToUInt16($Data, $el.Off + 28)
    $base = $el.Off + 16 + $attrStart
    $insertAt = $base + $attrCount * $attrSize
    if ($insertAt -ne ($el.Off + $el.Size)) {
        throw ("attributes of <" + $Element + "> are not at the end of the chunk")
    }
    $ns = [BitConverter]::ToUInt32($Data, $base)

    # ResXMLTree_attribute: ns, name, rawValue, then Res_value (size, res0, dataType, data)
    $attr = New-Object byte[] 20
    [Array]::Copy([BitConverter]::GetBytes([uint32]$ns), 0, $attr, 0, 4)
    [Array]::Copy([BitConverter]::GetBytes([uint32]$mapCount), 0, $attr, 4, 4)
    [Array]::Copy([BitConverter]::GetBytes([uint32]$NO_INDEX), 0, $attr, 8, 4)
    [Array]::Copy([BitConverter]::GetBytes([uint16]8), 0, $attr, 12, 2)
    $attr[14] = 0
    $attr[15] = $ValueType
    [Array]::Copy([BitConverter]::GetBytes([uint32]$Value), 0, $attr, 16, 4)

    $newMapCount = $mapCount + 1
    $newMap = New-Object byte[] (8 + 4 * $newMapCount)
    [Array]::Copy([BitConverter]::GetBytes([uint16]$RES_XML_RESOURCE_MAP_TYPE), 0, $newMap, 0, 2)
    [Array]::Copy([BitConverter]::GetBytes([uint16]8), 0, $newMap, 2, 2)
    [Array]::Copy([BitConverter]::GetBytes([uint32]$newMap.Length), 0, $newMap, 4, 4)
    for ($i = 0; $i -lt $mapCount; $i++) {
        [Array]::Copy([BitConverter]::GetBytes($resIds[$i]), 0, $newMap, 8 + 4 * $i, 4)
    }
    [Array]::Copy([BitConverter]::GetBytes([uint32]$ResId), 0, $newMap, 8 + 4 * $mapCount, 4)

    $out = New-Object 'System.Collections.Generic.List[byte]'
    $out.AddRange([byte[]]$Data[0..($map.Off - 1)])
    $out.AddRange($newMap)
    if (($insertAt - 1) -ge ($map.Off + $map.Size)) {
        $out.AddRange([byte[]]$Data[($map.Off + $map.Size)..($insertAt - 1)])
    }
    $out.AddRange($attr)
    if ($insertAt -lt $Data.Length) {
        $out.AddRange([byte[]]$Data[$insertAt..($Data.Length - 1)])
    }
    $bytes = $out.ToArray()

    # The resource map grew by 4 bytes, so the element chunk moved down by 4.
    $newOff = $el.Off + 4
    [Array]::Copy([BitConverter]::GetBytes([uint32]($el.Size + $attr.Length)), 0, $bytes, $newOff + 4, 4)
    [Array]::Copy([BitConverter]::GetBytes([uint16]($attrCount + 1)), 0, $bytes, $newOff + 28, 2)
    [Array]::Copy([BitConverter]::GetBytes([uint32]$bytes.Length), 0, $bytes, 4, 4)
    return $bytes
}

function Patch-Manifest {
    param([byte[]]$Data, [int]$Sdk, [bool]$Cleartext)
    $bytes = Add-Attribute -Data $Data -Element 'uses-sdk' -ResId $ATTR_TARGET_SDK_VERSION `
        -ValueType $TYPE_INT_DEC -Value ([uint32]$Sdk)
    if ($Cleartext) {
        $bytes = Add-Attribute -Data $bytes -Element 'application' -ResId $ATTR_USES_CLEARTEXT_TRAFFIC `
            -ValueType $TYPE_INT_BOOLEAN -Value $TRUE_VALUE
    }
    return $bytes
}

# ------------------------------------------------------------------------------- main

Add-Type -AssemblyName System.IO.Compression.FileSystem

$zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
try {
    $entry = $zip.Entries | Where-Object { $_.FullName -eq 'AndroidManifest.xml' } | Select-Object -First 1
    if (-not $entry) { throw ($Apk + " has no AndroidManifest.xml") }
    $stream = $entry.Open()
    $ms = New-Object System.IO.MemoryStream
    $stream.CopyTo($ms)
    $stream.Close()
    $manifest = $ms.ToArray()
    $ms.Close()
} finally {
    $zip.Dispose()
}

$patched = Patch-Manifest -Data $manifest -Sdk $TargetSdk -Cleartext (-not $NoCleartext)

Copy-Item -LiteralPath $Apk -Destination $Out -Force
$zip = [System.IO.Compression.ZipFile]::Open($Out, 'Update')
try {
    $old = $zip.Entries | Where-Object { $_.FullName -eq 'AndroidManifest.xml' } | Select-Object -First 1
    if ($old) { $old.Delete() }
    $new = $zip.CreateEntry('AndroidManifest.xml', [System.IO.Compression.CompressionLevel]::Optimal)
    $os = $new.Open()
    $os.Write($patched, 0, $patched.Length)
    $os.Close()
} finally {
    $zip.Dispose()
}

Write-Output ("manifest patched: targetSdkVersion=" + $TargetSdk + " usesCleartextTraffic=" + (-not $NoCleartext) `
    + " (" + $manifest.Length + " -> " + $patched.Length + " bytes)")
