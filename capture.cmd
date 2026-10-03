@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" -Action Capture
exit /b %errorlevel%
