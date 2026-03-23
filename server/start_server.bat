@echo off
title AndroPad Pro Server
cd /d "%~dp0"

echo ============================================================
echo   AndroPad Pro Server
echo   Double-click to start. Close window to stop.
echo ============================================================
echo.

REM Check Python
python --version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python not found.
    echo Download from https://python.org and install it.
    pause
    exit /b 1
)

REM Delete stale Python bytecode cache
if exist __pycache__ rmdir /s /q __pycache__

REM Auto-install missing packages
python -c "import vgamepad"       >nul 2>&1 || pip install vgamepad       -q
python -c "import pyautogui"      >nul 2>&1 || pip install pyautogui      -q
python -c "import pyaudiowpatch"  >nul 2>&1 || pip install pyaudiowpatch  -q
python -c "import sounddevice"    >nul 2>&1 || pip install sounddevice    -q
python -c "import numpy"          >nul 2>&1 || pip install numpy          -q
python -c "import mss"            >nul 2>&1 || pip install mss            -q
python -c "import PIL"            >nul 2>&1 || pip install Pillow         -q

echo Features:
echo   Gamepad input   ^> UDP port 5005
echo   PC audio        ^> TCP port 5007  (WASAPI loopback)
echo   PC screen       ^> TCP port 5008
echo   Mic input       ^> TCP port 5009
echo   Bluetooth       ^> RFCOMM channel 1  (pair PC in phone BT settings)
echo.
echo Waiting for AndroPad Pro app to connect...
echo Press Ctrl+C or close this window to stop.
echo.

python gamepad_server.py --no-keyboard --audio --screen --mic

echo.
echo Server stopped.
pause
