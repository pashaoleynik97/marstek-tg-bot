package com.mthkr.forecast

import com.mthkr.config.ForecastConfig
import com.mthkr.db.BatterySampleRepository
import com.mthkr.inverter.InverterSnapshot
import com.mthkr.monitor.GridState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val RECENT_WINDOW_MS = 30 * 60_000L
private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm")
private const val NOT_REACHABLE = "⚠️ Inverter not reachable (no fresh data from the dongle)."

/** Builds the Forecast menu replies (Current usage / Smart forecast). */
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
    private val retentionMs = config.retentionDays * 86_400_000L

    fun currentUsage(): String {
        val snapshot = snapshotProvider() ?: return NOT_REACHABLE
        noForecastReason(snapshot)?.let { return it }
        val now = clock()
        val drain = drainW(snapshot)
        val header = "Current usage: ${snapshot.outputPower} W" + solarLine(snapshot, drain)
        val energy = ForecastCalculator.availableWh(snapshot.batterySoc, config.cutoffSoc, nominalWhPerPercent)
        val estimate = ForecastCalculator.constantLoad(energy, drain.toDouble())
            ?: return "$header\n🌤 No battery drain right now."
        return "$header\n${estimateLines(estimate, now)}"
    }

    fun smartForecast(): String {
        val snapshot = snapshotProvider() ?: return NOT_REACHABLE
        noForecastReason(snapshot)?.let { return it }
        val now = clock()
        val samples = repository.findSince(now - retentionMs)
        val profile = ForecastCalculator.hourlyProfile(samples, zone)
            ?: return "Smart forecast: no history yet, using current usage.\n\n${currentUsage()}"

        val whPerPercent = ForecastCalculator.learnWhPerPercent(samples, intervalMs * 2, nominalWhPerPercent)
            ?: nominalWhPerPercent
        val energy = ForecastCalculator.availableWh(snapshot.batterySoc, config.cutoffSoc, whPerPercent)
        val recent = samples.filter { now - it.ts <= RECENT_WINDOW_MS }.map { it.drainW.toDouble() } +
            drainW(snapshot).toDouble()
        val estimate = ForecastCalculator.simulate(now, energy, recent.average(), profile, zone)

        val days = ForecastCalculator.daysOfData(samples, zone)
        return "Smart forecast based on $days ${if (days == 1) "day" else "days"} data.\n" +
            estimateLines(estimate, now)
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

    private fun drainW(s: InverterSnapshot) = maxOf(0, s.outputPower - s.pvPower)

    private fun solarLine(s: InverterSnapshot, drain: Int) =
        if (s.pvPower > 0) "\nSolar: ${s.pvPower} W (battery drain $drain W)" else ""

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
