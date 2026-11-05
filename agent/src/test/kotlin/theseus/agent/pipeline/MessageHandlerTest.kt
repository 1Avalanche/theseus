package theseus.agent.pipeline

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.agent.core.CoreClient
import theseus.agent.core.CoreUser
import theseus.agent.core.ToolResult
import theseus.agent.core.ToolSpec
import theseus.agent.llm.ChatMessage
import theseus.agent.llm.LlmClient
import theseus.agent.llm.LlmException
import theseus.agent.llm.LlmRequest
import theseus.agent.llm.LlmResponse
import theseus.agent.llm.Role
import theseus.agent.llm.ToolCall
import theseus.agent.prompt.PromptBuilder
import theseus.agent.texts.Texts
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MessageHandlerTest {
    private val texts = Texts.load("texts/agent.properties")
    private val owner = CoreUser(id = 7, chatId = 4242, status = "active", name = "Аня", gender = "female", timezone = "Europe/Moscow")
    private val clock = Clock.fixed(Instant.parse("2026-11-02T07:15:00Z"), ZoneOffset.UTC)

    private val createdJson =
        """{"status":"created","reminder":{"id":1,"text":"Выключить духовку","kind":"one_time","status":"active",""" +
            """"schedule":{"at_local":"2026-11-02T10:20"},"next_at_local":"2026-11-02T10:20"},"warnings":[]}"""

    private class FakeCore(
        val users: Map<Long, CoreUser>,
        var result: ToolResult = ToolResult("{}", isError = false),
    ) : CoreClient {
        val calls = mutableListOf<Triple<Long, String, JsonObject>>()

        override suspend fun findUserByChat(chatId: Long) = users[chatId]

        override suspend fun listTools() = listOf(ToolSpec("create_reminder", "Создать напоминание", JsonObject(emptyMap())))

        override suspend fun callTool(
            userId: Long,
            name: String,
            arguments: JsonObject,
        ): ToolResult {
            calls += Triple(userId, name, arguments)
            return result
        }
    }

    private class FakeLlm(
        vararg answers: ChatMessage,
    ) : LlmClient {
        private val queue = ArrayDeque(answers.toList())
        val requests = mutableListOf<LlmRequest>()
        var failure: Exception? = null

        override suspend fun complete(request: LlmRequest): LlmResponse {
            failure?.let { throw it }
            requests += request
            return LlmResponse(queue.removeFirst(), "test/model", null)
        }
    }

    private fun toolCall(arguments: String) =
        ChatMessage(Role.ASSISTANT, null, toolCalls = listOf(ToolCall("call_1", "create_reminder", arguments)))

    private fun handler(
        core: FakeCore,
        llm: FakeLlm,
    ) = MessageHandler(core, llm, PromptBuilder(listOf("Персона"), "Сейчас {now_local}", texts), ReplyRenderer(texts), texts, clock)

    @Test
    fun `unknown chat is dropped before llm`() =
        runTest {
            val llm = FakeLlm()

            assertNull(handler(FakeCore(emptyMap()), llm).handle(1, "напомни через 5 минут"))
            assertTrue(llm.requests.isEmpty())
        }

    @Test
    fun `inactive user is dropped before llm`() =
        runTest {
            val llm = FakeLlm()
            val core = FakeCore(mapOf(4242L to owner.copy(status = "revoked")))

            assertNull(handler(core, llm).handle(4242, "привет"))
            assertTrue(llm.requests.isEmpty())
        }

    @Test
    fun `successful tool call is answered by template without second llm call`() =
        runTest {
            val core = FakeCore(mapOf(4242L to owner), ToolResult(createdJson, isError = false))
            val llm = FakeLlm(toolCall("""{"text":"Выключить духовку","when":{"after":{"minutes":5}}}"""))

            val reply = handler(core, llm).handle(4242, "напомни через 5 минут выключить духовку")

            assertEquals("Поставил: «Выключить духовку» — сегодня в 10:20.", reply)
            assertEquals(1, llm.requests.size)
            val (userId, name, args) = core.calls.single()
            assertEquals(7L, userId)
            assertEquals("create_reminder", name)
            assertEquals("Выключить духовку", args["text"]!!.jsonPrimitive.content)
        }

    @Test
    fun `system prompt and tools from core are sent to llm`() =
        runTest {
            val llm = FakeLlm(ChatMessage(Role.ASSISTANT, "Привет."))

            handler(FakeCore(mapOf(4242L to owner)), llm).handle(4242, "привет")

            val request = llm.requests.single()
            assertEquals(listOf(Role.SYSTEM, Role.USER), request.messages.map { it.role })
            assertTrue(
                request.messages
                    .first()
                    .content!!
                    .contains("Сейчас 2026-11-02 10:15"),
            )
            assertEquals(listOf("create_reminder"), request.tools.map { it.name })
        }

    @Test
    fun `tool error goes back to llm which phrases the answer`() =
        runTest {
            val error = """{"error":{"code":"time_in_past","message":"Время уже прошло"}}"""
            val core = FakeCore(mapOf(4242L to owner), ToolResult(error, isError = true))
            val llm =
                FakeLlm(toolCall("""{"text":"X","when":{"time":"09:00"}}"""), ChatMessage(Role.ASSISTANT, "Это время уже прошло. Завтра?"))

            val reply = handler(core, llm).handle(4242, "напомни в 9 про X")

            assertEquals("Это время уже прошло. Завтра?", reply)
            val second = llm.requests[1].messages
            assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL), second.map { it.role })
            assertEquals(error, second.last().content)
            assertEquals("call_1", second.last().toolCallId)
        }

    @Test
    fun `malformed tool arguments are not sent to core`() =
        runTest {
            val core = FakeCore(mapOf(4242L to owner))
            val llm = FakeLlm(toolCall("{не json"), ChatMessage(Role.ASSISTANT, "Повтори, пожалуйста."))

            handler(core, llm).handle(4242, "напомни")

            assertTrue(core.calls.isEmpty())
            assertTrue(
                llm.requests[1]
                    .messages
                    .last()
                    .content!!
                    .contains("invalid_arguments"),
            )
        }

    @Test
    fun `endless tool calls stop with failure reply`() =
        runTest {
            val core = FakeCore(mapOf(4242L to owner), ToolResult("""{"error":{"code":"internal"}}""", isError = true))
            val call = toolCall("""{"text":"X","when":{"time":"09:00"}}""")
            val llm = FakeLlm(call, call, call, call)

            assertEquals(texts.get("reply.failure"), handler(core, llm).handle(4242, "X"))
            assertEquals(MessageHandler.DEFAULT_MAX_TOOL_ROUNDS + 1, llm.requests.size)
        }

    @Test
    fun `llm failure gives failure reply`() =
        runTest {
            val llm = FakeLlm().apply { failure = LlmException("OpenRouter 502") }

            assertEquals(texts.get("reply.failure"), handler(FakeCore(mapOf(4242L to owner)), llm).handle(4242, "привет"))
        }
}
