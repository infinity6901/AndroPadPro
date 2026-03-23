@echo off
title AndroPad Pro Server GUI
cd /d "%~dp0"

echo ============================================================
echo   AndroPad Pro Server GUI (Non-Admin)
echo ============================================================
echo.

:: Check ViGEmBus
python -c "import vgamepad; print('ViGEmBus: OK')" >nul 2>&1
if errorlevel 1 (
    echo WARNING: ViGEmBus driver not found!
    echo Download from: https://github.com/ViGEm/ViGEmBus/releases
    echo.
)

:: Run the GUI
if exist "AndroPadPro_Server_GUI\AndroPadPro_Server_GUI.exe" (
    echo Starting GUI...
    "AndroPadPro_Server_GUI\AndroPadPro_Server_GUI.exe"
) else (
    echo ERROR: AndroPadPro_Server_GUI.exe not found.
    echo Extract the zip file first.
    pause
)
