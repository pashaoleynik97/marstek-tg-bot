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
                "There were 2 hours 45 minutes of outages during this day.\n" +
                    "⚡ Presence: 00:00-12:00\n" +
                    "❌ Outage 12:00-13:45\n" +
                    "⚡ Presence: 13:45-18:00\n" +
                    "❌ Outage 18:00-now"
            ),
            s.statistic(StatsPeriod.TODAY)
        )
        assertEquals(
            "There were 2 hours 45 minutes of outages during this day. Time without outages: 16 hours 15 minutes.",
            s.summary(StatsPeriod.TODAY)
        )
    }

    @Test
    fun `week statistic is newest first, splits at midnight and skips days before first data`() {
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
            "There were 2 hours 0 minutes of outages during 04.11.2026 Wednesday.\n" +
                "❌ Outage 00:00-02:00\n" +
                "⚡ Presence: 02:00-now\n" +
                "---\n" +
                "There were 2 hours 0 minutes of outages during 03.11.2026 Tuesday.\n" +
                "⚡ Presence: 20:00-22:00\n" +
                "❌ Outage 22:00-00:00",
            msgs[0]
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
            "There were 0 hours 0 minutes of outages during this day.\n" +
                "⚡ Presence: 00:00-03:10\n" +
                "❔ No data 03:10-05:40\n" +
                "⚡ Presence: 05:40-now",
            s.statistic(StatsPeriod.TODAY).single()
        )
        assertTrue(s.summary(StatsPeriod.TODAY).endsWith("❔ No data: 2 hours 30 minutes."))
    }
}
