package com.mthkr

import com.mthkr.bot.buildBot
import com.mthkr.config.loadConfig
import com.mthkr.db.BotOnlineRepository
import com.mthkr.db.GridStateRepository
import com.mthkr.marstek.MarstekClient
import com.mthkr.monitor.GridMonitor
import com.mthkr.monitor.GridState
import com.mthkr.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val botOnlineRepository = BotOnlineRepository(config.database.dbPath)

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