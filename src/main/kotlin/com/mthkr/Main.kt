package com.mthkr

import com.mthkr.bot.buildBot
import com.mthkr.config.loadConfig
import com.mthkr.db.GridStateRepository
import com.mthkr.marstek.MarstekClient
import com.mthkr.monitor.GridMonitor
import com.mthkr.monitor.GridState
import com.mthkr.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

class Main

fun main(args: Array<String>) {
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

    val marstekClient = MarstekClient(
        deviceIp = config.marstek.deviceIp,
        udpPort = config.marstek.udpPort
    )

    // GridMonitor is created first; bot references its state via a lambda.
    // This breaks the circular dependency: GridMonitor -> Notifier -> Bot -> GridMonitor.
    // Use a nullable var (not lateinit) so the lambda can safely check before gridMonitor is assigned.
    var gridMonitor: GridMonitor? = null

    val bot = buildBot(
        token = config.bot.tgBotToken,
        allowedChatIds = chatIds,
        marstekClient = marstekClient,
        gridStateProvider = { gridMonitor?.getLastGridState() ?: GridState.UNKNOWN }
    )

    val notifier = Notifier(bot = bot, chatIds = chatIds)

    val repository = GridStateRepository(config.database.dbPath)

    val monitor = GridMonitor(
        client = marstekClient,
        notifier = notifier,
        pollIntervalSeconds = config.marstek.pollIntervalSeconds,
        repository = repository
    )
    gridMonitor = monitor

    log.info("Starting Marstek Venus-A bot...")
    log.info("Device: {}:{}", config.marstek.deviceIp, config.marstek.udpPort)
    log.info("Poll interval: {}s", config.marstek.pollIntervalSeconds)
    log.info("Notification targets: {}", chatIds)

    val scope = CoroutineScope(Dispatchers.IO)
    monitor.start(scope)

    runBlocking {
        bot.startPolling()
    }
}