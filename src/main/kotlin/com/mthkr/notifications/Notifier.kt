package com.mthkr.notifications

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.network.fold
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
                    ifSuccess = { log.info("Notification sent to {}: {}", chatId, message.lines().first()) },
                    ifError = { error -> log.warn("Failed to send message to {}: {}", chatId, error) }
                )
            } catch (e: Exception) {
                log.warn("Exception sending message to {}: {}", chatId, e.message)
            }
        }
    }

    fun sendWithPhoto(message: String, imageResourcePath: String) {
        val imageBytes = Notifier::class.java.getResourceAsStream(imageResourcePath)?.readBytes()
        if (imageBytes == null) {
            log.warn("Image resource not found: {} — falling back to text", imageResourcePath)
            send(message)
            return
        }
        val filename = imageResourcePath.substringAfterLast("/")
        for (chatId in chatIds) {
            try {
                @Suppress("UNCHECKED_CAST")
                val exception = (bot.sendPhoto(
                    chatId = ChatId.fromId(chatId),
                    photo = TelegramFile.ByByteArray(imageBytes, filename),
                    caption = message
                ) as Pair<*, Exception?>).second
                if (exception != null) {
                    log.warn("Failed to send photo to {}: {}", chatId, exception.message)
                } else {
                    log.info("Photo notification sent to {}: {}", chatId, message.lines().first())
                }
            } catch (e: Exception) {
                log.warn("Exception sending photo to {}: {}", chatId, e.message)
            }
        }
    }
}