package theseus.agent.telegram

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.send.sendActionTyping
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onContentMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onMessageDataCallbackQuery
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import dev.inmo.tgbotapi.types.chat.PrivateChat
import dev.inmo.tgbotapi.types.message.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import theseus.agent.pipeline.MessageHandler
import theseus.agent.system.AgentStore
import theseus.agent.system.BotReply
import theseus.agent.system.Incoming
import theseus.agent.system.OutgoingOperations
import theseus.agent.system.SystemHandler
import java.util.concurrent.ConcurrentHashMap

/**
 * Long polling Telegram. Только личные чаты; сообщения одного chatId обрабатываются строго
 * последовательно (мьютекс на чат), разные чаты — параллельно.
 */
class TelegramBotRunner(
    private val bot: TelegramBot,
    private val handler: MessageHandler,
    private val system: SystemHandler,
    private val store: AgentStore,
    private val operations: OutgoingOperations,
    private val adminChatId: Long,
) {
    private val log = LoggerFactory.getLogger(TelegramBotRunner::class.java)
    private val chatLocks = ConcurrentHashMap<Long, Mutex>()

    suspend fun run(scope: CoroutineScope): Job {
        recover()
        scope.launch {
            while (true) {
                delay(RECOVERY_INTERVAL_MS)
                recover()
            }
        }
        return bot.buildBehaviourWithLongPolling(scope) {
            onContentMessage { message ->
                val chat = message.chat as? PrivateChat ?: return@onContentMessage
                val chatId = chat.id.chatId.long
                process(Incoming("message:$chatId:${message.messageId}", chatId, (message.content as? TextContent)?.text, chat.firstName))
            }
            onMessageDataCallbackQuery { query ->
                val chat = query.message.chat as? PrivateChat ?: return@onMessageDataCallbackQuery
                if (query.from.id.chatId != chat.id.chatId) return@onMessageDataCallbackQuery
                bot.answerCallbackQuery(query)
                process(Incoming("callback:${query.id}", chat.id.chatId.long, firstName = query.from.firstName, callback = query.data))
            }
        }
    }

    private suspend fun process(input: Incoming) =
        locked(input.chatId) {
            // Незавершённый шаг чата завершаем перед приёмом следующего шага онбординга.
            for (event in store.pendingEvents().filter { it.snapshot.input.chatId == input.chatId && it.snapshot.input.id != input.id }) {
                send(input.chatId, system.resume(event))
                store.delivered(event.snapshot.input.id)
            }
            val reply = system.handle(input)
            if (reply != null) {
                send(input.chatId, reply)
                store.delivered(input.id)
            } else {
                val event = system.beginDialogue(input)
                bot.sendActionTyping(ChatId(RawChatId(input.chatId)))
                val response =
                    handler
                        .handle(input.chatId, requireNotNull(input.text), input.id)
                        ?.let(::BotReply) ?: system.resume(event)
                store.finishEvent(input.id, response)
                send(input.chatId, response)
                store.delivered(input.id)
            }
        }

    private suspend fun send(
        chatId: Long,
        reply: BotReply,
    ) {
        if (reply.text.isNotBlank()) {
            val keyboard =
                reply.buttons.takeIf { it.isNotEmpty() }?.let { rows ->
                    InlineKeyboardMarkup(rows.map { row -> row.map { CallbackDataInlineKeyboardButton(it.text, it.data) } })
                }
            // Длинные административные списки делятся без parse_mode и без интерпретации текста как Markdown.
            telegramChunks(reply.text).forEachIndexed { index, chunk ->
                bot.sendTextMessage(ChatId(RawChatId(chatId)), chunk, replyMarkup = if (index == 0) keyboard else null)
            }
        }
        reply.adminNotice?.let { bot.sendTextMessage(ChatId(RawChatId(adminChatId)), it) }
    }

    private suspend fun recover() {
        try {
            operations.recover()
            for (event in store.pendingEvents()) {
                locked(event.snapshot.input.chatId) {
                    send(event.snapshot.input.chatId, system.resume(event))
                    store.delivered(event.snapshot.input.id)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.warn("Восстановление системных событий отложено: {}", e.javaClass.simpleName)
        }
    }

    private suspend fun locked(
        chatId: Long,
        action: suspend () -> Unit,
    ) {
        chatLocks.computeIfAbsent(chatId) { Mutex() }.withLock {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                log.error("Ошибка обработки события: {}", e.javaClass.simpleName)
            }
        }
    }

    companion object {
        private const val RECOVERY_INTERVAL_MS = 30_000L
    }
}

/** Не разрывает UTF-16 пару Unicode-эмодзи на границе сообщения Telegram. */
internal fun telegramChunks(
    text: String,
    limit: Int = 4000,
): List<String> =
    buildList {
        var offset = 0
        while (offset < text.length) {
            var end = minOf(offset + limit, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            add(text.substring(offset, end))
            offset = end
        }
    }
