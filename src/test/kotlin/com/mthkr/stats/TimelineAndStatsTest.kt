package com.mthkr.stats

import com.mthkr.db.GridInterval
import com.mthkr.db.InverterStateRepository
import com.mthkr.monitor.GridState
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MIN = 60_000L
private const val GAP = 60_000L

class TimelineTest {
    private fun iv(id: Long, state: GridState, start: Long, end: Long) = GridInterval(id, state, start, end, null)

    @Test
    fun `contiguous intervals with small holes merge and big holes become no data`() {
        val intervals = listOf(
            iv(1, GridState.CONNECTED, 0, 60 * MIN),
            iv(2, GridState.DISCONNECTED, 60 * MIN + 10_000, 120 * MIN), // 10s hole → continuous
            iv(3, GridState.DISCONNECTED, 180 * MIN, 240 * MIN),          // 60 min hole → no data
        )
        val t = buildTimeline(intervals, 0, 240 * MIN, now = 10_000 * MIN, gapMs = GAP)
        assertEquals(
            listOf(
                Segment(SegmentKind.PRESENCE, 0, 60 * MIN + 10_000),
                Segment(SegmentKind.OUTAGE, 60 * MIN + 10_000, 120 * MIN),
                Segment(SegmentKind.NO_DATA, 120 * MIN, 180 * MIN),
                Segment(SegmentKind.OUTAGE, 180 * MIN, 240 * MIN),
            ),
            t
        )
    }

    @Test
    fun `last interval extends to now and window is clipped`() {
        val intervals = listOf(iv(1, GridState.DISCONNECTED, 0, 100 * MIN - 5_000))
        val t = buildTimeline(intervals, 30 * MIN, 100 * MIN, now = 100 * MIN, gapMs = GAP)
        assertEquals(listOf(Segment(SegmentKind.OUTAGE, 30 * MIN, 100 * MIN)), t)
    }

    @Test
    fun `tiny leading no-data is absorbed`() {
        val intervals = listOf(iv(1, GridState.CONNECTED, 20_000, 50 * MIN))
        val t = buildTimeline(intervals, 0, 50 * MIN, now = 50 * MIN, gapMs = GAP)
        assertEquals(listOf(Segment(SegmentKind.PRESENCE, 0, 50 * MIN)), t)
    }

    @Test
    fun `empty data yields single no-data segment`() {
        val t = buildTimeline(emptyList(), 0, 10 * MIN, now = 10 * MIN, gapMs = GAP)
        assertEquals(listOf(Segment(SegmentKind.NO_DATA, 0, 10 * MIN)), t)
    }

    @Test
    fun `hours minutes format`() {
        assertEquals("1 hours 45 minutes", formatHoursMinutes(105 * MIN + 30_000))
        assertEquals("0 hours 0 minutes", formatHoursMinutes(0))
        assertEquals("3h 20m", formatShort(200 * MIN + 59_000))
        assertEquals("0h 2m", formatShort(2 * MIN))
    }

    @Test
    fun `percentages sum to 100`() {
        assertEquals(57 to 43, percentages(4 * MIN, 3 * MIN))
        assertEquals(0 to 100, percentages(0, 5 * MIN))
        assertEquals(null, percentages(0, 0))
    }
}

class StatsServiceTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun repo(): InverterStateRepository {
        val dir = Files.createTempDirectory("stats-test")
        return InverterStateRepository(dir.resolve("t.db").toString())
    }

    @Test
    fun `today statistic and summary`() {
        val r = repo()
        r.insert(GridState.CONNECTED, ts(2026, 11, 5, 0, 0), ts(2026, 11, 5, 12, 0), 100)
        r.insert(GridState.DISCONNECTED, ts(2026, 11, 5, 12, 0), ts(2026, 11, 5, 13, 45), 90)
        r.insert(GridState.CONNECTED, ts(2026, 11, 5, 13, 45), ts(2026, 11, 5, 18, 0), 80)
        val now = ts(2026, 11, 5, 19, 0)
        r.insert(GridState.DISCONNECTED, ts(2026, 11, 5, 18, 0), now - 5_000, 80)

        val s = StatsService(r, zone, GAP) { now }
        assertEquals(
            listOf(
                "There were 2 hours 45 minutes of outages during this day.\n\n" +
                    "⚡ Presence: 00:00-12:00 (12h 0m)\n" +
                    "❌ Outage: 12:00-13:45 (1h 45m)\n" +
                    "⚡ Presence: 13:45-18:00 (4h 15m)\n" +
                    "❌ Outage: 18:00-now (1h 0m)"
            ),
            s.statistic(StatsPeriod.TODAY)
        )
        assertEquals(
            "There were 2 hours 45 minutes of outages during this day. Time without outages: 16 hours 15 minutes.",
            s.summary(StatsPeriod.TODAY)
        )
    }

    @Test
    fun `week and month statistic are newest first, splits at midnight and skips days before first data`() {
        val r = repo()
        // Outage across midnight 3rd → 4th, nothing recorded before 3rd 20:00.
        r.insert(GridState.CONNECTED, ts(2026, 11, 3, 20, 0), ts(2026, 11, 3, 22, 0), 100)
        r.insert(GridState.DISCONNECTED, ts(2026, 11, 3, 22, 0), ts(2026, 11, 4, 2, 0), 90)
        val now = ts(2026, 11, 4, 3, 0)
        r.insert(GridState.CONNECTED, ts(2026, 11, 4, 2, 0), now, 50)

        val s = StatsService(r, zone, GAP) { now }
        val msgs = s.statistic(StatsPeriod.WEEK)
        assertEquals(1, msgs.size)
        assertEquals(
            "There were 4 hours 0 minutes of outages during last 2 days.\n" +
                "Outage: 57%\n" +
                "Presence: 43%\n" +
                "\n" +
                "<b>04.11.2026 Wednesday</b>\n" +
                "<i>2 hours 0 minutes of outages</i>\n" +
                "❌ Outage: 00:00-02:00 (2h 0m)\n" +
                "⚡ Presence: 02:00-now (1h 0m)\n" +
                "----------\n" +
                "\n" +
                "<b>03.11.2026 Tuesday</b>\n" +
                "<i>2 hours 0 minutes of outages</i>\n" +
                "⚡ Presence: 20:00-22:00 (2h 0m)\n" +
                "❌ Outage: 22:00-00:00 (2h 0m)",
            msgs[0]
        )
        assertEquals(
            listOf(
                "Outages from 03-November-2026 to 04-November-2026\n" +
                    "\n" +
                    "<i>Total outages:</i> 4h 0m\n" +
                    "<i>Total presence:</i> 3h 0m\n" +
                    "\n" +
                    "<b>04.11.2026 Wednesday</b>\n" +
                    "❌ Outage: 2h 0m\n" +
                    "⚡ Presence: 1h 0m\n" +
                    "\n" +
                    "<b>03.11.2026 Tuesday</b>\n" +
                    "❌ Outage: 2h 0m\n" +
                    "⚡ Presence: 2h 0m"
            ),
            s.statistic(StatsPeriod.MONTH)
        )
        val summary = s.summary(StatsPeriod.WEEK)
        assertTrue(summary.startsWith("There were 4 hours 0 minutes of outages during last 7 days. Time without outages: 3 hours 0 minutes."), summary)
    }

    @Test
    fun `gap shows as no data and is reported in summary`() {
        val r = repo()
        r.insert(GridState.CONNECTED, ts(2026, 11, 5, 0, 0), ts(2026, 11, 5, 3, 10), 100)
        val now = ts(2026, 11, 5, 6, 0)
        r.insert(GridState.CONNECTED, ts(2026, 11, 5, 5, 40), now, 100)
        val s = StatsService(r, zone, GAP) { now }
        assertEquals(
            "There were 0 hours 0 minutes of outages during this day.\n\n" +
                "⚡ Presence: 00:00-03:10 (3h 10m)\n" +
                "❔ No data: 03:10-05:40 (2h 30m)\n" +
                "⚡ Presence: 05:40-now (0h 20m)",
            s.statistic(StatsPeriod.TODAY).single()
        )
        assertTrue(s.summary(StatsPeriod.TODAY).endsWith("❔ No data: 2 hours 30 minutes."))
    }
}
