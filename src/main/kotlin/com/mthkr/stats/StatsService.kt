package com.mthkr.stats

import com.mthkr.db.GridInterval
import com.mthkr.db.InverterStateRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

enum class StatsPeriod(val days: Long, val label: String) {
    TODAY(1, "this day"),
    WEEK(7, "last 7 days"),
    MONTH(30, "last month"),
}

/** Telegram hard limit is 4096 chars; keep a margin. */
private const val MAX_MESSAGE_LENGTH = 3800

/** One calendar day of the report; [segments] cover [max(dayStart, firstData), min(dayEnd, now)). */
data class DayTimeline(val date: LocalDate, val dayStart: Long, val dayEnd: Long, val segments: List<Segment>)

/** Read model shared by the text statistic and the infographics. */
data class StatsReport(
    val period: StatsPeriod,
    val today: LocalDate,
    val now: Long,
    val zone: ZoneId,
    /** Days that have data, oldest first. */
    val days: List<DayTimeline>,
) {
    val allSegments: List<Segment> get() = days.flatMap { it.segments }

    /** All calendar days of the period window (oldest first), including those without data. */
    val windowDates: List<LocalDate>
        get() = (period.days - 1 downTo 0).map { today.minusDays(it) }
}

class StatsService(
    private val repository: InverterStateRepository,
    private val zone: ZoneId,
    private val gapMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val longDateFmt = DateTimeFormatter.ofPattern("dd-MMMM-yyyy", Locale.ENGLISH)

    /** Returns the summary as a single plain-text message. */
    fun summary(period: StatsPeriod): String {
        val days = report(period).days
        if (days.isEmpty()) return noDataText(period)
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

    /** Returns the detailed statistic as HTML (Telegram parse mode HTML), split into Telegram-sized messages. */
    fun statistic(period: StatsPeriod): List<String> = statistic(report(period))

    fun statistic(report: StatsReport): List<String> {
        if (report.days.isEmpty()) return listOf(noDataText(report.period))
        return when (report.period) {
            StatsPeriod.TODAY -> listOf(todayText(report))
            StatsPeriod.WEEK -> weekText(report)
            StatsPeriod.MONTH -> monthText(report)
        }
    }

    /** Per-day timelines for the period, oldest first, starting no earlier than the first recorded data. */
    fun report(period: StatsPeriod): StatsReport {
        val now = clock()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val empty = StatsReport(period, today, now, zone, emptyList())
        val firstData = repository.findFirstStart() ?: return empty
        val firstDay = today.minusDays(period.days - 1)
        val windowFrom = maxOf(firstDay.atStartOfDay(zone).toInstant().toEpochMilli(), firstData)
        if (windowFrom >= now) return empty

        val intervals: List<GridInterval> = repository.findOverlapping(windowFrom - gapMs, now)
        val days = generateSequence(firstDay) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .mapNotNull { date ->
                val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val from = maxOf(dayStart, windowFrom)
                val to = minOf(dayEnd, now)
                if (to <= from) null
                else DayTimeline(date, dayStart, dayEnd, buildTimeline(intervals, from, to, now, gapMs))
            }
            .toList()
        return StatsReport(period, today, now, zone, days)
    }

    // ------------------------------------------------------------------ text formats

    private fun todayText(report: StatsReport): String {
        val day = report.days.single()
        val outage = day.segments.total(SegmentKind.OUTAGE)
        return "There were ${formatHoursMinutes(outage)} of outages during ${report.period.label}.\n\n" +
            segmentLines(day, report.now)
    }

    private fun weekText(report: StatsReport): List<String> {
        val all = report.allSegments
        val outage = all.total(SegmentKind.OUTAGE)
        val presence = all.total(SegmentKind.PRESENCE)
        val noData = all.total(SegmentKind.NO_DATA)
        val n = report.days.size
        val header = buildList {
            add("There were ${formatHoursMinutes(outage)} of outages during last $n ${if (n == 1) "day" else "days"}.")
            percentages(outage, presence)?.let { (o, p) ->
                add("Outage: $o%")
                add("Presence: $p%")
            }
            if (noData >= MIN_NO_DATA_MS) add("❔ No data: ${formatShort(noData)}")
        }.joinToString("\n")

        val dayBlocks = report.days.sortedByDescending { it.date }.map { day ->
            "<b>${dayTitle(day.date)}</b>\n" +
                "<i>${formatHoursMinutes(day.segments.total(SegmentKind.OUTAGE))} of outages</i>\n" +
                segmentLines(day, report.now)
        }
        return chunk(listOf(header) + dayBlocks) { i -> if (i == 1) "\n\n" else "\n----------\n\n" }
    }

    private fun monthText(report: StatsReport): List<String> {
        val all = report.allSegments
        val from = report.days.first().date.format(longDateFmt)
        val to = report.today.format(longDateFmt)
        val header = buildList {
            add("Outages from $from to $to")
            add("")
            add("<i>Total outages:</i> ${formatShort(all.total(SegmentKind.OUTAGE))}")
            add("<i>Total presence:</i> ${formatShort(all.total(SegmentKind.PRESENCE))}")
            val noData = all.total(SegmentKind.NO_DATA)
            if (noData >= MIN_NO_DATA_MS) add("<i>Total no data:</i> ${formatShort(noData)}")
        }.joinToString("\n")

        val dayBlocks = report.days.sortedByDescending { it.date }.map { day ->
            buildList {
                add("<b>${dayTitle(day.date)}</b>")
                add("❌ Outage: ${formatShort(day.segments.total(SegmentKind.OUTAGE))}")
                add("⚡ Presence: ${formatShort(day.segments.total(SegmentKind.PRESENCE))}")
                val noData = day.segments.total(SegmentKind.NO_DATA)
                if (noData >= MIN_NO_DATA_MS) add("❔ No data: ${formatShort(noData)}")
            }.joinToString("\n")
        }
        return chunk(listOf(header) + dayBlocks) { "\n\n" }
    }

    private fun segmentLines(day: DayTimeline, now: Long): String = day.segments.joinToString("\n") { seg ->
        val start = Instant.ofEpochMilli(seg.start).atZone(zone).format(timeFmt)
        val end = when {
            seg.end >= now -> "now"
            seg.end >= day.dayEnd -> "00:00"
            else -> Instant.ofEpochMilli(seg.end).atZone(zone).format(timeFmt)
        }
        val range = "$start-$end (${formatShort(seg.duration)})"
        when (seg.kind) {
            SegmentKind.OUTAGE -> "❌ Outage: $range"
            SegmentKind.PRESENCE -> "⚡ Presence: $range"
            SegmentKind.NO_DATA -> "❔ No data: $range"
        }
    }

    private fun dayTitle(date: LocalDate): String =
        "${date.format(dateFmt)} ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}"

    private fun noDataText(period: StatsPeriod) = "No outage data recorded for ${period.label} yet."

    /** Joins blocks into messages; [separatorBefore] gets the block index (never called for a message's first block). */
    private fun chunk(blocks: List<String>, separatorBefore: (Int) -> String): List<String> {
        val messages = mutableListOf<String>()
        val current = StringBuilder()
        for ((i, block) in blocks.withIndex()) {
            val separator = separatorBefore(i)
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

/** `3h 20m`, `0h 2m`. */
fun formatShort(millis: Long): String {
    val totalMinutes = maxOf(0L, millis) / 60_000
    return "${totalMinutes / 60}h ${totalMinutes % 60}m"
}

/** Integer (outage %, presence %) summing to 100, or null when both are zero. */
fun percentages(outage: Long, presence: Long): Pair<Int, Int>? {
    val total = outage + presence
    if (total <= 0) return null
    val o = (outage * 100.0 / total).roundToInt()
    return o to 100 - o
}