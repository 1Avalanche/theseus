package theseus.agent.telegram

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.extensions.api.send.sendActionTyping
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.types.chat.PrivateChat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import theseus.agent.pipeline.MessageHandler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Long polling Telegram. Только личные чаты; сообщения одного chatId обрабатываются строго
 * последовательно (мьютекс на чат), разные чаты — параллельно.
 */
class TelegramBotRunner(
    private val bot: TelegramBot,
    private val handler: MessageHandler,
) {
    private val log = LoggerFactory.getLogger(TelegramBotRunner::class.java)
    private val chatLocks = ConcurrentHashMap<Long, Mutex>()

    suspend fun run(scope: CoroutineScope): Job =
        bot.buildBehaviourWithLongPolling(scope) {
            onText { message ->
                if (message.chat !is PrivateChat) return@onText
                val chatId = message.chat.id.chatId.long
                chatLocks.computeIfAbsent(chatId) { Mutex() }.withLock {
                    try {
                        bot.sendActionTyping(message.chat.id)
                        val reply = handler.handle(chatId, message.content.text)
                        if (reply != null) bot.sendTextMessage(message.chat.id, reply)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        log.error("Ошибка обработки сообщения", e)
                    }
                }
            }
        }
}

/**
 * Пульс для healthcheck Docker: раз в [interval] проверяет связь с Telegram (`getMe`) и при успехе
 * обновляет время изменения [file]. Контейнер здоров, пока файл свежий (`docs/operations.md`).
 */
class HealthHeartbeat(
    private val bot: TelegramBot,
    private val file: Path,
    private val interval: Duration,
) {
    private val log = LoggerFactory.getLogger(HealthHeartbeat::class.java)

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                try {
                    bot.getMe()
                    if (Files.notExists(file)) Files.createFile(file)
                    Files.setLastModifiedTime(file, FileTime.from(Instant.now()))
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    log.warn("Telegram недоступен: {}", e.javaClass.simpleName)
                }
                delay(interval.toMillis())
            }
        }
}
