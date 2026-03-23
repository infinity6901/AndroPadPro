"""
AndroPad Pro Audio Streamer v3.6
================================
Captures system audio and streams raw 16-bit PCM stereo over TCP port 5007.

Wire protocol:
  [4 bytes little-endian uint32]  sample rate
  [continuous int16 PCM stereo]   raw audio frames

Fix v3.6:
  - Uses pyaudiowpatch for WASAPI loopback — the only reliable way to capture
    game audio on Windows without Stereo Mix or VB-Audio Cable.
  - Falls back to sounddevice if pyaudiowpatch is not installed.

Install:
    pip install pyaudiowpatch

Usage:
    python audio_streamer.py                (auto-detects speakers)
    python audio_streamer.py --list-devices
    python audio_streamer.py --device 5     (override speaker device index)
"""

import argparse
import socket
import struct
import sys
import threading
import time

PORT        = 5007
CHUNK       = 1024
SAMPLE_RATE = 48000
CHANNELS    = 2
FORMAT_PA   = None   # set at runtime: pyaudio.paInt16
DTYPE_SD    = "int16"
RETRY_DELAY = 3.0


def _try_pyaudiowpatch(device_override=None):
    """
    Attempt to capture via pyaudiowpatch WASAPI loopback.
    Returns (stream_generator, sample_rate, channels) or None if unavailable.
    """
    try:
        import pyaudiowpatch as pyaudio
        return pyaudio, True
    except ImportError:
        return None, False


class AudioStreamer:
    def __init__(self, device=None, port=PORT):
        self.device       = device
        self.port         = port
        self.running      = False
        self._sample_rate = SAMPLE_RATE
        self._clients: list[socket.socket] = []
        self._lock        = threading.Lock()

    # ── TCP ────────────────────────────────────────────────────────────────────

    def _accept_loop(self, srv):
        while self.running:
            try:
                srv.settimeout(1.0)
                conn, addr = srv.accept()
                conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                conn.setsockopt(socket.SOL_SOCKET,  socket.SO_SNDBUF,   65536)
                try:
                    conn.sendall(struct.pack("<I", self._sample_rate))
                except Exception:
                    conn.close()
                    continue
                print(f"Audio: client connected {addr[0]}  (rate={self._sample_rate} Hz)")
                with self._lock:
                    self._clients.append(conn)
            except socket.timeout:
                continue
            except OSError:
                break
            except Exception as e:
                if self.running:
                    print(f"Audio: accept error: {e}")

    def _send_to_all(self, data: bytes):
        dead = []
        with self._lock:
            for conn in self._clients:
                try:    conn.sendall(data)
                except: dead.append(conn)
            for conn in dead:
                self._clients.remove(conn)
                print("Audio: client disconnected")

    # ── Main ───────────────────────────────────────────────────────────────────

    def start(self):
        self.running = True

        try:
            srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind(("0.0.0.0", self.port))
            srv.listen(4)
            print(f"Audio: TCP server on port {self.port}")
        except OSError as e:
            print(f"Audio: cannot bind port {self.port}: {e}")
            self.running = False
            return

        threading.Thread(target=self._accept_loop, args=(srv,),
                         daemon=True, name="AudioAccept").start()

        # Strategy 1: pyaudiowpatch WASAPI loopback (best)
        try:
            import pyaudiowpatch as pyaudio
            self._run_pyaudio_loopback(pyaudio, self.device)
        except ImportError:
            print("Audio: pyaudiowpatch not installed.")
            print("  Run:  pip install pyaudiowpatch")
            print("  Then restart the server.")
            print()
            # Strategy 2: sounddevice fallback
            self._run_sounddevice_fallback(self.device)

        try: srv.close()
        except Exception: pass
        print("Audio: stopped")

    # ── pyaudiowpatch loopback ─────────────────────────────────────────────────

    def _run_pyaudio_loopback(self, pyaudio, device_override=None):
        """
        Use pyaudiowpatch to open the default WASAPI loopback device.
        This captures whatever is playing through speakers — no setup needed.
        """
        pa = pyaudio.PyAudio()
        try:
            # Find the WASAPI loopback device
            if device_override is not None:
                # User specified a device index — find its loopback counterpart
                device_info = pa.get_device_info_by_index(device_override)
                # pyaudiowpatch adds loopback devices right after real ones
                loopback_info = None
                for i in range(pa.get_device_count()):
                    d = pa.get_device_info_by_index(i)
                    if (d.get("isLoopbackDevice", False) and
                        device_info["name"] in d["name"]):
                        loopback_info = d
                        break
                if loopback_info is None:
                    # Try the device directly
                    loopback_info = device_info
            else:
                # Auto: get default output device's loopback
                try:
                    wasapi_info   = pa.get_host_api_info_by_type(pyaudio.paWASAPI)
                    default_out   = wasapi_info["defaultOutputDevice"]
                    device_info   = pa.get_device_info_by_index(default_out)
                    # Find the loopback version
                    loopback_info = None
                    for i in range(pa.get_device_count()):
                        d = pa.get_device_info_by_index(i)
                        if (d.get("isLoopbackDevice", False) and
                            device_info["name"] in d["name"]):
                            loopback_info = d
                            break
                    if loopback_info is None:
                        # No explicit loopback device — use the output device directly
                        loopback_info = device_info
                except Exception as e:
                    print(f"Audio: could not find WASAPI default output: {e}")
                    pa.terminate()
                    return

            rate     = int(loopback_info["defaultSampleRate"])
            channels = min(2, int(loopback_info["maxInputChannels"]) or 2)
            dev_idx  = int(loopback_info["index"])

            self._sample_rate = rate
            print(f"Audio: pyaudiowpatch loopback [{dev_idx}] '{loopback_info['name']}'  {rate} Hz  {channels}ch")

            while self.running:
                try:
                    stream = pa.open(
                        format=pyaudio.paInt16,
                        channels=channels,
                        rate=rate,
                        frames_per_buffer=CHUNK,
                        input=True,
                        input_device_index=dev_idx,
                    )
                    print(f"Audio: streaming — {rate} Hz  {channels}ch  int16")
                    while self.running:
                        try:
                            data = stream.read(CHUNK, exception_on_overflow=False)
                            if self._clients:
                                self._send_to_all(data)
                            else:
                                time.sleep(0.02)
                        except Exception as e:
                            print(f"Audio: read error: {e}")
                            break
                    stream.stop_stream()
                    stream.close()
                except KeyboardInterrupt:
                    self.running = False
                    break
                except Exception as e:
                    print(f"Audio: stream error: {e} — retrying in {RETRY_DELAY}s")
                    try:
                        if self.running: time.sleep(RETRY_DELAY)
                    except Exception:
                        break
        finally:
            pa.terminate()

    # ── sounddevice fallback ───────────────────────────────────────────────────

    def _run_sounddevice_fallback(self, device_override=None):
        """Last resort: try sounddevice with Stereo Mix if available."""
        try:
            import sounddevice as sd
        except ImportError:
            print("Audio: sounddevice not installed either.")
            print("  Run:  pip install pyaudiowpatch")
            return

        # Find Stereo Mix or loopback input
        loopback_keywords = ["stereo mix", "what u hear", "loopback", "cable output"]
        hapis = {i: h["name"] for i, h in enumerate(sd.query_hostapis())}
        target = device_override

        if target is None:
            for i, d in enumerate(sd.query_devices()):
                if d["max_input_channels"] < 1: continue
                hapi = hapis.get(d["hostapi"], "")
                if "WDM-KS" in hapi: continue
                if any(k in d["name"].lower() for k in loopback_keywords):
                    target = i
                    print(f"Audio: sounddevice fallback [{i}] '{d['name']}'  [{hapi}]")
                    break

        if target is None:
            print("Audio: no capture device found via sounddevice either.")
            print("  Install pyaudiowpatch:  pip install pyaudiowpatch")
            return

        rate = int(sd.query_devices(target)["default_samplerate"])
        self._sample_rate = rate

        while self.running:
            try:
                with sd.InputStream(device=target, samplerate=rate,
                                    channels=2, dtype=DTYPE_SD,
                                    blocksize=CHUNK) as s:
                    print(f"Audio: streaming (fallback) — {rate} Hz  2ch")
                    while self.running:
                        try:
                            data, _ = s.read(CHUNK)
                            if self._clients:
                                self._send_to_all(data.tobytes())
                            else:
                                time.sleep(0.02)
                        except Exception as e:
                            print(f"Audio: read error: {e}")
                            break
            except KeyboardInterrupt:
                break
            except Exception as e:
                print(f"Audio: fallback error: {e} — retrying in {RETRY_DELAY}s")
                try:
                    if self.running: time.sleep(RETRY_DELAY)
                except Exception:
                    break

    def stop(self):
        self.running = False


