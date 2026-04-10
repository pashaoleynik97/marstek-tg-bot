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
    @Volatile private var wasDisconnected: Boolean = false

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
            "Poll result: gridState={}, soc={}%, ongrid={}W, offgrid={}W, prevSoc={}",
            currentGridState, soc, status.ongrid_power, status.offgrid_power, previousSoc
        )

        handleGridStateTransition(currentGridState, soc)
        handleBatteryThresholds(currentGridState, soc)

        lastGridState = currentGridState
        previousSoc = soc
    }

    /**
     * Grid state detection (no CT sensor available):
     *
     * When ongrid_power == 0 and offgrid_power > 0 the device is either:
     *   A) In bypass mode — grid powers the load directly, battery is idle, SoC is flat.
     *   B) Real outage  — battery discharges to power the load, SoC is falling.
     *
     * The only reliable distinguishing signal is bat_soc direction:
     *   - SoC rising  → battery charging    → grid is on
     *   - SoC falling → battery discharging → actual outage
     *   - SoC flat    → UNKNOWN (bypass at full charge, or very early outage — stay silent)
     *
     * Detection latency for an outage that starts at 100% SoC: up to ~1 poll cycle after
     * SoC first ticks down (~6 min at 200 W load on a 2 kWh battery).
     */
    private fun determineGridState(ongridPower: Int?, offgridPower: Int?, soc: Int): GridState {
        if (ongridPower != null && ongridPower != 0) return GridState.CONNECTED
        if (offgridPower == null || offgridPower == 0) return GridState.CONNECTED

        val prev = previousSoc
        return when {
            prev == null    -> GridState.UNKNOWN       // no baseline yet
            soc > prev      -> GridState.CONNECTED     // SoC rising  → grid charging battery
            soc < prev      -> GridState.DISCONNECTED  // SoC falling → battery draining → outage
            else            -> GridState.UNKNOWN       // SoC flat    → bypass or very early outage
        }
    }

    private fun handleGridStateTransition(currentState: GridState, soc: Int) {
        when {
            !wasDisconnected && currentState == GridState.DISCONNECTED -> {
                log.info("Grid state transition: {} -> DISCONNECTED", lastGridState)
                wasDisconnected = true
                lastNotifiedSocThreshold = (soc / 10) * 10
                notifier.sendWithPhoto(
                    "⚡ POWER OUTAGE DETECTED\n" +
                    "Grid connection lost!\n" +
                    "🔋 Battery: $soc%",
                    "/img/dead_bot.jpeg"
                )
            }
            wasDisconnected && currentState == GridState.CONNECTED -> {
                log.info("Grid state transition: DISCONNECTED -> CONNECTED (via {})", lastGridState)
                wasDisconnected = false
                lastNotifiedSocThreshold = null
                notifier.sendWithPhoto(
                    "✅ GRID RESTORED\n" +
                    "Power is back!\n" +
                    "🔋 Battery: $soc%",
                    "/img/alive_bot.jpg"
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