package com.mthkr.monitor

import com.mthkr.marstek.MarstekClient
import com.mthkr.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

enum class GridState {
    CONNECTED,
    DISCONNECTED,
    UNKNOWN
}

class GridMonitor(
    private val client: MarstekClient,
    private val notifier: Notifier,
    private val pollIntervalSeconds: Long
) {
    private val log = LoggerFactory.getLogger(GridMonitor::class.java)

    @Volatile private var lastGridState: GridState = GridState.UNKNOWN
    @Volatile private var lastNotifiedSocThreshold: Int? = null
    @Volatile private var previousSoc: Int? = null
    private var suspectedOutageCount: Int = 0

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            log.info("GridMonitor started, polling every {}s", pollIntervalSeconds)
            while (true) {
                try {
                    poll()
                } catch (e: Exception) {
                    log.error("Unexpected error in polling loop: {}", e.message, e)
                }
                delay(pollIntervalSeconds * 1000L)
            }
        }
    }

    private fun poll() {
        val status = client.getEsStatus()
        if (status == null) {
            log.warn("Could not retrieve ES status — skipping this poll cycle")
            return
        }

        val soc = status.bat_soc ?: run {
            log.warn("ES.GetStatus missing bat_soc field")
            return
        }

        val currentGridState = determineGridState(status.ongrid_power, status.offgrid_power, soc)
        log.debug(
            "Poll result: gridState={}, soc={}%, ongrid={}W, offgrid={}W, prevSoc={}, suspectedOutage={}",
            currentGridState, soc, status.ongrid_power, status.offgrid_power,
            previousSoc, suspectedOutageCount
        )

        handleGridStateTransition(currentGridState, soc)
        handleBatteryThresholds(currentGridState, soc)

        lastGridState = currentGridState
        previousSoc = soc
    }

    /**
     * Grid state detection strategy:
     *
     * 1. ongrid_power != 0 → unambiguously CONNECTED (inverter actively exchanging with grid)
     * 2. offgrid_power == 0 → no load, device idle → CONNECTED
     * 3. offgrid_power > 0 AND ongrid_power == 0 → ambiguous: bypass mode or actual outage.
     *    The only reliable software signal without a CT sensor is bat_soc declining:
     *    - In bypass mode the grid keeps bat_soc stable.
     *    - During a real outage the battery discharges and bat_soc falls.
     *    Require 2 consecutive polls where soc has not increased to confirm DISCONNECTED.
     *    This adds ~1 poll (~30 s) of detection latency but eliminates bypass false positives.
     */
    private fun determineGridState(ongridPower: Int?, offgridPower: Int?, soc: Int): GridState {
        if (ongridPower != null && ongridPower != 0) {
            suspectedOutageCount = 0
            return GridState.CONNECTED
        }
        if (offgridPower == null || offgridPower == 0) {
            suspectedOutageCount = 0
            return GridState.CONNECTED
        }

        // offgrid > 0, ongrid == 0 — check if battery is actually draining
        val prev = previousSoc
        val socRising = prev != null && soc > prev

        if (socRising) {
            // SoC went up → battery is charging → grid must be connected
            suspectedOutageCount = 0
            return GridState.CONNECTED
        }

        // SoC flat or declining — could be bypass (stable at 100%) or real outage
        suspectedOutageCount++
        return if (suspectedOutageCount >= 2) GridState.DISCONNECTED else GridState.UNKNOWN
    }

    private fun handleGridStateTransition(currentState: GridState, soc: Int) {
        when {
            lastGridState != GridState.DISCONNECTED && currentState == GridState.DISCONNECTED -> {
                log.info("Grid state transition: {} -> DISCONNECTED", lastGridState)
                lastNotifiedSocThreshold = (soc / 10) * 10
                notifier.send(
                    "⚡ POWER OUTAGE DETECTED\n" +
                    "Grid connection lost!\n" +
                    "🔋 Battery: $soc%"
                )
            }
            lastGridState == GridState.DISCONNECTED && currentState == GridState.CONNECTED -> {
                log.info("Grid state transition: DISCONNECTED -> CONNECTED")
                lastNotifiedSocThreshold = null
                notifier.send(
                    "✅ GRID RESTORED\n" +
                    "Power is back!\n" +
                    "🔋 Battery: $soc%"
                )
            }
            else -> {
                if (lastGridState != currentState) {
                    log.info("Grid state changed: {} -> {}", lastGridState, currentState)
                }
            }
        }
    }

    private fun handleBatteryThresholds(currentState: GridState, soc: Int) {
        if (currentState != GridState.DISCONNECTED) return

        val currentThreshold = (soc / 10) * 10
        val lastThreshold = lastNotifiedSocThreshold

        if (currentThreshold < 10) return  // below 10%, device handles it

        if (lastThreshold == null || currentThreshold < lastThreshold) {
            log.info("Battery threshold crossed: {}% (was {}%)", currentThreshold, lastThreshold)
            lastNotifiedSocThreshold = currentThreshold
            notifier.send(
                "🔋 Battery at $currentThreshold%\n" +
                "Running on battery power."
            )
        }
    }

    fun getLastGridState(): GridState = lastGridState
}