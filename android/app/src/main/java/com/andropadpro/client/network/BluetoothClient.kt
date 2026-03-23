package com.andropadpro.client.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.andropadpro.client.model.ControllerState
import java.io.OutputStream
import java.util.UUID

class BluetoothClient : Transport {

    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var btSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var _connected = false
    private val tag = "BluetoothClient"

    /** Bluetooth has no round-trip measurement in this implementation. */
    override var latencyMs: Long = 0L

    /**
     * [address] is the Bluetooth MAC address of the paired PC
     * (e.g. "DC:A6:32:AB:CD:EF").  [port] is ignored for BT SPP.
     */
    override fun connect(address: String, port: Int): Boolean {
        if (address.isBlank()) {
            Log.e(tag, "No Bluetooth address configured")
            return false
        }
        return try {
            @Suppress("DEPRECATION")
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                Log.e(tag, "Bluetooth not available or not enabled")
                return false
            }
            val device = adapter.getRemoteDevice(address)
            adapter.cancelDiscovery()   // discovery slows down RFCOMM
            btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            btSocket?.connect()
            outputStream = btSocket?.outputStream
            _connected = true
            Log.d(tag, "Connected via Bluetooth to $address")
            true
        } catch (e: Exception) {
            Log.e(tag, "Bluetooth connect failed: ${e.message}")
            disconnect()
            false
        }
    }

    override fun send(state: ControllerState): Boolean {
        return try {
            outputStream?.write(state.toByteArray())
            true
        } catch (e: Exception) {
            Log.e(tag, "BT send failed: ${e.message}")
            _connected = false
            false
        }
    }

    override fun disconnect() {
        try { outputStream?.close() } catch (_: Exception) {}
        try { btSocket?.close()    } catch (_: Exception) {}
        outputStream = null
        btSocket     = null
        _connected   = false
    }

    override fun isConnected() = _connected
}
