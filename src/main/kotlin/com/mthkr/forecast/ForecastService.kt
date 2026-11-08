package com.mthkr.forecast

import com.mthkr.config.ForecastConfig
import com.mthkr.db.BatterySample
import com.mthkr.db.BatterySampleRepository
import com.mthkr.inverter.InverterSnapshot
import com.mthkr.monitor.GridState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val RECENT_WINDOW_MS = 30 * 60_000L
private const val DAY_MS = 86_400_000L
private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm")
private const val NOT_REACHABLE = "⚠️ Inverter not reachable (no fresh data from the dongle)."

/** Usable Wh per 1 % SoC and whether it was learned from history (else config). */
private data class Capacity(val whPerPercent: Double, val learned: Boolean)

/** Builds the Forecast menu replies (Current usage / Smart forecast). All drain values are DC battery power. */
class ForecastService(
    private val repository: BatterySampleRepository,
    private val config: ForecastConfig,
    private val zone: ZoneId,
    private val snapshotProvider: () -> InverterSnapshot?,
    private val gridStateProvider: () -> GridState?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val nominalWhPerPercent = config.batteryCapacityWh / 100.0
    private val intervalMs = config.sampleIntervalMinutes * 60_000L
    private val retentionMs = config.retentionDays * DAY_MS
    private val capacityWindowMs = config.capacityWindowDays * DAY_MS
    private val capacityHalfLifeMs = (config.capacityHalfLifeDays * DAY_MS).roundToLong()
    private val profileHalfLifeMs = (config.profileHalfLifeDays * DAY_MS).roundToLong()

    fun currentUsage(): String {
        val snapshot = snapshotProvider() ?: return NOT_REACHABLE
        noForecastReason(snapshot)?.let { return it }
        val now = clock()
        val drain = snapshot.batteryDischargeW
        val header = "Current usage: ${loadText(snapshot)}"
        if (drain <= 0.0) return "$header\n🌤 Battery is not discharging right now."
        val capacity = capacity(repository.findSince(now - capacityWindowMs), now)
        val energy = ForecastCalculator.availableWh(snapshot.batterySoc, config.cutoffSoc, capacity.whPerPercent)
        val estimate = ForecastCalculator.constantLoad(energy, drain)!!
        return "$header\nBattery drain: ${drain.roundToInt()} W\n${estimateLines(estimate, now)}"
    }

    fun smartForecast(): String {
        val snapshot = snapshotProvider() ?: return NOT_REACHABLE
        noForecastReason(snapshot)?.let { return it }
        val now = clock()
        val samples = repository.findSince(now - retentionMs)
        val profile = ForecastCalculator.hourlyProfile(samples, now, profileHalfLifeMs, zone)
            ?: return "Smart forecast: no history yet, using current usage.\n\n${currentUsage()}"

        val capacity = capacity(samples, now)
        val energy = ForecastCalculator.availableWh(snapshot.batterySoc, config.cutoffSoc, capacity.whPerPercent)
        val recent = ForecastCalculator.clean(samples).filter { now - it.ts <= RECENT_WINDOW_MS }.map { it.dischargeW } +
            snapshot.batteryDischargeW
        val estimate = ForecastCalculator.simulate(now, energy, recent.average(), profile, zone)

        val days = ForecastCalculator.daysOfData(samples, zone)
        val source = if (capacity.learned) "learned" else "config"
        return "Smart forecast based on $days ${if (days == 1) "day" else "days"} data.\n" +
            estimateLines(estimate, now) + "\n" +
            "Avg drain ${estimate.avgW.roundToInt()} W · ${capacity.whPerPercent.roundToInt()} Wh/% ($source)"
    }

    private fun capacity(samples: List<BatterySample>, now: Long): Capacity {
        val learned = ForecastCalculator.learnWhPerPercent(
            samples, now, capacityWindowMs, capacityHalfLifeMs, intervalMs * 2, nominalWhPerPercent
        )
        return if (learned != null) Capacity(learned, true) else Capacity(nominalWhPerPercent, false)
    }

    /** Reply explaining why no forecast can be made, or null if it can. */
    private fun noForecastReason(snapshot: InverterSnapshot): String? = when (gridStateProvider()) {
        null -> "❔ Grid state is not known yet, try again in a minute."
        GridState.CONNECTED -> "🔌 Connected to grid, no forecast."
        GridState.DISCONNECTED ->
            if (snapshot.batterySoc <= config.cutoffSoc) {
                "🪫 Battery is at ${snapshot.batterySoc}% — already at the ${config.cutoffSoc}% cutoff."
            } else {
                null
            }
    }

    private fun loadText(s: InverterSnapshot) = s.validOutputPower?.let { "$it W" } ?: "n/a"

    private fun estimateLines(e: Estimate, now: Long): String {
        val until = formatUntil(now + e.timeLeftMs, now)
        return if (e.capped) {
            "Estimated time left: > ${formatDuration(e.timeLeftMs)}\nLast until: after $until"
        } else {
            "Estimated time left: ${formatDuration(e.timeLeftMs)}\nLast until: $until"
        }
    }

    private fun formatUntil(ts: Long, now: Long): String {
        val at = Instant.ofEpochMilli(ts).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (at.toLocalDate()) {
            today -> at.format(TIME)
            today.plusDays(1) -> "${at.format(TIME)} (tomorrow)"
            else -> at.format(DATE_TIME)
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val totalMinutes = maxOf(0L, ms / 60_000)
    return "${totalMinutes / 60}h ${totalMinutes % 60}m"
}
