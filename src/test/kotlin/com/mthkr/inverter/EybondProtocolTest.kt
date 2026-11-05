package com.mthkr.inverter

import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun hex(s: String): ByteArray = s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

class EybondProtocolTest {

    @Test
    fun `modbus read request matches verified exchange`() {
        // From findings: 01 03 00 c9 00 22 15 ed
        assertContentEquals(hex("01 03 00 c9 00 22 15 ed"), EybondProtocol.modbusReadRequest(1, 201, 34))
    }

    @Test
    fun `forward frame matches verified exchange`() {
        val frame = EybondProtocol.encodeFrame(2, 1, 1, EybondProtocol.FCODE_FORWARD, EybondProtocol.modbusReadRequest(1, 201, 34))
        assertContentEquals(hex("00 02 00 01 00 0a 01 04 01 03 00 c9 00 22 15 ed"), frame)
    }

    @Test
    fun `heartbeat frame matches verified exchange`() {
        val payload = EybondProtocol.heartbeatPayload(LocalDateTime.of(2026, 11, 5, 21, 47, 37))
        val frame = EybondProtocol.encodeFrame(1, 1, 1, EybondProtocol.FCODE_HEARTBEAT, payload)
        assertContentEquals(hex("00 01 00 01 00 08 01 01 1a 0b 05 15 2f 25"), frame)
    }

    @Test
    fun `readFrame uses LEN header and leaves following bytes`() {
        val f1 = EybondProtocol.encodeFrame(7, 1, 1, 0x01, "ABC".toByteArray())
        val f2 = EybondProtocol.encodeFrame(8, 1, 1, 0x04, byteArrayOf(9))
        val input = ByteArrayInputStream(f1 + f2)
        val a = EybondProtocol.readFrame(input)
        val b = EybondProtocol.readFrame(input)
        assertEquals(7, a.tid)
        assertEquals("ABC", String(a.payload))
        assertEquals(8, b.tid)
        assertEquals(0x04, b.fcode)
        assertContentEquals(byteArrayOf(9), b.payload)
    }

    @Test
    fun `parse modbus response validates crc and decodes registers`() {
        val body = byteArrayOf(1, 3, 4, 0x08, 0xFC.toByte(), 0xFF.toByte(), 0xC8.toByte())
        val crc = EybondProtocol.crc16(body)
        val rtu = body + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
        val regs = EybondProtocol.parseModbusReadResponse(rtu, 1, 2)
        assertEquals(2300, regs[0])
        assertEquals(0xFFC8, regs[1])

        val broken = rtu.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertFailsWith<IOException> { EybondProtocol.parseModbusReadResponse(broken, 1, 2) }
    }

    @Test
    fun `snapshot decodes scaled and signed values`() {
        val regs = IntArray(34)
        fun set(addr: Int, v: Int) { regs[addr - 201] = v and 0xFFFF }
        set(201, 3); set(202, 0); set(203, 0); set(204, -5)
        set(205, 2302); set(211, 15); set(213, 242); set(214, 345)
        set(215, 526); set(219, 115); set(223, 0); set(224, 0)
        set(226, 23); set(227, 28); set(229, 55); set(232, -56)
        val s = InverterSnapshot.fromRegisters(regs, 0)
        assertEquals(InverterMode.BATTERY, s.mode)
        assertEquals(-5, s.gridPower)
        assertEquals(230.2, s.outputVoltage)
        assertEquals(1.5, s.outputCurrent)
        assertEquals(52.6, s.batteryVoltage)
        assertEquals(55, s.batterySoc)
        assertEquals(-5.6, s.batteryCurrent)
        assertEquals(28, s.dcTemp)
    }
}
