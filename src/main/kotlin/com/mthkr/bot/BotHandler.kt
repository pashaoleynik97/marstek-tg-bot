package com.mthkr.bot

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import com.mthkr.marstek.MarstekClient
import com.mthkr.monitor.GridState
import org.slf4j.LoggerFactory

fun buildBot(
    token: String,
    allowedChatIds: List<Long>,
    marstekClient: MarstekClient,
    gridStateProvider: () -> GridState
) = bot {
    val log = LoggerFactory.getLogger("BotHandler")

    this.token = token

    dispatch {
        command("status") {
            val senderChatId = message.chat.id
            if (senderChatId !in allowedChatIds) {
                log.warn("Ignoring /status from unauthorized chat {}", senderChatId)
                return@command
            }

            log.info("Received /status command from chat {}", senderChatId)
            val status = marstekClient.getEsStatus()

            if (status == null) {
                bot.sendMessage(
                    chatId = ChatId.fromId(senderChatId),
                    text = "⚠️ Could not reach Marstek device.\nCheck that it's powered on and on the same network."
                )
                return@command
            }

            val gridStateLabel = when (gridStateProvider()) {
                GridState.CONNECTED -> "✅ Connected"
                GridState.DISCONNECTED -> "❌ Disconnected"
                GridState.UNKNOWN -> "❓ Unknown"
            }

            val soc = status.bat_soc ?: "N/A"
            val ongridPower = status.ongrid_power ?: 0
            val offgridPower = status.offgrid_power ?: 0

            bot.sendMessage(
                chatId = ChatId.fromId(senderChatId),
                text = "📊 System Status\n\n" +
                    "Grid: $gridStateLabel\n" +
                    "🔋 Battery: $soc%\n" +
                    "⚡ Grid Power: ${ongridPower}W\n" +
                    "🏠 Off-grid Power: ${offgridPower}W"
            )
        }
    }
}