package com.mthkr.bot

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.KeyboardReplyMarkup
import com.github.kotlintelegrambot.entities.ReplyMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.entities.keyboard.KeyboardButton
import com.mthkr.inverter.InverterMode
import com.mthkr.inverter.InverterSnapshot
import com.mthkr.marstek.MarstekClient
import com.mthkr.monitor.GridState
import com.mthkr.stats.StatsPeriod
import com.mthkr.stats.StatsService
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("BotHandler")

// ---- Reply keyboard (/menu) labels
private const val BTN_STATUS = "📊 Status"
private const val BTN_STATS = "📈 Stats"
private const val BTN_SUMMARY = "📋 Summary"
private const val BTN_STATISTIC = "📅 Statistic"
private const val BTN_TODAY = "Today"
private const val BTN_WEEK = "7 days"
private const val BTN_MONTH = "Month"
private const val BTN_BACK = "⬅️ Back"

private enum class StatsKind { SUMMARY, STATISTIC }

/** Reply-keyboard navigation level per chat: null = root, STATS = Summary/Statistic, kind = period level. */
private sealed interface MenuLevel {
    data object Stats : MenuLevel
    data class Period(val kind: StatsKind) : MenuLevel
}

/** Data the bot handlers need from the rest of the app (lambdas avoid circular construction). */
class BotDependencies(
    val marstekClient: MarstekClient,
    val inverterSnapshotProvider: () -> InverterSnapshot?,
    val gridStateProvider: () -> GridState?,
    val statsServiceProvider: () -> StatsService,
)

fun buildBot(
    token: String,
    allowedChatIds: List<Long>,
    deps: BotDependencies,
) = bot {
    this.token = token
    val menuLevels = ConcurrentHashMap<Long, MenuLevel>()

    dispatch {
        command("status") {
            val chatId = message.chat.id
            if (!authorized(chatId, allowedChatIds, "/status")) return@command
            bot.sendMessage(ChatId.fromId(chatId), buildStatusText(deps))
        }

        command("menu") {
            val chatId = message.chat.id
            if (!authorized(chatId, allowedChatIds, "/menu")) return@command
            menuLevels.remove(chatId)
            bot.sendMessage(ChatId.fromId(chatId), "Menu:", replyMarkup = rootKeyboard())
        }

        command("stats") {
            val chatId = message.chat.id
            if (!authorized(chatId, allowedChatIds, "/stats")) return@command
            bot.sendMessage(ChatId.fromId(chatId), "📈 Stats — choose a report:", replyMarkup = inlineRoot())
        }

        // Reply keyboard buttons (plain text messages)
        text {
            val chatId = message.chat.id
            if (text.startsWith("/")) return@text
            if (chatId !in allowedChatIds) return@text
            val id = ChatId.fromId(chatId)
            when (text) {
                BTN_STATUS -> bot.sendMessage(id, buildStatusText(deps))
                BTN_STATS -> {
                    menuLevels[chatId] = MenuLevel.Stats
                    bot.sendMessage(id, "📈 Stats — choose a report:", replyMarkup = statsKeyboard())
                }
                BTN_SUMMARY, BTN_STATISTIC -> {
                    val kind = if (text == BTN_SUMMARY) StatsKind.SUMMARY else StatsKind.STATISTIC
                    menuLevels[chatId] = MenuLevel.Period(kind)
                    bot.sendMessage(id, "${text} — choose a period:", replyMarkup = periodKeyboard())
                }
                BTN_TODAY, BTN_WEEK, BTN_MONTH -> {
                    val kind = (menuLevels[chatId] as? MenuLevel.Period)?.kind ?: StatsKind.SUMMARY
                    sendStats(bot, chatId, kind, periodOf(text), deps)
                }
                BTN_BACK -> when (menuLevels[chatId]) {
                    is MenuLevel.Period -> {
                        menuLevels[chatId] = MenuLevel.Stats
                        bot.sendMessage(id, "📈 Stats — choose a report:", replyMarkup = statsKeyboard())
                    }
                    else -> {
                        menuLevels.remove(chatId)
                        bot.sendMessage(id, "Menu:", replyMarkup = rootKeyboard())
                    }
                }
            }
        }

        // Inline keyboard (/stats). Data: "stats", "stats:summary", "stats:statistic:today", ...
        callbackQuery {
            val data = callbackQuery.data
            if (!data.startsWith("stats")) return@callbackQuery
            val message = callbackQuery.message ?: return@callbackQuery
            val chatId = message.chat.id
            if (!authorized(chatId, allowedChatIds, "stats callback")) return@callbackQuery
            bot.answerCallbackQuery(callbackQuery.id)

            val parts = data.split(":")
            val id = ChatId.fromId(chatId)
            when (parts.size) {
                1 -> editMenu(bot, id, message.messageId, "📈 Stats — choose a report:", inlineRoot())
                2 -> {
                    val title = if (parts[1] == "summary") "📋 Summary" else "📅 Statistic"
                    editMenu(bot, id, message.messageId, "$title — choose a period:", inlinePeriods(parts[1]))
                }
                3 -> {
                    val kind = if (parts[1] == "summary") StatsKind.SUMMARY else StatsKind.STATISTIC
                    val period = runCatching { StatsPeriod.valueOf(parts[2].uppercase()) }.getOrNull() ?: return@callbackQuery
                    sendStats(bot, chatId, kind, period, deps)
                }
            }
        }
    }
}

