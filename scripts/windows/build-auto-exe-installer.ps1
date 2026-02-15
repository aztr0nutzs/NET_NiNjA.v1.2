param(
  [string]$AppVersion = "1.2.0",
  [switch]$SkipBuild,
  [string]$InnoSetupPath = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$fullScript = Join-Path $PSScriptRoot "build-full-installer.ps1"
$jpackageScript = Join-Path $PSScriptRoot "build-installer.ps1"

if (-not (Test-Path $fullScript)) { throw "Missing script: $fullScript" }
if (-not (Test-Path $jpackageScript)) { throw "Missing script: $jpackageScript" }

$hasInno = $false
if ($InnoSetupPath -and (Test-Path $InnoSetupPath)) {
  $hasInno = $true
} else {
  $isccCandidates = @(
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles}\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles(x86)}\Inno Setup 5\ISCC.exe"
  )
  $hasInno = $isccCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}

if ($hasInno) {
  Write-Host "Using Inno Setup pipeline (full self-contained EXE)..." -ForegroundColor Cyan
  $args = @("-AppVersion", $AppVersion)
  if ($SkipBuild) { $args += "-SkipBuild" }
  if ($InnoSetupPath) { $args += @("-InnoSetupPath", $InnoSetupPath) }
  & $fullScript @args
  exit $LASTEXITCODE
}

if (Get-Command jpackage -ErrorAction SilentlyContinue) {
  $hasWixLight = Get-Command light.exe -ErrorAction SilentlyContinue
  $hasWixCandle = Get-Command candle.exe -ErrorAction SilentlyContinue
  if (-not $hasWixLight -or -not $hasWixCandle) {
    throw "jpackage is installed, but WiX tools are missing (light.exe/candle.exe). Install WiX Toolset 3+ and add it to PATH, or install Inno Setup 6."
  }
  Write-Host "Inno Setup not found; falling back to jpackage EXE pipeline..." -ForegroundColor Yellow
  $args = @("-AppVersion", $AppVersion)
  & $jpackageScript @args
  exit $LASTEXITCODE
}

throw "No EXE builder available. Install Inno Setup 6 (preferred) or JDK 17+ with jpackage."
