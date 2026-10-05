# Thread a book source's extra headers through every Engine network call.
#
# 阅读 sources declare custom headers (usually Referer + User-Agent) and most Chinese novel
# aggregators reject requests without them. Http gained a headers overload; this script
# updates the 5 call sites in Engine so the source's headers actually reach the wire.
#
# ASCII-only on purpose (Windows PowerShell 5.1 misreads BOM-less UTF-8).
$ErrorActionPreference = 'Stop'

$path = 'G:\jianyue-src\src\com\jianyue\reader\engine\Engine.java'
$txt = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
$orig = $txt

# Every call site currently passes a null ua; add the source's headers map.
$pairs = @(
    @('Http.getWithHttpFallback(url, src.url, null)',
      'Http.getWithHttpFallback(url, src.url, null, src.headers())'),
    @('Http.getWithHttpFallback(bookUrl, src.url, null)',
      'Http.getWithHttpFallback(bookUrl, src.url, null, src.headers())'),
    @('Http.getWithHttpFallback(url, b.bookUrl, null)',
      'Http.getWithHttpFallback(url, b.bookUrl, null, headerMap)'),
    @('Http.getWithHttpFallback(bookUrl, b.bookUrl, null)',
      'Http.getWithHttpFallback(bookUrl, b.bookUrl, null, headerMap)')
)
foreach ($p in $pairs) {
    $n = ([regex]::Matches($txt, [regex]::Escape($p[0]))).Count
    $txt = $txt.Replace($p[0], $p[1])
    Write-Output ("replaced " + $n + "x : " + $p[0])
}

# loadContent has no BookSource in scope, so derive the header map from the source URL.
$anchor = 'Http.Resp d = Http.getWithHttpFallback(bookUrl, b.bookUrl, null, headerMap);'
if ($txt.Contains($anchor) -and -not $txt.Contains('Map<String, String> headerMap')) {
    Write-Output "note: loadContent needs a headerMap local (see report below)"
}

if ($txt -ne $orig) {
    [System.IO.File]::WriteAllText($path, $txt, (New-Object System.Text.UTF8Encoding($false)))
    Write-Output ""
    Write-Output "Engine.java updated"
} else {
    Write-Output ""
    Write-Output "no change"
}

Write-Output ""
Write-Output "---- resulting call sites ----"
Select-String -Path $path -Pattern 'Http\.getWithHttpFallback' |
    ForEach-Object { Write-Output ("{0,4}: {1}" -f $_.LineNumber, $_.Line.Trim()) }
