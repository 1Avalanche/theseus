package theseus.agent.pipeline

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.agent.core.CoreUser
import theseus.agent.core.ToolResult
import theseus.agent.core.ToolSpec
import theseus.agent.llm.ChatMessage
import theseus.agent.llm.Role
import theseus.agent.llm.ToolCall
import theseus.agent.pipeline.MessageHandlerTest.FakeCore
import theseus.agent.pipeline.MessageHandlerTest.FakeLlm
import theseus.agent.prompt.PromptBuilder
import theseus.agent.texts.Texts
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GroupDeletionTest {
    private val texts = Texts.load("texts/agent.properties")
    private val owner = CoreUser(7, 4242, "active", "Аня", "female", "Europe/Moscow")
    private val clock = Clock.fixed(Instant.parse("2026-11-02T07:15:00Z"), ZoneOffset.UTC)

    private fun handler(
        core: FakeCore,
        llm: FakeLlm,
    ) = MessageHandler(
        core,
        llm,
        PromptBuilder.fromResources(texts),
        ReplyRenderer(texts),
        texts,
        clock,
    )

    @Test fun `truncated search cannot be confirmed as a complete selected group`() =
        runTest {
            val core =
                FakeCore(mapOf(owner.chatId to owner), ToolResult("""{"candidates":[{"id":17,"text":"Вода"}],"truncated":true}""", false))
            val llm =
                FakeLlm(
                    ChatMessage(Role.ASSISTANT, null, listOf(ToolCall("find", "find_reminders", "{\"query\":\"вода\"}"))),
                    ChatMessage(
                        Role.ASSISTANT,
                        null,
                        listOf(ToolCall("delete", "delete_reminder", """{"scope":"selected","reminder_ids":[17]}""")),
                    ),
                )
            assertEquals(texts.get("reply.search_truncated"), handler(core, llm).handle(owner.chatId, "Удали все про воду"))
            assertEquals(listOf("find_reminders"), core.calls.map { it.second })
        }

    @Test fun `production context can select water reminder from full search results after list`() =
        runTest {
            val rows =
                kotlinx.serialization.json.JsonArray(
                    (1..10).map { id ->
                        kotlinx.serialization.json.Json.parseToJsonElement(
                            """{"id":$id,"text":"Пить воду $id","kind":"one_time","status":"active","timezone":"Europe/Moscow",
                    "schedule":{"at_local":"2026-11-03T10:00"},"next_at_local":"2026-11-03T10:00",
                    "paused_until_local":null,"snoozed_until_local":null}""",
                        )
                    },
                )
            val list =
                kotlinx.serialization.json
                    .buildJsonObject { put("reminders", rows) }
                    .toString()
            val candidates =
                kotlinx.serialization.json
                    .buildJsonObject { put("candidates", rows) }
                    .toString()
            val core =
                FakeCore(mapOf(owner.chatId to owner)).apply {
                    specifications = productionTools().map { ToolSpec(it.name, it.description, it.parameters) }
                    results += ToolResult(list, false)
                    results += ToolResult(candidates, false)
                }
            val llm =
                FakeLlm(
                    ChatMessage(Role.ASSISTANT, null, listOf(ToolCall("list", "list_reminders", "{}"))),
                    ChatMessage(Role.ASSISTANT, null, listOf(ToolCall("find", "find_reminders", "{\"query\":\"вода\"}"))),
                    ChatMessage(Role.ASSISTANT, null, listOf(ToolCall("choice", "assistant_reply", "{\"topic\":\"choose_reminder\"}"))),
                )
            val handler =
                MessageHandler(core, llm, PromptBuilder.fromResources(texts), ReplyRenderer(texts), texts, clock, dialogs = FakeDialogs())
            handler.handle(owner.chatId, "Покажи мои напоминания", "list-event")
            val reply = handler.handle(owner.chatId, "Удали все напоминания, связанные с водой", "water-event")!!
            assertTrue(reply.startsWith(texts.get("reply.choose_reminder")), reply)
            assertEquals(3, llm.requests.size)
            assertTrue(reply.contains("ID 1"))
            assertEquals(listOf("list_reminders", "find_reminders"), core.calls.map { it.second })
        }

    @Suppress("LongMethod") // Сквозной сценарий: список, поиск, карточка, callback и технический повтор.
    @Test
    fun `water group after shown list produces one confirmation and one idempotent group deletion`() =
        runTest {
            val waterIds = listOf(17, 18)
            val rows =
                kotlinx.serialization.json.JsonArray(
                    (1..10).map { index ->
                        val id = if (index <= 2) waterIds[index - 1] else 20 + index
                        val text = if (index <= 2) "Пить воду $index" else "Позвонить маме $index"
                        kotlinx.serialization.json.Json.parseToJsonElement(
                            """{"id":$id,"text":"$text","kind":"one_time","status":"active","timezone":"Europe/Moscow",
                    "schedule":{"at_local":"2026-11-03T10:00"},"next_at_local":"2026-11-03T10:00",
                    "paused_until_local":null,"snoozed_until_local":null}""",
                        )
                    },
                )
            val core =
                FakeCore(mapOf(owner.chatId to owner)).apply {
                    specifications = productionTools().map { ToolSpec(it.name, it.description, it.parameters) }
                    results +=
                        ToolResult(
                            kotlinx.serialization.json
                                .buildJsonObject { put("reminders", rows) }
                                .toString(),
                            false,
                        )
                    results +=
                        ToolResult(
                            kotlinx.serialization.json
                                .buildJsonObject { put("candidates", rows) }
                                .toString(),
                            false,
                        )
                    results +=
                        ToolResult(
                            kotlinx.serialization.json
                                .buildJsonObject {
                                    put("status", kotlinx.serialization.json.JsonPrimitive("confirmation_required"))
                                    put("fingerprint", kotlinx.serialization.json.JsonPrimitive("selected-snapshot"))
                                    put("reminders", kotlinx.serialization.json.JsonArray(rows.take(2)))
                                }.toString(),
                            false,
                        )
                }
            val llm =
                FakeLlm(
                    ChatMessage(Role.ASSISTANT, null, listOf(ToolCall("list", "list_reminders", "{}"))),
                    ChatMessage(Role.ASSISTANT, null, listOf(ToolCall("find", "find_reminders", "{\"query\":\"вода\"}"))),
                    ChatMessage(
                        Role.ASSISTANT,
                        null,
                        listOf(ToolCall("delete", "delete_reminder", """{"scope":"selected","reminder_ids":[17,18]}""")),
                    ),
                )
            val store = theseus.agent.system.InMemoryAgentStore()
            val actions = ReminderActions(core, store, theseus.agent.system.McpOperations(store, core), ReplyRenderer(texts), texts, clock)
            val handler =
                MessageHandler(
                    core,
                    llm,
                    PromptBuilder.fromResources(texts),
                    ReplyRenderer(texts),
                    texts,
                    clock,
                    actions = actions,
                    dialogs = FakeDialogs(),
                )
            handler.handle(owner.chatId, "Покажи мои напоминания", "list-water")
            val card = handler.handleReply(owner.chatId, "Удали все напоминания, связанные с водой", "delete-water")!!
            assertTrue(card.text.contains("Пить воду 1"), card.text)
            assertTrue(card.text.contains("Пить воду 2"), card.text)
            assertTrue(!card.text.contains("Позвонить маме"), card.text)
            assertEquals(1, card.buttons.size)
            assertEquals(
                "Удалить выбранные",
                card.buttons
                    .single()
                    .first()
                    .text,
            )
            assertTrue(core.confirmations.isEmpty())
            core.results += ToolResult("""{"status":"deleted_selected","deleted_count":2}""", false)
            val button =
                theseus.agent.system.Incoming(
                    "confirm-water",
                    owner.chatId,
                    callback =
                        card.buttons
                            .single()
                            .first()
                            .data,
                )
            val done = actions.callback(button, owner)!!
            assertEquals("Удалил выбранные напоминания: 2.", done.text)
            assertEquals(texts.get("confirmation.processed"), actions.callback(button.copy(id = "repeat-water"), owner)!!.text)
            assertEquals(1, core.confirmations.size)
            assertEquals(3, llm.requests.size)
        }
}
