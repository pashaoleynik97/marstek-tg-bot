package com.mthkr.forecast

import com.mthkr.config.ForecastConfig
import com.mthkr.db.BatterySample
import com.mthkr.db.BatterySampleRepository
import com.mthkr.inverter.InverterSnapshot
import com.mthkr.monitor.GridState
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ZONE = ZoneId.of("Europe/Kyiv")
private const val MIN = 60_000L
private const val HOUR = 3_600_000L

private fun at(day: Int, hour: Int, minute: Int = 0) =
    ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli()

private fun sample(ts: Long, soc: Int, load: Int, pv: Int = 0) = BatterySample(ts, soc, load, pv, 52.0, -10.0)

private fun snap(soc: Int, load: Int, pv: Int = 0) = InverterSnapshot(
    modeCode = 3, gridVoltage = 0.0, gridFrequency = 0.0, gridPower = 0,
    outputVoltage = 230.0, outputCurrent = 1.0, outputPower = load, outputApparentPower = load,
    batteryVoltage = 52.0, pvVoltage = 0.0, pvPower = pv, pvChargingPower = 0,
    inverterTemp = 25, dcTemp = 25, batterySoc = soc, batteryCurrent = 0.0, timestamp = 0,
)

private fun repo() = BatterySampleRepository(Files.createTempDirectory("fc").resolve("t.db").toString())

class ForecastCalculatorTest {
    @Test
    fun `constant load estimate`() {
        // 10 kWh battery → 100 Wh/%; 60 % → 12 % = 4800 Wh at 1000 W = 4h 48m
        val energy = ForecastCalculator.availableWh(60, 12, 100.0)
        assertEquals(4800.0, energy)
        assertEquals(Estimate((4.8 * HOUR).toLong(), false), ForecastCalculator.constantLoad(energy, 1000.0))
        assertNull(ForecastCalculator.constantLoad(energy, 0.0))
        assertEquals(Estimate(72 * HOUR, true), ForecastCalculator.constantLoad(energy, 10.0))
    }

    @Test
    fun `drain subtracts pv and never goes negative`() {
        assertEquals(700, sample(0, 50, 1000, 300).drainW)
        assertEquals(0, sample(0, 50, 200, 500).drainW)
    }

    @Test
    fun `learns wh per percent from continuous samples`() {
        // 1000 W for 2 h = 2000 Wh, SoC 80 → 55 (25 %) → 80 Wh/%
        val start = at(5, 18)
        val samples = (0..24).map { i -> sample(start + i * 5 * MIN, 80 - (i * 25 / 24), 1000) }
        val learned = ForecastCalculator.learnWhPerPercent(samples, 10 * MIN, 100.0)!!
        assertEquals(80.0, learned, 0.01)
    }

    @Test
    fun `learning ignores gaps, small drops and implausible values`() {
        val start = at(5, 18)
        // Small drop only
        val small = (0..12).map { i -> sample(start + i * 5 * MIN, 80 - i / 4, 500) }
        assertNull(ForecastCalculator.learnWhPerPercent(small, 10 * MIN, 100.0))
        // Big SoC jump across a gap is not counted
        val gapped = listOf(sample(start, 80, 500), sample(start + 5 * HOUR, 40, 500))
        assertNull(ForecastCalculator.learnWhPerPercent(gapped, 10 * MIN, 100.0))
        // 20 % drop with almost no energy → 5 Wh/% → rejected
        val bogus = (0..20).map { i -> sample(start + i * 5 * MIN, 80 - i, 60) }
        assertNull(ForecastCalculator.learnWhPerPercent(bogus, 10 * MIN, 100.0))
    }

    @Test
    fun `hourly profile uses median and falls back for sparse hours`() {
        val samples = listOf(
            sample(at(5, 20, 0), 80, 400), sample(at(5, 20, 5), 80, 500), sample(at(5, 20, 10), 80, 3000),
            sample(at(5, 21, 0), 80, 100),
        )
        val p = ForecastCalculator.hourlyProfile(samples, ZONE)!!
        assertEquals(500.0, p[20])      // median ignores the 3000 W spike
        assertEquals(450.0, p[3])       // overall median of 100, 400, 500, 3000
        assertEquals(450.0, p[21])      // single sample → fallback
        assertNull(ForecastCalculator.hourlyProfile(emptyList(), ZONE))
    }

    @Test
    fun `simulation with constant profile equals constant load`() {
        val profile = DoubleArray(24) { 1000.0 }
        val e = ForecastCalculator.simulate(at(6, 12), 4800.0, 1000.0, profile, ZONE)
        assertEquals(Estimate((4.8 * HOUR).toLong(), false), e)
    }

    @Test
    fun `simulation follows lower night profile`() {
        // Evening 1000 W until 23:00, then 200 W at night.
        val profile = DoubleArray(24) { h -> if (h in 0..6 || h == 23) 200.0 else 1000.0 }
        // From 20:00 with 4000 Wh: 3 h × 1000 = 3000 Wh (blend from 1000 → 1000 is flat), then 1000 Wh / 200 W = 5 h
        val e = ForecastCalculator.simulate(at(6, 20), 4000.0, 1000.0, profile, ZONE)
        assertEquals(8 * HOUR, e.timeLeftMs)
    }

