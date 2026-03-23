import sys
import os
import pathlib

block_cipher = None

def _find_vigem_dll():
    for base in [
        pathlib.Path(sys.prefix),
        pathlib.Path(sys.base_prefix),
        pathlib.Path(os.environ.get('LOCALAPPDATA', '')) / 'Programs' / 'Python',
    ]:
        dll = base / 'vgamepad' / 'win' / 'vigem' / 'client' / 'x64' / 'ViGEmClient.dll'
        if dll.is_file():
            return str(dll)
    import vgamepad.win.vigem_client as vc
    p = pathlib.Path(sys.modules[vc.__name__].__file__).parent
    dll = p / 'vigem' / 'client' / 'x64' / 'ViGEmClient.dll'
    if dll.is_file():
        return str(dll)
    raise FileNotFoundError('ViGEmClient.dll not found — install vgamepad first: pip install vgamepad')

VIGEM_DLL = _find_vigem_dll()
print(f'Bundling ViGEmClient.dll from: {VIGEM_DLL}')

a = Analysis(
    ['gamepad_server.py'],
    pathex=[],
    binaries=[
        (VIGEM_DLL, 'vgamepad\\win\\vigem\\client\\x64'),
    ],
    datas=[],
    hiddenimports=[
        'vgamepad',
        'vgamepad.win',
        'pyautogui',
        'keyboard',
        'pyaudiowpatch',
        'sounddevice',
        'numpy',
        'mss',
        'PIL',
        'PIL._imaging',
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
