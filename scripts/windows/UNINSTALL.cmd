@echo off
REM ============================================================
REM  NET NiNjA — Uninstaller
REM  Removes the installed files, Desktop shortcut, and
REM  Start Menu shortcut. Optionally removes user data.
REM ============================================================
setlocal
title NET NiNjA Uninstaller

echo.
echo  ============================================================
echo   NET NiNjA - Uninstaller
echo  ============================================================
echo.

REM Kill any running server
taskkill /F /IM java.exe /FI "WINDOWTITLE eq NET*" >nul 2>&1

set "INSTALL_DIR=%LOCALAPPDATA%\NET_NiNjA"

if not exist "%INSTALL_DIR%" (
  echo  NET NiNjA is not installed at %INSTALL_DIR%.
  pause
  exit /b 0
)

echo  This will remove NET NiNjA from:
echo    %INSTALL_DIR%
echo.

set /p CONFIRM="  Continue? (Y/N): "
if /I not "%CONFIRM%"=="Y" (
  echo  Cancelled.
  pause
  exit /b 0
)

echo.
echo  Removing Desktop shortcut...
del "%USERPROFILE%\Desktop\NET NiNjA.lnk" 2>nul

echo  Removing Start Menu shortcut...
del "%APPDATA%\Microsoft\Windows\Start Menu\Programs\NET NiNjA.lnk" 2>nul

echo  Removing auto-start registry entry...
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v "NET_NiNjA" /f 2>nul

echo  Removing application files...
rmdir /S /Q "%INSTALL_DIR%\lib" 2>nul
rmdir /S /Q "%INSTALL_DIR%\web-ui" 2>nul
rmdir /S /Q "%INSTALL_DIR%\jre" 2>nul
del "%INSTALL_DIR%\NetNiNjA.cmd" 2>nul
del "%INSTALL_DIR%\NetNiNjA-launcher.ps1" 2>nul
del "%INSTALL_DIR%\netninja.ico" 2>nul

set /p REMOVEDATA="  Also remove user data (database, logs)? (Y/N): "
if /I "%REMOVEDATA%"=="Y" (
  echo  Removing user data...
  del "%INSTALL_DIR%\netninja.db" 2>nul
  del "%INSTALL_DIR%\netninja.db-journal" 2>nul
  del "%INSTALL_DIR%\netninja.db-wal" 2>nul
  del "%INSTALL_DIR%\netninja.token" 2>nul
  del "%INSTALL_DIR%\server.log" 2>nul
)

rmdir "%INSTALL_DIR%" 2>nul

echo.
echo  ============================================================
echo   NET NiNjA has been uninstalled.
echo  ============================================================
echo.
pause
