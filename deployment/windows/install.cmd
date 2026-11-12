@echo off
rem start installs/starts services; support exports logs; backup exports the database.
setlocal
set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=start"
if exist "%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin\docker.exe" set "PATH=%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin;%PATH%"
if exist "%ProgramFiles%\Docker\Docker\resources\bin\docker.exe" set "PATH=%ProgramFiles%\Docker\Docker\resources\bin;%PATH%"
if /I "%ACTION%"=="backup" goto run_backup

if /I "%ACTION%"=="start" goto require_admin
if /I "%ACTION%"=="configure" goto require_admin
if /I "%ACTION%"=="remove" goto require_admin
goto run_script

:require_admin
net session >nul 2>&1
if %errorlevel% equ 0 goto run_script
echo Requesting Windows Administrator approval...
set "NWTRACK_INSTALL_ADMIN=%~dp0install-admin.cmd"
set "NWTRACK_INSTALL_ARGS=%*"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$launch = @{FilePath=$env:NWTRACK_INSTALL_ADMIN; Verb='RunAs'}; if (-not [string]::IsNullOrWhiteSpace($env:NWTRACK_INSTALL_ARGS)) { $launch.ArgumentList=$env:NWTRACK_INSTALL_ARGS }; Start-Process @launch"
if errorlevel 1 (
  echo Administrator approval was cancelled or could not be started.
  exit /b 1
)
echo Continue in the elevated NW Track window. It will pause before closing.
exit /b 0

:run_script
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" %*
exit /b %errorlevel%

:run_backup
rem Pass the directory as data, avoiding Windows native quoting of a trailing \.
set "NWTRACK_BACKUP_DIRECTORY="
if "%~2"=="" goto execute_backup
if /I not "%~2"=="-BackupDirectory" goto backup_usage
if "%~3"=="" goto backup_usage
if not "%~4"=="" goto backup_usage
set "NWTRACK_BACKUP_DIRECTORY=%~3"
:execute_backup
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" backup
exit /b %errorlevel%
:backup_usage
echo Usage: install.cmd backup [-BackupDirectory "E:\NWTrack-Backups"]
exit /b 1
