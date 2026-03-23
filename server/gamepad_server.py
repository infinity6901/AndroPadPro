"""
AndroPad Pro Server v3.3
========================
Fixes / additions vs v3.2:
  - RUMBLE FIX: replaced the broken get_output() polling with vgamepad's
    register_notification() callback.  The callback fires the instant ViGEmBus
    receives a rumble command from the game, and we relay the motor values to
    the phone immediately.  Previously rumble was always 0 because get_output()
    is not a real vgamepad API.
  - GESTURE SUPPORT: byte 15 of the 22-byte packet (was always 0) is now
    gestureCode.  Gestures trigger pyautogui hotkeys:
      1 = Zoom In   → Ctrl++        4 = Swipe Right → Alt+Right
      2 = Zoom Out  → Ctrl+-        5 = Swipe Up    → Win+Tab
      3 = Swipe Left→ Alt+Left      6 = Swipe Down  → Win+D
  - HORIZONTAL SCROLL: mouseButtons bits 5-6 (0x20/0x40) now drive
    pyautogui.hscroll() for 2-finger horizontal swipe on the touchpad.
  - MIC INPUT: --mic flag starts a TCP server on port 5009.  The phone
    streams 16kHz mono PCM; the server plays it via sounddevice so it comes
    out of the PC speakers (use VB-Audio Virtual Cable to route to apps).

Requirements:
    pip install vgamepad
    pip install pyautogui            (mouse/touchpad — no admin needed)
    pip install pyautogui keyboard   (keyboard sim   — needs admin on Windows)
    pip install sounddevice numpy    (mic receiver   — needed for --mic)
"""

import argparse
import socket
import struct
import sys
import threading
import time
import traceback

# ── ViGEmBus ──────────────────────────────────────────────────────────────────
try:
    from vgamepad import VX360Gamepad, XUSB_BUTTON
except ImportError:
    print("=" * 60)
    print("  ERROR: vgamepad is not installed.")
    print("  Run:  pip install vgamepad")
    print("  Also install ViGEmBus driver from:")
    print("  https://github.com/ViGEm/ViGEmBus/releases")
    print("=" * 60)
    sys.exit(1)
except Exception as e:
    print("=" * 60)
    print(f"  ERROR: Failed to load vgamepad: {e}")
    print("  Make sure ViGEmBus driver is installed.")
    print("=" * 60)
    sys.exit(1)

# ── Constants ──────────────────────────────────────────────────────────────────

PORT            = 5005
DEADMAN_TIMEOUT = 0.15
PACKET_SIZE     = 22
LEGACY_SIZE     = 9

MIC_PORT        = 5009
MIC_SAMPLE_RATE = 16000   # matches MicStreamClient on Android

BUTTON_BITS = {
    0:  XUSB_BUTTON.XUSB_GAMEPAD_DPAD_UP,
    1:  XUSB_BUTTON.XUSB_GAMEPAD_DPAD_DOWN,
    2:  XUSB_BUTTON.XUSB_GAMEPAD_DPAD_LEFT,
    3:  XUSB_BUTTON.XUSB_GAMEPAD_DPAD_RIGHT,
    4:  XUSB_BUTTON.XUSB_GAMEPAD_START,
    5:  XUSB_BUTTON.XUSB_GAMEPAD_BACK,
    6:  XUSB_BUTTON.XUSB_GAMEPAD_LEFT_THUMB,
    7:  XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_THUMB,
    8:  XUSB_BUTTON.XUSB_GAMEPAD_LEFT_SHOULDER,
    9:  XUSB_BUTTON.XUSB_GAMEPAD_RIGHT_SHOULDER,
    10: XUSB_BUTTON.XUSB_GAMEPAD_A,
    11: XUSB_BUTTON.XUSB_GAMEPAD_B,
    12: XUSB_BUTTON.XUSB_GAMEPAD_X,
    13: XUSB_BUTTON.XUSB_GAMEPAD_Y,
}

KEY_MAP = {
    0x57: "w",  0x41: "a",  0x53: "s",  0x44: "d",
    0x20: "space", 0x10: "shift",
    0x45: "e",  0x51: "q",  0x52: "r",  0x46: "f",
    0x1B: "escape", 0x0D: "enter",
}

