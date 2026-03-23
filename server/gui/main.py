"""
AndroPad Pro — Desktop Server Control Panel
A modern Windows desktop app for managing the gamepad server.
"""

import threading
import queue
import sys
import socket
import time
import argparse
import webbrowser
import logging
from pathlib import Path

try:
    import flet as ft
    from flet import (
        AppView,
        Card,
        Column,
        Container,
        ControlEvent,
        ElevatedButton,
        FilledButton,
        Icon,
        IconButton,
        Page,
        Row,
        Text,
        TextField,
        ThemeMode,
        View,
        ScrollMode,
        ButtonStyle,
        Padding,
        RoundedRectangleBorder,
        Tabs,
        Tab,
        Switch,
        Slider,
        TextButton,
        FilledTonalButton,
        Ref,
        border,
        border_radius,
        Colors,
        Icons,
        padding,
        margin,
        BoxShadow,
        Border,
        BorderSide,
        LinearGradient,
        Brightness,
        SafeArea,
        WindowEvent,
    )
except ImportError:
    import subprocess, sys

    print("Flet not installed. Installing...")
    subprocess.run([sys.executable, "-m", "pip", "install", "flet"], check=True)
    import flet as ft
    from flet import (
        AppView,
        Card,
        Column,
        Container,
        ControlEvent,
        ElevatedButton,
        FilledButton,
        Icon,
        IconButton,
        Page,
        Row,
        Text,
        TextField,
        ThemeMode,
        View,
        ScrollMode,
        ButtonStyle,
        Padding,
        RoundedRectangleBorder,
        Tabs,
        Tab,
        Switch,
        Slider,
        TextButton,
        FilledTonalButton,
        Ref,
        border,
        border_radius,
        Colors,
        Icons,
        padding,
        margin,
        BoxShadow,
        Border,
        BorderSide,
        LinearGradient,
        Brightness,
        SafeArea,
        WindowEvent,
    )


# ── Color constants ─────────────────────────────────────────────────────────────
C_ACCENT = "#107C10"
C_ACCENT_D = "#0B5C0B"
C_BG = "#0D0D0D"
C_SURFACE = "#1A1A1A"
C_SURFACE2 = "#242424"
C_TEXT = "#E0E0E0"
C_DIM = "#888888"
C_GREEN = "#107C10"
C_YELLOW = "#FFBA00"
C_RED = "#D0393B"
C_ORANGE = "#E67E22"


# ── Logging bridge ─────────────────────────────────────────────────────────────
class LogHandler(logging.Handler):
    def __init__(self, q: queue.Queue):
        super().__init__()
        self.q = q

    def emit(self, record: logging.LogRecord):
        ts = time.strftime("%H:%M:%S")
        lvl = record.levelname[:3].upper()
        self.q.put_nowait(f"[{ts}] [{lvl}] {record.getMessage()}")


