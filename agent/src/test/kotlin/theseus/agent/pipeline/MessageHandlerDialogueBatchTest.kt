package theseus.agent.pipeline

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.agent.core.CoreUser
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

class MessageHandlerDialogueBatchTest {
    private val texts = Texts.load("texts/agent.properties")
    private val owner = CoreUser(7, 4242, "active", "Аня", "female", "Europe/Moscow")
    private val clock = Clock.fixed(Instant.parse("2026-11-02T07:15:00Z"), ZoneOffset.UTC)

    private fun handler(
        core: FakeCore,
        llm: FakeLlm,
    ) = MessageHandler(core, llm, PromptBuilder.fromResources(texts), ReplyRenderer(texts), texts, clock)

    @Test
    fun `about and capabilities in same model response finish dialogue in either order`() =
        runTest {
            val about = ToolCall("about", "assistant_reply", """{"topic":"about"}""")
            val capabilities = ToolCall("capabilities", "assistant_reply", """{"topic":"capabilities"}""")
            for (calls in listOf(listOf(about, capabilities), listOf(capabilities, about))) {
                val core = FakeCore(mapOf(owner.chatId to owner))
                val llm =
                    FakeLlm(
                        ChatMessage(Role.ASSISTANT, null, calls),
                        ChatMessage(Role.ASSISTANT, "Я Сири."),
                    )
                val reply = requireNotNull(handler(core, llm).handle(owner.chatId, "Что ты умеешь?"))
                assertTrue(reply.contains(texts.get("reply.capabilities")), reply)
                assertTrue(reply.contains("Я Сири."), reply)
                assertTrue(core.calls.isEmpty())
                assertEquals(2, llm.requests.size)
                assertTrue(
                    llm.requests
                        .last()
                        .tools
                        .isEmpty(),
                )
            }
        }

    @Test
    fun `mixed persona and mutation are blocked before execution in either order`() =
        runTest {
            val about = ToolCall("about", "assistant_reply", """{"topic":"about"}""")
            val create = ToolCall("create", "create_reminder", """{"text":"Билеты","when":{"time":"15:00"}}""")
            for (calls in listOf(listOf(about, create), listOf(create, about))) {
                val core = FakeCore(mapOf(owner.chatId to owner))
                val llm = FakeLlm(ChatMessage(Role.ASSISTANT, null, calls))
                assertEquals(texts.get("reply.budget"), handler(core, llm).handle(owner.chatId, "Кто ты? Напомни про билеты"))
                assertTrue(core.calls.isEmpty())
                assertEquals(1, llm.requests.size)
            }
        }
}
