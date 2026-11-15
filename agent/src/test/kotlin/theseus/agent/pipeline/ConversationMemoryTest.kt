package theseus.agent.pipeline

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.agent.core.CoreUser
import theseus.agent.dialog.DialogEntry
import theseus.agent.dialog.DialogSession
import theseus.agent.dialog.DialogStore
import theseus.agent.dialog.ListReference
import theseus.agent.llm.ChatMessage
import theseus.agent.llm.ContextBudget
import theseus.agent.llm.LlmRequest
import theseus.agent.llm.Role
import theseus.agent.llm.ToolDefinition
import theseus.agent.prompt.PromptBuilder
import theseus.agent.texts.Texts
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

internal class FakeDialogs : DialogStore {
    var session = DialogSession(1, null, emptyList(), emptyList())
    private val events = mutableSetOf<String>()
    var summarizedUntil: Long? = null

    override suspend fun open(
        userId: Long,
        chatId: Long,
        now: Instant,
    ): DialogSession = session

    override suspend fun append(
        sessionId: Long,
        userId: Long,
        eventId: String,
        role: Role,
        content: String,
        now: Instant,
    ) {
        if (events.add("$eventId:$role")) {
            session =
                session.copy(entries = session.entries + DialogEntry((session.entries.size + 1).toLong(), role, content))
        }
    }

    override suspend fun summarize(
        sessionId: Long,
        summary: String,
        untilId: Long,
    ) {
        summarizedUntil = untilId
        session = session.copy(summary = summary, entries = session.entries.filter { it.id > untilId })
    }

    override suspend fun references(
        sessionId: Long,
        references: List<ListReference>,
    ) {
        session = session.copy(references = references)
    }

    override suspend fun cleanup(now: Instant) = Unit

    override suspend fun completeEvent(
        userId: Long,
        eventId: String,
        content: String,
        now: Instant,
    ) {
        append(session.id, userId, eventId, Role.ASSISTANT, content, now)
    }
}

class ConversationMemoryTest {
    private val texts = Texts.load("texts/agent.properties")

    @Test fun `malformed reference number never resolves a target`() {
        val store = FakeDialogs().apply { session = session.copy(references = listOf(ListReference(42))) }
        val memory = ConversationMemory(store, texts)
        for (arguments in listOf("""{"number":"1"}""", """{"number":{}}""", """{"number":0}""", """{"number":1,"user_id":2}""")) {
            assertEquals(
                texts.get("reply.reference_missing"),
                memory.resolve(store.session, Json.parseToJsonElement(arguments).jsonObject).reply?.text,
            )
        }
    }

    @Test fun `summary compresses contiguous old prefix and preserves recent text window`() =
        runTest {
            val store = FakeDialogs()
            store.session =
                store.session.copy(
                    entries =
                        (1..30).map {
                            DialogEntry(
                                it.toLong(),
                                if (it % 2 ==
                                    1
                                ) {
                                    Role.USER
                                } else {
                                    Role.ASSISTANT
                                },
                                "Сообщение $it",
                            )
                        },
                )
            val memory = ConversationMemory(store, texts)
            val history =
                memory.history(store.session) { request ->
                    assertEquals("summary", request.role)
                    assertEquals(1000, request.maxOutputTokens)
                    assertTrue(request.tools.isEmpty())
                    "Саммари".repeat(500)
                }
            assertEquals(12L, store.summarizedUntil)
            assertTrue(ContextBudget.size(history.first().content!!) <= 1000)
            assertEquals("Сообщение 30", history.last().content)
            assertEquals(19, history.size)
        }

    @Test fun `number resolves exact repeated occurrence and replacing list clears old numbers`() =
        runTest {
            val store = FakeDialogs()
            val memory = ConversationMemory(store, texts)
            memory.rememberList(
                store.session,
                """{"reminders":[{"id":42}],"occurrences":[
                    {"reminder_id":42,"at_local":"2026-11-16T09:00"},
                    {"reminder_id":42,"at_local":"2026-11-17T09:00"}]}""",
            )
            val result = memory.resolve(store.session, Json.parseToJsonElement("{\"number\":2}").jsonObject)
            assertTrue(result.result!!.text.contains("2026-11-17T09:00"))
            memory.rememberList(store.session, """{"reminders":[]}""")
            assertEquals(
                texts.get("reply.reference_missing"),
                memory.resolve(store.session, Json.parseToJsonElement("{\"number\":2}").jsonObject).reply?.text,
            )
        }

    @Test fun `full production prompt and all tools leave room for current user and summary`() {
        val relative = Path.of("../core/src/main/resources/mcp")
        val resources = if (Files.exists(relative)) relative else Path.of("core/src/main/resources/mcp")
        val names =
            listOf(
                "create_reminder",
                "list_reminders",
                "find_reminders",
                "get_reminder",
                "update_reminder",
                "delete_reminder",
                "snooze_reminder",
                "pause_reminder",
                "resume_reminder",
            )
        val tools =
            names.map { name ->
                ToolDefinition(
                    name,
                    Files.readString(resources.resolve("$name.md")),
                    Json.parseToJsonElement(Files.readString(resources.resolve("$name.schema.json"))).jsonObject,
                )
            } +
                DialogReplies(texts).definition +
                ConversationMemory(FakeDialogs(), texts).referenceTool
        val user = CoreUser(1, 1, "active", "Анна", "female", "Europe/Moscow")
        val messages =
            listOf(
                ChatMessage(Role.SYSTEM, PromptBuilder.fromResources(texts).system(user, Instant.parse("2026-11-15T09:00:00Z"))),
                ChatMessage(Role.USER, "s".repeat(1000)),
                ChatMessage(Role.USER, "Напомни через 5 минут выключить духовку"),
            )
        assertTrue(ContextBudget.fits(LlmRequest(messages, tools)), "Ресурсы исчерпали бюджет до пользовательского запроса")
    }
}
