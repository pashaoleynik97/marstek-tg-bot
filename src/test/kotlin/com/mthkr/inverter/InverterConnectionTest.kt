package com.mthkr.inverter

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Emulates the EyBond dongle: waits for `set>server=ip:port;` over UDP, then connects and answers frames. */
private class FakeDongle(private val gridDeciVolts: Int) {
    val udp = DatagramSocket(0)
    @Volatile var pairings = 0
    private val sockets = mutableListOf<Socket>()

    init {
        thread(isDaemon = true) {
            while (!udp.isClosed) {
                val buf = ByteArray(128)
                val p = DatagramPacket(buf, buf.size)
                runCatching { udp.receive(p) }.onFailure { return@thread }
                val msg = String(buf, 0, p.length)
                pairings++
                val reply = "rsp>server=2;".toByteArray()
                udp.send(DatagramPacket(reply, reply.size, p.socketAddress))
                val (ip, port) = msg.removePrefix("set>server=").removeSuffix(";").split(":")
                thread(isDaemon = true) { serve(Socket(ip, port.toInt())) }
            }
        }
    }

    private fun serve(s: Socket) {
        synchronized(sockets) { sockets += s }
        s.use {
            val input = it.getInputStream()
            val out = it.getOutputStream()
            while (true) {
                val f = runCatching { EybondProtocol.readFrame(input) }.getOrNull() ?: return
                val payload = when (f.fcode) {
                    EybondProtocol.FCODE_HEARTBEAT -> "I2000025TESTTEST00".toByteArray()
                    else -> {
                        val count = f.payload[5].toInt()
                        val data = ByteArray(count * 2)
                        data[2] = (gridDeciVolts shr 8).toByte(); data[3] = gridDeciVolts.toByte() // reg 202
                        data[57] = 77 // reg 229 (SOC) low byte
                        val body = byteArrayOf(1, 3, (count * 2).toByte()) + data
                        val crc = EybondProtocol.crc16(body)
                        body + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
                    }
                }
                out.write(EybondProtocol.encodeFrame(f.tid, f.devcode, f.devaddr, f.fcode, payload))
                out.flush()
            }
        }
    }

    fun dropConnections() = synchronized(sockets) { sockets.forEach { runCatching { it.close() } }; sockets.clear() }
    fun close() { udp.close(); dropConnections() }
}

class InverterConnectionTest {
    private val dongle = FakeDongle(gridDeciVolts = 2301)
    private val tcpPort = ServerSocket(0).use { it.localPort }
    private val conn = InverterConnection(
        dongleIp = "127.0.0.1",
        dongleUdpPort = dongle.udp.localPort,
        tcpPort = tcpPort,
        modbusSlave = 1,
        probeAttempts = 2,
        probeWaitMs = 500,
    )

    @AfterTest
    fun tearDown() { conn.close(); dongle.close() }

    @Test
    fun `pairs when dongle does not connect by itself, then reads snapshot`() {
        assertTrue(conn.establishSession())
        assertEquals(1, dongle.pairings)
        val s = assertNotNull(conn.readSnapshot())
        assertEquals(230.1, s.gridVoltage)
        assertEquals(77, s.batterySoc)
        assertTrue(conn.heartbeat())
    }

    @Test
    fun `session loss is detected and recovered`() {
        assertTrue(conn.establishSession())
        dongle.dropConnections()
        Thread.sleep(100)
        conn.readSnapshot() // fails → session dropped
        assertFalse(conn.hasSession())
        assertTrue(conn.establishSession())
        assertNotNull(conn.readSnapshot())
    }
}
