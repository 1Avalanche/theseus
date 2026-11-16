package theseus.agent.pipeline

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.agent.core.CoreUser
import theseus.agent.core.ToolResult
import theseus.agent.pipeline.MessageHandlerTest.FakeCore
import theseus.agent.system.InMemoryAgentStore
import theseus.agent.system.Incoming
import theseus.agent.system.McpOperations
import theseus.agent.texts.Texts
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class GroupActionLifecycleTest {
    private val user = CoreUser(7, 4242, "active", "Анна", "female", "Europe/Moscow")
    private val clock = Clock.fixed(Instant.parse("2026-11-02T07:15:00Z"), ZoneOffset.UTC)
    private val texts = Texts.load("texts/agent.properties")
    private val store = InMemoryAgentStore()
    private val core = FakeCore(mapOf(user.chatId to user))
    private val mcp = McpOperations(store, core)
    private val actions = ReminderActions(core, store, mcp, ReplyRenderer(texts), texts, clock)
    private val arguments = Json.parseToJsonElement("""{"scope":"selected","reminder_ids":[17,18]}""").jsonObject
    private val preview =
        ToolResult(
            """{"status":"confirmation_required","fingerprint":"selection","reminders":[
        {"id":17,"text":"Пить воду","schedule":{"at_local":"2026-11-03T10:00"}},
        {"id":18,"text":"Купить воду","schedule":{"at_local":"2026-11-03T11:00"}}]}""",
            false,
        )

    @Test fun `cancel and foreign callback never execute selected deletion`() =
        runTest {
            core.results += preview
            val card = actions.execute(user, "delete_reminder", arguments, "group:usage:0:tool:0").reply!!
            val rows = card.buttons.single()
            assertEquals(
                texts.get("confirmation.stale"),
                actions
                    .callback(
                        Incoming(
                            "foreign",
                            999,
                            callback = rows.first().data,
                        ),
                        user.copy(id = 99, chatId = 999),
                    )!!
                    .text,
            )
            val cancelled = actions.callback(Incoming("cancel", user.chatId, callback = rows.last().data), user)!!
            assertEquals(texts.get("confirmation.cancelled"), cancelled.text)
            assertEquals(
                texts.get("confirmation.processed"),
                actions.callback(Incoming("yes-after-cancel", user.chatId, callback = rows.first().data), user)!!.text,
            )
            assertTrue(core.confirmations.isEmpty())
            assertEquals(1, core.calls.size)
        }

    @Test fun `recovery creates missing group card from saved preview and confirmation executes once`() =
        runTest {
            core.results += preview
            mcp.perform("restore-group:usage:0:tool:0", user.id, "delete_reminder", arguments)
            val recovered = actions.recoverDialogue("restore-group", user)!!
            val again = actions.recoverDialogue("restore-group", user)!!
            assertEquals(recovered, again)
            assertTrue(core.confirmations.isEmpty())
            assertEquals(1, core.calls.size)
            core.results += ToolResult("""{"status":"deleted_selected","deleted_count":2}""", false)
            val callback =
                Incoming(
                    "yes",
                    user.chatId,
                    callback =
                        recovered.buttons
                            .single()
                            .first()
                            .data,
                )
            val done = actions.callback(callback, user)!!
            assertEquals("Удалил выбранные напоминания: 2.", done.text)
            assertEquals(texts.get("confirmation.processed"), actions.callback(callback.copy(id = "repeated"), user)!!.text)
            assertEquals(1, core.confirmations.size)
            assertEquals(2, core.calls.size)
        }
}
