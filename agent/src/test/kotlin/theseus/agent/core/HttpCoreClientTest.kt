package theseus.agent.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
}