# ── Device listing ─────────────────────────────────────────────────────────────

def list_devices():
    # List via pyaudiowpatch if available (shows loopback devices)
    try:
        import pyaudiowpatch as pyaudio
        pa = pyaudio.PyAudio()
        print("\nAudio devices (pyaudiowpatch):")
        print(f"  {'Idx':>3}  {'In':>3}  {'Out':>3}  {'Hz':>6}  {'Loop':>4}  Name")
        print(f"  {'---':>3}  {'---':>3}  {'---':>3}  {'------':>6}  {'----':>4}  ----")
        for i in range(pa.get_device_count()):
            d    = pa.get_device_info_by_index(i)
            loop = "YES" if d.get("isLoopbackDevice", False) else ""
            note = "  ← USE THIS" if d.get("isLoopbackDevice", False) else ""
            print(f"  [{i:>3}]  {int(d['maxInputChannels']):>3}  "
                  f"{int(d['maxOutputChannels']):>3}  "
                  f"{int(d['defaultSampleRate']):>6}  {loop:>4}  {d['name']}{note}")
        pa.terminate()
        return
    except ImportError:
        pass

    # Fallback to sounddevice
    try:
        import sounddevice as sd
        hapis = {i: h["name"] for i, h in enumerate(sd.query_hostapis())}
        print("\nAudio devices (sounddevice — install pyaudiowpatch for loopback):")
        for i, d in enumerate(sd.query_devices()):
            if d["max_input_channels"] < 1: continue
            hapi = hapis.get(d["hostapi"], "?")
            print(f"  [{i:>3}]  {int(d['default_samplerate']):>6} Hz  {hapi:<22}  {d['name']}")
    except ImportError:
        print("Neither pyaudiowpatch nor sounddevice installed.")
        print("Run:  pip install pyaudiowpatch")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description="AndroPad Pro Audio Streamer v3.6")
    p.add_argument("--port",         type=int, default=PORT)
    p.add_argument("--device",       type=int, default=None,
                   help="Output device index for loopback (default: system default speakers)")
    p.add_argument("--list-devices", action="store_true")
    args = p.parse_args()
    if args.list_devices:
        list_devices()
    else:
        AudioStreamer(device=args.device, port=args.port).start()
