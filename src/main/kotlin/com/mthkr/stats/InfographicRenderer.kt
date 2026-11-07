package com.mthkr.stats

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.imageio.ImageIO

/**
 * Renders 1:1 PNG infographics for a [StatsReport] with plain Java2D.
 * Everything happens in memory — nothing is written to disk (ImageIO's file cache is disabled too).
 */
object InfographicRenderer {
    const val SIZE = 1080

    private val PRESENCE = Color(0x2E, 0xA0, 0x43)
    private val OUTAGE = Color(0xD7, 0x3A, 0x3A)
    private val NO_DATA = Color(0xB5, 0xB5, 0xB5)
    private val EMPTY = Color(0xEE, 0xEE, 0xEE)
    private val TEXT = Color(0x22, 0x22, 0x22)
    private val MUTED = Color(0x77, 0x77, 0x77)
    private val NOW_MARKER = Color(0x22, 0x22, 0x22)

    private const val FONT = "SansSerif"
    private val dateFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val shortDateFmt = DateTimeFormatter.ofPattern("dd.MM")

    init {
        ImageIO.setUseCache(false)
    }

    fun render(report: StatsReport): ByteArray {
        val image = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.color = Color.WHITE
            g.fillRect(0, 0, SIZE, SIZE)
            when (report.period) {
                StatsPeriod.TODAY -> drawToday(g, report)
                StatsPeriod.WEEK -> drawWeek(g, report)
                StatsPeriod.MONTH -> drawMonth(g, report)
            }
        } finally {
            g.dispose()
        }
        val out = ByteArrayOutputStream(64 * 1024)
        check(ImageIO.write(image, "png", out)) { "No PNG writer available" }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------ layouts

    private fun drawToday(g: Graphics2D, report: StatsReport) {
        drawTitle(g, "Today ${report.today.format(dateFmt)}")
        val segments = report.allSegments
        drawPie(g, cx = 300, cy = 340, radius = 200, segments)
        drawPieLegend(g, x = 580, y = 290, segments, horizontal = false)

        val day = report.days.singleOrNull()
        val (dayStart, dayEnd) = dayBounds(report, report.today)
        drawTimelineRow(g, x = 80, y = 680, w = 920, h = 120, dayStart, dayEnd, day?.segments.orEmpty(), report.now)
        drawHourAxis(g, x = 80, w = 920, y = 680 + 120 + 8)
        // Grey is visible whenever elapsed time isn't fully covered by outage/presence.
        val elapsed = minOf(report.now, dayEnd) - dayStart
        val known = segments.total(SegmentKind.OUTAGE) + segments.total(SegmentKind.PRESENCE)
        drawKindLegend(g, y = 1000, withNoData = elapsed - known >= MIN_NO_DATA_MS)
    }

    private fun drawWeek(g: Graphics2D, report: StatsReport) {
        drawTitle(g, "Last ${report.period.days} days")
        val byDate = report.days.associateBy { it.date }
        val dates = report.windowDates.reversed() // newest at top
        val labelW = 170
        val x = 40 + labelW
        val w = SIZE - x - 50
        val top = 150
        val rowH = 84
        val gap = 30
        dates.forEachIndexed { i, date ->
            val y = top + i * (rowH + gap)
            val (dayStart, dayEnd) = dayBounds(report, date)
            drawTimelineRow(g, x, y, w, rowH, dayStart, dayEnd, byDate[date]?.segments.orEmpty(), report.now)
            g.color = TEXT
            g.font = Font(FONT, Font.BOLD, 30)
            val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            g.drawString(dow, 40, y + rowH / 2 - 4)
            g.font = Font(FONT, Font.PLAIN, 26)
            g.color = MUTED
            g.drawString(date.format(shortDateFmt), 40, y + rowH / 2 + 28)
        }
        val bottom = top + dates.size * (rowH + gap) - gap
        drawHourAxis(g, x, w, bottom + 8)
        drawKindLegend(g, y = 1040, withNoData = true)
    }

    private fun drawMonth(g: Graphics2D, report: StatsReport) {
        val from = report.days.firstOrNull()?.date ?: report.windowDates.first()
        drawTitle(g, "${from.format(dateFmt)} – ${report.today.format(dateFmt)}")
        val segments = report.allSegments
        drawPie(g, cx = SIZE / 2, cy = 460, radius = 300, segments)
        drawPieLegend(g, x = 0, y = 880, segments, horizontal = true)
    }

    // ------------------------------------------------------------------ primitives

    private fun drawTitle(g: Graphics2D, title: String) {
        g.color = TEXT
        g.font = Font(FONT, Font.BOLD, 46)
        val fm = g.fontMetrics
        g.drawString(title, (SIZE - fm.stringWidth(title)) / 2, 85)
    }

