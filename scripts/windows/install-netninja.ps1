<#
.SYNOPSIS
  NET NiNjA — Simplified One-Click Windows Installer
  No Inno Setup, no JDK required on the build machine.

  This script:
  1. Builds the server fat-JAR (if not pre-built)
  2. Downloads a portable JRE if no Java is found
  3. Copies everything to %LOCALAPPDATA%\NET_NiNjA
  4. Creates Desktop and Start Menu shortcuts
  5. Launches the server + opens the dashboard

  Run from the repo root:
    powershell -ExecutionPolicy Bypass -File scripts\windows\install-netninja.ps1

.PARAMETER SkipBuild
  Skip the Gradle build if server-all.jar already exists.
#>
param(
  [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# ── Paths ──────────────────────────────────────────────────────────────────
$repoRoot    = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$gradlew     = Join-Path $repoRoot "gradlew.bat"
$webUiDir    = Join-Path $repoRoot "web-ui"

$installDir  = Join-Path $env:LOCALAPPDATA "NET_NiNjA"
$installLib  = Join-Path $installDir "lib"
$installUi   = Join-Path $installDir "web-ui"
$installJre  = Join-Path $installDir "jre"

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  NET NiNjA — One-Click Windows Installer" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# ── Step 1: Build server fat-JAR ──────────────────────────────────────────
$fatJar = Join-Path $repoRoot "server\build\libs\server-all.jar"

if ($SkipBuild -and (Test-Path $fatJar)) {
  Write-Host "[1/5] Skipping build — fat-JAR exists." -ForegroundColor Yellow
} elseif (Test-Path $gradlew) {
  Write-Host "[1/5] Building server fat-JAR..." -ForegroundColor Yellow
  Push-Location $repoRoot
  try {
    & $gradlew :server:shadowJar --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle shadowJar failed (exit $LASTEXITCODE)" }
  } finally {
    Pop-Location
  }
  if (-not (Test-Path $fatJar)) {
    throw "Expected fat-JAR at $fatJar but it was not created."
  }
  $size = [math]::Round((Get-Item $fatJar).Length / 1MB, 1)
  Write-Host "  Built: server-all.jar ($size MB)" -ForegroundColor Green
} else {
  throw "gradlew.bat not found at $gradlew and no pre-built JAR at $fatJar."
}
Write-Host ""

# ── Step 2: Find or download a portable JRE ───────────────────────────────
Write-Host "[2/5] Checking for Java..." -ForegroundColor Yellow

$javaExe = $null

# Check if we already installed a JRE
if (Test-Path (Join-Path $installJre "bin\java.exe")) {
  $javaExe = Join-Path $installJre "bin\java.exe"
  Write-Host "  Using previously installed JRE: $javaExe" -ForegroundColor Green
}

# Check JAVA_HOME
if (-not $javaExe) {
  if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
    Write-Host "  Found JAVA_HOME: $javaExe" -ForegroundColor Green
  }
}

# Check system PATH
if (-not $javaExe) {
  $found = (Get-Command java -ErrorAction SilentlyContinue)
  if ($found) {
    $javaExe = $found.Source
    Write-Host "  Found system Java: $javaExe" -ForegroundColor Green
  }
}

# Download Adoptium (Eclipse Temurin) portable JRE if nothing found
if (-not $javaExe) {
  Write-Host "  Java not found — downloading portable JRE (Eclipse Temurin 21)..." -ForegroundColor Yellow
  $jreZipUrl = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk"
  $jreZip    = Join-Path $env:TEMP "adoptium-jre21.zip"
  $jreTmp    = Join-Path $env:TEMP "adoptium-jre21"

  try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $jreZipUrl -OutFile $jreZip -UseBasicParsing
    Write-Host "  Downloaded. Extracting..." -ForegroundColor Cyan

    if (Test-Path $jreTmp) { Remove-Item -Recurse -Force $jreTmp }
    Expand-Archive -Path $jreZip -DestinationPath $jreTmp -Force

    # Adoptium extracts to a subfolder like jdk-21.0.x+y-jre/
    $jreSubDir = Get-ChildItem -Path $jreTmp -Directory | Select-Object -First 1
    if (-not $jreSubDir) { throw "JRE extraction produced no subdirectory." }

    if (Test-Path $installJre) { Remove-Item -Recurse -Force $installJre }
    Copy-Item -Path $jreSubDir.FullName -Destination $installJre -Recurse
    $javaExe = Join-Path $installJre "bin\java.exe"

    # Clean up temp
    Remove-Item -Force $jreZip -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $jreTmp -ErrorAction SilentlyContinue

    Write-Host "  Portable JRE installed: $installJre" -ForegroundColor Green
  } catch {
    Write-Host ""
    Write-Host "  ERROR: Failed to download JRE. Install JDK 21 manually:" -ForegroundColor Red
    Write-Host "  https://adoptium.net/temurin/releases/" -ForegroundColor White
    throw "Java is required to run NET NiNjA."
  }
}
Write-Host ""

# ── Step 3: Install files ─────────────────────────────────────────────────
Write-Host "[3/5] Installing to $installDir ..." -ForegroundColor Yellow

# Create directories
foreach ($d in @($installDir, $installLib, $installUi)) {
  if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
}

# Copy fat-JAR
Copy-Item -Path $fatJar -Destination (Join-Path $installLib "server-all.jar") -Force

