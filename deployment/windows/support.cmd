@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0support.ps1" %*
exit /b %errorlevel%
