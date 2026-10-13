@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-on-windows.ps1"
if errorlevel 1 (
  echo.
  echo Build failed. Please copy the error and send it for diagnosis.
)
pause
