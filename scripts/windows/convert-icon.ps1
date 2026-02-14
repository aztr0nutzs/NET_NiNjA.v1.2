<#
.SYNOPSIS
  Converts a PNG to a multi-resolution ICO file for the Windows installer.
  Uses System.Drawing (available on all Windows machines) — no external tools needed.

.PARAMETER PngPath
  Path to the source PNG file.

.PARAMETER IcoPath
  Output path for the ICO file.
#>
param(
  [Parameter(Mandatory)][string]$PngPath,
  [Parameter(Mandatory)][string]$IcoPath
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$source = [System.Drawing.Image]::FromFile((Resolve-Path $PngPath).Path)

# ICO sizes from largest to smallest (Windows uses 256, 48, 32, 16)
$sizes = @(256, 128, 64, 48, 32, 16)

$ms = New-Object System.IO.MemoryStream

# --- ICO header (6 bytes) ---
$bw = New-Object System.IO.BinaryWriter($ms)
$bw.Write([UInt16]0)           # reserved
$bw.Write([UInt16]1)           # type: ICO
$bw.Write([UInt16]$sizes.Count)

# We'll fill in directory entries after rendering bitmaps.
$bitmapDataList = @()

foreach ($sz in $sizes) {
  $bmp = New-Object System.Drawing.Bitmap($sz, $sz)
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
  $g.DrawImage($source, 0, 0, $sz, $sz)
  $g.Dispose()

  $pngStream = New-Object System.IO.MemoryStream
  $bmp.Save($pngStream, [System.Drawing.Imaging.ImageFormat]::Png)
  $bitmapDataList += ,@($sz, $pngStream.ToArray())
  $pngStream.Dispose()
  $bmp.Dispose()
}

# --- Directory entries (16 bytes each) ---
# Offset to first image data = 6 (header) + 16 * count
$dataOffset = 6 + (16 * $sizes.Count)

foreach ($entry in $bitmapDataList) {
  $sz   = $entry[0]
  $data = $entry[1]

  $widthByte  = if ($sz -ge 256) { 0 } else { $sz }
  $heightByte = if ($sz -ge 256) { 0 } else { $sz }

  $bw.Write([byte]$widthByte)   # width  (0 = 256)
  $bw.Write([byte]$heightByte)  # height (0 = 256)
  $bw.Write([byte]0)            # palette count
  $bw.Write([byte]0)            # reserved
  $bw.Write([UInt16]1)          # color planes
  $bw.Write([UInt16]32)         # bits per pixel
  $bw.Write([UInt32]$data.Length)
  $bw.Write([UInt32]$dataOffset)

  $dataOffset += $data.Length
}

# --- Image data ---
foreach ($entry in $bitmapDataList) {
  $data = $entry[1]
  $bw.Write($data)
}

$bw.Flush()

# Write the ICO — use the provided path directly (may be absolute or relative)
$resolvedIcoPath = if ([System.IO.Path]::IsPathRooted($IcoPath)) {
  $IcoPath
} else {
  Join-Path (Get-Location) $IcoPath
}
[System.IO.File]::WriteAllBytes($resolvedIcoPath, $ms.ToArray())
$ms.Dispose()
$source.Dispose()

Write-Host "Created ICO: $resolvedIcoPath ($($sizes.Count) sizes: $($sizes -join ', ')px)"
