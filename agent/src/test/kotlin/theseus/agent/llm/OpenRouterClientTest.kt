package theseus.agent.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class OpenRouterClientTest {
    private val models = listOf("anthropic/claude-haiku-5.5", "google/gemini-3.1-flash-lite")

    private fun client(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String,
        captured: MutableList<String> = mutableListOf(),
        headers: MutableList<String?> = mutableListOf(),
    ): OpenRouterClient {
        val engine =
            MockEngine { request ->
                captured += (request.body as TextContent).text
                headers += request.headers[HttpHeaders.Authorization]
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        return OpenRouterClient(
            HttpClient(engine) { install(HttpTimeout) },
            "https://or.test/api/v1",
            "or-key",
            models,
            Duration.ofSeconds(30),
        )
    }

    private val request =
        LlmRequest(
            messages = listOf(ChatMessage(Role.SYSTEM, "Ты Сири"), ChatMessage(Role.USER, "напомни через 5 минут")),
            tools = listOf(ToolDefinition("create_reminder", "Создать", JsonObject(emptyMap()))),
        )

    @Test
    fun `sends fallback models tools and data collection deny`() =
        runTest {
            val captured = mutableListOf<String>()
            val headers = mutableListOf<String?>()
            client(
                body = """{"model":"m","choices":[{"message":{"role":"assistant","content":"ok"}}]}""",
                captured = captured,
                headers = headers,
            ).complete(request)

            val sent = Json.parseToJsonElement(captured.single()).jsonObject
            assertEquals(models, sent["models"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals("deny", sent["provider"]!!.jsonObject["data_collection"]!!.jsonPrimitive.content)
            assertEquals(
                "create_reminder",
                sent["tools"]!!
                    .jsonArray[0]
                    .jsonObject["function"]!!
                    .jsonObject["name"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(
                "system",
                sent["messages"]!!
                    .jsonArray[0]
                    .jsonObject["role"]!!
                    .jsonPrimitive.content,
            )
            assertEquals("Bearer or-key", headers.single())
        }

    @Test
    fun `parses tool calls model and usage`() =
        runTest {
            val body =
                """{"id":"gen-1","model":"google/gemini-3.1-flash-lite","choices":[{"message":{"role":"assistant","content":null,""" +
                    """"tool_calls":[{"id":"call_1","type":"function",""" +
                    """"function":{"name":"create_reminder","arguments":"{\"text\":\"X\"}"}}]}}],""" +
                    """"usage":{"prompt_tokens":2100,"completion_tokens":85,"cost":0.0002}}"""

            val response = client(body = body).complete(request)

            assertEquals("google/gemini-3.1-flash-lite", response.model)
            assertEquals(ToolCall("call_1", "create_reminder", """{"text":"X"}"""), response.message.toolCalls.single())
            assertEquals(LlmUsage(2100, 85), response.usage)
        }

    @Test
    fun `outgoing tool definition includes required function type`() =
        runTest {
            val captured = mutableListOf<String>()
            client(
                body = """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""",
                captured = captured,
            ).complete(request)

            val sent = Json.parseToJsonElement(captured.single()).jsonObject
            val tool = sent["tools"]!!.jsonArray.single().jsonObject
            assertEquals("function", tool["type"]?.jsonPrimitive?.content)
        }

    @Test
    fun `outgoing assistant tool call history includes required function type`() =
        runTest {
            val captured = mutableListOf<String>()
            val call = ChatMessage(Role.ASSISTANT, null, toolCalls = listOf(ToolCall("call_1", "create_reminder", "{}")))
            client(
                body = """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""",
                captured = captured,
            ).complete(LlmRequest(listOf(call, ChatMessage(Role.TOOL, "{}", toolCallId = "call_1")), request.tools))

            val messages = Json.parseToJsonElement(captured.single()).jsonObject["messages"]!!.jsonArray
            val serializedCall =
                messages
                    .first()
                    .jsonObject["tool_calls"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("function", serializedCall["type"]?.jsonPrimitive?.content)
            assertEquals("call_1", serializedCall["id"]!!.jsonPrimitive.content)
            assertEquals(
                "call_1",
                messages
                    .last()
                    .jsonObject["tool_call_id"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `http error becomes llm exception`() =
        runTest {
            assertThrows<LlmException> { client(HttpStatusCode.BadGateway, "{}").complete(request) }
        }

    @Test
    fun `tool result message is serialized with tool call id`() {
        val wire =
            client(body = "{}").toWire(
                LlmRequest(listOf(ChatMessage(Role.TOOL, "{\"status\":\"created\"}", toolCallId = "call_1")), emptyList()),
            )

        assertEquals("tool", wire.messages.single().role)
        assertEquals("call_1", wire.messages.single().toolCallId)
        assertEquals(null, wire.tools)
    }
}
