package com.mthkr.monitor

import com.mthkr.db.GridInterval
import com.mthkr.db.InverterStateRepository
import com.mthkr.inverter.InverterSnapshot
import com.mthkr.inverter.InverterSource
import com.mthkr.notifications.AlertSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private const val CONFIRM_POLLS = 2
private const val HEARTBEAT_INTERVAL_MS = 30_000L
private const val RECONNECT_RETRY_MS = 60_000L
private const val SNAPSHOT_MAX_AGE_MS = 60_000L

/** Max hole between two observations that is still considered continuous (no "No data" gap). */
fun gapToleranceMs(pollIntervalSeconds: Long): Long = maxOf(60_000L, pollIntervalSeconds * 3_000L)

/**
 * Polls the inverter, detects grid outages/restores (grid voltage based, debounced),
 * stores state intervals and sends Telegram alerts.
 */
class InverterMonitor(
    private val source: InverterSource,
    private val notifier: AlertSink,
    private val repository: InverterStateRepository,
    private val pollIntervalSeconds: Long,
    private val gridVoltageThreshold: Double,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(InverterMonitor::class.java)
    private val gapMs = gapToleranceMs(pollIntervalSeconds)

    @Volatile private var latestSnapshot: InverterSnapshot? = null
    /** Confirmed grid state; initialised from the DB so restarts can detect changes made while offline. */
    @Volatile private var confirmedState: GridState? = null
    private var currentInterval: GridInterval? = null
    private var candidateState: GridState? = null
    private var candidateSince: Long = 0
    private var candidateCount: Int = 0
    private var lastNotifiedSocThreshold: Int? = null
    private var connectionAlertSent = false
    private var lastHeartbeatAt = 0L
    private var initialized = false

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            log.info("InverterMonitor started, polling every {}s, grid threshold {} V", pollIntervalSeconds, gridVoltageThreshold)
            while (true) {
                val waitMs = try {
                    cycle()
                } catch (e: Exception) {
                    log.error("Unexpected error in inverter loop: {}", e.message, e)
                    pollIntervalSeconds * 1000L
                }
                delay(waitMs)
            }
        }
    }

    /** One loop iteration; returns how long to wait before the next one. */
    internal fun cycle(): Long {
        if (!source.hasSession()) {
            log.info("No inverter session — running setup sequence")
            if (!source.establishSession()) {
                if (!connectionAlertSent) {
                    connectionAlertSent = true
                    notifier.send(
                        "⚠️ INVERTER NOT RESPONDING\n" +
                            "Could not get data from the inverter dongle, even after re-pairing.\n" +
                            "Retrying every ${RECONNECT_RETRY_MS / 1000}s."
                    )
                }
                return RECONNECT_RETRY_MS
            }
            lastHeartbeatAt = clock()
            if (connectionAlertSent) {
                connectionAlertSent = false
                notifier.send("✅ Inverter connection restored")
            }
        }

        val now = clock()
        if (now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MS) {
            if (!source.heartbeat()) return 1_000L
            lastHeartbeatAt = now
        }

        val snapshot = source.readSnapshot() ?: return 1_000L // session dropped → re-setup quickly
        onSnapshot(snapshot, clock())
        return pollIntervalSeconds * 1000L
    }

    internal fun onSnapshot(snapshot: InverterSnapshot, now: Long) {
        latestSnapshot = snapshot.copy(timestamp = now)
        if (!initialized) {
            initialized = true
            currentInterval = repository.findLast()
            confirmedState = currentInterval?.state
            lastNotifiedSocThreshold = (snapshot.batterySoc / 10) * 10
            log.info("Last stored inverter grid state: {}", confirmedState)
        }

        val observed = if (snapshot.gridVoltage >= gridVoltageThreshold) GridState.CONNECTED else GridState.DISCONNECTED
        log.debug(
            "Inverter poll: observed={}, gridV={}, mode={}, soc={}%",
            observed, snapshot.gridVoltage, snapshot.mode, snapshot.batterySoc
        )

        if (observed == confirmedState) {
            candidateState = null
            candidateCount = 0
            recordObservation(observed, now, snapshot.batterySoc)
        } else {
            if (candidateState != observed) {
                candidateState = observed
                candidateSince = now
                candidateCount = 1
            } else {
                candidateCount++
            }
            if (candidateCount >= CONFIRM_POLLS) {
                transition(observed, candidateSince, now, snapshot.batterySoc)
                candidateState = null
                candidateCount = 0
            }
        }

        handleBatteryThresholds(snapshot.batterySoc)
    }

    private fun recordObservation(state: GridState, now: Long, soc: Int) {
        val cur = currentInterval
        currentInterval = if (cur != null && cur.state == state && now - cur.endTs <= gapMs) {
            repository.updateEnd(cur.id, now)
            cur.copy(endTs = now)
        } else {
            repository.insert(state, now, now, soc)
        }
    }

    private fun transition(newState: GridState, since: Long, now: Long, soc: Int) {
        val previous = confirmedState
        log.info("Grid state transition: {} -> {} (since {})", previous, newState, since)

        // Close the previous interval exactly at the transition point if it was continuous.
        currentInterval?.let { cur ->
            if (since - cur.endTs in 0..gapMs) repository.updateEnd(cur.id, since)
        }
        // How long the previous state lasted = since the last time the new state was seen.
        val lastSeenNewState = repository.findLastEnd(newState)
        currentInterval = repository.insert(newState, since, now, soc)
        confirmedState = newState

        if (previous == null) return // first ever record — nothing to compare against

        when (newState) {
            GridState.DISCONNECTED -> {
                lastNotifiedSocThreshold = (soc / 10) * 10
                val durationLine = lastSeenNewState?.let { "\n⏱ Electricity was available for ${formatHms(since - it)}" } ?: ""
                notifier.sendWithPhoto(
                    "⚡ POWER OUTAGE DETECTED\n" +
                        "Grid connection lost!\n" +
                        "🔋 Battery: $soc%" +
                        durationLine,
                    "/img/dead_bot.jpeg"
                )
            }
            GridState.CONNECTED -> {
                lastNotifiedSocThreshold = null
                val durationLine = lastSeenNewState?.let { "\n⏱ Outage was for ${formatHms(since - it)}" } ?: ""
                notifier.sendWithPhoto(
                    "✅ GRID RESTORED\n" +
                        "Power is back!\n" +
                        "🔋 Battery: $soc%" +
                        durationLine,
                    "/img/alive_bot.jpg"
                )
            }
        }
    }

    private fun handleBatteryThresholds(soc: Int) {
        if (confirmedState != GridState.DISCONNECTED) return
        val threshold = (soc / 10) * 10
        if (threshold < 10) return
        val last = lastNotifiedSocThreshold
        if (last == null || threshold < last) {
            log.info("Battery threshold crossed: {}% (was {}%)", threshold, last)
            lastNotifiedSocThreshold = threshold
            notifier.send("🔋 Battery at $threshold%\nRunning on battery power.")
        }
    }

    /** Latest snapshot if it is recent enough to be shown as "live". */
    fun getLatestSnapshot(): InverterSnapshot? =
        latestSnapshot?.takeIf { clock() - it.timestamp <= SNAPSHOT_MAX_AGE_MS }

    fun getGridState(): GridState? = confirmedState

    private fun formatHms(millis: Long): String {
        val totalSeconds = maxOf(0L, millis / 1000)
        return "%02d:%02d:%02d".format(totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60)
    }
}
