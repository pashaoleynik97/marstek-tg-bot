package com.mthkr.forecast

import com.mthkr.db.BatterySample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.pow

/**
 * Time until the cutoff is reached; [capped] = beyond the forecast horizon.
 * [avgW] = average battery power the estimate assumed (energy used / time).
 */
data class Estimate(val timeLeftMs: Long, val capped: Boolean, val avgW: Double)

/**
 * Pure forecast math (no I/O). All power values are DC battery discharge power (includes inverter losses).
 * Only discharging samples are used ("clean" samples) — see .claude/forecast/plan-v2.md.
 */
object ForecastCalculator {
    const val STEP_MS = 5 * 60_000L
    const val BLEND_MS = 60 * 60_000L
    const val HORIZON_MS = 72 * 3_600_000L
    /** Shrinkage prior: an hour needs about this many (recency-weighted) samples to follow its own mean. */
    const val SHRINK_K = 6.0
    const val MIN_LEARN_DROP = 10
    const val LEARN_MIN_FACTOR = 0.5
    const val LEARN_MAX_FACTOR = 1.5

    fun clean(samples: List<BatterySample>): List<BatterySample> = samples.filter { it.isDischarging }

    /** Recency weight: halves every [halfLifeMs]. */
    fun weight(ageMs: Long, halfLifeMs: Long): Double = 0.5.pow(maxOf(0L, ageMs).toDouble() / halfLifeMs)

    fun availableWh(soc: Int, cutoffSoc: Int, whPerPercent: Double): Double =
        maxOf(0, soc - cutoffSoc) * whPerPercent

    /** Constant-load estimate; null when there is no battery drain. */
    fun constantLoad(energyWh: Double, drainW: Double): Estimate? {
        if (drainW <= 0.0) return null
        val ms = Math.round(energyWh / drainW * 3_600_000.0)
        return if (ms > HORIZON_MS) Estimate(HORIZON_MS, true, drainW) else Estimate(ms, false, drainW)
    }

    /**
     * Learns usable Wh per 1 % SoC from consecutive discharging pairs (gap ≤ [maxGapMs]) within [windowMs]:
     * Σw·∫P·dt / Σw·ΔSoC with recency weight w. Null if less than [MIN_LEARN_DROP] % (unweighted) drop was
     * observed or the result is outside [LEARN_MIN_FACTOR]..[LEARN_MAX_FACTOR] × [nominalWhPerPercent].
     */
    fun learnWhPerPercent(
        samples: List<BatterySample>,
        now: Long,
        windowMs: Long,
        halfLifeMs: Long,
        maxGapMs: Long,
        nominalWhPerPercent: Double,
    ): Double? {
        var weightedWh = 0.0
        var weightedDrop = 0.0
        var drop = 0
        val window = samples.filter { it.ts >= now - windowMs }
        for ((a, b) in window.zipWithNext()) {
            val dt = b.ts - a.ts
            if (dt <= 0 || dt > maxGapMs || !a.isDischarging || !b.isDischarging) continue
            val w = weight(now - b.ts, halfLifeMs)
            weightedWh += w * (a.dischargeW + b.dischargeW) / 2.0 * dt / 3_600_000.0
            weightedDrop += w * (a.soc - b.soc)
            drop += a.soc - b.soc
        }
        if (drop < MIN_LEARN_DROP || weightedDrop <= 0.0) return null
        val learned = weightedWh / weightedDrop
        return learned.takeIf {
            it in nominalWhPerPercent * LEARN_MIN_FACTOR..nominalWhPerPercent * LEARN_MAX_FACTOR
        }
    }

    /**
     * Recency-weighted mean discharge power per local hour of day, shrunk toward the overall weighted mean:
     * (Σw·x + K·overall) / (Σw + K). Null if there are no clean samples.
     */
    fun hourlyProfile(samples: List<BatterySample>, now: Long, halfLifeMs: Long, zone: ZoneId): DoubleArray? {
        val clean = clean(samples)
        if (clean.isEmpty()) return null
        val sumW = DoubleArray(24)
        val sumWx = DoubleArray(24)
        for (s in clean) {
            val h = Instant.ofEpochMilli(s.ts).atZone(zone).hour
            val w = weight(now - s.ts, halfLifeMs)
            sumW[h] += w
            sumWx[h] += w * s.dischargeW
        }
        val overall = sumWx.sum() / sumW.sum()
        return DoubleArray(24) { h -> (sumWx[h] + SHRINK_K * overall) / (sumW[h] + SHRINK_K) }
    }

    /**
     * Steps forward from [now] in [STEP_MS] slices consuming [energyWh]. Load starts at [recentW] and blends
     * linearly into the hourly [profile] over [BLEND_MS]; afterwards it follows the profile.
     */
    fun simulate(now: Long, energyWh: Double, recentW: Double, profile: DoubleArray, zone: ZoneId): Estimate {
        var remaining = energyWh
        var elapsed = 0L
        fun avg(t: Long) = if (t > 0) (energyWh - remaining) / (t / 3_600_000.0) else recentW
        while (elapsed < HORIZON_MS) {
            if (remaining <= 0.0) return Estimate(elapsed, false, avg(elapsed))
            val hour = Instant.ofEpochMilli(now + elapsed).atZone(zone).hour
            val profileW = profile[hour]
            val w = if (elapsed < BLEND_MS) recentW + (profileW - recentW) * elapsed / BLEND_MS else profileW
            val stepWh = w * STEP_MS / 3_600_000.0
            if (w > 0 && stepWh >= remaining) {
                val t = elapsed + Math.round(remaining / w * 3_600_000.0)
                remaining = 0.0
                return Estimate(t, false, avg(t))
            }
            remaining -= maxOf(0.0, stepWh)
            elapsed += STEP_MS
        }
        return Estimate(HORIZON_MS, true, avg(HORIZON_MS))
    }

    /** Distinct local dates that have clean samples. */
    fun daysOfData(samples: List<BatterySample>, zone: ZoneId): Int =
        clean(samples).map { LocalDate.ofInstant(Instant.ofEpochMilli(it.ts), zone) }.distinct().size
}
