package theseus.agent.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class HttpCoreClientTest {
    private val token = "t".repeat(40)

    private fun client(
        status: HttpStatusCode,
        body: String,
        seen: MutableList<Pair<String, String?>> = mutableListOf(),
    ) = HttpCoreClient(
        HttpClient(
            MockEngine { request ->
                seen += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
        "http://core:8080",
        token,
    )

    private fun mcpHttp(
        captured: MutableList<JsonObject>,
        headers: MutableList<String?>,
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                headers += request.headers[HttpHeaders.Authorization]
                if (request.method == HttpMethod.Get) {
                    respond("", HttpStatusCode.MethodNotAllowed)
                } else {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val method = body.getValue("method").jsonPrimitive.content
                    if (method == "notifications/initialized") {
                        respond("", HttpStatusCode.Accepted)
                    } else {
                        if (method == "tools/call") captured += body
                        respond(
                            mcpReply(body).toString(),
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                }
            },
        ) { install(SSE) }

    private fun mcpReply(request: JsonObject): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", request.getValue("id"))
            put(
                "result",
                when (request.getValue("method").jsonPrimitive.content) {
                    "initialize" -> initializationResult(request)
                    "tools/call" -> toolResult()
                    else -> error("Неожиданный MCP-запрос")
                },
            )
        }

    private fun initializationResult(request: JsonObject): JsonObject =
        buildJsonObject {
            put("protocolVersion", request.getValue("params").jsonObject.getValue("protocolVersion"))
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", "test-core")
                    put("version", "1")
                },
            )
            put(
                "capabilities",
                buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) },
            )
        }

    private fun toolResult(): JsonObject =
        buildJsonObject {
            put("isError", false)
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", """{"status":"deleted"}""")
                        },
                    )
                },
            )
        }

    @Test
    fun `finds user by chat with service token`() =
        runTest {
            val seen = mutableListOf<Pair<String, String?>>()
            val body =
                """{"id":7,"chat_id":4242,"role":"admin","status":"active","name":"Аня","gender":"female",""" +
                    """"timezone":"Europe/Moscow","created_at":"2026-11-02T07:00:00Z","onboarded_at":null}"""

            val user = client(HttpStatusCode.OK, body, seen).findUserByChat(4242)

            assertEquals(7L, user!!.id)
            assertTrue(user.isActive)
            assertEquals("/internal/v1/users/by-chat/4242" to "Bearer $token", seen.single())
        }

    @Test
    fun `unknown chat is null`() =
        runTest {
            assertNull(client(HttpStatusCode.NotFound, """{"error":{"code":"not_found"}}""").findUserByChat(1))
        }

    @Test
    fun `core failure is not mistaken for unknown chat`() =
        runTest {
            assertThrows<CoreUnavailableException> { client(HttpStatusCode.ServiceUnavailable, "").findUserByChat(1) }
        }

    // MCP SDK обрабатывает ответы в собственных корутинах: виртуальное время runTest обгоняет их таймаутом.
    @Test
    fun `mcp wire sends operation and confirmation only in metadata with service token`() =
        runBlocking {
            val captured = mutableListOf<JsonObject>()
            val headers = mutableListOf<String?>()
            val http = mcpHttp(captured, headers)
            try {
                val core = HttpCoreClient(http, "http://core:8080", token)
                val key = UUID.randomUUID()
                val arguments = buildJsonObject { put("reminder_id", 17) }
                val confirmation =
                    buildJsonObject {
                        put("fingerprint", "snapshot-17")
                        put("created_at", "2026-11-02T07:15:00Z")
                    }

                val result = core.callTool(7, "delete_reminder", arguments, key, confirmation)
                core.callTool(7, "get_reminder", arguments)

                assertFalse(result.isError)
                assertEquals("""{"status":"deleted"}""", result.text)
                val mutation = captured.first().getValue("params").jsonObject
                assertEquals(arguments, mutation.getValue("arguments"))
                val metadata = mutation.getValue("_meta").jsonObject
                assertEquals(JsonPrimitive(7), metadata["theseus/user_id"])
                assertEquals(JsonPrimitive(key.toString()), metadata["theseus/operation_id"])
                assertEquals(confirmation, metadata["theseus/confirmation"])
                assertEquals(setOf("reminder_id"), mutation.getValue("arguments").jsonObject.keys)
                val read =
                    captured
                        .last()
                        .getValue("params")
                        .jsonObject
                        .getValue("_meta")
                        .jsonObject
                assertEquals(setOf("theseus/user_id"), read.keys)
                assertTrue(headers.isNotEmpty())
                assertTrue(headers.all { it == "Bearer $token" }, headers.toString())
            } finally {
                http.close()
            }
        }
}
