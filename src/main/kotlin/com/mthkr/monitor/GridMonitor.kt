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

        val currentGridState = determineGridState(status.ongrid_power, status.offgrid_power)
        log.debug("Poll result: gridState={}, soc={}%, ongrid={}W, offgrid={}W",
            currentGridState, soc, status.ongrid_power, status.offgrid_power)

        handleGridStateTransition(currentGridState, soc)
        handleBatteryThresholds(currentGridState, soc)

        lastGridState = currentGridState
    }

    private fun determineGridState(ongridPower: Int?, offgridPower: Int?): GridState {
        return when {
            offgridPower != null && offgridPower > 0 && (ongridPower == null || ongridPower == 0) ->
                GridState.DISCONNECTED
            ongridPower != null && ongridPower != 0 ->
                GridState.CONNECTED
            offgridPower == null || offgridPower == 0 ->
                GridState.CONNECTED
            else -> GridState.UNKNOWN
        }
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