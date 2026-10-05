# Grab the API-1 emulator's screen as a PNG.
#
#   & 'G:\jianyue-src\tools\grab-screen.ps1' [-Out G:\jianyue-src\out\shot.png]
#
# WHY THIS EXISTS
# ---------------
# API 1 has no `adb shell screencap` (that arrives at API 4) and this 2008 emulator's console
# has no `screenrecord` command, so neither of the usual grabs is available. The kernel
# framebuffer is: /dev/graphics/fb0 is readable by the emulator's root shell, and the emulator
# keeps rendering into it even under -no-window. fb_to_png.py turns the raw dump into PNGs.
#
# MEASURED GEOMETRY: 16 bpp RGB565, virtual_size "320,960" - the panel is 480x320 landscape
# but the framebuffer stores the image transposed as 320x480, two pages deep. Hence
# "--size 480x320 --rotate ccw" in the call below, which is what makes the PNG upright.
#
# WHICH PAGE IS ON SCREEN
# -----------------------
# The driver double-buffers and pans between the two pages, so the dump contains the live
# frame AND the previous one. /sys/class/graphics/fb0/pan reports the current y offset
# (0 or 480 here), which is what picks the live page - without it a shot of the 本地导入 page
# came back showing 书源 (the previous frame). Both pages are still copied next to the output
# as -p0/-p1, so a surprising shot can be checked against the other page.
#
# The pull is slow (~25 s for 600 KB through this ancient adbd), so this is for inspection,
# not for putting inside a long test loop.
param(
    [string]$Out = 'G:\jianyue-src\out\screen.png',
    [string]$Dev = 'emulator-5554'
)
$ErrorActionPreference = 'Continue'

$adb = 'C:\Users\LX\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$py = 'C:\Users\LX\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
$work = 'G:\jianyue-src\out'
$raw = "$work\fb.raw"
$prefix = "$work\screen"

# Read the live page BEFORE dumping: 0,0 means the visible frame starts at page 0.
$pan = (& $adb -s $Dev shell "cat /sys/class/graphics/fb0/pan" 2>&1) -join ''
$page = 0
if ($pan -match '^\s*\d+\s*,\s*(\d+)') { $page = [int]([int]$Matches[1] / 480) }
if ($page -lt 0 -or $page -gt 1) { $page = 0 }
Write-Output ("pan=" + $pan.Trim() + " -> live page " + $page)

& $adb -s $Dev shell "dd if=/dev/graphics/fb0 of=/sdcard/fb.raw bs=614400 count=1" 2>&1 | Out-Null
Remove-Item $raw -Force -ErrorAction SilentlyContinue
& $adb -s $Dev pull /sdcard/fb.raw $raw 2>&1 | Out-Null
if (-not (Test-Path $raw)) { Write-Output '!! fb dump failed'; exit 1 }

& $py 'G:\jianyue-src\tools\fb_to_png.py' $raw $prefix --size 480x320 --rotate ccw
$live = "$prefix-page$page.png"
if (-not (Test-Path $live)) { Write-Output '!! conversion failed'; exit 1 }
Copy-Item "$prefix-page0.png" "$Out-p0.png" -Force
Copy-Item "$prefix-page1.png" "$Out-p1.png" -Force
Copy-Item $live $Out -Force
Write-Output ("saved: " + $Out + "   (pages also at " + $Out + "-p0.png / -p1.png)")
