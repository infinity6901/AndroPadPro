# PyInstaller spec for AndroPad Pro Server (Administrator, full features)
# Run with: AndroPadPro_Server_Admin.exe
#
# Build:
#   pip install pyinstaller
#   pyinstaller AndroPadPro_Server_Admin.spec
# The exe lands in dist/AndroPadPro_Server_Admin.exe

import sys
import os

block_cipher = None

a = Analysis(
    ['gamepad_server.py'],
    pathex=[],
    binaries=[],
    datas=[],
    hiddenimports=[
        # Core gamepad / input
        'vgamepad',
        'vgamepad.win',
        'pyautogui',
        'keyboard',
        # Audio streaming
        'pyaudiowpatch',
        'sounddevice',
        'numpy',
        # Screen streaming
        'mss',
        'PIL',
        'PIL._imaging',
        # Built-in modules used by streamers
        'threading',
        'struct',
        'socket',
        'argparse',
        'ctypes',
        'traceback',
        'time',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='AndroPadPro_Server_Admin',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    # All features enabled (admin required for keyboard sim + BT)
    args=['--audio', '--screen', '--mic'],
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name='AndroPadPro_Server_Admin',
)