# ── Server controller ──────────────────────────────────────────────────────────
class ServerController:
    def __init__(self, log_q: queue.Queue):
        self.log_q = log_q
        self.logger = logging.getLogger("AndroPadPro")
        self.logger.setLevel(logging.DEBUG)
        self.logger.addHandler(LogHandler(log_q))

        self.running = False
        self._threads: list[threading.Thread] = []
        self._stop_event = threading.Event()

        self.features = {
            "gamepad": True,
            "audio": True,
            "screen": True,
            "mic": True,
        }
        self.status = {
            "gamepad": ("idle", Colors.GREY),
            "audio": ("idle", Colors.GREY),
            "screen": ("idle", Colors.GREY),
            "mic": ("idle", Colors.GREY),
        }
        self.clients: list[str] = []

    def _check_vigembus(self) -> bool:
        try:
            import vgamepad

            pad = vgamepad.VX360Gamepad()
            pad.reset()
            return True
        except Exception:
            return False

    def start(self):
        if self.running:
            return
        self.running = True
        self._stop_event.clear()
        self.logger.info("=" * 50)
        self.logger.info("  AndroPad Pro Server starting...")
        self.logger.info("=" * 50)

        if self.features["gamepad"]:
            if self._check_vigembus():
                self.logger.info("ViGEmBus: OK")
                self.status["gamepad"] = ("starting", Colors.YELLOW)
                t = threading.Thread(
                    target=self._run_gamepad, daemon=True, name="Gamepad"
                )
                self._threads.append(t)
                t.start()
            else:
                self.logger.error("ViGEmBus driver not found!")
                self.logger.error(
                    "  Download: https://github.com/ViGEm/ViGEmBus/releases"
                )
                self.status["gamepad"] = ("ViGEmBus not installed", Colors.RED)
        else:
            self.status["gamepad"] = ("disabled", Colors.GREY)

        if self.features["audio"]:
            self.status["audio"] = ("starting", Colors.YELLOW)
            t = threading.Thread(target=self._run_audio, daemon=True, name="Audio")
            self._threads.append(t)
            t.start()
        else:
            self.status["audio"] = ("disabled", Colors.GREY)

        if self.features["screen"]:
            self.status["screen"] = ("starting", Colors.YELLOW)
            t = threading.Thread(target=self._run_screen, daemon=True, name="Screen")
            self._threads.append(t)
            t.start()
        else:
            self.status["screen"] = ("disabled", Colors.GREY)

        if self.features["mic"]:
            self.status["mic"] = ("starting", Colors.YELLOW)
            t = threading.Thread(target=self._run_mic, daemon=True, name="Mic")
            self._threads.append(t)
            t.start()
        else:
            self.status["mic"] = ("disabled", Colors.GREY)

        self.logger.info("Server started. Waiting for connections...")

    def stop(self):
        if not self.running:
            return
        self.logger.info("Stopping server...")
        self.running = False
        self._stop_event.set()
        for t in self._threads:
            if t.is_alive():
                t.join(timeout=3)
        self._threads.clear()
        for key in self.status:
            if self.features.get(key, False):
                self.status[key] = ("stopped", Colors.GREY)
        self.logger.info("Server stopped.")

    def _run_gamepad(self):
        try:
            import vgamepad
            from vgamepad import VX360Gamepad
            import struct

            PAD_PORT = 5005
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("0.0.0.0", PAD_PORT))
            sock.settimeout(0.5)
            self.logger.info(f"Gamepad: listening on UDP {PAD_PORT}")
            self.status["gamepad"] = ("running — waiting for phone", Colors.GREEN)

            pad = VX360Gamepad()
            pad.reset()
            self.logger.info("Gamepad: ViGEmBus virtual controller registered")

            while self.running:
                try:
                    data, addr = sock.recvfrom(64)
                    if len(data) >= 2:
                        if addr[0] not in self.clients:
                            self.clients.append(addr[0])
                            self.logger.info(f"Gamepad: phone connected from {addr[0]}")
                        self._apply_gamepad(data, pad)
                except socket.timeout:
                    continue
                except Exception as e:
                    if self.running:
                        pass
            sock.close()
            pad.reset()
        except Exception as e:
            self.logger.error(f"Gamepad server error: {e}")
            self.status["gamepad"] = (f"error: {e}", Colors.RED)

    def _apply_gamepad(self, data: bytes, pad):
        import struct

        if len(data) < 2:
            return
        buttons, lx, ly, rx, ry = struct.unpack("<Hbbbb", data[:8])
        lt, rt = struct.unpack("BB", data[8:10])
        pad.left_trigger(int(lt))
        pad.right_trigger(int(rt))
        pad.left_joystick(float(lx) / 127.0, float(ly) / 127.0)
        pad.right_joystick(float(rx) / 127.0, float(ry) / 127.0)
        for i, bit in enumerate([0, 1, 2, 3, 9, 10, 4, 5, 6, 7, 12, 13, 14, 15]):
            if buttons & (1 << bit):
                pad.press_button(1 << i)
            else:
                pad.release_button(1 << i)
        pad.update()

    def _run_audio(self):
        try:
            import sounddevice as sd

            PORT = 5007
            self.logger.info(f"Audio: TCP server on port {PORT}")
            srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind(("0.0.0.0", PORT))
            srv.listen(1)
            srv.settimeout(1)
            self.status["audio"] = ("waiting for phone", Colors.YELLOW)
            stream = None

            while self.running:
                try:
                    conn, addr = srv.accept()
                    self.logger.info(f"Audio: client connected {addr[0]}")
                    self.status["audio"] = (f"streaming from {addr[0]}", Colors.GREEN)
                    if stream:
                        try:
                            stream.close()
                        except:
                            pass
                    try:
                        header = conn.recv(4)
                        if len(header) < 4:
                            conn.close()
                            continue
                        sample_rate = int.from_bytes(header, "little")
                        self.logger.info(f"Audio: sample rate {sample_rate} Hz")
                        import numpy as np

                        out = sd.OutputStream(
                            samplerate=sample_rate,
                            channels=2,
                            dtype="int16",
                            blocksize=1024,
                        )
                        out.start()
                        while self.running:
                            try:
                                data = conn.recv(4096)
                                if not data:
                                    break
                                buf = np.frombuffer(data, dtype="int16")
                                out.write(buf)
                            except Exception:
                                break
                        out.stop()
                        out.close()
                    except Exception as e:
                        self.logger.error(f"Audio stream error: {e}")
                    finally:
                        conn.close()
                except socket.timeout:
                    continue
                except OSError:
                    break
            if stream:
                try:
                    stream.close()
                except:
                    pass
            srv.close()
        except ImportError as e:
            self.logger.warning(f"Audio: {e}")
            self.status["audio"] = ("sounddevice not installed", Colors.ORANGE)
        except Exception as e:
            self.logger.error(f"Audio server error: {e}")
            self.status["audio"] = (f"error", Colors.RED)

    def _run_screen(self):
        try:
            PORT = 5008
            self.logger.info(f"Screen: TCP server on port {PORT}")
            srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind(("0.0.0.0", PORT))
            srv.listen(1)
            srv.settimeout(1)
            self.status["screen"] = ("waiting for phone", Colors.YELLOW)

            while self.running:
                try:
                    conn, addr = srv.accept()
                    self.logger.info(f"Screen: client connected {addr[0]}")
                    self.status["screen"] = (f"streaming from {addr[0]}", Colors.GREEN)
                    while self.running:
                        try:
                            hdr = b""
                            while len(hdr) < 4:
                                chunk = conn.recv(4 - len(hdr))
                                if not chunk:
                                    break
                                hdr += chunk
                            if len(hdr) < 4:
                                break
                            length = int.from_bytes(hdr, "big")
                            if length <= 0 or length > 10_000_000:
                                break
                            data = b""
                            while len(data) < length:
                                chunk = conn.recv(length - len(data))
                                if not chunk:
                                    break
                                data += chunk
                        except socket.timeout:
                            continue
                        except Exception:
                            break
                    conn.close()
                    self.status["screen"] = ("waiting for phone", Colors.YELLOW)
                except socket.timeout:
                    continue
                except OSError:
                    break
            srv.close()
        except ImportError as e:
            self.logger.warning(f"Screen: {e}")
            self.status["screen"] = ("mss not installed", Colors.ORANGE)
        except Exception as e:
            self.logger.error(f"Screen server error: {e}")
            self.status["screen"] = (f"error", Colors.RED)

    def _run_mic(self):
        try:
            import sounddevice as sd

            PORT = 5009
            self.logger.info(f"Mic: TCP server on port {PORT}")
            srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind(("0.0.0.0", PORT))
            srv.listen(1)
            srv.settimeout(1)
            self.status["mic"] = ("waiting for phone", Colors.YELLOW)
            stream = None

            while self.running:
                try:
                    conn, addr = srv.accept()
                    self.logger.info(f"Mic: client connected {addr[0]}")
                    self.status["mic"] = (f"receiving from {addr[0]}", Colors.GREEN)
                    if stream:
                        try:
                            stream.close()
                        except:
                            pass
                    try:
                        header = conn.recv(4)
                        if len(header) < 4:
                            conn.close()
                            continue
                        sample_rate = int.from_bytes(header, "little")
                        self.logger.info(f"Mic: sample rate {sample_rate} Hz")
                        import numpy as np

                        out = sd.OutputStream(
                            samplerate=sample_rate,
                            channels=1,
                            dtype="int16",
                            blocksize=1024,
                        )
                        out.start()
                        while self.running:
                            try:
                                data = conn.recv(2048)
                                if not data:
                                    break
                                buf = np.frombuffer(data, dtype="int16")
                                out.write(buf)
                            except Exception:
                                break
                        out.stop()
                        out.close()
                    except Exception as e:
                        self.logger.error(f"Mic stream error: {e}")
                    finally:
                        conn.close()
                except socket.timeout:
                    continue
                except OSError:
                    break
            if stream:
                try:
                    stream.close()
                except:
                    pass
            srv.close()
        except ImportError as e:
            self.logger.warning(f"Mic: {e}")
            self.status["mic"] = ("sounddevice not installed", Colors.ORANGE)
        except Exception as e:
            self.logger.error(f"Mic server error: {e}")
            self.status["mic"] = (f"error", Colors.RED)


