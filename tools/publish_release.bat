@echo off
title Game Nuke Premium - 1-Click Release Publisher
cd /d "%~dp0\.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish_release.ps1"
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Process failed with error code %ERRORLEVEL%.
    pause
) else (
    echo.
    echo [SUCCESS] Game Nuke Premium released successfully!
)
