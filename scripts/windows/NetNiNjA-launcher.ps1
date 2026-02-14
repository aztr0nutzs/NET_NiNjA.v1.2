<#
.SYNOPSIS
  NET NiNjA — Silent Windows launcher.
  Starts the Ktor server in a hidden window and opens the dashboard in the default browser.
  Used as the desktop shortcut target so no console window stays on screen.
#>
$ErrorActionPreference = "Stop"

$appDir   = Split-Path -Parent $MyInvocation.MyCommand.Path
$jreDir   = Join-Path $appDir "jre"
$libDir   = Join-Path $appDir "lib"
$webUiDir = Join-Path $appDir "web-ui"
$dataDir  = Join-Path $env:LOCALAPPDATA "NET_NiNjA"
$logFile  = Join-Path $dataDir "server.log"

# Determine repo root only if we are running from the repo (not from an installed location).
# Installed layout: {app}\NetNiNjA-launcher.ps1, {app}\lib\, {app}\web-ui\, {app}\jre\
# Repo layout:      scripts\windows\NetNiNjA-launcher.ps1, server\build\..., web-ui\
$repoRoot = $null
$candidate = Resolve-Path (Join-Path $appDir "..\..") -ErrorAction SilentlyContinue
if ($candidate -and (Test-Path (Join-Path $candidate "gradlew.bat"))) {
  $repoRoot = $candidate.Path
}

# Ensure data directory
if (-not (Test-Path $dataDir)) { New-Item -ItemType Directory -Path $dataDir -Force | Out-Null }

# Locate Java
$java = Join-Path $jreDir "bin\java.exe"
if (-not (Test-Path $java)) {
  $java = (Get-Command java -ErrorAction SilentlyContinue).Source
  if (-not $java) {
    [System.Windows.Forms.MessageBox]::Show(
      "Java not found.`nPlease reinstall NET NiNjA or install JDK 21.",
      "NET NiNjA — Error",
      [System.Windows.Forms.MessageBoxButtons]::OK,
      [System.Windows.Forms.MessageBoxIcon]::Error
    )
    exit 1
  }
}

# Resolve lib dir across install + staging + repo build layouts
if (-not (Test-Path (Join-Path $libDir "*.jar"))) {
  $candidate = Join-Path $appDir "staging\lib"
  if (Test-Path (Join-Path $candidate "*.jar")) { $libDir = $candidate }
}
if ($repoRoot -and -not (Test-Path (Join-Path $libDir "*.jar"))) {
  $candidate = Join-Path $repoRoot "server\build\install\server\lib"
  if (Test-Path (Join-Path $candidate "*.jar")) { $libDir = $candidate }
}
if ($repoRoot -and -not (Test-Path (Join-Path $libDir "*.jar"))) {
  $candidate = Join-Path $repoRoot "server\build\libs"
  if (Test-Path (Join-Path $candidate "server-all.jar")) { $libDir = $candidate }
}

# Resolve web-ui dir across install + staging + repo layouts
if (-not (Test-Path (Join-Path $webUiDir "ninja_mobile_new.html"))) {
  $candidate = Join-Path $appDir "staging\web-ui"
  if (Test-Path (Join-Path $candidate "ninja_mobile_new.html")) { $webUiDir = $candidate }
}
if ($repoRoot -and -not (Test-Path (Join-Path $webUiDir "ninja_mobile_new.html"))) {
  $candidate = Join-Path $repoRoot "web-ui"
  if (Test-Path (Join-Path $candidate "ninja_mobile_new.html")) { $webUiDir = $candidate }
}

# Locate server jar
$serverJar = Join-Path $libDir "server-all.jar"
if (-not (Test-Path $serverJar)) {
  $serverJar = Get-ChildItem -Path $libDir -Filter "server*.jar" -ErrorAction SilentlyContinue |
    Sort-Object Length -Descending | Select-Object -First 1 -ExpandProperty FullName
  if (-not $serverJar) {
    Add-Type -AssemblyName System.Windows.Forms
    [System.Windows.Forms.MessageBox]::Show(
      "Server JAR not found in:`n$libDir`n`nBuild it with:`ngradlew.bat :server:shadowJar",
      "NET NiNjA — Error",
      [System.Windows.Forms.MessageBoxButtons]::OK,
      [System.Windows.Forms.MessageBoxIcon]::Error
    )
    exit 1
  }
}
if (-not (Test-Path (Join-Path $webUiDir "ninja_mobile_new.html"))) {
  Add-Type -AssemblyName System.Windows.Forms
  [System.Windows.Forms.MessageBox]::Show(
    "web-ui not found at:`n$webUiDir",
    "NET NiNjA — Error",
    [System.Windows.Forms.MessageBoxButtons]::OK,
    [System.Windows.Forms.MessageBoxIcon]::Error
  )
  exit 1
}

# Set environment variables
$env:NET_NINJA_HOST = if ($env:NET_NINJA_HOST) { $env:NET_NINJA_HOST } else { "127.0.0.1" }
$env:NET_NINJA_PORT = if ($env:NET_NINJA_PORT) { $env:NET_NINJA_PORT } else { "8787" }
$env:NET_NINJA_DB   = if ($env:NET_NINJA_DB)   { $env:NET_NINJA_DB }   else { Join-Path $dataDir "netninja.db" }

# Build classpath (all jars in lib/)
$classpath = (Get-ChildItem -Path $libDir -Filter "*.jar" | ForEach-Object { $_.FullName }) -join ";"

# Launch server as hidden process — DesktopLauncher handles browser open
$processArgs = @(
  "-Xms64m", "-Xmx512m",
  "-Dfile.encoding=UTF-8",
  "-cp", "`"$classpath`"",
  "server.DesktopLauncherKt",
  "`"$webUiDir`""
)

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $java
$psi.Arguments = $processArgs -join " "
$psi.WorkingDirectory = $appDir
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden

try {
  $proc = [System.Diagnostics.Process]::Start($psi)

  # Async-read output to log file
  $job = Start-Job -ScriptBlock {
    param($proc, $logFile)
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    Set-Content -Path $logFile -Value "=== NET NiNjA Server Log $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ===" -Force
    Add-Content -Path $logFile -Value $stdout
    if ($stderr) { Add-Content -Path $logFile -Value "`n=== STDERR ===`n$stderr" }
  } -ArgumentList $proc, $logFile

  # Keep this script alive until server exits
  $proc.WaitForExit()
} catch {
  Add-Type -AssemblyName System.Windows.Forms
  [System.Windows.Forms.MessageBox]::Show(
    "Failed to start NET NiNjA server:`n$($_.Exception.Message)",
    "NET NiNjA — Error",
    [System.Windows.Forms.MessageBoxButtons]::OK,
    [System.Windows.Forms.MessageBoxIcon]::Error
  )
}