# Copy web-ui (mirror the directory)
if (Test-Path $installUi) { Remove-Item -Recurse -Force $installUi }
Copy-Item -Path $webUiDir -Destination $installUi -Recurse

# Copy launcher scripts
Copy-Item -Path (Join-Path $PSScriptRoot "NetNiNjA.cmd") -Destination $installDir -Force
Copy-Item -Path (Join-Path $PSScriptRoot "NetNiNjA-launcher.ps1") -Destination $installDir -Force

# Convert icon if possible
$iconPng = Join-Path $webUiDir "new_assets\new_ninjacon.png"
$iconIco = Join-Path $installDir "netninja.ico"
$iconScript = Join-Path $PSScriptRoot "convert-icon.ps1"
if ((Test-Path $iconPng) -and (Test-Path $iconScript)) {
  try {
    & $iconScript -PngPath $iconPng -IcoPath $iconIco
  } catch {
    Write-Host "  WARN: Icon conversion failed — using default icon." -ForegroundColor Yellow
  }
}

Write-Host "  Installed server, web-ui, launcher to $installDir" -ForegroundColor Green
Write-Host ""

# ── Step 4: Create shortcuts ──────────────────────────────────────────────
Write-Host "[4/5] Creating Desktop and Start Menu shortcuts..." -ForegroundColor Yellow

$shell = New-Object -ComObject WScript.Shell

# Desktop shortcut
$desktopLink = Join-Path ([Environment]::GetFolderPath("Desktop")) "NET NiNjA.lnk"
$sc = $shell.CreateShortcut($desktopLink)
$sc.TargetPath = "powershell.exe"
$sc.Arguments = "-ExecutionPolicy Bypass -WindowStyle Hidden -File `"$installDir\NetNiNjA-launcher.ps1`""
$sc.WorkingDirectory = $installDir
$sc.Description = "Launch NET NiNjA Network Dashboard"
if (Test-Path $iconIco) { $sc.IconLocation = $iconIco }
$sc.Save()

# Start Menu shortcut
$startMenu = Join-Path ([Environment]::GetFolderPath("StartMenu")) "Programs"
$startLink = Join-Path $startMenu "NET NiNjA.lnk"
$sc2 = $shell.CreateShortcut($startLink)
$sc2.TargetPath = "powershell.exe"
$sc2.Arguments = "-ExecutionPolicy Bypass -WindowStyle Hidden -File `"$installDir\NetNiNjA-launcher.ps1`""
$sc2.WorkingDirectory = $installDir
$sc2.Description = "Launch NET NiNjA Network Dashboard"
if (Test-Path $iconIco) { $sc2.IconLocation = $iconIco }
$sc2.Save()

Write-Host "  Desktop shortcut: $desktopLink" -ForegroundColor Green
Write-Host "  Start Menu: $startLink" -ForegroundColor Green
Write-Host ""

# ── Step 5: Launch ────────────────────────────────────────────────────────
Write-Host "[5/5] Launching NET NiNjA..." -ForegroundColor Yellow

$env:NET_NINJA_HOST = if ($env:NET_NINJA_HOST) { $env:NET_NINJA_HOST } else { "127.0.0.1" }
$env:NET_NINJA_PORT = if ($env:NET_NINJA_PORT) { $env:NET_NINJA_PORT } else { "8787" }
$env:NET_NINJA_DB   = if ($env:NET_NINJA_DB)   { $env:NET_NINJA_DB }   else { Join-Path $installDir "netninja.db" }

$dashUrl = "http://$($env:NET_NINJA_HOST):$($env:NET_NINJA_PORT)/ui/ninja_mobile_new.html"

# Start server in background
$serverArgs = "-Xms64m -Xmx512m -Dfile.encoding=UTF-8 -cp `"$(Join-Path $installLib '*')`" server.DesktopLauncherKt `"$installUi`""
Start-Process -FilePath $javaExe -ArgumentList $serverArgs -WorkingDirectory $installDir -WindowStyle Hidden

# Wait for server readiness (up to 15 seconds)
$deadline = (Get-Date).AddSeconds(15)
$ready = $false
while ((Get-Date) -lt $deadline) {
  try {
    $resp = Invoke-WebRequest -Uri "http://$($env:NET_NINJA_HOST):$($env:NET_NINJA_PORT)/api/v1/system/info" -TimeoutSec 2 -UseBasicParsing -ErrorAction SilentlyContinue
    if ($resp.StatusCode -eq 200) {
      $ready = $true
      break
    }
  } catch {}
  Start-Sleep -Milliseconds 500
}

if ($ready) {
  Start-Process $dashUrl
  Write-Host "  Dashboard opened in your browser." -ForegroundColor Green
} else {
  Write-Host "  Server is still starting — open $dashUrl manually." -ForegroundColor Yellow
  Start-Process $dashUrl
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "  INSTALLATION COMPLETE!" -ForegroundColor Green
Write-Host "" -ForegroundColor Green
Write-Host "  Install location: $installDir" -ForegroundColor White
Write-Host "  Dashboard:        $dashUrl" -ForegroundColor White
Write-Host "  Double-click 'NET NiNjA' on your Desktop to launch." -ForegroundColor White
Write-Host "" -ForegroundColor Green
Write-Host "  To uninstall: delete $installDir and the desktop shortcut." -ForegroundColor Gray
Write-Host "============================================================" -ForegroundColor Green
Write-Host ""
