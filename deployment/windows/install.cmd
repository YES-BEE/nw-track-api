@echo off
setlocal
set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=start"
if exist "%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin\docker.exe" set "PATH=%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin;%PATH%"
if exist "%ProgramFiles%\Docker\Docker\resources\bin\docker.exe" set "PATH=%ProgramFiles%\Docker\Docker\resources\bin;%PATH%"

if /I "%ACTION%"=="start" goto require_admin
if /I "%ACTION%"=="configure" goto require_admin
if /I "%ACTION%"=="remove" goto require_admin
goto run_script

:require_admin
net session >nul 2>&1
if %errorlevel% equ 0 goto run_script
echo Requesting Windows Administrator approval...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~dp0install-admin.cmd' -Verb RunAs -ArgumentList '%*'"
if errorlevel 1 (
  echo Administrator approval was cancelled or could not be started.
  exit /b 1
)
echo Continue in the elevated NW Track window. It will pause before closing.
exit /b 0

:run_script
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
exit /b %errorlevel%
