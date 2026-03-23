package com.andropadpro.client.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class DiscoveryClient {
    private val tag = "DiscoveryClient"
    private val broadcastPort = 5005
    private val discoveryPort = 5006
    
    fun discover(timeoutMs: Int = 3000): List<Pair<String, String>> {
        val servers = mutableListOf<Pair<String, String>>()
        
        try {
            val socket = DatagramSocket(discoveryPort)
            socket.broadcast = true
            socket.soTimeout = timeoutMs
            
            val discoverMsg = "ANDROPADPRO_DISCOVER".toByteArray()
            val packet = DatagramPacket(
                discoverMsg,
                discoverMsg.size,
                InetAddress.getByName("255.255.255.255"),
                broadcastPort
            )
            socket.send(packet)
            Log.d(tag, "Discovery broadcast sent")
            
            val responseBuffer = ByteArray(1024)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            
            try {
                while (true) {
                    socket.receive(responsePacket)
                    val response = String(responsePacket.data, 0, responsePacket.length)
                    val address = responsePacket.address.hostAddress ?: continue
                    
                    if (response.startsWith("ANDROPADPRO_SERVER")) {
                        servers.add(Pair(address, response))
                        Log.d(tag, "Found server: $address")
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "Discovery complete, found ${servers.size} servers")
            }
            
            socket.close()
        } catch (e: Exception) {
            Log.e(tag, "Discovery failed: ${e.message}")
        }
        
        return servers
    }
    
    fun measureLatency(serverIp: String, count: Int = 3): Float {
        val latencies = mutableListOf<Long>()
        
        try {
            val socket = DatagramSocket()
            socket.soTimeout = 1000
            
            for (i in 0 until count) {
                val pingMsg = "PING:$i".toByteArray()
                val packet = DatagramPacket(pingMsg, pingMsg.size, InetAddress.getByName(serverIp), broadcastPort)
                
                val startTime = System.currentTimeMillis()
                socket.send(packet)
                
                val responseBuffer = ByteArray(1024)
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                
                try {
                    socket.receive(responsePacket)
                    val endTime = System.currentTimeMillis()
                    latencies.add(endTime - startTime)
                } catch (e: Exception) {
                    latencies.add(-1L)
                }
            }
            
            socket.close()
        } catch (e: Exception) {
            Log.e(tag, "Latency measurement failed: ${e.message}")
        }
        
        val validLatencies = latencies.filter { it > 0 }
        return if (validLatencies.isNotEmpty()) {
            validLatencies.average().toFloat()
        } else {
            -1f
        }
    }
}