private fun authorized(chatId: Long, allowed: List<Long>, what: String): Boolean {
    if (chatId in allowed) {
        log.info("Received {} from chat {}", what, chatId)
        return true
    }
    log.warn("Ignoring {} from unauthorized chat {}", what, chatId)
    return false
}

private fun editMenu(bot: Bot, chatId: ChatId, messageId: Long, text: String, markup: ReplyMarkup) {
    // Cast hides retrofit's Response type (not on our compile classpath).
    val result: Any = bot.editMessageText(chatId, messageId, text = text, replyMarkup = markup)
    val error = (result as Pair<*, *>).second as? Exception
    if (error != null) log.warn("Failed to edit stats menu: {}", error.message)
}

private fun periodOf(label: String) = when (label) {
    BTN_TODAY -> StatsPeriod.TODAY
    BTN_WEEK -> StatsPeriod.WEEK
    else -> StatsPeriod.MONTH
}

private fun sendStats(bot: Bot, chatId: Long, kind: StatsKind, period: StatsPeriod, deps: BotDependencies) {
    val id = ChatId.fromId(chatId)
    try {
        val stats = deps.statsServiceProvider()
        val messages = when (kind) {
            StatsKind.SUMMARY -> listOf(stats.summary(period))
            StatsKind.STATISTIC -> stats.statistic(period)
        }
        messages.forEach { bot.sendMessage(id, it) }
    } catch (e: Exception) {
        log.error("Failed to build stats: {}", e.message, e)
        bot.sendMessage(id, "⚠️ Failed to build stats: ${e.message}")
    }
}

// ---------------------------------------------------------------- keyboards

private fun replyKeyboard(vararg rows: List<String>): ReplyMarkup =
    KeyboardReplyMarkup(
        keyboard = rows.map { row -> row.map { KeyboardButton(it) } },
        resizeKeyboard = true,
    )

private fun rootKeyboard() = replyKeyboard(listOf(BTN_STATUS, BTN_STATS))
private fun statsKeyboard() = replyKeyboard(listOf(BTN_SUMMARY, BTN_STATISTIC), listOf(BTN_BACK))
private fun periodKeyboard() = replyKeyboard(listOf(BTN_TODAY, BTN_WEEK, BTN_MONTH), listOf(BTN_BACK))

private fun inlineRoot() = InlineKeyboardMarkup.create(
    listOf(
        InlineKeyboardButton.CallbackData("📋 Summary", "stats:summary"),
        InlineKeyboardButton.CallbackData("📅 Statistic", "stats:statistic"),
    )
)

private fun inlinePeriods(kind: String) = InlineKeyboardMarkup.create(
    listOf(
        InlineKeyboardButton.CallbackData(BTN_TODAY, "stats:$kind:today"),
        InlineKeyboardButton.CallbackData(BTN_WEEK, "stats:$kind:week"),
        InlineKeyboardButton.CallbackData(BTN_MONTH, "stats:$kind:month"),
    ),
    listOf(InlineKeyboardButton.CallbackData(BTN_BACK, "stats")),
)

// ---------------------------------------------------------------- /status

internal fun buildStatusText(deps: BotDependencies): String {
    val inverter = formatInverterSection(deps.inverterSnapshotProvider())
    val marstek = formatMarstekSection(deps)
    return "$inverter\n--------------------\n\n$marstek"
}

internal fun formatInverterSection(s: InverterSnapshot?): String {
    if (s == null) {
        return "🔌 INVERTER:\n\n⚠️ Inverter not reachable (no fresh data from the dongle)."
    }
    val status = when (s.mode) {
        InverterMode.LINE -> "🔌 GRID"
        InverterMode.BATTERY -> "🔋 BATTERY"
        InverterMode.STANDBY -> "💤 STANDBY"
        InverterMode.POWER_ON -> "⏻ POWER ON"
        InverterMode.UNKNOWN -> "❓ UNKNOWN (${s.modeCode})"
    }
    fun d(v: Double) = String.format(Locale.US, "%.1f", v)
    return "🔌 INVERTER:\n\n" +
        "Status: $status\n" +
        "Grid: ${d(s.gridVoltage)} V, ${String.format(Locale.US, "%.2f", s.gridFrequency)} Hz, ${s.gridPower} W\n" +
        "Output: ${d(s.outputVoltage)} V, ${d(s.outputCurrent)} A, ${s.outputPower} W\n" +
        "Battery: ${d(s.batteryVoltage)} V, ${s.batterySoc}%\n" +
        "PV: ${d(s.pvVoltage)} V, ${s.pvPower} W (charging ${s.pvChargingPower} W)\n" +
        "Temps: inv ${s.inverterTemp}°C, DC ${s.dcTemp}°C"
}

private fun formatMarstekSection(deps: BotDependencies): String {
    val status = deps.marstekClient.getEsStatus()
        ?: return "Ⓜ️ MARSTEK:\n\n⚠️ Could not reach Marstek device."
    val gridLabel = when (deps.gridStateProvider()) {
        GridState.CONNECTED -> "✅ Connected"
        GridState.DISCONNECTED -> "❌ Disconnected"
        null -> "❔ Unknown"
    }
    return "Ⓜ️ MARSTEK:\n\n" +
        "Grid: $gridLabel\n" +
        "🔋 Battery: ${status.bat_soc ?: "N/A"}%\n" +
        "⚡ Grid Power: ${status.ongrid_power ?: 0}W\n" +
        "🏠 Off-grid Power: ${status.offgrid_power ?: 0}W"
}
