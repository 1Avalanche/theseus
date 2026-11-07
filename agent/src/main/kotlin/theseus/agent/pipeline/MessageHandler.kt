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
import theseus.agent.texts.Texts
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Обработка одного входящего текстового сообщения (v1: один агент с инструментами).
 *
 * 1. Пользователь ищется в core **до** LLM: незнакомый или неактивный chatId — без ответа и без вызова модели.
 * 2. /start и /help отвечают шаблонами без LLM. Остальные запросы получают инструменты core и локального диалога.
 * 3. Вызовы инструментов уходят в core с `userId` в `_meta`. Успех — ответ кодом по шаблону;
 *    ошибка или нераспознанный результат — результат возвращается модели для исправления аргументов.
 *    Текст модели не подтверждает выполнение: без результата core ответ формируется по безопасному шаблону.
 *
 * Этап 2: без истории диалога, подтверждений и учёта usage (этапы 3 и 5).
 */
class MessageHandler(
    private val core: CoreClient,
    private val llm: LlmClient,
    private val prompt: PromptBuilder,
    private val renderer: ReplyRenderer,
    private val texts: Texts,
    private val clock: Clock,
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
) {
    private val log = LoggerFactory.getLogger(MessageHandler::class.java)
    private val toolsLock = Mutex()
    private val dialogue = DialogReplies(texts)
    private var tools: List<ToolDefinition>? = null

    /** Текст ответа или `null`, если отвечать не нужно. */
    suspend fun handle(
        chatId: Long,
        text: String,
    ): String? {
        val user = core.findUserByChat(chatId)
        if (user == null || !user.isActive) {
            log.info("Сообщение от неизвестного или неактивного chatId отброшено")
            return null
        }
        return try {
            dialogue.commandReply(text, user) ?: converse(user, text)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.error("Не удалось обработать сообщение user_id={}", user.id, e)
            texts.get("reply.failure")
        }
    }

    private suspend fun converse(
        user: CoreUser,
        text: String,
    ): String {
        val now = clock.instant()
        val zone = ZoneId.of(requireNotNull(user.timezone))
        val messages =
            mutableListOf(
                ChatMessage(Role.SYSTEM, prompt.system(user, now)),
                ChatMessage(Role.USER, text),
            )
        repeat(maxToolRounds + 1) {
            round(user, messages, zone, now)?.let { return it }
        }
        log.warn("Превышено число раундов инструментов, user_id={}", user.id)
        return texts.get("reply.failure")
    }

    /** Один вызов модели. Возвращает ответ пользователю или `null`, если результаты инструментов ушли модели. */
    private suspend fun round(
        user: CoreUser,
        messages: MutableList<ChatMessage>,
        zone: ZoneId,
        now: Instant,
    ): String? {
        val response = llm.complete(LlmRequest(messages, tools()))
        log.debug("LLM ответила моделью {}, usage={}", response.model, response.usage)
        val calls = response.message.toolCalls
        if (calls.isEmpty()) {
            log.info("Ответ модели без вызова инструмента, user_id={}", user.id)
            return texts.get(if (messages.any { it.role == Role.TOOL }) "reply.unconfirmed" else "reply.not_created")
        }
        messages += response.message
        val results = calls.map { call -> call to execute(user, call) }
        val rendered =
            results.map { (call, result) ->
                when {
                    result.isError -> null
                    call.name == DialogReplies.TOOL_NAME -> result.text
                    else -> renderer.render(call.name, result.text, zone, now)
                }
            }
        val renderedReplies = rendered.filterNotNull()
        log.info("Обработаны инструменты, user_id={}, calls={}, rendered={}", user.id, calls.size, renderedReplies.size)
        return if (renderedReplies.isNotEmpty()) {
            // Не терять уже выполненные действия и не предлагать модели повторить их при частичном успехе.
            val replies = if (renderedReplies.size == results.size) renderedReplies else renderedReplies + texts.get("reply.unconfirmed")
            replies.joinToString("\n")
        } else {
            results.forEach { (call, result) -> messages += ChatMessage(Role.TOOL, result.text, toolCallId = call.id) }
            null
        }
    }

    private suspend fun execute(
        user: CoreUser,
        call: ToolCall,
    ): ToolResult {
        val arguments =
            runCatching { Json.parseToJsonElement(call.argumentsJson.ifBlank { "{}" }).jsonObject }.getOrNull()
                ?: return ToolResult(texts.get("tool.invalid_json"), isError = true)
        return if (call.name == DialogReplies.TOOL_NAME) dialogue.execute(user, arguments) else core.callTool(user.id, call.name, arguments)
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
