package theseus.agent.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import theseus.agent.core.CoreClient
import theseus.agent.core.CoreUser
import theseus.agent.core.ToolResult
import theseus.agent.llm.ChatMessage
import theseus.agent.llm.LlmClient
import theseus.agent.llm.LlmRequest
import theseus.agent.llm.Role
import theseus.agent.llm.ToolCall
import theseus.agent.llm.ToolDefinition
import theseus.agent.prompt.PromptBuilder
import theseus.agent.system.BotReply
import theseus.agent.system.NoUsageRecorder
import theseus.agent.system.UsageRecorder
import theseus.agent.texts.Texts
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Обработка одного входящего текстового сообщения (v1: один агент с инструментами).
 *
 * 1. Пользователь ищется в core **до** LLM: незнакомый или неактивный chatId — без ответа и без вызова модели.
 * 2. /start, /help и /version отвечают шаблонами без LLM. Остальные запросы получают инструменты core и локального диалога.
 * 3. Вызовы инструментов уходят в core с `userId` в `_meta`. Успех — ответ кодом по шаблону;
 *    ошибка или нераспознанный результат — результат возвращается модели для исправления аргументов.
 *    Текст модели не подтверждает выполнение: без результата core ответ формируется по безопасному шаблону.
 *
 * Поиск кандидатов возвращается модели для выбора цели; списки и итог изменения рендерит код.
 * История остаётся этапом 5; критичные действия уже используют долговечные карточки подтверждения.
 * Расход каждого ответа модели сохраняется до исполнения инструментов.
 */
class MessageHandler(
    private val core: CoreClient,
    private val llm: LlmClient,
    private val prompt: PromptBuilder,
    private val renderer: ReplyRenderer,
    private val texts: Texts,
    private val clock: Clock,
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
    private val usage: UsageRecorder = NoUsageRecorder,
    private val actions: ReminderActions? = null,
) {
    private val log = LoggerFactory.getLogger(MessageHandler::class.java)
    private val toolsLock = Mutex()
    private val dialogue = DialogReplies(texts)
    private var tools: List<ToolDefinition>? = null

    /** Текст ответа или `null`, если отвечать не нужно. */
    suspend fun handle(
        chatId: Long,
        text: String,
        eventId: String = UUID.randomUUID().toString(),
    ): String? = handleReply(chatId, text, eventId)?.text

    suspend fun handleReply(
        chatId: Long,
        text: String,
        eventId: String,
    ): BotReply? {
        val user = core.findUserByChat(chatId)
        if (user == null || !user.isActive) {
            log.info("Сообщение от неизвестного или неактивного chatId отброшено")
            return null
        }
        return try {
            dialogue.commandReply(text, user)?.let(::BotReply) ?: converse(user, text, eventId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: theseus.agent.system.UncertainMcpException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.error("Не удалось обработать сообщение user_id={}", user.id, e)
            BotReply(texts.get("reply.failure"))
        }
    }

    private suspend fun converse(
        user: CoreUser,
        text: String,
        eventId: String,
    ): BotReply {
        val now = clock.instant()
        usage.before(user.id)
        val zone = ZoneId.of(requireNotNull(user.timezone))
        val messages =
            mutableListOf(
                ChatMessage(Role.SYSTEM, prompt.system(user, now)),
                ChatMessage(Role.USER, text),
            )
        repeat(maxToolRounds + 1) {
            round(user, messages, zone, now, "$eventId:usage:$it")?.let { return it }
        }
        log.warn("Превышено число раундов инструментов, user_id={}", user.id)
        return BotReply(texts.get("reply.failure"))
    }

    /** Один вызов модели. Возвращает ответ пользователю или `null`, если результаты инструментов ушли модели. */
    private suspend fun round(
        user: CoreUser,
        messages: MutableList<ChatMessage>,
        zone: ZoneId,
        now: Instant,
        usageKey: String,
    ): BotReply? {
        val invocationKey = "$usageKey:${UUID.randomUUID()}"
        val response = llm.complete(LlmRequest(messages, tools()))
        usage.record(user.id, invocationKey, response)
        log.debug("LLM ответила моделью {}, usage={}", response.model, response.usage)
        val calls = response.message.toolCalls
        if (calls.isEmpty()) {
            log.info("Ответ модели без вызова инструмента, user_id={}", user.id)
            return BotReply(texts.get(if (messages.any { it.role == Role.TOOL }) "reply.unconfirmed" else "reply.not_created"))
        }
        messages += response.message
        val results = calls.mapIndexed { index, call -> call to execute(user, call, "$usageKey:tool:$index") }
        val rendered =
            results.map { (call, execution) ->
                val result = execution.result
                when {
                    execution.reply != null -> execution.reply.text
                    result == null || result.isError -> null
                    call.name == "find_reminders" -> null
                    call.name == DialogReplies.TOOL_NAME -> result.text
                    else -> renderer.render(call.name, result.text, zone, now)
                }
            }
        val renderedReplies = rendered.filterNotNull()
        log.info("Обработаны инструменты, user_id={}, calls={}, rendered={}", user.id, calls.size, renderedReplies.size)
        return if (renderedReplies.isNotEmpty()) {
            // Не терять уже выполненные действия и не предлагать модели повторить их при частичном успехе.
            val replies = if (renderedReplies.size == results.size) renderedReplies else renderedReplies + texts.get("reply.unconfirmed")
            BotReply(
                replies.joinToString("\n"),
                results.flatMap {
                    it.second.reply
                        ?.buttons
                        .orEmpty()
                },
            )
        } else {
            results.forEach { (call, result) ->
                messages += ChatMessage(Role.TOOL, requireNotNull(result.result).text, toolCallId = call.id)
            }
            null
        }
    }

    private suspend fun execute(
        user: CoreUser,
        call: ToolCall,
        eventKey: String,
    ): ReminderExecution {
        val arguments =
            runCatching { Json.parseToJsonElement(call.argumentsJson.ifBlank { "{}" }).jsonObject }.getOrNull()
                ?: return ReminderExecution(ToolResult(texts.get("tool.invalid_json"), isError = true))
        return if (call.name == DialogReplies.TOOL_NAME) {
            ReminderExecution(dialogue.execute(user, arguments))
        } else {
            actions?.execute(user, call.name, arguments, eventKey)
                ?: ReminderExecution(core.callTool(user.id, call.name, arguments))
        }
    }

    private suspend fun tools(): List<ToolDefinition> =
        toolsLock.withLock {
            tools ?: (core.listTools().map { ToolDefinition(it.name, it.description, it.inputSchema) } + dialogue.definition)
                .also { tools = it }
        }

    companion object {
        const val DEFAULT_MAX_TOOL_ROUNDS = 2
    }
}
