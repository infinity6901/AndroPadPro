@echo off
title AndroPad Pro Server (Admin)
cd /d "%~dp0"

REM Self-elevate to Administrator if not already
net session >nul 2>&1
if errorlevel 1 (
    echo Requesting Administrator privileges...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

echo ============================================================
echo   AndroPad Pro Server - Full Mode (Administrator)
echo ============================================================
echo.
echo Running as Administrator - all features enabled including
echo keyboard simulation and Bluetooth.
echo.

REM Auto-install missing packages
python -c "import vgamepad"      >nul 2>&1 || pip install vgamepad      -q
python -c "import pyautogui"     >nul 2>&1 || pip install pyautogui     -q
python -c "import keyboard"      >nul 2>&1 || pip install keyboard      -q
python -c "import pyaudiowpatch" >nul 2>&1 || pip install pyaudiowpatch -q
python -c "import sounddevice"   >nul 2>&1 || pip install sounddevice   -q
python -c "import numpy"         >nul 2>&1 || pip install numpy         -q
python -c "import mss"           >nul 2>&1 || pip install mss           -q
python -c "import PIL"           >nul 2>&1 || pip install Pillow        -q

echo.
echo Features:
echo   Gamepad input   ^> UDP port 5005
echo   PC audio        ^> TCP port 5007  (WASAPI loopback)
echo   PC screen       ^> TCP port 5008
echo   Mic input       ^> TCP port 5009
echo   Bluetooth       ^> RFCOMM channel 1
echo   Mouse + Keyboard simulation enabled (Admin mode)
echo.
echo To stop: press Ctrl+C or close this window.
echo.

python gamepad_server.py --audio --screen --mic

echo.
pause
