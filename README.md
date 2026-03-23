# AndroPad Pro — v3.0 (Complete)

Turn your Android phone into a full Xbox 360 gamepad over WiFi or Bluetooth,
with true force feedback, analog triggers, stable gyro steering, per-element
opacity controls, PC audio streaming, and PC screen streaming as a live background.

---

## Features

| Feature | Details |
|---|---|
| **Gamepad** | Full Xbox 360 layout — analog sticks, D-pad, A/B/X/Y, LB/RB, analog LT/RT, Start/Select, L3/R3 |
| **Transport** | WiFi UDP (auto-discover or manual IP) or Bluetooth RFCOMM SPP |
| **Force Feedback** | Phone vibrates in sync with actual in-game rumble events |
| **Gyro steering** | ComplementaryFilter-based drift-free pitch/roll → left or right stick |
| **Analog triggers** | LT/RT slide from 0–100%; fill bar shows live pressure |
| **Real latency** | HUD shows actual round-trip ms (green < 30 / yellow < 80 / red > 80) |
| **Per-element opacity** | Every control group + background has its own alpha slider |
| **PC Audio** | Stream your PC game audio to the phone's speakers/headphones over WiFi |
| **PC Screen** | Live MJPEG preview of PC screen displayed as the app background |
| **Video wallpaper** | Pick an MP4/WebM as an animated looping background (ExoPlayer) |
| **Custom layout** | Drag & resize every button to your preferred position |
| **Themes** | Xbox, PlayStation, Minimal, Dark Carbon + accent color cycling |

---

## Quick Start

### Android
1. Open `android/` in Android Studio → Sync Gradle → Build APK

### Python Server (two options)

**Option A — Standalone .exe (no Python needed)**
```bash
cd server
# Build the exe (requires Python + pip install pyinstaller):
pip install pyinstaller
pyinstaller AndroPadPro_Server.spec         # → dist/AndroPadPro_Server/AndroPadPro_Server.exe
pyinstaller AndroPadPro_Server_Admin.spec    # → dist/AndroPadPro_Server_Admin/AndroPadPro_Server_Admin.exe

# Run (double-click .bat or .exe):
start_server.bat          # non-admin, no keyboard sim
start_server_admin.bat    # admin, all features including keyboard sim
```

**Option B — Run with Python directly**
```bash
cd server
pip install -r requirements.txt
python gamepad_server.py
```

---

## Server Usage

```bash
# Gamepad only (default)
python gamepad_server.py

# Gyro → right stick (camera)
python gamepad_server.py --gyro right_stick

# Gyro → left stick (steering games)
python gamepad_server.py --gyro left_stick

# All features
python gamepad_server.py --gyro right_stick --audio --screen

# Tune quality/fps of screen stream
python gamepad_server.py --audio --screen
# (edit screen_streamer.py defaults or use standalone: python screen_streamer.py --fps 20 --quality 50)

# Other flags
python gamepad_server.py --no-rumble        # disable force-feedback to phone
python gamepad_server.py --no-input         # gamepad only, no mouse/keyboard
python gamepad_server.py --no-bluetooth     # WiFi only
python gamepad_server.py --deadzone 0.12    # adjust stick deadzone
```

---

## PC Audio Streaming Setup (Windows)

1. Install **VB-Audio Virtual Cable** (free): https://vb-audio.com/Cable/
2. In Windows Sound Settings, set **CABLE Input** as default playback device
3. Run `python gamepad_server.py --audio`
4. In the Android app Settings → **PC Streaming → Stream audio from PC** → ON
5. Adjust volume slider

The server will print the detected loopback device name. If it picks the wrong
device, run `python audio_streamer.py --list-devices` and pass the index:
```bash
python gamepad_server.py --audio
# or standalone:
python audio_streamer.py --device 3
```

---

## PC Screen Streaming

The screen streamer captures your primary monitor at 15 fps / JPEG quality 40
and sends it as the app background. The gamepad controls are overlaid on top.

