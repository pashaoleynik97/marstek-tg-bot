package com.mthkr.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class AppConfig(
    val bot: BotConfig,
    val marstek: MarstekConfig,
    val database: DatabaseConfig = DatabaseConfig()
)

@Serializable
data class DatabaseConfig(
    @SerialName("db-path") val dbPath: String = "data/grid_state.db"
)

@Serializable
data class BotConfig(
    @SerialName("tg-bot-token") val tgBotToken: String,
    @SerialName("tg-chat-ids") val tgChatIds: List<Long>
)

@Serializable
data class MarstekConfig(
    @SerialName("device-ip") val deviceIp: String,
    @SerialName("udp-port") val udpPort: Int = 30000,
    @SerialName("poll-interval-seconds") val pollIntervalSeconds: Long = 30
)

fun loadConfig(path: String): AppConfig {
    val file = File(path)
    if (!file.exists()) {
        System.err.println("Config file not found: $path")
        System.exit(1)
    }
    return try {
        Yaml.default.decodeFromString(AppConfig.serializer(), file.readText())
    } catch (e: Exception) {
        System.err.println("Failed to parse config file: ${e.message}")
        System.exit(1)
        throw e
    }
}