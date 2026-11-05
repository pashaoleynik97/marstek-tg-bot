package com.mthkr.inverter

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.time.LocalDateTime

/**
 * EyBond collector framing + Modbus RTU helpers.
 * See .claude/aninerel/sources-and-findings/anenji-inverter-local-protocol.md
 *
 * Frame (big-endian): TID(2) | DEVCODE(2) | LEN(2) | DEVADDR(1) | FCODE(1) | PAYLOAD(LEN-2)
 */
object EybondProtocol {
    const val FCODE_HEARTBEAT = 0x01
    const val FCODE_FORWARD = 0x04
    const val DEFAULT_DEVCODE = 0x0001
    const val DEFAULT_DEVADDR = 0x01
    private const val HEADER_SIZE = 6
    private const val MAX_LEN = 1024

    class Frame(val tid: Int, val devcode: Int, val devaddr: Int, val fcode: Int, val payload: ByteArray)

    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    /** Modbus RTU "read holding registers" (FC 0x03) request incl. CRC (low byte first). */
    fun modbusReadRequest(slave: Int, start: Int, count: Int): ByteArray {
        val body = byteArrayOf(
            slave.toByte(), 0x03,
            (start shr 8).toByte(), start.toByte(),
            (count shr 8).toByte(), count.toByte(),
        )
        val crc = crc16(body)
        return body + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
    }

    /**
     * Parses a Modbus RTU FC 0x03 response: [slave][0x03][byteCount][data...][crcLo][crcHi].
     * Throws [IOException] on any inconsistency.
     */
    fun parseModbusReadResponse(rtu: ByteArray, expectedSlave: Int, expectedCount: Int): IntArray {
        if (rtu.size < 5) throw IOException("Modbus response too short: ${rtu.size} bytes")
        val fc = rtu[1].toInt() and 0xFF
        if (fc and 0x80 != 0) throw IOException("Modbus exception response, code=${rtu[2].toInt() and 0xFF}")
        if (fc != 0x03) throw IOException("Unexpected Modbus function 0x%02x".format(fc))
        if ((rtu[0].toInt() and 0xFF) != expectedSlave) throw IOException("Unexpected Modbus slave ${rtu[0]}")
        val byteCount = rtu[2].toInt() and 0xFF
        if (byteCount != expectedCount * 2) throw IOException("Unexpected byte count $byteCount (expected ${expectedCount * 2})")
        if (rtu.size < 3 + byteCount + 2) throw IOException("Modbus response truncated")
        val crcCalc = crc16(rtu.copyOfRange(0, 3 + byteCount))
        val crcRecv = (rtu[3 + byteCount].toInt() and 0xFF) or ((rtu[4 + byteCount].toInt() and 0xFF) shl 8)
        if (crcCalc != crcRecv) throw IOException("Modbus CRC mismatch")
        return IntArray(expectedCount) { i ->
            ((rtu[3 + 2 * i].toInt() and 0xFF) shl 8) or (rtu[4 + 2 * i].toInt() and 0xFF)
        }
    }

    fun encodeFrame(tid: Int, devcode: Int, devaddr: Int, fcode: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).apply {
            putShort(tid.toShort())
            putShort(devcode.toShort())
            putShort((payload.size + 2).toShort())
            put(devaddr.toByte())
            put(fcode.toByte())
            put(payload)
        }.array()

    fun heartbeatPayload(now: LocalDateTime): ByteArray = byteArrayOf(
        (now.year - 2000).toByte(), now.monthValue.toByte(), now.dayOfMonth.toByte(),
        now.hour.toByte(), now.minute.toByte(), now.second.toByte(),
    )

    /** Reads exactly one frame using the LEN header field (blocking; honours socket timeout). */
    fun readFrame(input: InputStream): Frame {
        val header = readExactly(input, HEADER_SIZE)
        val bb = ByteBuffer.wrap(header)
        val tid = bb.short.toInt() and 0xFFFF
        val devcode = bb.short.toInt() and 0xFFFF
        val len = bb.short.toInt() and 0xFFFF
        if (len < 2 || len > MAX_LEN) throw IOException("Invalid EyBond frame length $len")
        val rest = readExactly(input, len)
        return Frame(
            tid = tid,
            devcode = devcode,
            devaddr = rest[0].toInt() and 0xFF,
            fcode = rest[1].toInt() and 0xFF,
            payload = rest.copyOfRange(2, rest.size),
        )
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw EOFException("Connection closed by dongle")
            off += r
        }
        return buf
    }
}