# ── Flet App ───────────────────────────────────────────────────────────────────
class ServerApp:
    def __init__(self, page: Page):
        self.page = page
        self.page.title = "AndroPad Pro Server"
        self.page.theme_mode = ThemeMode.DARK
        self.page.bgcolor = C_BG
        self.page.spacing = 0
        self.page.padding = 0
        self.page.on_close = self._on_close

        self.page.theme = ft.Theme(
            color_scheme_seed=C_ACCENT,
        )

        self.log_q = queue.Queue(maxsize=500)
        self.controller = ServerController(self.log_q)
        self._log_lines: list[str] = []
        self._running = False

        self._build_ui()
        self._start_log_poll()

    def _on_close(self):
        if self._running:
            self.controller.stop()

    def _toggle_server(self, _):
        if self._running:
            self.controller.stop()
            self._running = False
            self._update_stopped()
        else:
            self.controller.start()
            self._running = True
            self._update_running()

    def _update_running(self):
        self.start_btn.text = "■  Stop Server"
        self.start_btn.icon = Icons.STOP
        self.start_btn.style.bgcolor = Colors.RED
        self.status_badge.bgcolor = Colors.GREEN
        self.status_text.value = "Server running"
        self._refresh_features()

    def _update_stopped(self):
        self.start_btn.text = "▶  Start Server"
        self.start_btn.icon = Icons.PLAY_ARROW
        self.start_btn.style.bgcolor = Colors.GREEN
        self.status_badge.bgcolor = Colors.GREY
        self.status_text.value = "Server stopped"
        self._refresh_features()

    def _refresh_features(self):
        for key in ["gamepad", "audio", "screen", "mic"]:
            st, color = self.controller.status.get(key, ("?", Colors.GREY))
            self.feature_status[key].value = st
            self.feature_status[key].color = color
            self.feature_dot[key].bgcolor = color

    def _clear_log(self, _):
        self._log_lines.clear()
        self.log_field.value = ""
        self.page.update()

    def _toggle_feature(self, key: str, sw: Switch):
        self.controller.features[key] = sw.value
        self.log(f"Feature '{key}' {'enabled' if sw.value else 'disabled'}")

    def log(self, msg: str):
        self.log_q.put_nowait(f"[INFO] {msg}")

    def _build_ui(self):
        page = self.page

        # ── Header ────────────────────────────────────────────────────────────
        self.status_badge = Container(
            width=10, height=10, border_radius=5, bgcolor=Colors.GREY
        )
        self.status_text = Text("Server stopped", size=13, color=C_DIM)

        header = Container(
            bgcolor=C_SURFACE,
            content=Row(
                [
                    Row(
                        [
                            Icon(Icons.GAMEPAD, color=C_ACCENT, size=28),
                            Column(
                                [
                                    Text(
                                        "AndroPad Pro",
                                        size=22,
                                        weight="bold",
                                        color=C_TEXT,
                                    ),
                                    Text("Server Control Panel", size=12, color=C_DIM),
                                ],
                                spacing=0,
                            ),
                        ],
                        spacing=10,
                    ),
                    Row(
                        [
                            Container(
                                content=Row(
                                    [
                                        self.status_badge,
                                        self.status_text,
                                    ],
                                    spacing=6,
                                ),
                                bgcolor=C_SURFACE2,
                                padding=Padding(10, 6, 10, 6),
                                border_radius=20,
                            ),
                            IconButton(
                                Icons.CODE,
                                tooltip="GitHub",
                                icon_color=C_DIM,
                                on_click=lambda _: webbrowser.open(
                                    "https://github.com/infinity6901/AndroPadPro"
                                ),
                            ),
                            IconButton(
                                Icons.HELP_OUTLINE,
                                tooltip="Help",
                                icon_color=C_DIM,
                                on_click=self._show_help,
                            ),
                        ],
                        spacing=8,
                    ),
                ],
                spacing=10,
                alignment="spaceBetween",
            ),
            padding=Padding(20, 12, 20, 12),
        )

        # ── IP display ────────────────────────────────────────────────────────
        ip = "—"
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
        except Exception:
            pass

        ip_card = Card(
            bgcolor=C_SURFACE,
            content=Container(
                content=Row(
                    [
                        Column(
                            [
                                Text("Your PC IP", size=10, color=C_DIM),
                                Text(
                                    ip,
                                    size=20,
                                    weight="bold",
                                    color=C_TEXT,
                                    font_family="monospace",
                                ),
                            ],
                            spacing=0,
                        ),
                        Column(
                            [
                                Text("UDP Port", size=10, color=C_DIM),
                                Text(
                                    "5005",
                                    size=20,
                                    weight="bold",
                                    color=C_ACCENT,
                                    font_family="monospace",
                                ),
                            ],
                            spacing=0,
                        ),
                        Column(
                            [
                                Text("TCP Audio", size=10, color=C_DIM),
                                Text(
                                    "5007",
                                    size=20,
                                    weight="bold",
                                    color=C_ACCENT,
                                    font_family="monospace",
                                ),
                            ],
                            spacing=0,
                        ),
                    ],
                    spacing=32,
                    alignment="start",
                ),
                padding=Padding(16, 14, 16, 14),
            ),
        )

        # ── Feature toggles ──────────────────────────────────────────────────
        self.feature_dot: dict[str, Container] = {}
        self.feature_status: dict[str, Text] = {}
        self.feature_switch: dict[str, Switch] = {}

        def feature_row(
            key: str, label: str, icon: str, desc: str, port: int
        ) -> Container:
            dot = Container(width=10, height=10, border_radius=5, bgcolor=Colors.GREY)
            status = Text("idle", size=12, color=Colors.GREY)
            sw = Switch(
                value=True,
                on_change=lambda e, k=key: self._toggle_feature(k, e.control),
            )

            self.feature_dot[key] = dot
            self.feature_status[key] = status
            self.feature_switch[key] = sw

            return Container(
                content=Row(
                    [
                        Container(
                            content=Icon(icon, color=C_ACCENT, size=22),
                            width=40,
                            height=40,
                            bgcolor=C_SURFACE2,
                            border_radius=8,
                        ),
                        Column(
                            [
                                Text(label, size=14, weight="bold", color=C_TEXT),
                                Text(f"Port {port} — {desc}", size=11, color=C_DIM),
                            ],
                            spacing=1,
                            expand=True,
                        ),
                        Column(
                            [
                                Container(
                                    width=10,
                                    height=10,
                                    border_radius=5,
                                    bgcolor=Colors.GREY,
                                    ref=dot,
                                ),
                                Text("idle", size=11, color=Colors.GREY, ref=status),
                            ],
                            spacing=2,
                            horizontal_alignment="end",
                        ),
                        sw,
                    ],
                    spacing=12,
                    alignment="center",
                ),
                padding=Padding(12, 10, 12, 10),
                bgcolor=C_SURFACE,
                border_radius=12,
                margin=margin.only(0, 0, 0, 8),
            )

        features_col = Column(
            [
                Text("Features", size=18, weight="bold", color=C_TEXT),
                Text(
                    "Toggle features before starting the server", size=12, color=C_DIM
                ),
                Container(height=4),
                feature_row(
                    "gamepad",
                    "Gamepad (ViGEmBus)",
                    Icons.GAMEPAD,
                    "Virtual Xbox 360 controller",
                    5005,
                ),
                feature_row(
                    "audio",
                    "PC Audio Stream",
                    Icons.VOLUME_UP,
                    "Stream PC audio to phone",
                    5007,
                ),
                feature_row(
                    "screen",
                    "PC Screen Stream",
                    Icons.MONITOR,
                    "Stream PC screen to phone",
                    5008,
                ),
                feature_row(
                    "mic",
                    "Microphone Input",
                    Icons.MIC,
                    "Receive phone mic on PC",
                    5009,
                ),
            ],
            spacing=0,
            scroll=ScrollMode.AUTO,
        )

        # ── Start / Stop button ──────────────────────────────────────────────
        self.start_btn = ElevatedButton(
            "▶  Start Server",
            icon=Icons.PLAY_ARROW,
            on_click=self._toggle_server,
            style=ButtonStyle(
                bgcolor=Colors.GREEN,
                color=Colors.WHITE,
                padding=Padding(20, 16, 20, 16),
                shape=RoundedRectangleBorder(radius=12),
            ),
        )

        # ── Log console ───────────────────────────────────────────────────────
        self.log_field = TextField(
            multiline=True,
            min_lines=10,
            max_lines=12,
            read_only=True,
            border_color="transparent",
            fill_color=C_BG,
            text_style=ft.TextStyle(
                font_family="Courier New", size=11, color="#CCCCCC"
            ),
        )

        log_card = Card(
            bgcolor=C_SURFACE,
            content=Container(
                content=Column(
                    [
                        Row(
                            [
                                Text(
                                    "Console Log", size=15, weight="bold", color=C_TEXT
                                ),
                                IconButton(
                                    Icons.CLEAR_ALL,
                                    icon_color=C_DIM,
                                    tooltip="Clear log",
                                    on_click=self._clear_log,
                                ),
                            ],
                            spacing=8,
                        ),
                        Container(
                            content=self.log_field,
                            height=200,
                            padding=8,
                            bgcolor=C_BG,
                            border_radius=8,
                        ),
                    ],
                    spacing=8,
                ),
                padding=Padding(14, 14, 14, 14),
            ),
        )

        # ── Info card ────────────────────────────────────────────────────────
        info_card = Card(
            bgcolor=C_SURFACE,
            content=Container(
                content=Column(
                    [
                        Text("Quick Start", size=15, weight="bold", color=C_TEXT),
                        Container(height=4),
                        Row(
                            [
                                Icon(Icons.DOWNLOAD, color=C_ACCENT, size=16),
                                Text(
                                    "1. Install ViGEmBus from github.com/ViGEm/ViGEmBus",
                                    size=11,
                                    color=C_DIM,
                                    expand=True,
                                ),
                            ]
                        ),
                        Row(
                            [
                                Icon(Icons.PHONE_ANDROID, color=C_ACCENT, size=16),
                                Text(
                                    "2. Install AndroPad Pro app on your Android phone",
                                    size=11,
                                    color=C_DIM,
                                    expand=True,
                                ),
                            ]
                        ),
                        Row(
                            [
                                Icon(Icons.WIFI, color=C_ACCENT, size=16),
                                Text(
                                    "3. Start this server, enter your PC IP in the app",
                                    size=11,
                                    color=C_DIM,
                                    expand=True,
                                ),
                            ]
                        ),
                        Row(
                            [
                                Icon(Icons.TOUCH_APP, color=C_ACCENT, size=16),
                                Text(
                                    "4. Tap Connect on the phone",
                                    size=11,
                                    color=C_DIM,
                                    expand=True,
                                ),
                            ]
                        ),
                    ],
                    spacing=6,
                ),
                padding=Padding(14, 14, 14, 14),
            ),
        )

        # ── Layout ───────────────────────────────────────────────────────────
        page.add(
            Column(
                [
                    header,
                    SafeArea(
                        Container(
                            content=Row(
                                [
                                    # Left column
                                    Column(
                                        [
                                            Row(
                                                [
                                                    self.start_btn,
                                                ],
                                                spacing=12,
                                            ),
                                            ip_card,
                                            log_card,
                                            info_card,
                                        ],
                                        spacing=12,
                                        expand=True,
                                    ),
                                    # Right column: features
                                    Container(
                                        content=SafeArea(features_col),
                                        width=340,
                                        padding=Padding(12, 16, 12, 12),
                                        bgcolor=C_BG,
                                    ),
                                ],
                                spacing=0,
                            ),
                            padding=Padding(16, 16, 16, 16),
                            bgcolor=C_BG,
                        ),
                        expand=True,
                    ),
                ],
                spacing=0,
            ),
        )

    def _show_help(self, _):
        self.log_q.put_nowait("[INFO] AndroPad Pro Server v1.0.0")
        self.log_q.put_nowait("[INFO] GitHub: github.com/infinity6901/AndroPadPro")
        self.log_q.put_nowait("[INFO] ViGEmBus: github.com/ViGEm/ViGEmBus")

    def _start_log_poll(self):
        def poll():
            try:
                while True:
                    try:
                        while True:
                            line = self.log_q.get_nowait()
                            self._log_lines.append(line)
                            if len(self._log_lines) > 500:
                                self._log_lines = self._log_lines[-400:]
                            self.log_field.value = "\n".join(self._log_lines[-300:])
                            self.log_field.scroll_to(offset=len(self.log_field.value))
                    except queue.Empty:
                        pass
                    # Refresh feature status
                    try:
                        self._refresh_features()
                    except Exception:
                        pass
                    time.sleep(0.2)
            except Exception:
                pass

        t = threading.Thread(target=poll, daemon=True, name="LogPoll")
        t.start()


def main():
    parser = argparse.ArgumentParser(description="AndroPad Pro Server")
    parser.add_argument("--port", type=int, default=None, help="Override gamepad port")
    args = parser.parse_args()
    ft.run(main=ServerApp, view=AppView.FLET_APP)


if __name__ == "__main__":
    main()
