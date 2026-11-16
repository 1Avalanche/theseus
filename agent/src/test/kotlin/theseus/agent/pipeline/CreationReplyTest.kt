package theseus.agent.pipeline

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.agent.core.CoreUser
import theseus.agent.core.ToolResult
import theseus.agent.core.ToolSpec
import theseus.agent.llm.ChatMessage
import theseus.agent.llm.LlmClient
import theseus.agent.llm.LlmRequest
import theseus.agent.llm.LlmResponse
import theseus.agent.llm.Role
import theseus.agent.llm.ToolCall
import theseus.agent.pipeline.MessageHandlerTest.FakeCore
import theseus.agent.prompt.PromptBuilder
import theseus.agent.system.BotReply
import theseus.agent.system.EventSnapshot
import theseus.agent.system.InMemoryAgentStore
import theseus.agent.system.Incoming
import theseus.agent.system.McpOperations
import theseus.agent.system.Outgoing
import theseus.agent.system.ReminderReplyLinks
import theseus.agent.system.RestReply
import theseus.agent.system.SystemCore
import theseus.agent.texts.Texts
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class CreationReplyTest {
    @Suppress("LongMethod") // Сквозная связь из журнала создания через Telegram message_id до изменения серии.
    @Test
    fun `reply to daily creation receipt updates series hour without asking for reminder text`() =
        runTest {
            val user = CoreUser(7, 4242, "active", "Анна", "female", "Europe/Moscow")
            val now = Instant.parse("2026-11-02T07:15:00Z")
            val clock = Clock.fixed(now, ZoneOffset.UTC)
            val texts = Texts.load("texts/agent.properties")
            val dialogs = FakeDialogs()
            val store = InMemoryAgentStore()
            val reminder =
                Json
                    .parseToJsonElement(
                        """{"id":17,"text":"Погладить кота","kind":"recurring","status":"active",
            "timezone":"Europe/Moscow","schedule":{"recurrence":{"freq":"daily","time":"10:00","start_date":"2026-11-01"}},
            "next_at_local":"2026-11-03T10:00"}""",
                    ).jsonObject
            store.beginEvent(EventSnapshot(Incoming("created", user.chatId), user, null, dialogue = true))
            val created = Outgoing(UUID.randomUUID(), "created:tool:0", "MCP", "create_reminder", JsonObject(emptyMap()), user.id)
            store.beginOperation(created)
            store.finishOperation(
                created.id,
                RestReply(
                    200,
                    buildJsonObject {
                        put("status", JsonPrimitive("created"))
                        put("reminder", reminder)
                    },
                ),
            )
            ReminderReplyLinks(store, dialogs, clock).sent(
                "created",
                user.chatId,
                777,
                BotReply("Поставил: «Погладить кота» — ежедневно в 10:00."),
            )
            val core =
                FakeCore(mapOf(user.chatId to user)).apply {
                    specifications = productionTools().map { ToolSpec(it.name, it.description, it.parameters) }
                    results +=
                        ToolResult(
                            buildJsonObject {
                                put("reminder", reminder)
                                put("excluded_count", JsonPrimitive(0))
                                put("pending_children_count", JsonPrimitive(0))
                            }.toString(),
                            false,
                        )
                    results +=
                        ToolResult(
                            buildJsonObject {
                                put("status", JsonPrimitive("updated"))
                                put("reminder", Json.parseToJsonElement(reminder.toString().replace("10:00", "11:00")))
                            }.toString(),
                            false,
                        )
                }
            val llm =
                object : LlmClient {
                    override suspend fun complete(request: LlmRequest): LlmResponse {
                        val context = request.messages.last().content!!
                        assertTrue(context.contains("\"reminder_id\":17"), context)
                        assertTrue(context.contains("scope=series"), context)
                        assertTrue(context.endsWith("перенеси на 11.00"), context)
                        return LlmResponse(
                            ChatMessage(
                                Role.ASSISTANT,
                                null,
                                listOf(
                                    ToolCall(
                                        "update",
                                        "update_reminder",
                                        """{"reminder_id":17,"scope":"series","when":{"time":"11:00"}}""",
                                    ),
                                ),
                            ),
                            "test/main",
                            null,
                        )
                    }
                }
            val actions = ReminderActions(core, store, McpOperations(store, core), ReplyRenderer(texts), texts, clock)
            val handler =
                MessageHandler(
                    core,
                    llm,
                    PromptBuilder.fromResources(texts),
                    ReplyRenderer(texts),
                    texts,
                    clock,
                    actions = actions,
                    dialogs = dialogs,
                    systemCore = SystemCore { _, _, _, _ -> error("Связь есть в agent, REST не нужен") },
                )
            val answer = handler.handleReply(user.chatId, "перенеси на 11.00", "move-hour", 777)!!
            assertTrue(answer.text.startsWith("Изменил:"), answer.text)
            assertTrue(answer.text.contains("11:00"), answer.text)
            assertEquals(listOf("get_reminder", "update_reminder"), core.calls.map { it.second })
        }
}
