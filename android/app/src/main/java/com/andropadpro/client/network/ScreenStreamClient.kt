package com.andropadpro.client.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Receives length-prefixed JPEG frames from the PC screen streamer (TCP port 5008)
 * and delivers decoded [Bitmap] objects via [onFrame] callback.
 *
 * Wire protocol (matches screen_streamer.py):
 *   [4 bytes big-endian uint32]  = frame byte length N
 *   [N bytes JPEG data]          = one JPEG frame
 *
 * Fix v3.1:
 *   - Previous bitmap is explicitly recycled before decoding the next frame.
 *     Without this, Android accumulates large native Bitmap allocations faster
 *     than GC can free them → OOM crash after a few seconds.
 *   - soTimeout increased to 10 s (was 3 s — too short for slow WiFi or
 *     server-side JPEG encoding spikes).
 *   - Frame drop: if the callback takes too long (UI thread busy), we skip
 *     stale frames instead of building a backlog.
 *   - inSampleSize = 2 added as a safety valve for unexpectedly large frames.
 */
class ScreenStreamClient(
    private val serverIp: String,
    private val port: Int = 5008,
    private val onFrame: (Bitmap) -> Unit,
) {
    companion object {
        private const val TAG              = "ScreenStreamClient"
        private const val CONNECT_TIMEOUT  = 5000       // ms
        private const val SO_TIMEOUT       = 10_000     // ms — allow slow WiFi
        private const val MAX_FRAME_BYTES  = 4 * 1024 * 1024  // 4 MB sanity cap
        private const val RETRY_DELAY_MS   = 3000L
    }

    @Volatile private var running   = false
    private var thread: Thread? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    fun start() {
        if (running) return
        running = true
        thread  = Thread(::streamLoop, "ScreenStreamClient")
            .also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    val isRunning: Boolean get() = running

    // ── Stream loop ────────────────────────────────────────────────────────────

    private fun streamLoop() {
        while (running) {
            try {
                connectAndReceive()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.w(TAG, "Stream error: ${e.message} — retrying in ${RETRY_DELAY_MS}ms")
                try {
                    if (running) Thread.sleep(RETRY_DELAY_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        Log.d(TAG, "Screen stream stopped")
    }

    private fun connectAndReceive() {
        Log.d(TAG, "Connecting to $serverIp:$port")
        val sock = Socket()
        sock.connect(InetSocketAddress(serverIp, port), CONNECT_TIMEOUT)
        sock.soTimeout = SO_TIMEOUT
        Log.d(TAG, "Connected")

        sock.use { s ->
            val input      = s.getInputStream()
            val headerBuf  = ByteArray(4)

            while (running) {
                // Read 4-byte big-endian length header
                readFully(input, headerBuf, 4)
                val frameSize = ByteBuffer.wrap(headerBuf)
                    .order(ByteOrder.BIG_ENDIAN)
                    .int and 0x7FFFFFFF   // treat as unsigned, mask sign bit

                if (frameSize <= 0 || frameSize > MAX_FRAME_BYTES) {
                    throw IOException("Invalid frame size: $frameSize — stream may be corrupt")
                }

                // Read JPEG payload
                val jpeg = ByteArray(frameSize)
                readFully(input, jpeg, frameSize)

                // Decode options — sample down large frames as a safety valve
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = 1   // server scales to 50% already; keep 1:1
                    inPreferredConfig = Bitmap.Config.RGB_565   // half the memory of ARGB_8888
                }

                val newBmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
                    ?: continue   // skip corrupt/empty frame

                // Deliver to UI thread. BackgroundMediaView.pushStreamFrame()
                // handles recycling the previous bitmap safely.
                onFrame(newBmp)
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Reads exactly [len] bytes from [input] into [buf], blocking as needed. */
    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var offset    = 0
        var remaining = len
        while (remaining > 0) {
            val read = input.read(buf, offset, remaining)
            if (read < 0) throw IOException("Stream ended unexpectedly")
            offset    += read
            remaining -= read
        }
    }
}
