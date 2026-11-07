package com.mthkr

import com.github.kotlintelegrambot.entities.BotCommand
import com.mthkr.bot.BotDependencies
import com.mthkr.bot.buildBot
import com.mthkr.config.loadConfig
import com.mthkr.db.BatterySampleRepository
import com.mthkr.db.BotOnlineRepository
import com.mthkr.db.InverterStateRepository
import com.mthkr.forecast.BatterySampleRecorder
import com.mthkr.forecast.ForecastService
import com.mthkr.inverter.InverterConnection
import com.mthkr.marstek.MarstekClient
import com.mthkr.monitor.InverterMonitor
import com.mthkr.monitor.gapToleranceMs
import com.mthkr.notifications.Notifier
import com.mthkr.stats.StatsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.ZoneId

class Main

fun main(args: Array<String>) {
    // Infographics are rendered with Java2D; no display is available on the server.
    System.setProperty("java.awt.headless", "true")
    val log = LoggerFactory.getLogger(Main::class.java)

    val configPath = if (args.size >= 2 && args[0] == "--config") args[1] else "config.yml"

    log.info("Loading config from {}", configPath)
    val config = loadConfig(configPath)

    val chatIds = config.bot.tgChatIds
    if (chatIds.isEmpty()) {
        System.err.println("tg-chat-ids in config is empty — add at least one chat ID")
        System.exit(1)
        return
    }

    val zone = try {
        ZoneId.of(config.timezone)
    } catch (e: Exception) {
        System.err.println("Invalid timezone '${config.timezone}': ${e.message}")
        System.exit(1)
        return
    }

    val marstekClient = MarstekClient(
        deviceIp = config.marstek.deviceIp,
        udpPort = config.marstek.udpPort
    )

    val inverterConfig = config.inverter
    val inverterConnection = InverterConnection(
        dongleIp = inverterConfig.dongleIp,
        dongleUdpPort = inverterConfig.dongleUdpPort,
        tcpPort = inverterConfig.tcpPort,
        modbusSlave = inverterConfig.modbusSlave
    )
    val inverterRepository = InverterStateRepository(config.database.dbPath)
    val botOnlineRepository = BotOnlineRepository(config.database.dbPath)
    val statsService = StatsService(
        repository = inverterRepository,
        zone = zone,
        gapMs = gapToleranceMs(inverterConfig.pollIntervalSeconds)
    )

    val forecastConfig = config.forecast
    val batterySampleRepository = BatterySampleRepository(config.database.dbPath)

    // The monitor needs the notifier (which needs the bot), and the bot reads monitor state —
    // break the cycle with a nullable var referenced from lambdas.
    var inverterMonitor: InverterMonitor? = null
    val forecastService = ForecastService(
        repository = batterySampleRepository,
        config = forecastConfig,
        zone = zone,
        snapshotProvider = { inverterMonitor?.getLatestSnapshot() },
        gridStateProvider = { inverterMonitor?.getGridState() }
    )

    val bot = buildBot(
        token = config.bot.tgBotToken,
        allowedChatIds = chatIds,
        deps = BotDependencies(
            marstekClient = marstekClient,
            inverterSnapshotProvider = { inverterMonitor?.getLatestSnapshot() },
            gridStateProvider = { inverterMonitor?.getGridState() },
            statsServiceProvider = { statsService },
            forecastServiceProvider = { forecastService }
        )
    )

    val notifier = Notifier(bot = bot, chatIds = chatIds)

    val monitor = InverterMonitor(
        source = inverterConnection,
        notifier = notifier,
        repository = inverterRepository,
        pollIntervalSeconds = inverterConfig.pollIntervalSeconds,
        gridVoltageThreshold = inverterConfig.gridVoltageThreshold,
        sampleRecorder = BatterySampleRecorder(
            repository = batterySampleRepository,
            intervalMs = forecastConfig.sampleIntervalMinutes * 60_000L,
            retentionMs = forecastConfig.retentionDays * 86_400_000L
        )
    )
    inverterMonitor = monitor

    bot.setMyCommands(
        listOf(
            BotCommand("status", "Inverter + Marstek status"),
            BotCommand("stats", "Outage statistics"),
            BotCommand("menu", "Show button menu")
        )
    ).fold(
        ifSuccess = { log.info("Bot commands registered") },
        ifError = { log.warn("Failed to register bot commands: {}", it) }
    )

    log.info("Starting Marstek / inverter bot...")
    log.info("Marstek device: {}:{}", config.marstek.deviceIp, config.marstek.udpPort)
    log.info("Inverter dongle: {} (TCP server :{}, poll {}s)", inverterConfig.dongleIp, inverterConfig.tcpPort, inverterConfig.pollIntervalSeconds)
    log.info("Timezone: {}", zone)
    log.info("Forecast: battery {} Wh, cutoff {}%", forecastConfig.batteryCapacityWh, forecastConfig.cutoffSoc)
    log.info("Notification targets: {}", chatIds)

    val now = System.currentTimeMillis()
    val lastOnline = botOnlineRepository.getLastOnlineTimestamp()
    val startupMessage = if (lastOnline == null) {
        "Bot is online now. First launch!"
    } else {
        "Bot is online now. Offline duration: ${formatOfflineDuration(now - lastOnline)}"
    }
    notifier.send(startupMessage)
    botOnlineRepository.updateTimestamp(now)

    val scope = CoroutineScope(Dispatchers.IO)

    scope.launch {
        while (true) {
            delay(10_000L)
            try {
                botOnlineRepository.updateTimestamp()
            } catch (e: Exception) {
                log.error("Failed to update bot_online timestamp: {}", e.message, e)
            }
        }
    }

    monitor.start(scope)

    runBlocking {
        bot.startPolling()
    }
}

private fun formatOfflineDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    return when {
        hours > 0 -> "%02d:%02d:%02d".format(hours, minutes, seconds)
        minutes > 0 && seconds > 0 -> "$minutes minutes and $seconds seconds"
        minutes > 0 -> "$minutes minutes"
        else -> "$totalSeconds seconds"
    }
}