package com.mthkr.forecast

import com.mthkr.bot.formatInverterSection
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ZONE = ZoneId.of("Europe/Kyiv")
private const val MIN = 60_000L
private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

private fun at(day: Int, hour: Int, minute: Int = 0) =
    ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli()

/** Sample drawing [dcW] from a 50 V battery (current = −dcW / 50). */
private fun sample(ts: Long, soc: Int, dcW: Double, load: Int = dcW.toInt()) =
    BatterySample(ts, soc, load, 0, 50.0, -dcW / 50.0)

private fun snap(soc: Int, load: Int, batteryA: Double, gridV: Double = 0.0, batteryV: Double = 50.0) = InverterSnapshot(
    modeCode = 3, gridVoltage = gridV, gridFrequency = 0.0, gridPower = 0,
    outputVoltage = 230.0, outputCurrent = 1.0, outputPower = load, outputApparentPower = load,
    batteryVoltage = batteryV, pvVoltage = 0.0, pvPower = 0, pvChargingPower = 0,
    inverterTemp = 25, dcTemp = 25, batterySoc = soc, batteryCurrent = batteryA, timestamp = 0,
)

private fun repo() = BatterySampleRepository(Files.createTempDirectory("fc").resolve("t.db").toString())

class ForecastCalculatorTest {
    @Test
    fun `dc discharge power and validity`() {
        val s = BatterySample(0, 50, 65535, 0, 53.2, -6.3)
        assertEquals(335.16, s.dischargeW, 0.001)
        assertNull(s.validLoadW)
        assertTrue(s.isDischarging)
        val charging = BatterySample(0, 50, 1, 0, 54.4, 95.2)
        assertEquals(0.0, charging.dischargeW)
        assertEquals(1, charging.validLoadW)
        val live = snap(80, 65535, -2.0)
        assertNull(live.validOutputPower)
        assertEquals(100.0, live.batteryDischargeW)
    }

    @Test
    fun `constant load estimate`() {
        val energy = ForecastCalculator.availableWh(60, 12, 100.0)
        assertEquals(4800.0, energy)
        assertEquals(Estimate((4.8 * HOUR).toLong(), false, 1000.0), ForecastCalculator.constantLoad(energy, 1000.0))
        assertNull(ForecastCalculator.constantLoad(energy, 0.0))
        assertTrue(ForecastCalculator.constantLoad(energy, 10.0)!!.capped)
    }

    @Test
    fun `learns wh per percent from discharging pairs only`() {
        val now = at(6, 12)
        // 1000 W for 2 h = 2000 Wh over a 25 % drop → 80 Wh/%
        val start = at(6, 8)
        val samples = (0..24).map { i -> sample(start + i * 5 * MIN, 80 - (i * 25 / 24), 1000.0) }.toMutableList()
        // a charging sample in the middle of a gap must not break or inflate the result
        samples += BatterySample(start + 3 * HOUR, 99, 1, 0, 54.4, 95.2)
        val learned = ForecastCalculator.learnWhPerPercent(samples, now, 30 * DAY, 30 * DAY, 10 * MIN, 100.0)
        assertEquals(80.0, learned!!, 0.01)
    }

    @Test
    fun `learning ignores gaps, small drops, old data and implausible values`() {
        val now = at(6, 12)
        val start = at(6, 8)
        val small = (0..12).map { i -> sample(start + i * 5 * MIN, 80 - i / 4, 500.0) }
        assertNull(ForecastCalculator.learnWhPerPercent(small, now, 30 * DAY, 30 * DAY, 10 * MIN, 100.0))
        val gapped = listOf(sample(start, 80, 500.0), sample(start + 5 * HOUR, 40, 500.0))
        assertNull(ForecastCalculator.learnWhPerPercent(gapped, now, 30 * DAY, 30 * DAY, 10 * MIN, 100.0))
        val bogus = (0..20).map { i -> sample(start + i * 5 * MIN, 80 - i, 60.0) }
        assertNull(ForecastCalculator.learnWhPerPercent(bogus, now, 30 * DAY, 30 * DAY, 10 * MIN, 100.0))
        // valid data, but outside the capacity window
        val old = (0..24).map { i -> sample(at(1, 8) + i * 5 * MIN, 80 - (i * 25 / 24), 1000.0) }
        assertNull(ForecastCalculator.learnWhPerPercent(old, now, 2 * DAY, 30 * DAY, 10 * MIN, 100.0))
    }

