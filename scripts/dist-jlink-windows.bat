@echo off
rem =============================================================
rem Plumb "extract-and-use" packager (Windows) - jlink + ZIP
rem Thin wrapper around dist-jlink-windows.ps1.
rem
rem Usage:
rem   scripts\dist-jlink-windows.bat                (default x64)
rem   scripts\dist-jlink-windows.bat arm64
rem
rem Output:
rem   dist-jlink\plumb-<version>-jre-windows-<arch>.zip
rem =============================================================
setlocal

set ARCH=x64
if /I "%~1"=="arm64" set ARCH=arm64
if /I "%~1"=="x64"   set ARCH=x64

where pwsh >nul 2>nul
if %ERRORLEVEL%==0 (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0dist-jlink-windows.ps1" -Arch %ARCH% %*
  exit /b %ERRORLEVEL%
)

where powershell >nul 2>nul
if %ERRORLEVEL%==0 (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0dist-jlink-windows.ps1" -Arch %ARCH% %*
  exit /b %ERRORLEVEL%
)

echo ERROR: PowerShell not found. Install PowerShell 5.1+ and retry.
exit /b 1
