package com.mthkr.stats

import com.mthkr.db.GridInterval
import com.mthkr.monitor.GridState

enum class SegmentKind { OUTAGE, PRESENCE, NO_DATA }

data class Segment(val kind: SegmentKind, val start: Long, val end: Long) {
    val duration: Long get() = end - start
}

/** No-data holes shorter than this are absorbed into the neighbouring segment (e.g. bot restarts). */
const val MIN_NO_DATA_MS = 60_000L

/**
 * Builds a gap-free list of segments covering [from, to).
 *
 * @param intervals stored intervals (any order) — those far outside the window are ignored.
 * @param now       current time; the latest interval is considered ongoing until now if it was observed recently.
 * @param gapMs     max hole between observations still treated as continuous.
 */
fun buildTimeline(intervals: List<GridInterval>, from: Long, to: Long, now: Long, gapMs: Long): List<Segment> {
    if (to <= from) return emptyList()
    val sorted = intervals.sortedWith(compareBy({ it.startTs }, { it.id }))

    // 1) Effective coverage: bridge small holes to the next interval / to "now".
    val covered = sorted.mapIndexed { i, iv ->
        val next = sorted.getOrNull(i + 1)
        val effEnd = when {
            next != null && next.startTs - iv.endTs in 0..gapMs -> next.startTs
            next == null && now - iv.endTs in 0..gapMs -> now
            else -> iv.endTs
        }
        Segment(iv.state.toKind(), iv.startTs, maxOf(effEnd, iv.endTs))
    }

    // 2) Clip to window and fill holes with NO_DATA.
    val raw = mutableListOf<Segment>()
    var cursor = from
    for (seg in covered) {
        val s = maxOf(seg.start, cursor)
        val e = minOf(seg.end, to)
        if (e <= s) continue
        if (s > cursor) raw += Segment(SegmentKind.NO_DATA, cursor, s)
        raw += Segment(seg.kind, s, e)
        cursor = e
    }
    if (cursor < to) raw += Segment(SegmentKind.NO_DATA, cursor, to)

    // 3) Absorb tiny no-data holes into the previous segment (or the next one at the start).
    val absorbed = mutableListOf<Segment>()
    for ((i, seg) in raw.withIndex()) {
        val tiny = seg.kind == SegmentKind.NO_DATA && seg.duration < MIN_NO_DATA_MS && raw.size > 1
        when {
            tiny && absorbed.isNotEmpty() -> absorbed[absorbed.lastIndex] = absorbed.last().copy(end = seg.end)
            tiny -> {
                val next = raw[i + 1]
                raw[i + 1] = next.copy(start = seg.start)
            }
            else -> absorbed += seg
        }
    }

    // 4) Merge adjacent segments of the same kind.
    val merged = mutableListOf<Segment>()
    for (seg in absorbed) {
        val last = merged.lastOrNull()
        if (last != null && last.kind == seg.kind && last.end == seg.start) {
            merged[merged.lastIndex] = last.copy(end = seg.end)
        } else {
            merged += seg
        }
    }
    return merged
}

fun List<Segment>.total(kind: SegmentKind): Long = filter { it.kind == kind }.sumOf { it.duration }

private fun GridState.toKind() = when (this) {
    GridState.CONNECTED -> SegmentKind.PRESENCE
    GridState.DISCONNECTED -> SegmentKind.OUTAGE
}