    @Test
    fun `recent capacity data outweighs old data`() {
        val now = at(30, 12)
        fun run(day: Int, wPerPct: Double) =
            (0..20).map { i -> sample(at(day, 8) + i * 5 * MIN, 80 - i / 2, wPerPct * 0.5 * 12) }
        // ~ old: 120 Wh/%, recent: 80 Wh/%; with a 3-day half-life recent dominates
        val samples = run(5, 120.0) + run(29, 80.0)
        val learned = ForecastCalculator.learnWhPerPercent(samples, now, 30 * DAY, 3 * DAY, 10 * MIN, 100.0)!!
        assertTrue(learned < 82.0, "learned=$learned")
    }

    @Test
    fun `hourly profile is weighted mean shrunk toward overall`() {
        val now = at(6, 23)
        val samples = listOf(
            sample(at(6, 20, 0), 80, 400.0), sample(at(6, 20, 5), 80, 500.0), sample(at(6, 20, 10), 80, 3000.0),
            sample(at(6, 21, 0), 80, 100.0),
            BatterySample(at(6, 22), 80, 65535, 0, 54.0, 50.0), // charging → ignored
        )
        val p = ForecastCalculator.hourlyProfile(samples, now, 1000 * DAY, ZONE)!! // ~no recency effect
        val overall = (400 + 500 + 3000 + 100) / 4.0
        assertEquals(overall, p[3], 0.5)                               // no data → overall mean
        assertEquals((3900 + 6 * overall) / 9, p[20], 0.5)            // mean (spike counts), shrunk
        assertEquals((100 + 6 * overall) / 7, p[21], 0.5)
        assertNull(ForecastCalculator.hourlyProfile(emptyList(), now, 7 * DAY, ZONE))
    }

    @Test
    fun `hourly profile prefers recent days`() {
        val now = at(30, 12)
        val old = (0 until 12).map { i -> sample(at(2, 20) + i * 5 * MIN, 80, 1000.0) }
        val recent = (0 until 12).map { i -> sample(at(29, 20) + i * 5 * MIN, 80, 200.0) }
        val p = ForecastCalculator.hourlyProfile(old + recent, now, 7 * DAY, ZONE)!!
        assertTrue(p[20] < 300.0, "p20=${p[20]}")
    }

    @Test
    fun `simulation with constant profile equals constant load`() {
        val profile = DoubleArray(24) { 1000.0 }
        val e = ForecastCalculator.simulate(at(6, 12), 4800.0, 1000.0, profile, ZONE)
        assertEquals((4.8 * HOUR).toLong(), e.timeLeftMs)
        assertEquals(1000.0, e.avgW, 0.01)
    }

    @Test
    fun `simulation follows lower night profile`() {
        val profile = DoubleArray(24) { h -> if (h in 0..6 || h == 23) 200.0 else 1000.0 }
        val e = ForecastCalculator.simulate(at(6, 20), 4000.0, 1000.0, profile, ZONE)
        assertEquals(8 * HOUR, e.timeLeftMs)
        assertEquals(500.0, e.avgW, 0.01)
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
        assertEquals(Estimate(72 * HOUR, true, 0.0), e)
    }

    @Test
    fun `days of data counts local dates of clean samples`() {
        val samples = listOf(
            sample(at(5, 23, 50), 50, 1.0), sample(at(6, 0, 10), 50, 1.0),
            BatterySample(at(7, 5), 50, 1, 0, 54.0, 10.0), // charging
        )
        assertEquals(2, ForecastCalculator.daysOfData(samples, ZONE))
    }
}

