package com.mthkr.notifications

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.network.fold
import com.github.kotlintelegrambot.entities.ChatId
import org.slf4j.LoggerFactory

class Notifier(
    private val bot: Bot,
    private val chatIds: List<Long>
) {
    private val log = LoggerFactory.getLogger(Notifier::class.java)

    fun send(message: String) {
        for (chatId in chatIds) {
            try {
                bot.sendMessage(
                    chatId = ChatId.fromId(chatId),
                    text = message
                ).fold(
                    ifSuccess = {
                        log.info("Notification sent to {}: {}", chatId, message.lines().first())
                    },
                    ifError = { error ->
                        log.warn("Failed to send Telegram message to {}: {}", chatId, error)
                    }
                )
            } catch (e: Exception) {
                log.warn("Exception sending Telegram message to {}: {}", chatId, e.message)
            }
        }
    }
}