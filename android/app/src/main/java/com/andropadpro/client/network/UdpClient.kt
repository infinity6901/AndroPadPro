package com.andropadpro.client.network

import android.util.Log
import com.andropadpro.client.model.ControllerState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class UdpClient : Transport {

    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var serverPort: Int = 5005
    private var _connected = false
    private val tag = "UdpClient"

    // ── RTT tracking ──────────────────────────────────────────────────────────
    /** Round-trip latency in milliseconds; -1 until first measurement. */
    override var latencyMs: Long = -1L
        private set

    /**
     * Wall-clock time (ms) of the last ACK received from the server.
     * Starts at 0 (never received). Use this to determine real connectivity:
     *   connected = (System.currentTimeMillis() - lastAckTimeMs) < 500
     */
    @Volatile var lastAckTimeMs: Long = 0L
        private set

    /** seq (0–255) → nanoTime of send */
    private val pendingSendTimes = ConcurrentHashMap<Int, Long>()

    // ── Force-feedback callback ───────────────────────────────────────────────
    interface RumbleListener {
        fun onRumble(leftMotor: Int, rightMotor: Int)
    }
    var rumbleListener: RumbleListener? = null

    // ── Receive thread ────────────────────────────────────────────────────────
    @Volatile private var receiveRunning = false
    private var receiveThread: Thread? = null

    // ── Transport ─────────────────────────────────────────────────────────────

    override fun connect(address: String, port: Int): Boolean {
        return try {
            socket?.close()
            serverPort = port
            serverAddress = InetAddress.getByName(address)
            val s = DatagramSocket()
            s.soTimeout = 500   // 500 ms so receive thread can check receiveRunning
            socket = s
            _connected = true
            startReceiveLoop()
            Log.d(tag, "Connected to $address:$port")
            true
        } catch (e: Exception) {
            Log.e(tag, "Connection failed: ${e.message}")
            disconnect()
            false
        }
    }

    fun connectBroadcast(port: Int = 5005): Boolean {
        return try {
            socket?.close()
            serverPort = port
            serverAddress = InetAddress.getByName("255.255.255.255")
            val s = DatagramSocket()
            s.broadcast = true
            s.soTimeout = 500
            socket = s
            _connected = true
            Log.d(tag, "Connected to broadcast address")
            true
        } catch (e: Exception) {
            Log.e(tag, "Broadcast connection failed: ${e.message}")
            disconnect()
            false
        }
    }

    override fun send(state: ControllerState): Boolean {
        val addr = serverAddress
        if (!_connected || socket == null || addr == null) return false

        return try {
            val data   = state.toByteArray()
            val packet = DatagramPacket(data, data.size, addr, serverPort)
            // Record send time for RTT (keyed by sequence byte)
            val seq = state.sequence and 0xFF
            pendingSendTimes[seq] = System.nanoTime()
            // Keep map bounded
            if (pendingSendTimes.size > 64) {
                pendingSendTimes.keys.take(pendingSendTimes.size - 64)
                    .forEach { pendingSendTimes.remove(it) }
            }
            socket?.send(packet)
            true
        } catch (e: Exception) {
            Log.e(tag, "Send failed: ${e.message}")
            false
        }
    }

    override fun disconnect() {
        receiveRunning = false
        try { socket?.close() } catch (_: Exception) {}
        socket         = null
        serverAddress  = null
        _connected     = false
        latencyMs      = -1L
        lastAckTimeMs  = 0L
        pendingSendTimes.clear()
        Log.d(tag, "Disconnected")
    }

    override fun isConnected() = _connected

    // ── Receive loop ──────────────────────────────────────────────────────────

    private fun startReceiveLoop() {
        receiveRunning = true
        receiveThread = Thread {
            val buf = ByteArray(64)
            val pkt = DatagramPacket(buf, buf.size)
            while (receiveRunning) {
                try {
                    socket?.receive(pkt) ?: break
                    handleIncoming(buf, pkt.length)
                } catch (e: java.net.SocketTimeoutException) {
                    // Normal — loop and re-check receiveRunning
                } catch (e: Exception) {
                    if (receiveRunning) Log.w(tag, "Receive: ${e.message}")
                }
            }
        }.also { it.isDaemon = true; it.name = "UdpReceiver"; it.start() }
    }

    /**
     * Handles packets arriving from the server.
     *
     * Packet types:
     *   1 byte  — sequence ACK  → used to calculate RTT
     *   4 bytes — [leftMotor, rightMotor, seq, 0] → force-feedback rumble
     */
    private fun handleIncoming(buf: ByteArray, len: Int) {
        when (len) {
            1 -> {
                // Sequence ACK — measure RTT and record that server is alive
                val seq      = buf[0].toInt() and 0xFF
                val sendTime = pendingSendTimes.remove(seq)
                if (sendTime != null) {
                    latencyMs    = (System.nanoTime() - sendTime) / 1_000_000L
                    lastAckTimeMs = System.currentTimeMillis()
                }
            }
            4 -> {
                // Force-feedback rumble
                val left  = buf[0].toInt() and 0xFF
                val right = buf[1].toInt() and 0xFF
                rumbleListener?.onRumble(left, right)
            }
        }
    }
}