- Bandwidth: ~200–400 KB/s on typical settings (well within home WiFi)
- Latency: ~100–200 ms (fine for seeing game state; not for twitch-reaction play)
- In Settings → **PC Streaming → Show PC screen as background** → ON
- The screen stream overrides the static image / video wallpaper background

Tune the streamer:
```bash
python screen_streamer.py --fps 20 --quality 55 --scale 0.6
python screen_streamer.py --region 0,0,1280,720   # top-left region only
```

---

## Network Ports

| Port | Protocol | Direction | Purpose |
|---|---|---|---|
| 5005 | UDP | Phone → PC | 22-byte gamepad packets + discovery |
| 5005 | UDP | PC → Phone | 1-byte RTT ACK + 4-byte rumble reply |
| 5007 | TCP | PC → Phone | Raw PCM audio (44100 Hz, stereo, 16-bit) |
| 5008 | TCP | PC → Phone | Length-prefixed MJPEG screen stream |

Make sure your firewall allows these ports, or add exceptions for `gamepad_server.py`.

---

## Controls

| Control | Function |
|---|---|
| Left Stick | Movement / steering (click = L3) |
| Right Stick | Camera / look (click = R3) |
| D-Pad | Navigation |
| A / B / X / Y | Face buttons |
| LB / RB | Shoulder buttons |
| LT / RT | **Analog** triggers — slide for 0–100% pressure |
| START / SELECT | Menu buttons |
| CAL | Reset gyro complementary filter (hold phone still for 2 s) |
| 🔓 / 🔒 | Screen lock — tap once to lock, triple-tap lock icon to unlock |

---

## Settings Reference

### Connection
- **WiFi manual** — type PC IP address
- **WiFi auto** — broadcasts UDP to discover server on LAN
- **Bluetooth** — select paired PC from spinner; uses RFCOMM SPP

### Appearance
- Theme preset (Xbox / PlayStation / Minimal / Dark Carbon)
- Accent color (7 presets, cycle with button)
- Background — pick static image **or** video/MP4 (auto-detected by MIME type)

### Opacity
- **Master** — multiplies all control-group sliders
- **Joysticks / D-Pad / Face Buttons / Triggers / Shoulders / Center Buttons**
- **Background** — image / video / screen-stream layer alpha (independent of master)

### Gyroscope
- Enable toggle
- Output: Left stick or Right stick
- Sensitivity 0.1–3.0
- Deadzone 0.00–0.30
- CAL: resets complementary filter

### Other
- Haptic feedback toggle + strength
- Controller profile preset
- Custom layout toggle + drag-resize editor

### PC Streaming
- **Stream audio from PC** — ON/OFF + volume slider
  - Requires `--audio` flag on server + VB-Audio Virtual Cable
- **Show PC screen as background** — ON/OFF
  - Requires `--screen` flag on server
  - Overrides static image / video wallpaper when active

---

## Architecture

```
Android (phone)                          PC (server)
┌──────────────────────────────┐        ┌─────────────────────────────┐
│ MainActivity                 │        │ gamepad_server.py           │
│  ├─ UdpClient / BtClient     │──22B──▶│  VX360Gamepad (ViGEmBus)    │
│  │   └─ RumbleListener       │◀──4B───│  force-feedback reply       │
│  ├─ AudioStreamClient        │◀─PCM───│ audio_streamer.py (port 5007)│
│  ├─ ScreenStreamClient       │◀─JPEG──│ screen_streamer.py (port 5008)│
│  └─ BackgroundMediaView      │        └─────────────────────────────┘
│      ├─ Static image         │
│      ├─ Video loop (ExoPlayer)│
│      └─ Screen stream frames │
└──────────────────────────────┘
```

---

## Requirements

**Android**: minSdk 24 (Android 7.0+)

**Python server**:
```
vgamepad     ← requires ViGEmBus driver
pyautogui
keyboard
sounddevice  ← audio streaming
numpy        ← audio buffer handling
mss          ← screen capture
Pillow       ← JPEG compression
```

ViGEmBus driver: https://github.com/ViGEm/ViGEmBus/releases

---

## License
MIT