    @Test
    fun `simulation blends recent load into profile`() {
        val profile = DoubleArray(24) { 500.0 }
        val blended = ForecastCalculator.simulate(at(6, 12), 3000.0, 2000.0, profile, ZONE)
        val pure = ForecastCalculator.simulate(at(6, 12), 3000.0, 500.0, profile, ZONE)
        assertTrue(blended.timeLeftMs < pure.timeLeftMs)
        assertEquals(6 * HOUR, pure.timeLeftMs)
    }

    @Test
    fun `simulation caps at horizon when no drain`() {
        val e = ForecastCalculator.simulate(at(6, 12), 1000.0, 0.0, DoubleArray(24), ZONE)
        assertEquals(Estimate(72 * HOUR, true), e)
    }

    @Test
    fun `days of data counts local dates`() {
        val samples = listOf(sample(at(5, 23, 50), 50, 1), sample(at(6, 0, 10), 50, 1), sample(at(6, 5), 50, 1))
        assertEquals(2, ForecastCalculator.daysOfData(samples, ZONE))
    }
}

class BatterySampleStorageTest {
    @Test
    fun `repository inserts, queries and prunes`() {
        val r = repo()
        r.insert(sample(1_000, 80, 500, 100))
        r.insert(sample(2_000, 79, 600))
        r.insert(sample(3_000, 78, 700))
        assertEquals(listOf(2_000L, 3_000L), r.findSince(2_000).map { it.ts })
        assertEquals(sample(1_000, 80, 500, 100), r.findSince(0).first())
        assertEquals(1, r.deleteOlderThan(2_000))
        assertEquals(2, r.findSince(0).size)
    }

    @Test
    fun `recorder records only on battery, respects interval and retention`() {
        val r = repo()
        val rec = BatterySampleRecorder(r, intervalMs = 5 * MIN, retentionMs = 7 * 24 * HOUR)
        val t0 = at(1, 12)
        rec.onSnapshot(snap(80, 500), GridState.CONNECTED, t0)
        rec.onSnapshot(snap(80, 500), null, t0)
        assertEquals(0, r.findSince(0).size)

        rec.onSnapshot(snap(80, 500), GridState.DISCONNECTED, t0)
        rec.onSnapshot(snap(80, 500), GridState.DISCONNECTED, t0 + 4 * MIN)
        rec.onSnapshot(snap(79, 500), GridState.DISCONNECTED, t0 + 5 * MIN)
        assertEquals(listOf(t0, t0 + 5 * MIN), r.findSince(0).map { it.ts })

        rec.onSnapshot(snap(70, 500), GridState.DISCONNECTED, t0 + 8 * 24 * HOUR)
        assertEquals(listOf(t0 + 8 * 24 * HOUR), r.findSince(0).map { it.ts })
    }
}

class ForecastServiceTest {
    private val config = ForecastConfig(batteryCapacityWh = 10_000.0, cutoffSoc = 12)

    private fun service(
        r: BatterySampleRepository,
        snapshot: InverterSnapshot?,
        grid: GridState?,
        now: Long,
    ) = ForecastService(r, config, ZONE, { snapshot }, { grid }, { now })

    @Test
    fun `no forecast messages`() {
        val now = at(6, 20)
        assertTrue(service(repo(), null, GridState.DISCONNECTED, now).currentUsage().contains("not reachable"))
        assertEquals("🔌 Connected to grid, no forecast.", service(repo(), snap(80, 500), GridState.CONNECTED, now).currentUsage())
        assertTrue(service(repo(), snap(12, 500), GridState.DISCONNECTED, now).smartForecast().contains("cutoff"))
    }

    @Test
    fun `current usage message`() {
        // 60 % → 12 % = 4800 Wh at 1000 W → 4h 48m, 20:00 + 4:48 = 00:48 next day
        val text = service(repo(), snap(60, 1000), GridState.DISCONNECTED, at(6, 20)).currentUsage()
        assertEquals("Current usage: 1000 W\nEstimated time left: 4h 48m\nLast until: 00:48 (tomorrow)", text)
    }

    @Test
    fun `current usage with solar`() {
        val text = service(repo(), snap(60, 1000, pv = 1200), GridState.DISCONNECTED, at(6, 12)).currentUsage()
        assertTrue(text.contains("Solar: 1200 W (battery drain 0 W)"))
        assertTrue(text.contains("No battery drain"))
    }

    @Test
    fun `smart forecast falls back without history and uses history otherwise`() {
        val r = repo()
        val now = at(6, 20)
        val noHistory = service(r, snap(60, 1000), GridState.DISCONNECTED, now).smartForecast()
        assertTrue(noHistory.startsWith("Smart forecast: no history yet"))

        // Two evenings of 1000 W samples.
        for (day in listOf(4, 5)) for (i in 0 until 36) r.insert(sample(at(day, 18) + i * 5 * MIN, 80, 1000))
        val text = service(r, snap(60, 1000), GridState.DISCONNECTED, now).smartForecast()
        assertEquals("Smart forecast based on 2 days data.\nEstimated time left: 4h 48m\nLast until: 00:48 (tomorrow)", text)
    }
}
