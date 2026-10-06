@echo off
setlocal
cd /d "%~dp0\.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build_with_dynamic_timer.ps1" %*
endlocal
