package theseus.agent.system

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import theseus.agent.core.CoreUser
import theseus.agent.dialog.ReminderReplyTarget
import theseus.agent.pipeline.FakeDialogs
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ReminderReplyLinksTest {
    private val user = CoreUser(7, 4242, "active", "Анна", "female", "Europe/Moscow")
    private val now = Instant.parse("2026-11-02T07:15:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val store = InMemoryAgentStore()
    private val dialogs = FakeDialogs()
    private val links = ReminderReplyLinks(store, dialogs, clock)
    private val target = ReminderReplyTarget(17, "recurring", "Europe/Moscow")
    private val created =
        Json
            .parseToJsonElement(
                """{"status":"created","reminder":{"id":17,"kind":"recurring","timezone":"Europe/Moscow"}}""",
            ).jsonObject

    private suspend fun event(
        id: String,
        callback: String? = null,
    ) {
        store.beginEvent(EventSnapshot(Incoming(id, user.chatId, callback = callback), user, null, dialogue = callback == null))
    }

    private suspend fun operation(
        key: String,
        body: JsonObject = created,
        ownerId: Long = user.id,
        status: Int = 200,
        complete: Boolean = true,
    ) {
        val op = Outgoing(UUID.randomUUID(), key, "MCP", "create_reminder", JsonObject(emptyMap()), ownerId)
        store.beginOperation(op)
        if (complete) store.finishOperation(op.id, RestReply(status, body))
    }

    @Test fun `sent receipt uses confirmed result rather than rendered words and survives service restart`() =
        runTest {
            event("created-event")
            operation("created-event:tool:0")
            links.sent("created-event", user.chatId, 500, BotReply("Совсем другая формулировка ответа"))
            assertEquals(target, dialogs.replyTarget(user.id, 500, now))
            ReminderReplyLinks(store, dialogs, clock).sent("created-event", user.chatId, 501, BotReply("Повтор доставки"))
            assertEquals(target, dialogs.replyTarget(user.id, 501, now))
            assertNull(dialogs.replyTarget(99, 500, now))
        }

    @Test fun `unknown error foreign and unfinished outcomes never become a reply target`() =
        runTest {
            for (case in listOf(
                Case("foreign", 99, 200, true),
                Case("error", 7, 400, true),
                Case("unfinished", 7, 200, false),
            )) {
                event(case.name)
                operation("${case.name}:tool:0", ownerId = case.owner, status = case.status, complete = case.complete)
                links.sent(case.name, user.chatId, 500, BotReply("Поставил"))
                assertNull(dialogs.replyTarget(user.id, 500, now))
            }
            event("no-operation")
            links.sent("no-operation", user.chatId, 500, BotReply("Поставил"))
            assertNull(dialogs.replyTarget(user.id, 500, now))
        }

    @Test fun `multiple targets foreign chat and confirmation buttons stay unbound`() =
        runTest {
            event("multi")
            operation("multi:tool:0")
            operation("multi:tool:1", Json.parseToJsonElement(created.toString().replace("17", "18")).jsonObject)
            links.sent("multi", user.chatId, 500, BotReply("Два напоминания"))
            assertNull(dialogs.replyTarget(user.id, 500, now))
            event("single")
            operation("single:tool:0")
            links.sent("single", 999, 500, BotReply("Поставил"))
            links.sent("single", user.chatId, 500, BotReply("Подтверди", listOf(listOf(Button("Да", "rem:yes:card")))))
            assertNull(dialogs.replyTarget(user.id, 500, now))
        }

    @Test fun `confirmed creation callback binds its own execution result`() =
        runTest {
            val id = UUID.randomUUID()
            store.beginOperation(Outgoing(id, "original:card", "CONFIRM", "create_reminder", JsonObject(emptyMap()), user.id))
            store.finishOperation(id, RestReply(200, Json.parseToJsonElement("""{"resolution":"CONFIRMED"}""").jsonObject))
            event("callback-created", "rem:yes:$id")
            operation("confirmation:$id:execute")
            links.sent("callback-created", user.chatId, 500, BotReply("Поставил"))
            assertEquals(target, dialogs.replyTarget(user.id, 500, now))
        }

    private data class Case(
        val name: String,
        val owner: Long,
        val status: Int,
        val complete: Boolean,
    )
}
