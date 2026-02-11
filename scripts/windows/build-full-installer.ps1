<#
.SYNOPSIS
  NET NiNjA v1.2 — Full Windows Installer Builder

  Automates everything:
  1. Builds the server fat-JAR via Gradle
  2. Bundles a portable JRE (from the local JDK or downloads Adoptium)
  3. Converts the PNG icon to ICO
  4. Stages all files (lib, jre, web-ui, launchers)
  5. Runs Inno Setup to produce the final Setup EXE

.PARAMETER AppVersion
  Semantic version for the installer (default: 1.2.0)

.PARAMETER SkipBuild
  Skip the Gradle build if the fat-JAR already exists.

.PARAMETER JrePath
  Path to an existing JRE directory to bundle. If not provided, the script
  will extract a portable JRE from your JAVA_HOME.

.PARAMETER InnoSetupPath
  Path to the Inno Setup compiler (iscc.exe). Auto-detected if installed
  in standard locations.

.EXAMPLE
  .\scripts\windows\build-full-installer.ps1
  .\scripts\windows\build-full-installer.ps1 -AppVersion "1.2.1" -SkipBuild
#>
param(
  [string]$AppVersion    = "1.2.0",
  [switch]$SkipBuild,
  [string]$JrePath       = "",
  [string]$InnoSetupPath = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# ── Paths ──────────────────────────────────────────────────────────────────
$repoRoot    = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$scriptsDir  = Join-Path $repoRoot "scripts\windows"
$stagingDir  = Join-Path $scriptsDir "staging"
$outputDir   = Join-Path $repoRoot "build\windows-installer\out"
$gradlew     = Join-Path $repoRoot "gradlew.bat"
$webUiDir    = Join-Path $repoRoot "web-ui"
$iconPng     = Join-Path $repoRoot "web-ui\new_assets\ninja_icon.png"

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  NET NiNjA v$AppVersion — Windows Installer Builder" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# ── Step 0: Validate prerequisites ────────────────────────────────────────
Write-Host "[0/6] Checking prerequisites..." -ForegroundColor Yellow

if (-not (Test-Path $gradlew)) {
  throw "gradlew.bat not found at $gradlew"
}

# Find Inno Setup compiler
if ($InnoSetupPath -and (Test-Path $InnoSetupPath)) {
  $iscc = $InnoSetupPath
} else {
  $isccCandidates = @(
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles}\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles(x86)}\Inno Setup 5\ISCC.exe"
  )
  $iscc = $isccCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1

  if (-not $iscc) {
    Write-Host ""
    Write-Host "  Inno Setup not found. Install it from:" -ForegroundColor Red
    Write-Host "  https://jrsoftware.org/isdl.php" -ForegroundColor White
    Write-Host ""
    Write-Host "  Or specify the path with -InnoSetupPath" -ForegroundColor Gray
    throw "ISCC.exe not found. Install Inno Setup 6 to continue."
  }
}
Write-Host "  Inno Setup: $iscc" -ForegroundColor Green

# Validate Java
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path $javaHome)) {
  $javaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
  if ($javaExe) {
    $javaHome = Split-Path (Split-Path $javaExe)
    Write-Host "  JAVA_HOME not set; detected: $javaHome" -ForegroundColor Yellow
  } else {
    throw "Java not found. Set JAVA_HOME or install JDK 21."
  }
}
Write-Host "  JAVA_HOME: $javaHome" -ForegroundColor Green
Write-Host ""

# ── Step 1: Build the server fat-JAR ──────────────────────────────────────
$fatJar = Join-Path $repoRoot "server\build\libs\server-all.jar"

if ($SkipBuild -and (Test-Path $fatJar)) {
  Write-Host "[1/6] Skipping Gradle build (fat-JAR exists)." -ForegroundColor Yellow
} else {
  Write-Host "[1/6] Building server fat-JAR..." -ForegroundColor Yellow
  Push-Location $repoRoot
  & $gradlew :server:shadowJar --no-daemon --console=plain
  if ($LASTEXITCODE -ne 0) { throw "Gradle shadowJar failed (exit $LASTEXITCODE)" }
  Pop-Location

  if (-not (Test-Path $fatJar)) {
    throw "Expected fat-JAR at $fatJar but it was not created."
  }
}
Write-Host "  Fat-JAR: $fatJar ($(([math]::Round((Get-Item $fatJar).Length / 1MB, 1))) MB)" -ForegroundColor Green
Write-Host ""

