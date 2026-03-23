package com.andropadpro.client.network

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Receives raw 16-bit PCM stereo audio from the PC server (TCP port 5007).
 *
 * Wire protocol:
 *   [4 bytes little-endian uint32]  sample rate (e.g. 44100 or 48000)
 *   [continuous int16 PCM stereo]   raw audio frames
 *
 * The 4-byte header is read first so AudioTrack is configured to exactly
 * match the server's capture device rate. Stereo Mix on Realtek runs at
 * 48000 Hz — forcing 44100 caused distortion or failure.
 */
class AudioStreamClient(
    private val serverIp: String,
    private val port: Int = 5007,
    initialVolume: Float = 0.8f,
) {
    companion object {
        private const val TAG             = "AudioStreamClient"
        private const val CHANNELS        = AudioFormat.CHANNEL_OUT_STEREO
        private const val ENCODING        = AudioFormat.ENCODING_PCM_16BIT
        private const val CONNECT_TIMEOUT = 5000
        private const val RETRY_DELAY_MS  = 3000L
        private const val READ_CHUNK      = 16384
        private const val HEADER_BYTES    = 4   // little-endian uint32 sample rate
    }

    var volume: Float = initialVolume.coerceIn(0f, 1f)
        set(value) {
            field = value.coerceIn(0f, 1f)
            audioTrack?.setVolume(field)
        }

    @Volatile private var running    = false
    private var thread:     Thread?    = null
    private var audioTrack: AudioTrack? = null

    // ── Public API ─────────────────────────────────────────────────────────────

    fun start() {
        if (running) return
        running = true
        thread  = Thread(::streamLoop, "AudioStreamClient")
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
                connectAndPlay()
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
            } finally {
                releaseTrack()
            }
        }
        Log.d(TAG, "Audio stream stopped")
    }

    private fun connectAndPlay() {
        Log.d(TAG, "Connecting to $serverIp:$port")
        val sock = Socket()
        sock.connect(InetSocketAddress(serverIp, port), CONNECT_TIMEOUT)
        // No soTimeout — PCM is continuous; we block until data arrives
        Log.d(TAG, "Connected")

        sock.use { s ->
            val input = s.getInputStream()

            // ── Read 4-byte sample rate header ────────────────────────────────
            val sampleRate = readSampleRateHeader(input)
            Log.d(TAG, "Server sample rate: $sampleRate Hz")

            // ── Build AudioTrack with the server's native rate ─────────────────
            val track = buildAudioTrack(sampleRate)
            audioTrack = track

            if (track.state == AudioTrack.STATE_UNINITIALIZED) {
                track.release()
                audioTrack = null
                throw IllegalStateException("AudioTrack failed to initialize at $sampleRate Hz")
            }

            track.setVolume(volume)
            track.play()

            // ── Stream PCM ────────────────────────────────────────────────────
            val readBuf = ByteArray(READ_CHUNK)
            while (running) {
                val read = input.read(readBuf)
                if (read < 0) throw IOException("Server closed audio connection")
                if (read > 0) {
                    val result = track.write(readBuf, 0, read)
                    if (result < 0) {
                        throw IOException("AudioTrack.write() error code: $result")
                    }
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Reads the 4-byte little-endian uint32 sample rate header sent by the server.
     * Falls back to 48000 Hz if the header is missing or malformed.
     */
    private fun readSampleRateHeader(input: InputStream): Int {
        return try {
            val header = ByteArray(HEADER_BYTES)
            var offset = 0
            while (offset < HEADER_BYTES) {
                val read = input.read(header, offset, HEADER_BYTES - offset)
                if (read < 0) throw IOException("Stream ended reading header")
                offset += read
            }
            val rate = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
            // Sanity check — accept common audio rates only
            if (rate in 8000..192000) rate else 48000
        } catch (e: Exception) {
            Log.w(TAG, "Could not read sample rate header: ${e.message} — defaulting to 48000")
            48000
        }
    }

    private fun buildAudioTrack(sampleRate: Int): AudioTrack {
        val minBuf  = AudioTrack.getMinBufferSize(sampleRate, CHANNELS, ENCODING)
        val bufSize = if (minBuf > 0) maxOf(minBuf * 4, READ_CHUNK * 2) else READ_CHUNK * 4

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(CHANNELS)
                        .setEncoding(ENCODING)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate, CHANNELS, ENCODING,
                bufSize,
                AudioTrack.MODE_STREAM
            )
        }
    }

    private fun releaseTrack() {
        try { audioTrack?.pause()   } catch (_: Exception) {}
        try { audioTrack?.flush()   } catch (_: Exception) {}
        try { audioTrack?.stop()    } catch (_: Exception) {}
        try { audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }
}
