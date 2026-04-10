package com.mthkr.marstek

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

class MarstekClient(
    private val deviceIp: String,
    private val udpPort: Int
) {
    private val log = LoggerFactory.getLogger(MarstekClient::class.java)
    private val requestId = AtomicInteger(1)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val socketTimeoutMs = 3000

    private fun sendRequest(method: String): RpcResponse? {
        val id = requestId.getAndIncrement()
        val request = RpcRequest(id = id, method = method, params = RpcParams())
        val requestJson = json.encodeToString(RpcRequest.serializer(), request)
        val requestBytes = requestJson.toByteArray(Charsets.UTF_8)

        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = socketTimeoutMs
                val address = InetAddress.getByName(deviceIp)
                val sendPacket = DatagramPacket(requestBytes, requestBytes.size, address, udpPort)
                socket.send(sendPacket)

                val buffer = ByteArray(4096)
                val receivePacket = DatagramPacket(buffer, buffer.size)
                socket.receive(receivePacket)

                val responseJson = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                log.debug("Response from {}: {}", deviceIp, responseJson)
                json.decodeFromString(RpcResponse.serializer(), responseJson)
            }
        } catch (e: SocketTimeoutException) {
            log.warn("UDP timeout reaching Marstek device at {}:{}", deviceIp, udpPort)
            null
        } catch (e: Exception) {
            log.warn("Error communicating with Marstek device: {}", e.message)
            null
        }
    }

    fun getEsStatus(): ESGetStatusResult? {
        val response = sendRequest("ES.GetStatus") ?: return null
        val result = response.result ?: run {
            log.warn("ES.GetStatus returned no result. Error: {}", response.error)
            return null
        }
        return try {
            json.decodeFromJsonElement(ESGetStatusResult.serializer(), result)
        } catch (e: Exception) {
            log.warn("Failed to parse ES.GetStatus result: {}", e.message)
            null
        }
    }

    fun getBatStatus(): BatGetStatusResult? {
        val response = sendRequest("Bat.GetStatus") ?: return null
        val result = response.result ?: run {
            log.warn("Bat.GetStatus returned no result")
            return null
        }
        return try {
            json.decodeFromJsonElement(BatGetStatusResult.serializer(), result)
        } catch (e: Exception) {
            log.warn("Failed to parse Bat.GetStatus result: {}", e.message)
            null
        }
    }
}