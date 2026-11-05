package com.mthkr.stats

import com.mthkr.db.GridInterval
import com.mthkr.db.InverterStateRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

enum class StatsPeriod(val days: Long, val label: String) {
    TODAY(1, "this day"),
    WEEK(7, "last 7 days"),
    MONTH(30, "last month"),
}

/** Telegram hard limit is 4096 chars; keep a margin. */
private const val MAX_MESSAGE_LENGTH = 3800

class StatsService(
    private val repository: InverterStateRepository,
    private val zone: ZoneId,
    private val gapMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    private data class DayTimeline(val date: LocalDate, val dayEnd: Long, val segments: List<Segment>)

    /** Returns the summary as a single message. */
    fun summary(period: StatsPeriod): String {
        val days = timelines(period)
        if (days.isEmpty()) return "No outage data recorded for ${period.label} yet."
        val all = days.flatMap { it.segments }
        val outage = all.total(SegmentKind.OUTAGE)
        val presence = all.total(SegmentKind.PRESENCE)
        val noData = all.total(SegmentKind.NO_DATA)
        val sb = StringBuilder()
        sb.append("There were ${formatHoursMinutes(outage)} of outages during ${period.label}. ")
        sb.append("Time without outages: ${formatHoursMinutes(presence)}.")
        if (noData >= MIN_NO_DATA_MS) sb.append("\n❔ No data: ${formatHoursMinutes(noData)}.")
        return sb.toString()
    }

    /** Returns the detailed statistic, split into Telegram-sized messages. */
    fun statistic(period: StatsPeriod): List<String> {
        val days = timelines(period)
        if (days.isEmpty()) return listOf("No outage data recorded for ${period.label} yet.")
        val now = clock()

        val blocks = if (period == StatsPeriod.TODAY) {
            listOf(dayBlock(days.single(), period.label, now))
        } else {
            days.sortedByDescending { it.date }.map { day ->
                val dow = day.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                dayBlock(day, "${day.date.format(dateFmt)} $dow", now)
            }
        }
        return chunk(blocks, "\n---\n")
    }

    private fun dayBlock(day: DayTimeline, label: String, now: Long): String {
        val outage = day.segments.total(SegmentKind.OUTAGE)
        val lines = mutableListOf("There were ${formatHoursMinutes(outage)} of outages during $label.")
        for (seg in day.segments) {
            val start = Instant.ofEpochMilli(seg.start).atZone(zone).format(timeFmt)
            val end = when {
                seg.end >= now -> "now"
                seg.end >= day.dayEnd -> "00:00"
                else -> Instant.ofEpochMilli(seg.end).atZone(zone).format(timeFmt)
            }
            lines += when (seg.kind) {
                SegmentKind.OUTAGE -> "❌ Outage $start-$end"
                SegmentKind.PRESENCE -> "⚡ Presence: $start-$end"
                SegmentKind.NO_DATA -> "❔ No data $start-$end"
            }
        }
        return lines.joinToString("\n")
    }

    /** Per-day timelines for the period, oldest first, starting no earlier than the first recorded data. */
    private fun timelines(period: StatsPeriod): List<DayTimeline> {
        val now = clock()
        val firstData = repository.findFirstStart() ?: return emptyList()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val firstDay = today.minusDays(period.days - 1)
        val windowFrom = maxOf(firstDay.atStartOfDay(zone).toInstant().toEpochMilli(), firstData)
        if (windowFrom >= now) return emptyList()

        val intervals: List<GridInterval> = repository.findOverlapping(windowFrom - gapMs, now)
        return generateSequence(firstDay) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .mapNotNull { date ->
                val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val from = maxOf(dayStart, windowFrom)
                val to = minOf(dayEnd, now)
                if (to <= from) null
                else DayTimeline(date, dayEnd, buildTimeline(intervals, from, to, now, gapMs))
            }
            .toList()
    }

    private fun chunk(blocks: List<String>, separator: String): List<String> {
        val messages = mutableListOf<String>()
        val current = StringBuilder()
        for (block in blocks) {
            if (current.isNotEmpty() && current.length + separator.length + block.length > MAX_MESSAGE_LENGTH) {
                messages += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(separator)
            current.append(block)
        }
        if (current.isNotEmpty()) messages += current.toString()
        return messages
    }
}

fun formatHoursMinutes(millis: Long): String {
    val totalMinutes = maxOf(0L, millis) / 60_000
    return "${totalMinutes / 60} hours ${totalMinutes % 60} minutes"
}
