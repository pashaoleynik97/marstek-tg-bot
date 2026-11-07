package com.mthkr.stats

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Synthetic [StatsReport]s for infographic tests (no DB involved). */
object InfographicSamples {
    val zone: ZoneId = ZoneId.of("Europe/Kyiv")
    val today: LocalDate = LocalDate.of(2026, 11, 7)
    val now: Long = ms(today, "11:20")

    fun ms(date: LocalDate, hhmm: String): Long =
        date.atTime(LocalTime.parse(hhmm)).atZone(zone).toInstant().toEpochMilli()

    /** Builds a day from alternating boundaries, e.g. day(d, OUTAGE, "00:00", "03:20", "06:08", "now"). */
    fun day(date: LocalDate, first: SegmentKind, vararg bounds: String): DayTimeline {
        val dayStart = ms(date, "00:00")
        val dayEnd = ms(date.plusDays(1), "00:00")
        fun t(s: String) = when (s) {
            "now" -> now
            "24:00" -> dayEnd
            else -> ms(date, s)
        }
        var kind = first
        val segments = bounds.toList().zipWithNext().map { (a, b) ->
            Segment(kind, t(a), t(b)).also {
                kind = if (kind == SegmentKind.OUTAGE) SegmentKind.PRESENCE else SegmentKind.OUTAGE
            }
        }
        return DayTimeline(date, dayStart, dayEnd, segments)
    }

    fun todayReport() = StatsReport(
        StatsPeriod.TODAY, today, now, zone,
        listOf(day(today, SegmentKind.OUTAGE, "00:00", "03:20", "06:08", "now")),
    )

    fun weekDays(): List<DayTimeline> {
        val d = { n: Long -> today.minusDays(n) }
        val withNoData = day(d(2), SegmentKind.PRESENCE, "00:00", "08:00", "10:00").let {
            it.copy(segments = it.segments + Segment(SegmentKind.NO_DATA, ms(d(2), "10:00"), ms(d(2), "14:30")) +
                Segment(SegmentKind.PRESENCE, ms(d(2), "14:30"), ms(d(1), "00:00")))
        }
        return listOf(
            // 4 days ago: data starts at 23:13 (rows before are empty/grey)
            DayTimeline(d(4), ms(d(4), "00:00"), ms(d(3), "00:00"),
                listOf(Segment(SegmentKind.OUTAGE, ms(d(4), "23:13"), ms(d(3), "00:00")))),
            day(d(3), SegmentKind.OUTAGE, "00:00", "00:09", "00:11", "01:39", "02:15", "02:18", "07:07", "07:09",
                "10:30", "10:36", "11:10", "18:50", "22:12", "24:00"),
            withNoData,
            day(d(1), SegmentKind.PRESENCE, "00:00", "04:00", "08:00", "16:00", "20:00", "24:00"),
            day(today, SegmentKind.OUTAGE, "00:00", "03:20", "06:08", "now"),
        )
    }

    fun weekReport() = StatsReport(StatsPeriod.WEEK, today, now, zone, weekDays())

    fun monthReport() = StatsReport(StatsPeriod.MONTH, today, now, zone, weekDays())

    fun emptyReport(period: StatsPeriod) = StatsReport(period, today, now, zone, emptyList())
}
