package com.mthkr.forecast

import com.mthkr.db.BatterySample
import com.mthkr.db.BatterySampleRepository
import com.mthkr.inverter.InverterSnapshot
import com.mthkr.monitor.GridState
import org.slf4j.LoggerFactory

/**
 * Records a history sample every [intervalMs] while running on battery and prunes samples older than
 * [retentionMs]. A sample is recorded only when the confirmed grid state is DISCONNECTED, the snapshot itself
 * shows no grid (skips the restore debounce window) and the battery is discharging. The load value is NOT
 * required to be valid — forecasts use DC battery power. Never throws — the monitor loop must not break.
 */
class BatterySampleRecorder(
    private val repository: BatterySampleRepository,
    private val intervalMs: Long,
    private val retentionMs: Long,
    private val gridVoltageThreshold: Double,
) {
    private val log = LoggerFactory.getLogger(BatterySampleRecorder::class.java)
    private var lastRecordedAt: Long? = null

    fun onSnapshot(snapshot: InverterSnapshot, gridState: GridState?, now: Long) {
        if (gridState != GridState.DISCONNECTED) return
        if (snapshot.gridVoltage >= gridVoltageThreshold) return
        if (snapshot.batteryCurrent >= 0) return
        val last = lastRecordedAt
        if (last != null && now - last < intervalMs) return
        try {
            repository.insert(snapshot.toSample(now))
            lastRecordedAt = now
            val deleted = repository.deleteOlderThan(now - retentionMs)
            if (deleted > 0) log.info("Pruned {} old battery samples", deleted)
        } catch (e: Exception) {
            log.error("Failed to record battery sample: {}", e.message, e)
        }
    }
}

fun InverterSnapshot.toSample(ts: Long) = BatterySample(
    ts = ts,
    soc = batterySoc,
    loadW = outputPower,
    pvW = pvPower,
    batteryV = batteryVoltage,
    batteryA = batteryCurrent,
)
