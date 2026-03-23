"""
AndroPad Pro Screen Streamer v3.1
==================================
Captures the primary monitor and streams length-prefixed JPEG frames over TCP
to the Android app on port 5008.

Wire protocol (Android reads):
  [4 bytes big-endian uint32]  = frame byte length N
  [N bytes JPEG data]          = the frame

Requirements:
    pip install mss Pillow

Tuning:
  --fps      target capture rate (default 15)
  --quality  JPEG quality 1-95  (default 40 — lower = smaller = faster)
  --scale    downscale factor 0.1–1.0 (default 0.5 = 50% of native res)
  --region   capture region as x,y,w,h (default: full primary monitor)

Usage (standalone):
    python screen_streamer.py
    python screen_streamer.py --fps 20 --quality 55
    python screen_streamer.py --scale 0.4
    python screen_streamer.py --region 0,0,1280,720

Or imported by gamepad_server.py:
    from screen_streamer import ScreenStreamer
    ScreenStreamer().start()
"""

import argparse
import io
import socket
import struct
import sys
import threading
import time

PORT        = 5008
FPS         = 20
QUALITY     = 40
SCALE       = 0.75
RETRY_DELAY = 3.0


# ── Dependency check ───────────────────────────────────────────────────────────

def _check_deps():
    missing = []
    try:
        import mss  # noqa: F401
    except ImportError:
        missing.append("mss")
    try:
        from PIL import Image  # noqa: F401
    except ImportError:
        missing.append("Pillow")
    if missing:
        print(f"Screen: missing packages: {', '.join(missing)}")
        print(f"  Run:  pip install {' '.join(missing)}")
        return False
    return True


# ── Pillow 10 compatibility ────────────────────────────────────────────────────
# Image.BILINEAR was removed in Pillow 10.0.0 (released 2023).
# Use Image.Resampling.BILINEAR on Pillow 9.1+ and fall back to the old
# constant on older versions so the code works on any installed version.

def _get_resample_filter():
    try:
        from PIL import Image
        # Pillow 9.1+
        return Image.Resampling.BILINEAR
    except AttributeError:
        from PIL import Image
        # Pillow < 9.1
        return Image.BILINEAR  # type: ignore[attr-defined]


