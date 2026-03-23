package com.andropadpro.client.network

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records from the phone microphone and streams raw 16-bit mono PCM to the
 * PC server over TCP port 5009.
 *
 * Echo cancellation:
 *   VOICE_COMMUNICATION source activates hardware reference-signal AEC on
 *   devices that route playback through the same hardware path — it subtracts
 *   whatever the speaker is playing from the mic capture so the PC audio
 *   stream doesn't loop back.  We also attach AcousticEchoCanceler explicitly
 *   for devices that expose it as a separate effect (most Qualcomm + MediaTek
 *   SoCs do).  NoiseSuppressor and AutomaticGainControl are added on top for
 *   cleaner voice quality.
 *
 * Wire protocol (same header style as AudioStreamClient, phone → PC):
 *   [4 bytes little-endian uint32]  sample rate (always 16000)
 *   [continuous int16 mono PCM]     raw audio frames
 *
 * Requires: android.permission.RECORD_AUDIO
 */
class MicStreamClient(
    private val serverIp: String,
    private val port: Int = 5009,
) {
    companion object {
        private const val TAG              = "MicStreamClient"
        const val SAMPLE_RATE             = 16000
        private const val CHANNELS        = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING        = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_BYTES     = 1024
        private const val CONNECT_TIMEOUT = 5000
        private const val RETRY_DELAY_MS  = 3000L
    }

    /** Called on the recording thread when AEC is unavailable — post to UI thread to show a Toast. */
    var onEchoWarning: (() -> Unit)? = null

    @Volatile var isRunning = false
    private var thread: Thread? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        thread = Thread(::streamLoop, "MicStreamClient").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        isRunning = false
        thread?.interrupt()
        thread = null
    }

    private fun streamLoop() {
        while (isRunning) {
            try {
                connectAndRecord()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.w(TAG, "Mic error: ${e.message} — retrying in ${RETRY_DELAY_MS}ms")
                try {
                    if (isRunning) Thread.sleep(RETRY_DELAY_MS)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        Log.d(TAG, "Mic stream stopped")
    }

    private fun connectAndRecord() {
        Log.d(TAG, "Mic connecting to $serverIp:$port")
        val sock = Socket()
        sock.connect(InetSocketAddress(serverIp, port), CONNECT_TIMEOUT)
        Log.d(TAG, "Mic connected")

        val minBuf  = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        val bufSize = maxOf(minBuf, CHUNK_BYTES * 4)

        // VOICE_COMMUNICATION source: Android activates hardware AEC/NS reference
        // path so the speaker output is subtracted from capture at the HAL level.
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNELS, ENCODING, bufSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            sock.close()
            throw IllegalStateException("AudioRecord failed to initialise")
        }

        val sessionId = recorder.audioSessionId

        // ── Attach audio effects for clean voice capture ──────────────────────

        // 1. Acoustic Echo Canceler — removes PC speaker output from mic signal.
        //    This is the primary fix for the echo loop: phone plays PC audio →
        //    mic picks it up → AEC subtracts it before we stream it back to PC.
        var aec: AcousticEchoCanceler? = null
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                aec = AcousticEchoCanceler.create(sessionId)
                aec?.enabled = true
                Log.d(TAG, "AcousticEchoCanceler enabled")
            } catch (e: Exception) {
                Log.w(TAG, "AcousticEchoCanceler failed: ${e.message}")
            }
        } else {
            Log.w(TAG, "AcousticEchoCanceler not available on this device")
            onEchoWarning?.invoke()
        }

        // 2. Noise Suppressor — reduces background hiss, fan noise, etc.
        var ns: NoiseSuppressor? = null
        if (NoiseSuppressor.isAvailable()) {
            try {
                ns = NoiseSuppressor.create(sessionId)
                ns?.enabled = true
            } catch (_: Exception) {}
        }

        // 3. Automatic Gain Control — keeps mic volume consistent.
        var agc: AutomaticGainControl? = null
        if (AutomaticGainControl.isAvailable()) {
            try {
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
            } catch (_: Exception) {}
        }

        try {
            // Send 4-byte little-endian sample rate header
            val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(SAMPLE_RATE).array()
            sock.getOutputStream().write(header)

            recorder.startRecording()
            val buf = ByteArray(CHUNK_BYTES)
            val out = sock.getOutputStream()

            while (isRunning) {
                val read = recorder.read(buf, 0, CHUNK_BYTES)
                if (read > 0) {
                    try {
                        out.write(buf, 0, read)
                    } catch (e: IOException) {
                        break
                    }
                } else if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                           read == AudioRecord.ERROR_BAD_VALUE) {
                    throw IOException("AudioRecord.read() error: $read")
                }
            }
        } finally {
            aec?.release()
            ns?.release()
            agc?.release()
            try { recorder.stop()    } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            try { sock.close()       } catch (_: Exception) {}
        }
    }
}