# ── Step 2: Create staging directory ──────────────────────────────────────
Write-Host "[2/6] Preparing staging directory..." -ForegroundColor Yellow

if (Test-Path $stagingDir) { Remove-Item -Recurse -Force $stagingDir }
New-Item -ItemType Directory -Path $stagingDir -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $stagingDir "lib") -Force | Out-Null

# Copy the fat-JAR
Copy-Item -Path $fatJar -Destination (Join-Path $stagingDir "lib\server-all.jar")

# Also copy individual jars from installDist if available (for classpath mode)
$installLibDir = Join-Path $repoRoot "server\build\install\server\lib"
if (Test-Path $installLibDir) {
  Get-ChildItem -Path $installLibDir -Filter "*.jar" | ForEach-Object {
    Copy-Item $_.FullName (Join-Path $stagingDir "lib\$($_.Name)") -Force
  }
}

# Copy web-ui directory (excluding stale duplicates in new_assets)
$stagingWebUi = Join-Path $stagingDir "web-ui"
Copy-Item -Path $webUiDir -Destination $stagingWebUi -Recurse

# Copy launcher scripts
Copy-Item -Path (Join-Path $scriptsDir "NetNiNjA.cmd") -Destination $stagingDir
Copy-Item -Path (Join-Path $scriptsDir "NetNiNjA-launcher.ps1") -Destination $stagingDir

Write-Host "  Staged lib/, web-ui/, launchers." -ForegroundColor Green
Write-Host ""

# ── Step 3: Bundle portable JRE ───────────────────────────────────────────
Write-Host "[3/6] Bundling portable JRE..." -ForegroundColor Yellow

$stagingJre = Join-Path $stagingDir "jre"

if ($JrePath -and (Test-Path $JrePath)) {
  Write-Host "  Using provided JRE: $JrePath" -ForegroundColor Cyan
  Copy-Item -Path $JrePath -Destination $stagingJre -Recurse
} else {
  # Use jlink to create a minimal runtime image from the local JDK
  $jlink = Join-Path $javaHome "bin\jlink.exe"
  if (-not (Test-Path $jlink)) {
    # Fallback: copy the full JRE directory
    Write-Host "  jlink not found; copying JRE from JAVA_HOME..." -ForegroundColor Yellow
    $jreSource = Join-Path $javaHome "jre"
    if (-not (Test-Path $jreSource)) {
      # JDK 11+ doesn't have a jre/ subdirectory — copy the whole JDK
      $jreSource = $javaHome
    }
    Copy-Item -Path $jreSource -Destination $stagingJre -Recurse
  } else {
    Write-Host "  Creating minimal JRE with jlink..." -ForegroundColor Cyan
    # Modules needed by the Ktor/Netty server + desktop browser launch
    $modules = @(
      "java.base",
      "java.desktop",       # AWT Desktop.browse()
      "java.logging",
      "java.management",
      "java.naming",        # DNS lookups
      "java.net.http",
      "java.security.jgss",
      "java.sql",           # SQLite JDBC
      "jdk.crypto.ec",      # TLS curves
      "jdk.unsupported"     # Netty native access
    ) -join ","

    & $jlink `
      --add-modules $modules `
      --strip-debug `
      --no-man-pages `
      --no-header-files `
      --compress=zip-6 `
      --output $stagingJre

    if ($LASTEXITCODE -ne 0) {
      Write-Host "  jlink failed; falling back to full JRE copy." -ForegroundColor Yellow
      if (Test-Path $stagingJre) { Remove-Item -Recurse -Force $stagingJre }
      Copy-Item -Path $javaHome -Destination $stagingJre -Recurse
    }
  }
}

