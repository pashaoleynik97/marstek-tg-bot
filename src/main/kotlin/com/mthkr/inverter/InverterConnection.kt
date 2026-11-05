package com.mthkr.inverter

import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the local "reverse" session with the EyBond Wi-Fi dongle:
 * we listen on TCP [tcpPort], the dongle connects to us after a UDP `set>server=...` command.
 * All requests are serialized (one in flight) and matched by TID.
 */
/** Abstraction over the inverter session (allows fakes in tests). */
interface InverterSource {
    fun hasSession(): Boolean
    fun establishSession(): Boolean
    fun readSnapshot(): InverterSnapshot?
    fun heartbeat(): Boolean
}

class InverterConnection(
    private val dongleIp: String,
    private val dongleUdpPort: Int,
    private val tcpPort: Int,
    private val modbusSlave: Int,
    private val probeAttempts: Int = 5,
    private val probeWaitMs: Int = 6_000,
    private val readTimeoutMs: Int = 5_000,
) : InverterSource {
    private val log = LoggerFactory.getLogger(InverterConnection::class.java)
    private val lock = ReentrantLock()
    private var serverSocket: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    private var tid = 0
    private var devcode = EybondProtocol.DEFAULT_DEVCODE
    private var devaddr = EybondProtocol.DEFAULT_DEVADDR

    override fun hasSession(): Boolean = socket?.let { !it.isClosed } ?: false

    /**
     * Setup sequence (see task.md "Wiring / setup"):
     *  1. probe up to [probeAttempts] times — the dongle may still remember us and reconnect by itself;
     *  2. if nothing works, send the UDP pairing command;
     *  3. probe again up to [probeAttempts] times.
     * Returns true when a working session (heartbeat + register read OK) exists.
     */
    override fun establishSession(): Boolean = lock.withLock {
        ensureServerSocket()
        if (probe("initial")) return true

        val serverIp = detectServerIp()
        if (serverIp == null) {
            log.error("Could not detect own LAN IP to pair the dongle at {}", dongleIp)
            return false
        }
        sendPairing(serverIp)

        probe("after pairing")
    }

    override fun readSnapshot(): InverterSnapshot? = lock.withLock {
        if (!hasSession()) return null
        try {
            val regs = readRegistersLocked(InverterSnapshot.START_REGISTER, InverterSnapshot.REGISTER_COUNT)
            InverterSnapshot.fromRegisters(regs)
        } catch (e: Exception) {
            log.warn("Inverter register read failed: {} — dropping session", e.message)
            closeSessionLocked()
            null
        }
    }

    override fun heartbeat(): Boolean = lock.withLock {
        if (!hasSession()) return false
        try {
            heartbeatLocked()
            true
        } catch (e: Exception) {
            log.warn("Inverter heartbeat failed: {} — dropping session", e.message)
            closeSessionLocked()
            false
        }
    }

    fun close() = lock.withLock {
        closeSessionLocked()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    // ---------------------------------------------------------------- internals (lock held)

    private fun ensureServerSocket() {
        if (serverSocket?.isClosed == false) return
        serverSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(tcpPort))
        }
        log.info("Inverter TCP server listening on :{}", tcpPort)
    }

    private fun probe(phase: String): Boolean {
        repeat(probeAttempts) { i ->
            val attempt = i + 1
            if (!hasSession()) {
                if (!acceptLocked()) {
                    log.info("Inverter probe {} ({}/{}): dongle did not connect within {} ms", phase, attempt, probeAttempts, probeWaitMs)
                    return@repeat
                }
            }
            try {
                val pn = heartbeatLocked()
                readRegistersLocked(InverterSnapshot.START_REGISTER, InverterSnapshot.REGISTER_COUNT)
                log.info("Inverter session established ({}, attempt {}), datalogger PN prefix {}", phase, attempt, pn.take(6))
                return true
            } catch (e: Exception) {
                log.info("Inverter probe {} ({}/{}) failed: {}", phase, attempt, probeAttempts, e.message)
                closeSessionLocked()
            }
        }
        return false
    }

    private fun acceptLocked(): Boolean {
        val server = serverSocket ?: return false
        return try {
            server.soTimeout = probeWaitMs
            val s = server.accept()
            s.soTimeout = readTimeoutMs
            s.tcpNoDelay = true
            s.keepAlive = true
            socket = s
            log.info("Dongle connected from {}", s.remoteSocketAddress)
            true
        } catch (e: SocketTimeoutException) {
            false
        }
    }

    /** Returns the datalogger PN reported by the dongle. */
    private fun heartbeatLocked(): String {
        val reply = exchange(EybondProtocol.FCODE_HEARTBEAT, EybondProtocol.heartbeatPayload(LocalDateTime.now()))
        devcode = reply.devcode
        devaddr = reply.devaddr
        return String(reply.payload, Charsets.US_ASCII).filter { it.code in 32..126 }
    }

    private fun readRegistersLocked(start: Int, count: Int): IntArray {
        val reply = exchange(EybondProtocol.FCODE_FORWARD, EybondProtocol.modbusReadRequest(modbusSlave, start, count))
        return EybondProtocol.parseModbusReadResponse(reply.payload, modbusSlave, count)
    }

    private fun exchange(fcode: Int, payload: ByteArray): EybondProtocol.Frame {
        val s = socket ?: throw IOException("No inverter session")
        tid = (tid % 0xFFFF) + 1
        val myTid = tid
        val out = s.getOutputStream()
        out.write(EybondProtocol.encodeFrame(myTid, devcode, devaddr, fcode, payload))
        out.flush()
        val input = s.getInputStream()
        // Skip stale / unsolicited frames (e.g. a late reply to a timed-out request).
        repeat(5) {
            val frame = EybondProtocol.readFrame(input)
            if (frame.tid == myTid && frame.fcode == fcode) return frame
            log.debug("Skipping frame tid={} fcode={} (waiting for tid={})", frame.tid, frame.fcode, myTid)
        }
        throw IOException("No matching response for tid=$myTid")
    }

    private fun closeSessionLocked() {
        socket?.let { runCatching { it.close() } }
        socket = null
    }

    private fun sendPairing(serverIp: String) {
        val msg = "set>server=$serverIp:$tcpPort;".toByteArray(Charsets.US_ASCII)
        try {
            DatagramSocket().use { udp ->
                udp.soTimeout = 3_000
                udp.send(DatagramPacket(msg, msg.size, InetAddress.getByName(dongleIp), dongleUdpPort))
                log.info("Pairing command sent to {}:{}: {}", dongleIp, dongleUdpPort, String(msg))
                val buf = ByteArray(128)
                val packet = DatagramPacket(buf, buf.size)
                try {
                    udp.receive(packet)
                    log.info("Dongle pairing reply: {}", String(buf, 0, packet.length, Charsets.US_ASCII))
                } catch (e: SocketTimeoutException) {
                    log.info("No UDP pairing reply (this is normal, continuing)")
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to send pairing command: {}", e.message)
        }
    }

    /** The local IPv4 address the OS would use to reach the dongle (no packet is sent). */
    private fun detectServerIp(): String? {
        val routed = runCatching {
            DatagramSocket().use { udp ->
                udp.connect(InetAddress.getByName(dongleIp), dongleUdpPort)
                udp.localAddress
            }
        }.getOrNull()
        if (routed is Inet4Address && !routed.isAnyLocalAddress && !routed.isLoopbackAddress) {
            return routed.hostAddress
        }
        // Fallback: first site-local IPv4 on an active interface.
        return NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
            .also { log.info("Server IP detected via interface scan: {}", it) }
    }
}
