package com.andropadpro.client.network

import com.andropadpro.client.model.ControllerState

/** Common interface for WiFi-UDP and Bluetooth transports. */
interface Transport {
    fun connect(address: String, port: Int = 5005): Boolean
    fun send(state: ControllerState): Boolean
    fun disconnect()
    fun isConnected(): Boolean

    /** Round-trip latency in milliseconds; 0 if not measured yet. */
    val latencyMs: Long
}
