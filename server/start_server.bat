@echo off
title AndroPad Pro Server
cd /d "%~dp0"

echo ============================================================
echo   AndroPad Pro Server
echo ============================================================
echo.

:: Check for pre-built exe first
if exist "dist\AndroPadPro_Server\AndroPadPro_Server.exe" (
    echo Starting AndroPadPro_Server.exe...
    echo   Gamepad input  ^> UDP port 5005
    echo   PC audio      ^> TCP port 5007
    echo   PC screen     ^> TCP port 5008
    echo   Mic input     ^> TCP port 5009
    echo.
    echo Waiting for AndroPad Pro app to connect...
    echo Press Ctrl+C or close this window to stop.
    echo.
    "dist\AndroPadPro_Server\AndroPadPro_Server.exe"
    goto :done
)

:: Fall back to Python
python --version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python not found and no exe found.
    echo Option 1: Install Python from https://python.org
    echo Option 2: Build the exe with: pip install pyinstaller ^&^& pyinstaller AndroPadPro_Server.spec
    pause
    exit /b 1
)

echo Starting with Python (no exe found)...
echo.
echo Auto-installing missing packages...
python -c "import vgamepad"      >nul 2>&1 || pip install vgamepad      -q
python -c "import pyautogui"    >nul 2>&1 || pip install pyautogui     -q
python -c "import pyaudiowpatch">nul 2>&1 || pip install pyaudiowpatch -q
python -c "import sounddevice"  >nul 2>&1 || pip install sounddevice   -q
python -c "import numpy"        >nul 2>&1 || pip install numpy         -q
python -c "import mss"          >nul 2>&1 || pip install mss           -q
python -c "import PIL"          >nul 2>&1 || pip install Pillow        -q

echo.
echo Features:
echo   Gamepad input  ^> UDP port 5005
echo   PC audio      ^> TCP port 5007  (WASAPI loopback)
echo   PC screen     ^> TCP port 5008
echo   Mic input     ^> TCP port 5009
echo.
echo Waiting for AndroPad Pro app to connect...
echo Press Ctrl+C or close this window to stop.
echo.
python gamepad_server.py --no-keyboard --audio --screen --mic

:done
echo.
echo Server stopped.
pause
