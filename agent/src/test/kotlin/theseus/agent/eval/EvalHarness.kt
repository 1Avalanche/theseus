package theseus.agent.eval

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import theseus.agent.core.CoreClient
import theseus.agent.core.CoreUser
import theseus.agent.core.ToolResult
import theseus.agent.core.ToolSpec
import theseus.agent.dialog.DialogEntry
import theseus.agent.dialog.ReminderReplyTarget
import theseus.agent.llm.LlmClient
import theseus.agent.llm.Role
import theseus.agent.pipeline.FakeDialogs
import theseus.agent.pipeline.MessageHandler
import theseus.agent.pipeline.ReminderActions
import theseus.agent.pipeline.ReplyRenderer
import theseus.agent.prompt.PromptBuilder
import theseus.agent.system.BotReply
import theseus.agent.system.InMemoryAgentStore
import theseus.agent.system.McpOperations
import theseus.agent.system.RestReply
import theseus.agent.system.SystemCore
import theseus.agent.texts.Texts
import theseus.core.adapters.mcp.ReminderTools
import theseus.core.application.OperationReply
import theseus.core.application.Operations
import theseus.core.application.ReminderRepository
import theseus.core.application.ReminderService
import theseus.core.application.UserRepository
import theseus.core.application.UserService
import theseus.core.domain.reminder.NewReminder
import theseus.core.domain.reminder.PartOfDay
import theseus.core.domain.reminder.Recurrence
import theseus.core.domain.reminder.RecurrenceResolver
import theseus.core.domain.reminder.Reminder
import theseus.core.domain.reminder.ReminderSchedule
import theseus.core.domain.reminder.ReminderStatus
import theseus.core.domain.reminder.WhenResolver
import theseus.core.domain.user.Gender
import theseus.core.domain.user.User
import theseus.core.domain.user.UserRole
import theseus.core.domain.user.UserStatus
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Изолированный core: настоящие сервисы и адаптеры, репозитории только в памяти. */
internal class EvalHarness(
    val data: EvalData,
    val case: JsonObject,
) {
    val context = data.context(case)
    val clock: Clock = Clock.fixed(now(context).atZone(zone(context)).toInstant(), zone(context))
    val state = linkedMapOf<Long, Reminder>()
    val executed = mutableListOf<JsonObject>()
    private val user =
        User(1, 1, UserRole.USER, UserStatus.ACTIVE, context.string("name"), Gender.FEMALE, zone(context), clock.instant(), clock.instant())
    private val users = mockk<UserRepository>()
    private val repository = mockk<ReminderRepository>()
    private val operations = mockk<Operations>()
    private val texts = Texts.load("texts/agent.properties")
    private val coreTexts =
        theseus.core.texts.Texts
            .load("texts/core.properties")
    private val tools: ReminderTools

    init {
        coEvery { users.findById(any()) } answers { user.takeIf { firstArg<Long>() == 1L } }
        coEvery { users.findByChatId(any()) } returns user
        coEvery { repository.withUserLock<Any?>(any(), any()) } coAnswers { secondArg<suspend () -> Any?>().invoke() }
        coEvery { repository.list(any()) } answers { state.values.filter { it.userId == firstArg<Long>() } }
        coEvery { repository.find(any(), any()) } answers { state[secondArg<Long>()]?.takeIf { it.userId == firstArg<Long>() } }
        coEvery { repository.save(any(), any()) } answers { firstArg<Reminder>().also { state[it.id] = it } }
        coEvery { repository.create(any(), any()) } answers {
            val value = firstArg<NewReminder>()
            Reminder(
                (state.keys.maxOrNull() ?: 0) + 1,
                value.userId,
                value.text,
                value.timezone,
                value.startLocal,
                value.recurrenceRule,
                ReminderStatus.ACTIVE,
                value.nextFireAt,
                null,
                clock.instant(),
                parentId = value.parentId,
            ).also { state[it.id] = it }
        }
        coEvery { operations.execute(any(), any(), any(), any()) } coAnswers {
            thirdArg<suspend () -> Unit>().invoke()
            arg<suspend () -> OperationReply>(3).invoke()
        }
        val service = ReminderService(repository, UserService(users), WhenResolver(LocalTime.of(9, 0)), clock)
        tools = ReminderTools(service, coreTexts, operations)
        data.reminders(case["fixture"]?.jsonPrimitive?.content ?: "empty").forEach { row ->
            val repeated = row["recurrence"] as? JsonObject
            val schedule = repeated?.let { RecurrenceResolver().normalize(recurrence(it), now(context).toLocalDate()) }
            val local =
                schedule?.startLocal ?: row.getValue("when").jsonObject.let {
                    LocalDate.parse(it.string("date")).atTime(LocalTime.parse(it.string("time")))
                }
            val id = row.string("id").toLong()
            state[id] =
                Reminder(
                    id,
                    1,
                    row.string("text"),
                    zone(context),
                    local,
                    schedule?.rule,
                    ReminderStatus.valueOf(row.string("status").uppercase()),
                    local.atZone(zone(context)).toInstant(),
                    null,
                    clock.instant(),
                )
            val value = state.getValue(id)
            state[id] = value.copy(nextFireAt = ReminderSchedule().next(value, clock.instant()))
        }
    }

    val core =
        object : CoreClient {
            override suspend fun findUserByChat(chatId: Long) =
                CoreUser(1, 1, "active", user.name, context.string("gender"), user.timezone!!.id)

            override suspend fun listTools(): List<ToolSpec> =
                ReminderTools.names.map { name ->
                    val path = data.root.resolve("core/src/main/resources/mcp")
                    ToolSpec(
                        name,
                        java.nio.file.Files
                            .readString(path.resolve("$name.md")),
                        objectJson(
                            java.nio.file.Files
                                .readString(path.resolve("$name.schema.json")),
                        ),
                    )
                }

            override suspend fun callTool(
                userId: Long,
                name: String,
                arguments: JsonObject,
            ) = callTool(userId, name, arguments, UUID.randomUUID())

            override suspend fun callTool(
                userId: Long,
                name: String,
                arguments: JsonObject,
                operationId: UUID,
                confirmation: JsonObject?,
            ): ToolResult {
                val result = tools.call(name, userId, arguments, operationId.toString(), confirmation)
                executed +=
                    buildJsonObject {
                        put("tool", name)
                        put("args", arguments)
                        put("result", objectJson(result.json))
                        put("error", result.isError)
                    }
                return ToolResult(result.json, result.isError)
            }
        }

    suspend fun run(llm: LlmClient): BotReply? {
        val dialogs = FakeDialogs()
        val history =
            case.rows("history").mapNotNull { value ->
                val row = value.jsonObject
                row["content"]?.jsonPrimitive?.content?.let { row.string("role") to it }
            }
        dialogs.session =
            dialogs.session.copy(
                entries =
                    history.mapIndexed { index, (role, content) ->
                        DialogEntry(index + 1L, if (role == "user") Role.USER else Role.ASSISTANT, content)
                    },
            )
        val reply = case["reply_to"] as? JsonObject
        if (reply?.get("source")?.jsonPrimitive?.content == "agent_reply") {
            dialogs.rememberReply(
                1,
                1,
                ReminderReplyTarget(reply.string("reminder_id").toLong(), reply.string("kind"), reply.string("timezone")),
                clock.instant(),
            )
        }
        val store = InMemoryAgentStore()
        val renderer = ReplyRenderer(texts)
        val actions = ReminderActions(core, store, McpOperations(store, core), renderer, texts, clock)
        val system = SystemCore { _, _, _, _ -> RestReply(200, reply ?: JsonObject(emptyMap())) }
        return MessageHandler(
            core,
            llm,
            PromptBuilder.fromResources(texts),
            renderer,
            texts,
            clock,
            actions = actions,
            dialogs = dialogs,
            systemCore = system,
        ).handleReply(1, case.string("input"), case.string("id"), reply?.let { 1L })
    }
}

internal fun recurrence(value: JsonObject): Recurrence =
    Recurrence(
        value.string("freq"),
        value["interval"]?.jsonPrimitive?.int ?: 1,
        value.rows("by_weekday").map { it.jsonPrimitive.content },
        value.rows("by_month_day").map { it.jsonPrimitive.int },
        value.rows("by_month").map { it.jsonPrimitive.int },
        value["time"]?.jsonPrimitive?.content?.let(LocalTime::parse),
        value["start_date"]?.jsonPrimitive?.content?.let(LocalDate::parse),
        value["until"]
            ?.takeUnless {
                it == JsonNull
            }?.jsonPrimitive
            ?.content
            ?.let(LocalDate::parse),
        value["count"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.int,
        value["part_of_day"]?.jsonPrimitive?.content?.let { PartOfDay.valueOf(it.uppercase()) },
    )