# Touchpad gesture code → (hotkey tuple, description)
# Codes must match TouchpadView.companion object constants on Android.
GESTURE_MAP = {
    1: (("ctrl", "+"),          "zoom in"),
    2: (("ctrl", "-"),          "zoom out"),
    3: (("alt", "left"),        "browser back"),
    4: (("alt", "right"),       "browser forward"),
    5: (("win", "tab"),         "task view"),
    6: (("win", "d"),           "show desktop"),
}

DISCOVERY_MSG  = b"ANDROPADPRO_DISCOVER"
DISCOVERY_RESP = f"ANDROPADPRO_SERVER:3.3:{PORT}".encode()


# ── Server ─────────────────────────────────────────────────────────────────────

class GamepadServer:
    def __init__(
        self,
        gyro_mode        = "off",
        enable_mouse     = True,
        enable_keyboard  = True,
        enable_bluetooth = True,
        enable_rumble    = True,
        deadzone         = 0.10,
        bind_ip          = "0.0.0.0",
    ):
        self.gyro_mode        = gyro_mode
        self.enable_bluetooth = enable_bluetooth
        self.enable_rumble    = enable_rumble
        self.deadzone         = deadzone
        self.bind_ip          = bind_ip
        self.mic_monitor      = False   # set by start() when --mic-monitor is passed
        self.running          = True

        # ── Virtual gamepad ───────────────────────────────────────────────────
        print("Initialising virtual Xbox 360 controller…", end=" ", flush=True)
        try:
            self.gamepad = VX360Gamepad()
            print("OK")
        except Exception as e:
            print(f"FAILED\n  {e}")
            print("  Make sure ViGEmBus driver is installed and running.")
            sys.exit(1)

        self.current_buttons  = 0
        self.last_sequence    = -1
        self.packet_count     = 0
        self.last_packet_time = float("inf")
        self._first_client    = True

        # ── Force-feedback ────────────────────────────────────────────────────
        self.client_addr        = None
        self.client_sock        = None
        self.last_left_motor    = 0
        self.last_right_motor   = 0
        self.last_feedback_time = 0.0
        self._rumble_lock       = threading.Lock()
        self._pending_left      = 0
        self._pending_right     = 0

        # Register vgamepad rumble notification callback
        if enable_rumble:
            self._register_rumble_callback()

        # ── Mouse / keyboard ──────────────────────────────────────────────────
        self.mouse_enabled    = False
        self.keyboard_enabled = False
        self.pyautogui        = None
        self.keyboard         = None
        self.mouse_x          = 0
        self.mouse_y          = 0
        self.mouse_btns       = 0
        self.prev_mouse_btns  = 0
        self.mouse_scroll     = 0
        self.mouse_hscroll    = 0
        self.mouse_lock       = threading.Lock()
        self.keyboard_lock    = threading.Lock()
        self.current_key      = 0

        # Gesture (byte 15)
        self.gesture_lock     = threading.Lock()
        self.pending_gesture  = 0

        if enable_mouse or enable_keyboard:
            self._load_input_libraries(
                enable_mouse=enable_mouse, enable_keyboard=enable_keyboard
            )

    # ── Rumble notification callback ───────────────────────────────────────────

    def _register_rumble_callback(self):
        """
        vgamepad uses ViGEmBus notifications to deliver rumble values.
        register_notification() takes a callable with signature:
            callback(client, target, large_motor, small_motor, led_number, user_data)
        where large_motor / small_motor are 0-255.
        """
        try:
            def _rumble_cb(client, target, large_motor, small_motor, led_number, user_data):
                with self._rumble_lock:
                    self._pending_left  = large_motor & 0xFF
                    self._pending_right = small_motor & 0xFF

            self.gamepad.register_notification(callback_function=_rumble_cb)
            print("Force-feedback: notification callback registered")
        except AttributeError:
            # Older vgamepad build that doesn't have register_notification yet.
            # Fall back to silent no-op — rumble won't work but nothing crashes.
            print("Force-feedback: register_notification not available in this "
                  "vgamepad version — upgrade with: pip install --upgrade vgamepad")
        except Exception as e:
            print(f"Force-feedback: callback registration failed: {e}")

    # ── Input libraries ────────────────────────────────────────────────────────

    def _load_input_libraries(self, enable_mouse=True, enable_keyboard=True):
        if enable_mouse:
            try:
                import pyautogui
                pyautogui.FAILSAFE = False
                pyautogui.PAUSE    = 0
                self.pyautogui     = pyautogui
                self.mouse_enabled = True
                print("PyAutoGUI loaded — mouse/touchpad/gestures enabled")
            except ImportError:
                print("PyAutoGUI not installed — mouse disabled  (pip install pyautogui)")
            except Exception as e:
                print(f"PyAutoGUI failed to load: {e} — mouse disabled")
        else:
            print("Mouse disabled (--no-mouse or --no-input)")

        if enable_keyboard:
            try:
                import keyboard as kb
                kb.is_pressed("a")
                self.keyboard         = kb
                self.keyboard_enabled = True
                print("keyboard loaded — keyboard simulation enabled")
            except ImportError:
                print("keyboard not installed — keyboard disabled  (pip install keyboard)")
            except OSError:
                print("keyboard: requires Administrator on Windows — keyboard disabled")
                print("  → Restart as Administrator, or use --no-keyboard")
            except Exception as e:
                print(f"keyboard failed: {e} — keyboard disabled")
        else:
            print("Keyboard simulation disabled (--no-keyboard or --no-input)")

    # ── Packet parsing ─────────────────────────────────────────────────────────

    def _unpack_legacy(self, data):
        buttons, = struct.unpack_from("<H",   data, 0)
        lx, ly, rx, ry = struct.unpack_from("bbbb", data, 2)
        lt, rt, seq    = struct.unpack_from("BBB",  data, 6)
        return buttons, lx, ly, rx, ry, lt, rt, seq, 0, 0, 0, 0, 0, 0.0, 0.0, 0.0

    def _unpack_full(self, data):
        buttons, = struct.unpack_from("<H",  data, 0)
        lx, ly, rx, ry = struct.unpack_from("bbbb", data, 2)
        lt, rt, seq    = struct.unpack_from("BBB",  data, 6)
        mdx, = struct.unpack_from("<h", data, 9)
        mdy, = struct.unpack_from("<h", data, 11)
        mbtn = data[13]
        kkey = data[14]
        gcode= data[15]   # byte 15 — was reserved=0, now gestureCode
        gx,  = struct.unpack_from("<h", data, 16)
        gy,  = struct.unpack_from("<h", data, 18)
        gz,  = struct.unpack_from("<h", data, 20)
        return (buttons, lx, ly, rx, ry, lt, rt, seq,
                mdx, mdy, mbtn, kkey, gcode,
                gx / 32767.0, gy / 32767.0, gz / 32767.0)

    # ── Incoming dispatch ──────────────────────────────────────────────────────

    def process_packet(self, data, sock=None, addr=None):
        try:
            self._process_packet_inner(data, sock, addr)
        except Exception as e:
            print(f"  [!] process_packet error from {addr}: {e}")

    def _process_packet_inner(self, data, sock, addr):
        length = len(data)

        if length < LEGACY_SIZE:
            try:
                msg = data.decode("utf-8", errors="ignore").strip()
                if msg == "ANDROPADPRO_DISCOVER" and sock and addr:
                    print(f"  Discovery from {addr[0]}")
                    sock.sendto(DISCOVERY_RESP, addr)
            except Exception:
                pass
            return

        if self._first_client and addr:
            print(f"  Client connected: {addr[0]}")
            self._first_client = False

        if length == LEGACY_SIZE:
            if self.packet_count < 2 and addr:
                print(f"  [{addr[0]}] Legacy 9-byte packet (v1/v2 app)")
            fields = self._unpack_legacy(data)
        else:
            fields = self._unpack_full(data[:PACKET_SIZE])

        (buttons, lx, ly, rx, ry, lt, rt, seq,
         mdx, mdy, mbtn, kkey, gcode,
         gyro_x, gyro_y, gyro_z) = fields

        self._update_gamepad(
            buttons, lx, ly, rx, ry, lt, rt, seq,
            mdx, mdy, mbtn, kkey, gcode,
            gyro_x, gyro_y, gyro_z
        )

        if sock and addr:
            try:
                sock.sendto(bytes([seq & 0xFF]), addr)
            except Exception:
                pass

        if addr:
            self.client_addr = addr
            self.client_sock = sock

        self.last_packet_time = time.time()
        self.packet_count    += 1

    # ── Gamepad update ─────────────────────────────────────────────────────────

    def _update_gamepad(
        self,
        buttons, lx, ly, rx, ry, lt, rt, seq,
        mdx=0, mdy=0, mbtn=0, kkey=0, gcode=0,
        gyro_x=0.0, gyro_y=0.0, gyro_z=0.0,
    ):
        self.last_sequence = seq

        # Buttons
        pressed  = buttons & ~self.current_buttons
        released = self.current_buttons & ~buttons
        for bit, btn in BUTTON_BITS.items():
            try:
                if   pressed  & (1 << bit): self.gamepad.press_button(btn)
                elif released & (1 << bit): self.gamepad.release_button(btn)
            except Exception:
                pass
        self.current_buttons = buttons

        # Sticks + triggers
        def dz(v): return 0.0 if abs(v) < self.deadzone else v

        slx = max(-1.0, min(1.0, dz(lx / 127.0)))
        sly = max(-1.0, min(1.0, dz(ly / 127.0)))
        srx = max(-1.0, min(1.0, dz(rx / 127.0)))
        sry = max(-1.0, min(1.0, dz(ry / 127.0)))

        if self.gyro_mode == "left_stick" and (abs(gyro_x) > 0.01 or abs(gyro_y) > 0.01):
            slx = dz(gyro_x); sly = dz(gyro_y)
        elif self.gyro_mode == "right_stick" and (abs(gyro_x) > 0.01 or abs(gyro_y) > 0.01):
            srx = dz(gyro_x); sry = dz(gyro_y)

        try:
            self.gamepad.left_joystick_float(x_value_float=slx, y_value_float=sly)
            self.gamepad.right_joystick_float(x_value_float=srx, y_value_float=sry)
            self.gamepad.left_trigger_float(value_float=lt  / 255.0)
            self.gamepad.right_trigger_float(value_float=rt / 255.0)
            self.gamepad.update()
        except Exception as e:
            print(f"  [!] gamepad update error: {e}")

        # Relay rumble to phone whenever motor values changed
        if self.enable_rumble and self.client_addr and self.client_sock:
            self._relay_rumble_if_changed()

        # Mouse deltas (accumulated) + buttons
        with self.mouse_lock:
            self.mouse_x    += mdx
            self.mouse_y    += mdy
            self.mouse_btns  = mbtn
            # Vertical scroll: bits 3-4
            if mbtn & 0x08: self.mouse_scroll  += 3
            if mbtn & 0x10: self.mouse_scroll  -= 3
            # Horizontal scroll: bits 5-6
            if mbtn & 0x20: self.mouse_hscroll += 3
            if mbtn & 0x40: self.mouse_hscroll -= 3

        # Keyboard
        if kkey != 0 and self.keyboard_enabled:
            with self.keyboard_lock:
                self.current_key = kkey

        # Gesture (byte 15)
        if gcode != 0:
            with self.gesture_lock:
                self.pending_gesture = gcode

    # ── Force feedback ─────────────────────────────────────────────────────────

    def _relay_rumble_if_changed(self):
        """Send motor values to phone whenever they change (called per packet)."""
        try:
            with self._rumble_lock:
                left  = self._pending_left
                right = self._pending_right

            now = time.time()
            # Send if values changed OR heartbeat every 0.5 s (keeps phone vibrating)
            if (left  != self.last_left_motor or
                right != self.last_right_motor or
                (now - self.last_feedback_time > 0.5 and (left or right))):
                self.last_left_motor  = left
                self.last_right_motor = right
                self.last_feedback_time = now
                fb = bytes([left, right, self.last_sequence & 0xFF, 0])
                self.client_sock.sendto(fb, self.client_addr)
        except Exception:
            pass

    # ── Gamepad reset ──────────────────────────────────────────────────────────

    def reset_gamepad(self):
        try:
            for btn in BUTTON_BITS.values():
                self.gamepad.release_button(btn)
            self.gamepad.left_joystick_float(x_value_float=0.0, y_value_float=0.0)
            self.gamepad.right_joystick_float(x_value_float=0.0, y_value_float=0.0)
            self.gamepad.left_trigger_float(value_float=0.0)
            self.gamepad.right_trigger_float(value_float=0.0)
            self.gamepad.update()
        except Exception:
            pass
        self.current_buttons = 0

    # ── Background threads ─────────────────────────────────────────────────────

    def _deadman_loop(self):
        while self.running:
            try:
                if self.packet_count > 0 and \
                   time.time() - self.last_packet_time > DEADMAN_TIMEOUT:
                    self.reset_gamepad()
                    if not self._first_client:
                        self._first_client = True
                        print("  Client disconnected (dead-man timeout)")
            except Exception:
                pass
            time.sleep(0.01)

    def _mouse_loop(self):
        prev_btns = 0
        pa = self.pyautogui
        while self.running:
            try:
                with self.mouse_lock:
                    dx      = self.mouse_x;   self.mouse_x    = 0
                    dy      = self.mouse_y;   self.mouse_y    = 0
                    btns    = self.mouse_btns
                    vscroll = self.mouse_scroll;  self.mouse_scroll  = 0
                    hscroll = self.mouse_hscroll; self.mouse_hscroll = 0

                # Gesture (outside mouse_lock to avoid holding it during hotkey)
                with self.gesture_lock:
                    gcode = self.pending_gesture
                    self.pending_gesture = 0

                if pa:
                    if dx or dy:
                        pa.move(dx, dy, _pause=False)

                    pressed  = btns & ~prev_btns
                    released = prev_btns & ~btns
                    if pressed  & 0x01: pa.mouseDown(button='left',   _pause=False)
                    if released & 0x01: pa.mouseUp(button='left',     _pause=False)
                    if pressed  & 0x02: pa.mouseDown(button='right',  _pause=False)
                    if released & 0x02: pa.mouseUp(button='right',    _pause=False)
                    if pressed  & 0x04: pa.mouseDown(button='middle', _pause=False)
                    if released & 0x04: pa.mouseUp(button='middle',   _pause=False)

                    if vscroll != 0:
                        pa.scroll(vscroll, _pause=False)

                    if hscroll != 0:
                        try:
                            pa.hscroll(hscroll, _pause=False)
                        except Exception:
                            pass  # hscroll not available on all platforms

                    if gcode and gcode in GESTURE_MAP:
                        keys, desc = GESTURE_MAP[gcode]
                        try:
                            pa.hotkey(*keys, _pause=False)
                        except Exception:
                            pass

                    prev_btns = btns

            except Exception:
                pass
            time.sleep(1.0 / 60)

    def _keyboard_loop(self):
        last_key = 0
        kb = self.keyboard
        while self.running:
            try:
                with self.keyboard_lock:
                    key = self.current_key
                    self.current_key = 0
                if key != last_key:
                    if last_key and last_key in KEY_MAP:
                        try: kb.release(KEY_MAP[last_key])
                        except Exception: pass
                    if key and key in KEY_MAP:
                        try: kb.press(KEY_MAP[key])
                        except Exception: pass
                    last_key = key
            except Exception:
                pass
            time.sleep(0.01)

    def _bluetooth_loop(self):
        if not hasattr(socket, "AF_BLUETOOTH"):
            print("Bluetooth: not available on this platform — skipping")
            return
        try:
            srv = socket.socket(
                socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM
            )
            srv.bind(("", 1))
            srv.listen(1)
            print("Bluetooth RFCOMM ready on channel 1")
        except OSError as e:
            print(f"Bluetooth: could not bind RFCOMM ({e}) — BT disabled, WiFi still works")
            return

        buf = b""
        while self.running:
            try:
                srv.settimeout(2.0)
                cli, info = srv.accept()
                print(f"Bluetooth: connected from {info}")
                buf = b""
                while self.running:
                    try:
                        cli.settimeout(1.0)
                        chunk = cli.recv(PACKET_SIZE * 8)
                        if not chunk: break
                        buf += chunk
                        while len(buf) >= LEGACY_SIZE:
                            if len(buf) >= PACKET_SIZE:
                                self.process_packet(buf[:PACKET_SIZE])
                                buf = buf[PACKET_SIZE:]
                            elif len(buf) == LEGACY_SIZE:
                                self.process_packet(buf[:LEGACY_SIZE])
                                buf = buf[LEGACY_SIZE:]
                            else:
                                break
                    except socket.timeout:
                        continue
                    except Exception as e:
                        print(f"Bluetooth recv error: {e}")
                        break
                cli.close()
                print("Bluetooth: client disconnected")
            except socket.timeout:
                continue
            except Exception as e:
                if self.running:
                    print(f"Bluetooth accept error: {e}")
        try: srv.close()
        except Exception: pass

    def _mic_receiver_loop(self):
        """
        TCP server on port 5009.  Receives phone mic audio (16kHz mono int16 PCM).

        Routing priority:
          1. VB-Audio Virtual Cable output (CABLE Input) — if installed.
             Apps then use "CABLE Output" as their microphone input.
          2. If --mic-monitor flag is set: play to default speakers so you can
             hear yourself (useful for testing / speakerphone use).
          3. Otherwise: audio is received and discarded silently.
             No speaker playback unless explicitly requested.

        Install VB-Audio Virtual Cable (free):
          https://vb-audio.com/Cable/
        """
        try:
            import sounddevice as sd
            import numpy as np
        except ImportError:
            print("Mic receiver: sounddevice/numpy not installed — mic disabled")
            print("  pip install sounddevice numpy")
            return

        # Find VB-Audio Cable Input device (the virtual mic sink)
        vb_device = None
        try:
            devices = sd.query_devices()
            for i, d in enumerate(devices):
                name = d.get('name', '').lower()
                if ('cable input' in name or 'vb-audio' in name or
                        'virtual audio cable' in name) and d.get('max_output_channels', 0) > 0:
                    vb_device = i
                    print(f"Mic receiver: VB-Audio Virtual Cable found → device {i} '{d['name']}'")
                    break
        except Exception:
            pass

        if vb_device is None and not self.mic_monitor:
            print("Mic receiver: VB-Audio Virtual Cable not found.")
            print("  Mic audio will be received but NOT played to speakers.")
            print("  Install VB-Cable to route phone mic to apps as virtual microphone:")
            print("  https://vb-audio.com/Cable/")
            print("  Or start server with --mic-monitor to hear yourself through speakers.")

        try:
            srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind((self.bind_ip, MIC_PORT))
            srv.listen(1)
            route = ("CABLE Input (VB-Audio)" if vb_device is not None
                     else ("speakers (--mic-monitor)" if self.mic_monitor else "silent (no VB-Cable)"))
            print(f"Mic receiver: listening on TCP port {MIC_PORT} → {route}")
        except OSError as e:
            print(f"Mic receiver: cannot bind port {MIC_PORT}: {e}")
            return

        while self.running:
            try:
                srv.settimeout(2.0)
                cli, info = srv.accept()
                print(f"Mic: phone connected from {info[0]}")
                try:
                    self._mic_stream_client(cli, sd, np, vb_device)
                except Exception as e:
                    print(f"Mic: stream error: {e}")
                finally:
                    try: cli.close()
                    except Exception: pass
                print("Mic: phone disconnected")
            except socket.timeout:
                continue
            except Exception as e:
                if self.running:
                    print(f"Mic: accept error: {e}")
        try: srv.close()
        except Exception: pass

    def _mic_stream_client(self, cli, sd, np, vb_device):
        """Receive PCM from phone and route to VB-Cable, speakers (monitor mode), or /dev/null."""
        # Read 4-byte sample rate header
        hdr = b""
        while len(hdr) < 4:
            chunk = cli.recv(4 - len(hdr))
            if not chunk:
                raise IOError("Mic: connection closed reading header")
            hdr += chunk
        sample_rate = struct.unpack_from("<I", hdr)[0]
        if sample_rate not in range(8000, 192001):
            sample_rate = MIC_SAMPLE_RATE
        print(f"Mic: {sample_rate} Hz mono")

        CHUNK = 1024
        raw   = b""

        # Decide output device:
        #   vb_device = VB-Cable index  → route there (best option)
        #   mic_monitor + no VB-Cable   → default speakers
        #   neither                     → no output stream (discard)
        output_device = vb_device if vb_device is not None else (None if not self.mic_monitor else sd.default.device[1])

        if output_device is not None:
            with sd.OutputStream(
                device=output_device,
                samplerate=sample_rate,
                channels=1,
                dtype='int16',
                blocksize=CHUNK,
            ) as stream:
                cli.settimeout(2.0)
                while self.running:
                    try:
                        data = cli.recv(CHUNK * 2)
                        if not data:
                            break
                        raw += data
                        while len(raw) >= CHUNK * 2:
                            frame = np.frombuffer(raw[:CHUNK * 2], dtype=np.int16)
                            stream.write(frame)
                            raw = raw[CHUNK * 2:]
                    except socket.timeout:
                        continue
        else:
            # No output device — receive and discard (keeps connection alive)
            cli.settimeout(2.0)
            while self.running:
                try:
                    data = cli.recv(CHUNK * 2)
                    if not data:
                        break
                except socket.timeout:
                    continue

    # ── Part 2 streamers ───────────────────────────────────────────────────────

    def _start_audio_streamer(self, device=None):
        try:
            from audio_streamer import AudioStreamer
            threading.Thread(
                target=AudioStreamer(device=device).start, daemon=True, name="AudioStreamer"
            ).start()
            print("Audio streamer started  (TCP port 5007)")
        except ImportError:
            print("audio_streamer.py not found — skipping audio stream")
        except Exception as e:
            print(f"Audio streamer failed to start: {e}")

    def _start_screen_streamer(self):
        try:
            from screen_streamer import ScreenStreamer
            threading.Thread(
                target=ScreenStreamer().start, daemon=True, name="ScreenStreamer"
            ).start()
            print("Screen streamer started  (TCP port 5008)")
        except ImportError:
            print("screen_streamer.py not found — skipping screen stream")
        except Exception as e:
            print(f"Screen streamer failed to start: {e}")

    # ── Main loop ──────────────────────────────────────────────────────────────

    def start(self, enable_audio=False, enable_screen=False,
              audio_device=None, enable_mic=False, mic_monitor=False):
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8192)
            sock.bind((self.bind_ip, PORT))
        except OSError as e:
            print(f"\nERROR: Cannot bind UDP port {PORT}: {e}")
            print("  → Is another instance already running?")
            sys.exit(1)

        self.mic_monitor = mic_monitor

        print()
        print("=" * 56)
        print("  AndroPad Pro Server v3.3")
        print(f"  Listening on    : {self.bind_ip}:{PORT}  (UDP)")
        print(f"  Gyro mode       : {self.gyro_mode}")
        print(f"  Deadzone        : {self.deadzone:.2f}")
        print(f"  Force feedback  : {'on' if self.enable_rumble else 'off'}")
        print(f"  Mouse           : {'on' if self.mouse_enabled else 'off'}")
        print(f"  Keyboard        : {'on' if self.keyboard_enabled else 'off'}")
        print(f"  Bluetooth       : {'on' if self.enable_bluetooth else 'off'}")
        if enable_audio:  print("  Audio stream    : TCP port 5007")
        if enable_screen: print("  Screen stream   : TCP port 5008")
        if enable_mic:
            mic_route = "→ VB-Cable (auto-detect) or silent"
            if mic_monitor: mic_route = "→ speakers (monitor mode)"
            print(f"  Mic receiver    : TCP port {MIC_PORT}  {mic_route}")
        print("=" * 56)
        print()
        print("Waiting for AndroPad Pro app to connect…")
        print("(Ctrl+C to quit)\n")

        if self.mouse_enabled:
            threading.Thread(target=self._mouse_loop,    daemon=True, name="MouseLoop").start()
        if self.keyboard_enabled:
            threading.Thread(target=self._keyboard_loop, daemon=True, name="KeyboardLoop").start()
        threading.Thread(target=self._deadman_loop, daemon=True, name="DeadmanLoop").start()
        if self.enable_bluetooth:
            threading.Thread(target=self._bluetooth_loop, daemon=True, name="BluetoothLoop").start()
        if enable_audio:
            self._start_audio_streamer(device=audio_device)
        if enable_screen:
            self._start_screen_streamer()
        if enable_mic:
            threading.Thread(target=self._mic_receiver_loop, daemon=True, name="MicReceiver").start()

        last_report = time.time()
        last_count  = 0
        consecutive_errors = 0

        while self.running:
            try:
                data, addr = sock.recvfrom(PACKET_SIZE + 64)
                self.process_packet(data, sock=sock, addr=addr)
                consecutive_errors = 0

                now = time.time()
                if now - last_report >= 10.0 and self.packet_count > 0:
                    rate = (self.packet_count - last_count) / (now - last_report)
                    print(f"  {addr[0]}  |  {rate:.0f} pkt/s  |  total: {self.packet_count}")
                    last_report = now
                    last_count  = self.packet_count

            except KeyboardInterrupt:
                print("\nShutting down…")
                break
            except OSError as e:
                consecutive_errors += 1
                print(f"  [!] Socket error: {e}")
                if consecutive_errors > 10:
                    print("  Too many consecutive errors — stopping.")
                    break
                time.sleep(0.1)
            except Exception as e:
                consecutive_errors += 1
                print(f"  [!] Unexpected error: {e}")
                traceback.print_exc()
                if consecutive_errors > 10:
                    print("  Too many consecutive errors — stopping.")
                    break
                time.sleep(0.1)

        self.running = False
        try: sock.close()
        except Exception: pass
        self.reset_gamepad()
        print("Gamepad released. Goodbye.")