    /** Pie of outage vs presence (no-data excluded), outage first, clockwise from 12 o'clock. */
    private fun drawPie(g: Graphics2D, cx: Int, cy: Int, radius: Int, segments: List<Segment>) {
        val outage = segments.total(SegmentKind.OUTAGE)
        val presence = segments.total(SegmentKind.PRESENCE)
        val d = radius * 2.0
        val x = (cx - radius).toDouble()
        val y = (cy - radius).toDouble()
        val total = outage + presence
        if (total <= 0) {
            g.color = NO_DATA
            g.fill(Ellipse2D.Double(x, y, d, d))
            g.color = Color.WHITE
            g.font = Font(FONT, Font.BOLD, 40)
            val text = "No data"
            g.drawString(text, cx - g.fontMetrics.stringWidth(text) / 2, cy + 14)
            return
        }
        val outageDeg = 360.0 * outage / total
        g.color = PRESENCE
        g.fill(Ellipse2D.Double(x, y, d, d))
        if (outage > 0) {
            g.color = OUTAGE
            g.fill(Arc2D.Double(x, y, d, d, 90.0, -outageDeg, Arc2D.PIE))
        }
        // White separators between slices.
        if (outage in 1 until total) {
            g.color = Color.WHITE
            g.stroke = BasicStroke(4f)
            for (angle in listOf(90.0, 90.0 - outageDeg)) {
                val rad = Math.toRadians(angle)
                g.drawLine(cx, cy, (cx + radius * Math.cos(rad)).toInt(), (cy - radius * Math.sin(rad)).toInt())
            }
        }
    }

    /** Vertical: items stacked starting at ([x], [y]). Horizontal: items in centered columns at [y] ([x] ignored). */
    private fun drawPieLegend(g: Graphics2D, x: Int, y: Int, segments: List<Segment>, horizontal: Boolean) {
        val outage = segments.total(SegmentKind.OUTAGE)
        val presence = segments.total(SegmentKind.PRESENCE)
        val noData = segments.total(SegmentKind.NO_DATA)
        val pct = percentages(outage, presence)
        val showNoData = noData >= MIN_NO_DATA_MS
        val columnW = 330
        var cx = if (horizontal) (SIZE - columnW * (if (showNoData) 3 else 2)) / 2 + 20 else x
        var cy = y
        fun item(color: Color, name: String, percent: Int?, millis: Long) {
            val x = cx
            g.color = color
            g.fillRoundRect(x, cy - 30, 34, 34, 8, 8)
            g.color = TEXT
            g.font = Font(FONT, Font.BOLD, 36)
            val head = if (percent != null) "$name $percent%" else name
            g.drawString(head, x + 52, cy)
            g.color = MUTED
            g.font = Font(FONT, Font.PLAIN, 30)
            g.drawString(formatShort(millis), x + 52, cy + 40)
            if (horizontal) cx += columnW else cy += 110
        }
        item(OUTAGE, "Outage", pct?.first, outage)
        item(PRESENCE, "Presence", pct?.second, presence)
        if (showNoData) item(NO_DATA, "No data", null, noData)
    }

    /**
     * One day bar: past time without data is grey, future time is empty, segments are drawn on top,
     * plus a "now" marker if the day is still in progress.
     */
    private fun drawTimelineRow(
        g: Graphics2D, x: Int, y: Int, w: Int, h: Int,
        dayStart: Long, dayEnd: Long, segments: List<Segment>, now: Long,
    ) {
        fun px(t: Long): Int = x + ((t.coerceIn(dayStart, dayEnd) - dayStart).toDouble() / (dayEnd - dayStart) * w).toInt()

        g.color = EMPTY
        g.fillRect(x, y, w, h)
        if (now > dayStart) {
            g.color = NO_DATA
            g.fillRect(x, y, px(minOf(now, dayEnd)) - x, h)
        }
        for (seg in segments) {
            g.color = when (seg.kind) {
                SegmentKind.OUTAGE -> OUTAGE
                SegmentKind.PRESENCE -> PRESENCE
                SegmentKind.NO_DATA -> NO_DATA
            }
            val sx = px(seg.start)
            val ex = maxOf(px(seg.end), sx + 1) // keep very short segments visible
            g.fillRect(sx, y, ex - sx, h)
        }
        if (now in (dayStart + 1) until dayEnd) {
            g.color = NOW_MARKER
            g.stroke = BasicStroke(3f)
            val nx = px(now)
            g.drawLine(nx, y - 6, nx, y + h + 6)
        }
    }

    private fun drawHourAxis(g: Graphics2D, x: Int, w: Int, y: Int) {
        g.font = Font(FONT, Font.PLAIN, 24)
        g.stroke = BasicStroke(2f)
        val fm = g.fontMetrics
        for (hour in 0..24 step 3) {
            val tx = x + w * hour / 24
            g.color = MUTED
            g.drawLine(tx, y, tx, y + 10)
            val label = "%02d".format(hour)
            g.drawString(label, tx - fm.stringWidth(label) / 2, y + 38)
        }
    }

    private fun drawKindLegend(g: Graphics2D, y: Int, withNoData: Boolean) {
        val items = buildList {
            add(PRESENCE to "Presence")
            add(OUTAGE to "Outage")
            if (withNoData) add(NO_DATA to "No data")
        }
        g.font = Font(FONT, Font.PLAIN, 28)
        val fm = g.fontMetrics
        val itemW = items.map { 26 + 12 + fm.stringWidth(it.second) }
        val spacing = 50
        var x = (SIZE - itemW.sum() - spacing * (items.size - 1)) / 2
        items.forEachIndexed { i, (color, name) ->
            g.color = color
            g.fillRoundRect(x, y - 22, 26, 26, 6, 6)
            g.color = TEXT
            g.drawString(name, x + 38, y)
            x += itemW[i] + spacing
        }
    }

    private fun dayBounds(report: StatsReport, date: LocalDate): Pair<Long, Long> =
        date.atStartOfDay(report.zone).toInstant().toEpochMilli() to
            date.plusDays(1).atStartOfDay(report.zone).toInstant().toEpochMilli()
}
