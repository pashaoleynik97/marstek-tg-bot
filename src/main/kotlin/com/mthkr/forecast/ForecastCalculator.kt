package com.mthkr.forecast

import com.mthkr.db.BatterySample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Time until the cutoff is reached; [capped] = beyond the forecast horizon. */
data class Estimate(val timeLeftMs: Long, val capped: Boolean)

/** Pure forecast math (no I/O) — see .claude/forecast/analysis.md for the rationale. */
object ForecastCalculator {
    const val STEP_MS = 5 * 60_000L
    const val BLEND_MS = 60 * 60_000L
    const val HORIZON_MS = 72 * 3_600_000L
    const val MIN_SAMPLES_PER_HOUR = 3
    const val MIN_LEARN_DROP = 10
    const val LEARN_MIN_FACTOR = 0.5
    const val LEARN_MAX_FACTOR = 1.5

    fun availableWh(soc: Int, cutoffSoc: Int, whPerPercent: Double): Double =
        maxOf(0, soc - cutoffSoc) * whPerPercent

    /** Constant-load estimate; null when there is no battery drain. */
    fun constantLoad(energyWh: Double, drainW: Double): Estimate? {
        if (drainW <= 0.0) return null
        val ms = Math.round(energyWh / drainW * 3_600_000.0)
        return if (ms > HORIZON_MS) Estimate(HORIZON_MS, true) else Estimate(ms, false)
    }

    /**
     * Learns usable Wh per 1 % SoC from continuous runs of samples (consecutive gap ≤ [maxGapMs]):
     * ∫drain·dt / ΔSoC. Returns null if less than [MIN_LEARN_DROP] % drop was observed or the result is
     * outside [LEARN_MIN_FACTOR]..[LEARN_MAX_FACTOR] × [nominalWhPerPercent] (bad SoC readings).
     */
    fun learnWhPerPercent(samples: List<BatterySample>, maxGapMs: Long, nominalWhPerPercent: Double): Double? {
        var energyWh = 0.0
        var drop = 0
        for ((a, b) in samples.zipWithNext()) {
            val dt = b.ts - a.ts
            if (dt <= 0 || dt > maxGapMs) continue
            energyWh += (a.drainW + b.drainW) / 2.0 * dt / 3_600_000.0
            drop += a.soc - b.soc
        }
        if (drop < MIN_LEARN_DROP) return null
        val learned = energyWh / drop
        return learned.takeIf {
            it in nominalWhPerPercent * LEARN_MIN_FACTOR..nominalWhPerPercent * LEARN_MAX_FACTOR
        }
    }

    /** Median drain per local hour of day; sparse hours fall back to the overall median. Null if no samples. */
    fun hourlyProfile(samples: List<BatterySample>, zone: ZoneId): DoubleArray? {
        if (samples.isEmpty()) return null
        val overall = median(samples.map { it.drainW.toDouble() })
        val byHour = samples.groupBy { Instant.ofEpochMilli(it.ts).atZone(zone).hour }
        return DoubleArray(24) { h ->
            val hs = byHour[h]
            if (hs != null && hs.size >= MIN_SAMPLES_PER_HOUR) median(hs.map { it.drainW.toDouble() }) else overall
        }
    }

    /**
     * Steps forward from [now] in [STEP_MS] slices consuming [energyWh]. Load starts at [recentW] and blends
     * linearly into the hourly [profile] over [BLEND_MS]; afterwards it follows the profile.
     */
    fun simulate(now: Long, energyWh: Double, recentW: Double, profile: DoubleArray, zone: ZoneId): Estimate {
        var remaining = energyWh
        var elapsed = 0L
        while (elapsed < HORIZON_MS) {
            if (remaining <= 0.0) return Estimate(elapsed, false)
            val hour = Instant.ofEpochMilli(now + elapsed).atZone(zone).hour
            val profileW = profile[hour]
            val w = if (elapsed < BLEND_MS) recentW + (profileW - recentW) * elapsed / BLEND_MS else profileW
            val stepWh = w * STEP_MS / 3_600_000.0
            if (w > 0 && stepWh >= remaining) {
                return Estimate(elapsed + Math.round(remaining / w * 3_600_000.0), false)
            }
            remaining -= maxOf(0.0, stepWh)
            elapsed += STEP_MS
        }
        return Estimate(HORIZON_MS, true)
    }

    fun daysOfData(samples: List<BatterySample>, zone: ZoneId): Int =
        samples.map { LocalDate.ofInstant(Instant.ofEpochMilli(it.ts), zone) }.distinct().size

    internal fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}
