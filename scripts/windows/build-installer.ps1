param(
  [string]$AppVersion = "1.2.0"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$gradlew = Join-Path $repoRoot "gradlew.bat"

if (-not (Test-Path $gradlew)) {
  throw "gradlew.bat not found at $gradlew"
}

if (-not (Get-Command jpackage -ErrorAction SilentlyContinue)) {
  throw "jpackage not found. Install JDK 17+ and ensure JAVA_HOME/bin is on PATH."
}

Write-Host "Building server distribution..."
& $gradlew :server:installDist

$libDir = Join-Path $repoRoot "server\build\install\server\lib"
if (-not (Test-Path $libDir)) {
  throw "Expected server lib directory at $libDir"
}

$mainJar = Get-ChildItem -Path $libDir -Filter "server*.jar" |
  Sort-Object Length -Descending |
  Select-Object -First 1

if (-not $mainJar) {
  throw "Unable to locate server*.jar in $libDir"
}

$stagingRoot = Join-Path $repoRoot "build\windows-installer\input"
$outputDir = Join-Path $repoRoot "build\windows-installer\out"

if (Test-Path $stagingRoot) {
  Remove-Item -Recurse -Force $stagingRoot
}
New-Item -ItemType Directory -Path $stagingRoot | Out-Null

Write-Host "Staging jars and web-ui assets..."
Copy-Item -Path (Join-Path $libDir "*") -Destination $stagingRoot
Copy-Item -Path (Join-Path $repoRoot "web-ui") -Destination $stagingRoot -Recurse

if (Test-Path $outputDir) {
  Remove-Item -Recurse -Force $outputDir
}
New-Item -ItemType Directory -Path $outputDir | Out-Null

$mainJarName = $mainJar.Name

Write-Host "Creating Windows installer exe..."
jpackage `
  --type exe `
  --dest $outputDir `
  --input $stagingRoot `
  --name "NET_NiNjA" `
  --app-version $AppVersion `
  --main-jar $mainJarName `
  --main-class "server.DesktopLauncherKt" `
  --arguments "app\web-ui" `
  --win-per-user-install `
  --win-dir-chooser `
  --win-menu `
  --win-shortcut `
  --win-shortcut-prompt

Write-Host "Installer ready in $outputDir"
