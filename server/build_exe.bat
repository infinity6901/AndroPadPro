@echo off
title AndroPad Pro - Build Executables
cd /d "%~dp0"

echo ============================================================
echo   AndroPad Pro Server — Build Executables
echo ============================================================
echo.

:: Install PyInstaller
pip show pyinstaller >nul 2>&1
if errorlevel 1 (
    echo Installing PyInstaller...
    pip install pyinstaller -q
)

:: Verify ViGEmBus (required at build time for vgamepad import)
python -c "import vgamepad" >nul 2>&1
if errorlevel 1 (
    echo.
    echo WARNING: vgamepad not installed.
    echo Install ViGEmBus driver from: https://github.com/ViGEm/ViGEmBus/releases
    echo Then run: pip install vgamepad
    echo.
    pause
    exit /b 1
)

:: Clean old builds
if exist dist rmdir /s /q dist
if exist build rmdir /s /q build

:: Build non-admin exe
echo.
echo Building AndroPadPro_Server.exe (non-admin, no keyboard sim)...
pyinstaller AndroPadPro_Server.spec
if errorlevel 1 (
    echo FAILED: AndroPadPro_Server.spec
    goto :fail
)
echo OK:  dist\AndroPadPro_Server\AndroPadPro_Server.exe

:: Build admin exe
echo.
echo Building AndroPadPro_Server_Admin.exe (admin, full features)...
pyinstaller AndroPadPro_Server_Admin.spec
if errorlevel 1 (
    echo FAILED: AndroPadPro_Server_Admin.spec
    goto :fail
)
echo OK:  dist\AndroPadPro_Server_Admin\AndroPadPro_Server_Admin.exe

echo.
echo ============================================================
echo   Build complete!
echo ============================================================
echo.
echo   dist\AndroPadPro_Server\           — non-admin exe
echo   dist\AndroPadPro_Server_Admin\    — admin exe
echo.
echo   Run with:
echo     start_server.bat          (non-admin)
echo     start_server_admin.bat    (admin)
echo.
pause
exit /b 0

:fail
echo.
echo Build failed. See errors above.
pause
exit /b 1