class ScreenStreamer:
    def __init__(
        self,
        port    = PORT,
        fps     = FPS,
        quality = QUALITY,
        scale   = SCALE,
        region  = None,     # (x, y, w, h) tuple or None = full primary monitor
    ):
        self.port    = port
        self.fps     = fps
        self.quality = max(1, min(95, quality))
        self.scale   = max(0.1, min(1.0, scale))
        self.region  = region
        self.running = False
        self._clients: list[socket.socket] = []
        self._lock   = threading.Lock()

    # ── TCP accept loop ────────────────────────────────────────────────────────

    def _accept_loop(self, srv: socket.socket):
        while self.running:
            try:
                srv.settimeout(1.0)
                conn, addr = srv.accept()
                conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                conn.setsockopt(socket.SOL_SOCKET,  socket.SO_SNDBUF,   1 << 20)
                print(f"Screen: client connected {addr[0]}")
                with self._lock:
                    self._clients.append(conn)
            except socket.timeout:
                continue
            except OSError:
                # Socket closed during shutdown — exit cleanly
                break
            except Exception as e:
                if self.running:
                    print(f"Screen: accept error: {e}")

    def _send_frame(self, jpeg_bytes: bytes):
        """Send one JPEG frame prefixed with its 4-byte big-endian length."""
        payload = struct.pack(">I", len(jpeg_bytes)) + jpeg_bytes
        dead = []
        with self._lock:
            for conn in self._clients:
                try:
                    conn.sendall(payload)
                except Exception:
                    dead.append(conn)
            for conn in dead:
                self._clients.remove(conn)
                print("Screen: client disconnected")

    # ── Capture one frame → JPEG bytes ────────────────────────────────────────

    def _capture_frame(self, sct, mon, resample) -> bytes:
        from PIL import Image

        raw = sct.grab(mon)
        # mss gives BGRA — convert to RGB
        img = Image.frombytes("RGB", raw.size, raw.bgra, "raw", "BGRX")

        if self.scale < 1.0:
            nw = max(1, int(img.width  * self.scale))
            nh = max(1, int(img.height * self.scale))
            img = img.resize((nw, nh), resample)

        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=self.quality, optimize=False)
        return buf.getvalue()

    # ── Main entry ─────────────────────────────────────────────────────────────

    def start(self):
        if not _check_deps():
            return

        import mss
        resample = _get_resample_filter()

        self.running = True
        interval     = 1.0 / self.fps

        # ── Bind TCP server ───────────────────────────────────────────────────
        try:
            srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind(("0.0.0.0", self.port))
            srv.listen(4)
            print(
                f"Screen: TCP server on port {self.port}  "
                f"({self.fps} fps  quality={self.quality}  scale={self.scale:.0%})"
            )
        except OSError as e:
            print(f"Screen: cannot bind port {self.port}: {e}")
            print(f"  → Is another instance already running?")
            self.running = False
            return

        threading.Thread(
            target=self._accept_loop, args=(srv,), daemon=True, name="ScreenAccept"
        ).start()

        # ── Capture loop with auto-retry ──────────────────────────────────────
        while self.running:
            try:
                with mss.mss() as sct:
                    # Build monitor region
                    if self.region:
                        x, y, w, h = self.region
                        mon = {"left": x, "top": y, "width": w, "height": h}
                    else:
                        mon = sct.monitors[1]   # primary monitor

                    print(
                        f"Screen: capturing {mon.get('width','?')}×{mon.get('height','?')}"
                        f" → {self.scale:.0%}"
                    )

                    while self.running:
                        t0 = time.perf_counter()

                        # Skip capture when no client connected — save CPU
                        with self._lock:
                            has_clients = bool(self._clients)
                        if not has_clients:
                            time.sleep(0.1)
                            continue

                        try:
                            jpeg = self._capture_frame(sct, mon, resample)
                            self._send_frame(jpeg)
                        except Exception as e:
                            print(f"Screen: capture error: {e}")
                            time.sleep(1.0)

                        # Throttle to target fps
                        elapsed = time.perf_counter() - t0
                        wait    = interval - elapsed
                        if wait > 0:
                            time.sleep(wait)

            except KeyboardInterrupt:
                break
            except Exception as e:
                print(f"Screen: fatal error: {e} — retrying in {RETRY_DELAY}s")
                if self.running:
                    time.sleep(RETRY_DELAY)

        try: srv.close()
        except Exception: pass
        print("Screen: stopped")

    def stop(self):
        self.running = False


# ── CLI entry ──────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    p = argparse.ArgumentParser(description="AndroPad Pro Screen Streamer")
    p.add_argument("--port",    type=int,   default=PORT,    help=f"TCP port (default {PORT})")
    p.add_argument("--fps",     type=int,   default=FPS,     help=f"Target fps (default {FPS})")
    p.add_argument("--quality", type=int,   default=QUALITY, help=f"JPEG quality 1-95 (default {QUALITY})")
    p.add_argument("--scale",   type=float, default=SCALE,   help=f"Downscale 0.1-1.0 (default {SCALE})")
    p.add_argument("--region",  type=str,   default=None,    help="Capture region: x,y,w,h")
    args = p.parse_args()

    region = None
    if args.region:
        try:
            parts = [int(v) for v in args.region.split(",")]
            assert len(parts) == 4, "Need exactly 4 values"
            region = tuple(parts)
        except Exception as e:
            print(f"--region error: {e}")
            print("  Example: --region 0,0,1920,1080")
            sys.exit(1)

    ScreenStreamer(
        port    = args.port,
        fps     = args.fps,
        quality = args.quality,
        scale   = args.scale,
        region  = region,
    ).start()
