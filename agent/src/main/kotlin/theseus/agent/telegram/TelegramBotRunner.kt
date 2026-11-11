package theseus.agent.telegram

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.send.sendActionTyping
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.types.chat.PrivateChat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import theseus.agent.pipeline.MessageHandler
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
                        log.error("Ошибка обработки сообщения: {}", e.javaClass.simpleName)
                    }
                }
            }
        }
}
