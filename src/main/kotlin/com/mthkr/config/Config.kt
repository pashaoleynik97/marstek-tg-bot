package com.mthkr.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class AppConfig(
    val bot: BotConfig,
    val marstek: MarstekConfig,
    val inverter: InverterConfig,
    val database: DatabaseConfig = DatabaseConfig(),
    val forecast: ForecastConfig = ForecastConfig(),
    /** IANA zone used for stats day boundaries and HH:mm times. */
    val timezone: String = "Europe/Kyiv"
)

@Serializable
data class InverterConfig(
    @SerialName("dongle-ip") val dongleIp: String,
    @SerialName("dongle-udp-port") val dongleUdpPort: Int = 58899,
    @SerialName("tcp-port") val tcpPort: Int = 8899,
    @SerialName("modbus-slave") val modbusSlave: Int = 1,
    @SerialName("poll-interval-seconds") val pollIntervalSeconds: Long = 10,
    @SerialName("grid-voltage-threshold") val gridVoltageThreshold: Double = 170.0
)

@Serializable
data class ForecastConfig(
    /** Usable battery energy from 100 % to 0 %, Wh. */
    @SerialName("battery-capacity-wh") val batteryCapacityWh: Double = 10_000.0,
    /** Forecasts estimate time until the battery reaches this SoC, %. */
    @SerialName("cutoff-soc") val cutoffSoc: Int = 12,
    /** How often a history sample is recorded while running on battery. */
    @SerialName("sample-interval-minutes") val sampleIntervalMinutes: Long = 5,
    /** Samples older than this are deleted. */
    @SerialName("retention-days") val retentionDays: Long = 7
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
    /** No longer used (Marstek is only queried on /status); kept for config compatibility. */
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