class BatterySampleStorageTest {
    @Test
    fun `repository inserts, queries and prunes`() {
        val r = repo()
        r.insert(sample(1_000, 80, 500.0))
        r.insert(sample(2_000, 79, 600.0))
        r.insert(sample(3_000, 78, 700.0))
        assertEquals(listOf(2_000L, 3_000L), r.findSince(2_000).map { it.ts })
        assertEquals(sample(1_000, 80, 500.0), r.findSince(0).first())
        assertEquals(1, r.deleteOlderThan(2_000))
        assertEquals(2, r.findSince(0).size)
    }

    private fun recorder(r: BatterySampleRepository) =
        BatterySampleRecorder(r, intervalMs = 5 * MIN, retentionMs = 90 * DAY, gridVoltageThreshold = 170.0)

    @Test
    fun `recorder records only on battery while discharging, respects interval and retention`() {
        val r = repo()
        val rec = recorder(r)
        val t0 = at(1, 12)
        rec.onSnapshot(snap(80, 500, -10.0), GridState.CONNECTED, t0)
        rec.onSnapshot(snap(80, 500, -10.0), null, t0)
        rec.onSnapshot(snap(80, 1, 95.0), GridState.DISCONNECTED, t0)                // charging
        rec.onSnapshot(snap(80, 500, -10.0, gridV = 230.0), GridState.DISCONNECTED, t0) // restore debounce
        assertEquals(0, r.findSince(0).size)

        rec.onSnapshot(snap(80, 500, -10.0), GridState.DISCONNECTED, t0)
        rec.onSnapshot(snap(80, 500, -10.0), GridState.DISCONNECTED, t0 + 4 * MIN)
        rec.onSnapshot(snap(79, 65535, -10.0), GridState.DISCONNECTED, t0 + 5 * MIN)   // invalid load still recorded
        assertEquals(listOf(t0, t0 + 5 * MIN), r.findSince(0).map { it.ts })
        assertNull(r.findSince(0).last().validLoadW)

        rec.onSnapshot(snap(70, 500, -10.0), GridState.DISCONNECTED, t0 + 91 * DAY)
        assertEquals(listOf(t0 + 91 * DAY), r.findSince(0).map { it.ts })
    }
}

class ForecastServiceTest {
    private val config = ForecastConfig(batteryCapacityWh = 10_000.0, cutoffSoc = 12)

    private fun service(r: BatterySampleRepository, snapshot: InverterSnapshot?, grid: GridState?, now: Long) =
        ForecastService(r, config, ZONE, { snapshot }, { grid }, { now })

    @Test
    fun `no forecast messages`() {
        val now = at(6, 20)
        assertTrue(service(repo(), null, GridState.DISCONNECTED, now).currentUsage().contains("not reachable"))
        // On grid the inverter reports 65535 W — must still say "Connected to grid"
        val onGrid = snap(99, 65535, -0.7, gridV = 230.0)
        assertEquals("🔌 Connected to grid, no forecast.", service(repo(), onGrid, GridState.CONNECTED, now).currentUsage())
        assertEquals("🔌 Connected to grid, no forecast.", service(repo(), onGrid, GridState.CONNECTED, now).smartForecast())
        assertTrue(service(repo(), snap(12, 500, -10.0), GridState.DISCONNECTED, now).smartForecast().contains("cutoff"))
    }

    @Test
    fun `status shows n-a for invalid output power`() {
        val text = formatInverterSection(snap(99, 65535, -0.7, gridV = 230.0))
        assertTrue(text.contains("Output: 230.0 V, 1.0 A, n/a W"), text)
    }