# ── Entry point ────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    p = argparse.ArgumentParser(
        description="AndroPad Pro PC server v3.3",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
QUICK START (no admin — touchpad/mouse/rumble/gestures all work):
  python gamepad_server.py --no-keyboard --no-bluetooth

WITH MIC INPUT:
  python gamepad_server.py --no-keyboard --no-bluetooth --mic

FULL START (requires Administrator for keyboard):
  python gamepad_server.py --audio --screen --mic

FLAGS:
  --no-keyboard   mouse works, keyboard sim disabled (no admin needed)
  --no-input      disable BOTH mouse AND keyboard
  --no-bluetooth  skip Bluetooth listener
  --no-rumble     disable force-feedback to phone
  --mic           receive phone mic audio on TCP port 5009
  --audio         stream PC audio  to phone on TCP port 5007
  --screen        stream PC screen to phone on TCP port 5008
  --deadzone N    stick deadzone 0.0–0.3  (default 0.10)

NOTE: pyautogui (mouse/gestures) does NOT require admin.
      'keyboard' module requires Administrator on Windows.
        """
    )
    p.add_argument("--gyro",          choices=["off", "left_stick", "right_stick"], default="off")
    p.add_argument("--no-input",      action="store_true", help="Disable BOTH mouse AND keyboard")
    p.add_argument("--no-mouse",      action="store_true", help="Disable mouse/touchpad")
    p.add_argument("--no-keyboard",   action="store_true", help="Disable keyboard sim (no admin needed)")
    p.add_argument("--no-bluetooth",  action="store_true", help="Skip Bluetooth listener")
    p.add_argument("--no-rumble",     action="store_true", help="Disable force-feedback replies to phone")
    p.add_argument("--mic",           action="store_true", help="Receive phone mic audio (TCP port 5009)")
    p.add_argument("--mic-monitor",   action="store_true", help="Play phone mic through PC speakers (default: route to VB-Cable or discard)")
    p.add_argument("--deadzone",      type=float, default=0.10, metavar="0.0-0.3")
    p.add_argument("--ip",            type=str,   default="0.0.0.0", metavar="IP")
    p.add_argument("--audio",         action="store_true", help="Stream PC audio to phone (TCP port 5007)")
    p.add_argument("--audio-device",  type=int,   default=None, metavar="N")
    p.add_argument("--screen",        action="store_true", help="Stream PC screen to phone (TCP port 5008)")
    args = p.parse_args()

    no_mouse    = args.no_input or args.no_mouse
    no_keyboard = args.no_input or args.no_keyboard

    server = GamepadServer(
        gyro_mode        = args.gyro,
        enable_mouse     = not no_mouse,
        enable_keyboard  = not no_keyboard,
        enable_bluetooth = not args.no_bluetooth,
        enable_rumble    = not args.no_rumble,
        deadzone         = max(0.0, min(0.3, args.deadzone)),
        bind_ip          = args.ip,
    )
    server.start(
        enable_audio  = args.audio,
        enable_screen = args.screen,
        audio_device  = args.audio_device,
        enable_mic    = args.mic,
        mic_monitor   = args.mic_monitor,
    )
