package com.mthkr.monitor

import com.mthkr.db.InverterStateRepository
import com.mthkr.inverter.InverterSnapshot
import com.mthkr.inverter.InverterSource
import com.mthkr.notifications.AlertSink
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RecordingSink : AlertSink {
    val messages = mutableListOf<String>()
    override fun send(message: String) { messages += message }
    override fun sendWithPhoto(message: String, imageResourcePath: String) { messages += message }
}

private class FakeSource(var establishOk: Boolean) : InverterSource {
    var session = false
    var snapshot: InverterSnapshot? = null
    override fun hasSession() = session
    override fun establishSession(): Boolean { session = establishOk; return establishOk }
    override fun readSnapshot() = snapshot
    override fun heartbeat() = true
}

private fun snap(gridV: Double, soc: Int) = InverterSnapshot(
    modeCode = if (gridV > 0) 2 else 3, gridVoltage = gridV, gridFrequency = 50.0, gridPower = 0,
    outputVoltage = 230.0, outputCurrent = 1.0, outputPower = 200, outputApparentPower = 250,
    batteryVoltage = 52.0, pvVoltage = 0.0, pvPower = 0, pvChargingPower = 0,
    inverterTemp = 25, dcTemp = 25, batterySoc = soc, batteryCurrent = 0.0, timestamp = 0,
)

class InverterMonitorTest {
    private fun repo() = InverterStateRepository(Files.createTempDirectory("mon").resolve("t.db").toString())

    @Test
    fun `outage needs two polls, is recorded and notified, then restore`() {
        val repo = repo()
        val sink = RecordingSink()
        var now = 1_000_000L
        val m = InverterMonitor(FakeSource(true), sink, repo, 10, 170.0) { now }

        m.onSnapshot(snap(230.0, 80), now)        // first ever → recorded silently after confirm
        now += 10_000; m.onSnapshot(snap(230.0, 80), now)
        assertEquals(GridState.CONNECTED, m.getGridState())
        assertTrue(sink.messages.isEmpty())

        now += 10_000; m.onSnapshot(snap(230.0, 80), now)
        now += 10_000; m.onSnapshot(snap(0.0, 80), now)   // candidate
        val outageStart = now
        assertEquals(GridState.CONNECTED, m.getGridState())
        now += 10_000; m.onSnapshot(snap(0.0, 79), now)   // confirmed
        assertEquals(GridState.DISCONNECTED, m.getGridState())
        assertEquals(1, sink.messages.size)
        assertTrue(sink.messages[0].startsWith("⚡ POWER OUTAGE DETECTED"))

        now += 10_000; m.onSnapshot(snap(0.0, 69), now)   // crossed 70 → threshold alert
        assertTrue(sink.messages.last().startsWith("🔋 Battery at 60%"), sink.messages.last())

        now += 10_000; m.onSnapshot(snap(231.0, 69), now)
        now += 10_000; m.onSnapshot(snap(231.0, 69), now)
        assertEquals(GridState.CONNECTED, m.getGridState())
        assertTrue(sink.messages.last().startsWith("✅ GRID RESTORED"))
        assertTrue(sink.messages.last().contains("Outage was for"))

        val all = repo.findOverlapping(0, Long.MAX_VALUE)
        assertEquals(listOf(GridState.CONNECTED, GridState.DISCONNECTED, GridState.CONNECTED), all.map { it.state })
        assertEquals(outageStart, all[1].startTs)
        assertEquals(outageStart, all[0].endTs) // previous interval closed at transition point
    }

    @Test
    fun `single glitch poll does not trigger transition`() {
        val sink = RecordingSink()
        var now = 0L
        val m = InverterMonitor(FakeSource(true), sink, repo(), 10, 170.0) { now }
        repeat(3) { now += 10_000; m.onSnapshot(snap(230.0, 80), now) }
        now += 10_000; m.onSnapshot(snap(0.0, 80), now)
        now += 10_000; m.onSnapshot(snap(230.0, 80), now)
        assertEquals(GridState.CONNECTED, m.getGridState())
        assertTrue(sink.messages.isEmpty())
    }

    @Test
    fun `restart detects change that happened while offline`() {
        val repo = repo()
        repo.insert(GridState.CONNECTED, 0, 1_000_000, 100)
        val sink = RecordingSink()
        var now = 5_000_000L
        val m = InverterMonitor(FakeSource(true), sink, repo, 10, 170.0) { now }
        m.onSnapshot(snap(0.0, 60), now)
        now += 10_000; m.onSnapshot(snap(0.0, 60), now)
        assertEquals(1, sink.messages.size)
        assertTrue(sink.messages[0].startsWith("⚡ POWER OUTAGE DETECTED"))
        // old interval not stretched across downtime
        assertEquals(1_000_000, repo.findOverlapping(0, Long.MAX_VALUE).first().endTs)
    }

    @Test
    fun `connection lost and restored alerts are sent once`() {
        val sink = RecordingSink()
        val source = FakeSource(false)
        val m = InverterMonitor(source, sink, repo(), 10, 170.0)
        m.cycle(); m.cycle()
        assertEquals(1, sink.messages.size)
        assertTrue(sink.messages[0].startsWith("⚠️ INVERTER NOT RESPONDING"))
        source.establishOk = true
        source.snapshot = snap(230.0, 90)
        m.cycle()
        assertEquals("✅ Inverter connection restored", sink.messages[1])
        m.cycle()
        assertEquals(2, sink.messages.size)
    }
}
