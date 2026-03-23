@echo off
title AndroPad Pro Server GUI
cd /d "%~dp0"

echo ============================================================
echo   AndroPad Pro Server GUI
echo ============================================================
echo.

:: Self-elevate to Administrator if not already (needed for vgamepad + keyboard)
net session >nul 2>&1
if errorlevel 1 (
    echo Requesting Administrator privileges...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

:: Run the GUI
if exist "AndroPadPro_Server_GUI\AndroPadPro_Server_GUI.exe" (
    echo Starting AndroPadPro_Server_GUI.exe...
    echo.
    echo Tip: This app requires ViGEmBus driver.
    echo Download from: https://github.com/ViGEm/ViGEmBus/releases
    echo.
    "AndroPadPro_Server_GUI\AndroPadPro_Server_GUI.exe"
) else (
    echo ERROR: AndroPadPro_Server_GUI.exe not found.
    echo Extract the zip file first.
    pause
)
