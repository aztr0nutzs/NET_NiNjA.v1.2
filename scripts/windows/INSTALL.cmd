@echo off
REM ============================================================
REM  NET NiNjA — Double-Click Installer for Windows
REM  Just double-click this file to install + launch.
REM ============================================================
setlocal
title NET NiNjA Installer

echo.
echo  ============================================================
echo   NET NiNjA - One-Click Installer
echo  ============================================================
echo.
echo  This will install NET NiNjA to your computer and create
echo  a Desktop shortcut. Press Ctrl+C to cancel.
echo.
pause

REM Navigate to repo root (this file lives at scripts\windows\)
cd /d "%~dp0..\.."

REM Run the PowerShell installer
powershell.exe -ExecutionPolicy Bypass -NoProfile -File "%~dp0install-netninja.ps1"

if errorlevel 1 (
  echo.
  echo  Installation failed. Check the error messages above.
  echo.
  pause
)