    @Test
    fun `current usage uses dc drain`() {
        // 1000 W DC at 50 V = −20 A; 60 % → 12 % = 4800 Wh (config) → 4h 48m
        val text = service(repo(), snap(60, 900, -20.0), GridState.DISCONNECTED, at(6, 20)).currentUsage()
        assertEquals(
            "Current usage: 900 W\nBattery drain: 1000 W\nEstimated time left: 4h 48m\nLast until: 00:48 (tomorrow)",
            text
        )
        val invalidLoad = service(repo(), snap(60, 65535, -20.0), GridState.DISCONNECTED, at(6, 20)).currentUsage()
        assertTrue(invalidLoad.startsWith("Current usage: n/a\nBattery drain: 1000 W\nEstimated time left: 4h 48m"))
        val notDischarging = service(repo(), snap(60, 300, 2.0), GridState.DISCONNECTED, at(6, 12)).currentUsage()
        assertEquals("Current usage: 300 W\n🌤 Battery is not discharging right now.", notDischarging)
    }

    @Test
    fun `smart forecast falls back without history and uses history otherwise`() {
        val r = repo()
        val now = at(6, 20)
        val noHistory = service(r, snap(60, 900, -20.0), GridState.DISCONNECTED, now).smartForecast()
        assertTrue(noHistory.startsWith("Smart forecast: no history yet"))

        for (day in listOf(4, 5)) for (i in 0 until 36) r.insert(sample(at(day, 18) + i * 5 * MIN, 80, 1000.0))
        val text = service(r, snap(60, 900, -20.0), GridState.DISCONNECTED, now).smartForecast()
        assertEquals(
            "Smart forecast based on 2 days data.\nEstimated time left: 4h 48m\nLast until: 00:48 (tomorrow)\n" +
                "Avg drain 1000 W · 100 Wh/% (config)",
            text
        )
    }

    @Test
    fun `smart forecast still works when battery is not discharging right now`() {
        val r = repo()
        for (i in 0 until 36) r.insert(sample(at(5, 18) + i * 5 * MIN, 80, 1000.0))
        val text = service(r, snap(60, 300, 2.0), GridState.DISCONNECTED, at(6, 12)).smartForecast()
        assertTrue(text.startsWith("Smart forecast based on 1 day data.\nEstimated time left: "), text)
    }
}

/** Regression on real server data (2026-11-08) — see .claude/forecast/review-accuracy.md. */
class ForecastRealDataTest {
    private fun load(): List<BatterySample> =
        javaClass.getResourceAsStream("/forecast/samples-2026-11-08.csv")!!.bufferedReader().readLines().drop(1).map {
            val c = it.split(",")
            BatterySample(c[0].toLong(), c[1].toInt(), c[2].toInt(), c[3].toInt(), c[4].toDouble(), c[5].toDouble())
        }

    @Test
    fun `real data gives realistic forecast`() {
        val now = ZonedDateTime.of(2026, 11, 8, 20, 58, 30, 0, ZONE).toInstant().toEpochMilli()
        val r = repo()
        load().filter { it.ts <= now }.forEach(r::insert)

        val learned = ForecastCalculator.learnWhPerPercent(r.findSince(0), now, 30 * DAY, 30 * DAY, 10 * MIN, 100.0)
        assertNotNull(learned)
        assertEquals(92.0, learned, 3.0)

        // Live at request time: 285 W load, 53.2 V, −5.6 A, SoC 92 %
        val svc = ForecastService(r, ForecastConfig(), ZONE, { snap(92, 285, -5.6, batteryV = 53.2) },
            { GridState.DISCONNECTED }, { now })
        val smart = svc.smartForecast()
        val hours = Regex("Estimated time left: (\\d+)h (\\d+)m").find(smart)!!.destructured
            .let { (h, m) -> h.toInt() + m.toInt() / 60.0 }
        assertEquals(32.6, hours, 1.0, smart)
        assertTrue(smart.contains("(learned)"), smart)
        println(smart)
        println(svc.currentUsage())
    }
}
