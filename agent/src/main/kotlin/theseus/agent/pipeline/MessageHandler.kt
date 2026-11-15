package theseus.agent.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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
 * История и саммари ограничены сессией и бюджетом; критичные действия используют долговечные карточки.
 * Расход каждого ответа модели сохраняется до исполнения инструментов.
 */
@Suppress("TooManyFunctions") // Граница одного диалогового хода: доступ, контекст, бюджеты и инструменты.
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
    private val dialogs: theseus.agent.dialog.DialogStore? = null,
    private val systemCore: theseus.agent.system.SystemCore? = null,
    private val modelContextTokens: Int = DEFAULT_MODEL_CONTEXT,
) {
    private val log = LoggerFactory.getLogger(MessageHandler::class.java)
    private val toolsLock = Mutex()
    private val dialogue = DialogReplies(texts)
    private var tools: List<ToolDefinition>? = null
    private val memory = dialogs?.let { ConversationMemory(it, texts) }

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
        replyMessageId: Long? = null,
    ): BotReply? {
        val budget = TurnBudget()
        return try {
            withTimeout(TurnBudget.TIMEOUT_MS) { handleWithinBudget(chatId, text, eventId, replyMessageId, budget) }
        } catch (e: TimeoutCancellationException) {
            if (budget.mutationStarted) throw theseus.agent.system.UncertainMcpException(e)
            BotReply(texts.get("reply.budget"))
        }
    }

    private suspend fun handleWithinBudget(
        chatId: Long,
        text: String,
        eventId: String,
        replyMessageId: Long?,
        budget: TurnBudget,
    ): BotReply? {
        val user = core.findUserByChat(chatId)
        if (user == null || !user.isActive) {
            log.info("Сообщение от неизвестного или неактивного chatId отброшено")
            return null
        }
        return try {
            dialogue.commandReply(text, user)?.let(::BotReply) ?: converse(user, text, eventId, replyMessageId, budget)
        } catch (e: CancellationException) {
            throw e
        } catch (e: theseus.agent.system.UncertainMcpException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.error("Не удалось обработать сообщение user_id={}, ошибка={}", user.id, e.javaClass.simpleName)
            BotReply(texts.get("reply.failure"))
        }
    }

    private suspend fun converse(
        user: CoreUser,
        text: String,
        eventId: String,
        replyMessageId: Long?,
        budget: TurnBudget,
    ): BotReply {
        val now = clock.instant()
        val session = dialogs?.open(user.id, user.chatId, now)
        val reply =
            try {
                conversation(user, text, eventId, replyMessageId, session, budget, now)
            } catch (_: BudgetExceeded) {
                BotReply(texts.get("reply.budget"))
            } catch (_: theseus.agent.llm.LlmBudgetException) {
                BotReply(texts.get("reply.budget"))
            }
        if (session != null) dialogs.append(session.id, user.id, eventId, Role.ASSISTANT, reply.text, clock.instant())
        return reply
    }

    private suspend fun conversation(
        user: CoreUser,
        text: String,
        eventId: String,
        replyMessageId: Long?,
        session: theseus.agent.dialog.DialogSession?,
        budget: TurnBudget,
        now: Instant,
    ): BotReply {
        usage.before(user.id)
        val zone = ZoneId.of(requireNotNull(user.timezone))
        val messages = mutableListOf(ChatMessage(Role.SYSTEM, prompt.system(user, now)))
        if (session != null) {
            dialogs?.append(session.id, user.id, eventId, Role.USER, text, now)
            messages +=
                requireNotNull(memory).history(session) { request ->
                    try {
                        complete(user, request, eventId, budget).message.content
                    } catch (_: SummaryUnavailable) {
                        null
                    }
                }
        }
        var currentText = text
        replyMessageId?.let { id ->
            val delivery = systemCore?.request("GET", "/deliveries/by-message?user_id=${user.id}&message_id=$id", null, null)
            if (delivery?.successful == true) {
                currentText = texts.get("context.reply", mapOf("delivery" to delivery.body.toString())) + "\n\n" + text
            }
        }
        messages += ChatMessage(Role.USER, currentText)
        repeat(maxToolRounds + 1) {
            round(user, messages, zone, now, "$eventId:usage:$it", budget, session, text)?.let { return it }
        }
        log.warn("Превышено число раундов инструментов, user_id={}", user.id)
        return BotReply(texts.get("reply.budget"))
    }

    private suspend fun complete(
        user: CoreUser,
        request: LlmRequest,
        key: String,
        budget: TurnBudget,
    ): theseus.agent.llm.LlmResponse {
        val contextual =
            if (request.role == "summary") {
                request.copy(
                    messages =
                        request.messages.mapIndexed { index, message ->
                            if (index ==
                                0
                            ) {
                                message.copy(content = message.content + "\n" + prompt.context(user, clock.instant()))
                            } else {
                                message
                            }
                        },
                )
            } else {
                request
            }
        budget.request(contextual, minOf(MAX_INPUT_TOKENS, modelContextTokens - contextual.maxOutputTokens))
        val response =
            try {
                llm.complete(contextual)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Ошибка генерации саммари не закрывает обычный диалог; ошибка учёта usage ниже не подавляется.
                throw if (request.role == "summary") SummaryUnavailable(e) else e
            }
        usage.record(user.id, "$key:${UUID.randomUUID()}", response.copy(role = request.role))
        return response
    }

    /** Один вызов модели. Возвращает ответ пользователю или `null`, если результаты инструментов ушли модели. */
    private suspend fun round(
        user: CoreUser,
        messages: MutableList<ChatMessage>,
        zone: ZoneId,
        now: Instant,
        usageKey: String,
        budget: TurnBudget,
        session: theseus.agent.dialog.DialogSession?,
        question: String,
    ): BotReply? {
        fitHistory(messages)
        val response = complete(user, LlmRequest(messages.toList(), tools()), usageKey, budget)
        log.debug("LLM ответила моделью {}, usage={}", response.model, response.usage)
        val calls = response.message.toolCalls
        if (calls.isEmpty()) {
            log.info("Ответ модели без вызова инструмента, user_id={}", user.id)
            return BotReply(texts.get(if (messages.any { it.role == Role.TOOL }) "reply.unconfirmed" else "reply.not_created"))
        }
        budget.tools(calls, maxToolRounds)
        // Генерация о себе требует отдельного обращения; смешанный ответ не должен терять уже исполненное изменение.
        if (calls.size > 1 && calls.any(::isAbout)) throw BudgetExceeded()
        messages += response.message
        val targetChanges = calls.filter { it.name in TARGET_CHANGES }
        val candidates = messages.lastOrNull { it.role == Role.TOOL && it.content?.contains("\"candidates\"") == true }?.content
        val results =
            if (targetChanges.size > 1) {
                listOf(calls.first() to ReminderExecution(null, BotReply(renderer.choices(candidates, zone, now))))
            } else {
                calls.mapIndexed { index, call ->
                    if (call.name in TARGET_CHANGES || call.name == "create_reminder") budget.mutationStarted = true
                    val execution = executeCall(user, call, "$usageKey:tool:$index", session, question, budget)
                    if (call.name == "list_reminders" &&
                        execution.result?.isError == false
                    ) {
                        memory?.rememberList(session, execution.result.text)
                    }
                    call to execution
                }
            }
        val rendered =
            results.map { (call, execution) ->
                render(call, execution, candidates, zone, now)
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

    /** Старую текстовую историю можно убрать; текущий ввод, reply и цепочки инструментов сохраняются целиком. */
    private suspend fun fitHistory(messages: MutableList<ChatMessage>) {
        val definitions = tools()
        val limit = minOf(MAX_INPUT_TOKENS, modelContextTokens - OUTPUT_TOKENS)
        while (!theseus.agent.llm.ContextBudget
                .fits(LlmRequest(messages, definitions), limit) &&
            messages.indexOfLast { it.role == Role.USER } > 1
        ) {
            val removed = messages.removeAt(1)
            if (removed.role == Role.USER && messages.getOrNull(1)?.role == Role.ASSISTANT) messages.removeAt(1)
        }
    }

    private fun isAbout(call: ToolCall): Boolean =
        call.name == DialogReplies.TOOL_NAME &&
            runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull() ==
            Json.parseToJsonElement("{\"topic\":\"about\"}")

    private suspend fun executeCall(
        user: CoreUser,
        call: ToolCall,
        key: String,
        session: theseus.agent.dialog.DialogSession?,
        question: String,
        budget: TurnBudget,
    ): ReminderExecution {
        if (call.name == "resolve_reference" && memory != null) {
            val args =
                runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull()
                    ?: return ReminderExecution(ToolResult(texts.get("tool.invalid_json"), true))
            return memory.resolve(session, args)
        }
        if (!isAbout(call)) return execute(user, call, key)
        val instructions =
            listOf("persona/siri.md", "prompts/persona-answer.md", "persona/siri-facts.md")
                .joinToString("\n") { PromptBuilder.stripComments(Texts.resourceText(it)) } + "\n" + prompt.context(user, clock.instant())
        val answer =
            complete(
                user,
                LlmRequest(
                    listOf(
                        ChatMessage(Role.SYSTEM, instructions),
                        ChatMessage(Role.USER, question),
                    ),
                    emptyList(),
                ),
                "$key:persona",
                budget,
            ).message
        return ReminderExecution(
            null,
            BotReply(
                answer.content?.takeIf { answer.toolCalls.isEmpty() && it.isNotBlank() } ?: texts.get("reply.about"),
            ),
        )
    }

    private fun render(
        call: ToolCall,
        execution: ReminderExecution,
        candidates: String?,
        zone: ZoneId,
        now: Instant,
    ): String? {
        val result = execution.result
        return when {
            execution.reply != null -> execution.reply.text
            result == null -> null
            result.isError -> renderer.error(result.text)
            call.name in setOf("find_reminders", "resolve_reference") -> null
            call.name == DialogReplies.TOOL_NAME ->
                if (result.text == texts.get("reply.choose_reminder")) renderer.choices(candidates, zone, now) else result.text
            else -> renderer.render(call.name, result.text, zone, now)
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
            tools
                ?: (
                    core.listTools().map { ToolDefinition(it.name, it.description, it.inputSchema) } + dialogue.definition +
                        listOfNotNull(memory?.referenceTool)
                ).also { tools = it }
        }

    companion object {
        const val DEFAULT_MAX_TOOL_ROUNDS = 2
        private const val DEFAULT_MODEL_CONTEXT = 18000
        private const val MAX_INPUT_TOKENS = 16000
        private const val OUTPUT_TOKENS = 2000
        private val TARGET_CHANGES = setOf("update_reminder", "delete_reminder", "snooze_reminder", "pause_reminder", "resume_reminder")
    }
}