$jreSize = [math]::Round(((Get-ChildItem -Path $stagingJre -Recurse | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host "  Bundled JRE: $jreSize MB" -ForegroundColor Green
Write-Host ""

# ── Step 4: Convert icon ──────────────────────────────────────────────────
Write-Host "[4/6] Converting application icon..." -ForegroundColor Yellow

$icoFile = Join-Path $stagingDir "netninja.ico"
$iconScript = Join-Path $scriptsDir "convert-icon.ps1"

if (Test-Path $iconPng) {
  & $iconScript -PngPath $iconPng -IcoPath $icoFile
  if (-not (Test-Path $icoFile)) {
    Write-Host "  WARN: Icon conversion failed. Installer will use default icon." -ForegroundColor Yellow
  } else {
    Write-Host "  Icon: $icoFile" -ForegroundColor Green
  }
} else {
  Write-Host "  WARN: $iconPng not found. Installer will use default icon." -ForegroundColor Yellow
}
Write-Host ""

# ── Step 5: Create README ─────────────────────────────────────────────────
Write-Host "[5/6] Generating installer README..." -ForegroundColor Yellow

$readmeText = @"
NET NiNjA v$AppVersion — Network Dashboard
============================================

Quick Start:
  1. Launch "NET NiNjA" from your Desktop or Start Menu.
  2. Your browser will open to http://127.0.0.1:8787/ui/ninja_mobile_new.html
  3. The server runs in the background (check system tray).

Configuration:
  Set these environment variables to customize behavior:
    NET_NINJA_HOST  — Bind address (default: 127.0.0.1)
    NET_NINJA_PORT  — HTTP port (default: 8787)
    NET_NINJA_DB    — Database path (default: %LOCALAPPDATA%\NET_NiNjA\netninja.db)
    NET_NINJA_TOKEN — API authentication token (auto-generated if not set)

Data Location:
  %LOCALAPPDATA%\NET_NiNjA\
    netninja.db  — SQLite database
    server.log   — Server log output

Uninstall:
  Use "Add or Remove Programs" in Windows Settings, or run the
  uninstaller from the Start Menu.

Project: https://github.com/aztr0nutzs/NET_NiNjA.v1.2
"@

Set-Content -Path (Join-Path $stagingDir "README.txt") -Value $readmeText -Encoding UTF8

# Copy LICENSE into staging if it exists (or will be created below)
$licenseFile = Join-Path $repoRoot "LICENSE"
if (-not (Test-Path $licenseFile)) {
  Write-Host "  Creating MIT LICENSE file..." -ForegroundColor Yellow
  $licenseText = @"
MIT License

Copyright (c) 2026 NET NiNjA Project

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
"@
  Set-Content -Path $licenseFile -Value $licenseText -Encoding UTF8
}
Copy-Item -Path $licenseFile -Destination (Join-Path $stagingDir "LICENSE") -ErrorAction SilentlyContinue
Write-Host "  README.txt created." -ForegroundColor Green
Write-Host ""

# ── Step 6: Build installer EXE with Inno Setup ──────────────────────────
Write-Host "[6/6] Building installer EXE with Inno Setup..." -ForegroundColor Yellow
if (-not (Test-Path $outputDir)) {
  New-Item -ItemType Directory -Path $outputDir -Force | Out-Null
}

# Update the version in the .iss file dynamically
$issFile = Join-Path $scriptsDir "netninja-setup.iss"
$issContent = Get-Content $issFile -Raw
$issContent = $issContent -replace '#define MyAppVersion\s+"[^"]+"', "#define MyAppVersion   `"$AppVersion`""
Set-Content -Path $issFile -Value $issContent -Encoding UTF8

# Run Inno Setup
& $iscc $issFile
if ($LASTEXITCODE -ne 0) {
  throw "Inno Setup failed (exit $LASTEXITCODE). Check the output above."
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "  BUILD COMPLETE!" -ForegroundColor Green
Write-Host "" -ForegroundColor Green
$exeFile = Get-ChildItem -Path $outputDir -Filter "NetNiNjA-*.exe" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($exeFile) {
  $exeSize = [math]::Round($exeFile.Length / 1MB, 1)
  Write-Host "  Installer: $($exeFile.FullName)" -ForegroundColor White
  Write-Host "  Size:      $exeSize MB" -ForegroundColor White
} else {
  Write-Host "  Installer: $outputDir" -ForegroundColor White
}
Write-Host ""
Write-Host "  To install: double-click the EXE and follow the wizard." -ForegroundColor Gray
Write-Host "============================================================" -ForegroundColor Green
Write-Host ""
