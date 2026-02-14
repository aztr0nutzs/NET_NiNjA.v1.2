@echo off
REM ============================================================
REM  NET NiNjA — Windows Desktop Launcher
REM  Starts the Ktor server and opens the dashboard in browser.
REM ============================================================
setlocal enabledelayedexpansion

set "APP_DIR=%~dp0"
set "JRE_DIR=%APP_DIR%jre"
set "LIB_DIR=%APP_DIR%lib"
set "WEBUI_DIR=%APP_DIR%web-ui"
set "DATA_DIR=%LOCALAPPDATA%\NET_NiNjA"
set "LOG_FILE=%DATA_DIR%\server.log"

REM Detect repo root only if running from the repo (not installed)
set "REPO_ROOT="
if exist "%APP_DIR%..\..\gradlew.bat" (
  set "REPO_ROOT=%APP_DIR%..\.."
)

REM Ensure data directory exists
if not exist "%DATA_DIR%" mkdir "%DATA_DIR%"

REM Use the bundled JRE, falling back to system java
if exist "%JRE_DIR%\bin\java.exe" (
  set "JAVA_EXE=%JRE_DIR%\bin\java.exe"
) else (
  where java >nul 2>&1
  if errorlevel 1 (
    echo ERROR: Java not found. Please reinstall NET NiNjA.
    echo The bundled JRE is missing and no system Java is available.
    pause
    exit /b 1
  )
  set "JAVA_EXE=java"
)

REM Resolve lib directory across install + staging + repo build layouts.
if not exist "%LIB_DIR%\*.jar" (
  if exist "%APP_DIR%staging\lib\*.jar" set "LIB_DIR=%APP_DIR%staging\lib"
)
if defined REPO_ROOT (
  if not exist "%LIB_DIR%\*.jar" (
    if exist "%REPO_ROOT%\server\build\install\server\lib\*.jar" set "LIB_DIR=%REPO_ROOT%\server\build\install\server\lib"
  )
  if not exist "%LIB_DIR%\*.jar" (
    if exist "%REPO_ROOT%\server\build\libs\server-all.jar" (
      set "LIB_DIR=%REPO_ROOT%\server\build\libs"
    )
  )
)

REM Resolve web-ui directory across install + staging + repo layouts.
if not exist "%WEBUI_DIR%\ninja_mobile_new.html" (
  if exist "%APP_DIR%staging\web-ui\ninja_mobile_new.html" set "WEBUI_DIR=%APP_DIR%staging\web-ui"
)
if defined REPO_ROOT (
  if not exist "%WEBUI_DIR%\ninja_mobile_new.html" (
    if exist "%REPO_ROOT%\web-ui\ninja_mobile_new.html" set "WEBUI_DIR=%REPO_ROOT%\web-ui"
  )
)

REM Find the server JAR in the resolved lib directory.
set "SERVER_JAR="
for %%f in ("%LIB_DIR%\server-all.jar") do (
  if exist "%%f" set "SERVER_JAR=%%f"
)
if "%SERVER_JAR%"=="" (
  for %%f in ("%LIB_DIR%\server*.jar") do (
    if exist "%%f" set "SERVER_JAR=%%f"
  )
)
if "%SERVER_JAR%"=="" (
  echo ERROR: server JAR not found in %LIB_DIR%
  echo Checked app/staging/repo build paths.
  echo Build it with: gradlew.bat :server:shadowJar
  pause
  exit /b 1
)
if not exist "%WEBUI_DIR%\ninja_mobile_new.html" (
  echo ERROR: web-ui not found at %WEBUI_DIR%
  echo Expected ninja_mobile_new.html.
  pause
  exit /b 1
)

REM Set environment defaults if not already configured
if not defined NET_NINJA_HOST set "NET_NINJA_HOST=127.0.0.1"
if not defined NET_NINJA_PORT set "NET_NINJA_PORT=8787"
if not defined NET_NINJA_DB   set "NET_NINJA_DB=%DATA_DIR%\netninja.db"

echo ============================================================
echo   NET NiNjA v1.2 — Starting server...
echo   Dashboard: http://%NET_NINJA_HOST%:%NET_NINJA_PORT%/ui/ninja_mobile_new.html
echo   Runtime:   %JAVA_EXE%
echo   Lib dir:   %LIB_DIR%
echo   Web UI:    %WEBUI_DIR%
echo   Database:  %NET_NINJA_DB%
echo   Log:       %LOG_FILE%
echo ============================================================

REM Launch the server using DesktopLauncher (opens browser automatically)
"%JAVA_EXE%" -Xms64m -Xmx512m ^
  -Dfile.encoding=UTF-8 ^
  -cp "%LIB_DIR%\*" ^
  server.DesktopLauncherKt ^
  "%WEBUI_DIR%" > "%LOG_FILE%" 2>&1

if errorlevel 1 (
  echo.
  echo Server exited with an error. Check the log: %LOG_FILE%
  pause
)
