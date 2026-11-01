@echo off
setlocal
title NW Track Home Wi-Fi Setup - Administrator
echo ============================================================
echo NW Track Home Wi-Fi Setup - Administrator
echo ============================================================
echo Working directory: %~dp0
echo Command: install.ps1 %*
echo.
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
set "RESULT=%errorlevel%"
echo.
echo ============================================================
if "%RESULT%"=="0" (
  echo NW Track command completed successfully.
) else (
  echo NW Track command FAILED with exit code %RESULT%.
)
echo This window will remain open. Review or copy any error above.
echo ============================================================
pause
exit /b %RESULT%
