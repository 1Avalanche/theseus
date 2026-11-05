package theseus.core.adapters.http

import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.core.adapters.mcp.McpServerFactory
import theseus.core.adapters.mcp.ReminderTools
import theseus.core.application.InMemoryReminderRepository
import theseus.core.application.InMemoryUserRepository
import theseus.core.application.MutableClock
import theseus.core.application.ReminderService
import theseus.core.application.UserService
import theseus.core.application.user
import theseus.core.domain.reminder.WhenResolver
import theseus.core.texts.Texts
import java.time.Instant
import java.time.LocalTime

class HttpModuleTest {
    private val token = "t".repeat(40)
    private val reminders = InMemoryReminderRepository()
    private var healthy = true

    private fun ApplicationTestBuilder.setUp() {
        val users = UserService(InMemoryUserRepository(user(id = 7, chatId = 4242)))
        val service =
            ReminderService(reminders, users, WhenResolver(LocalTime.of(9, 0)), MutableClock(Instant.parse("2026-11-02T07:15:00Z")))
        application {
            coreModule(
                HttpDependencies(
                    serviceToken = token,
                    health = { healthy },
                    users = users,
                    mcpServer = McpServerFactory.create(ReminderTools(service, Texts.load("texts/core.properties"))),
                ),
            )
        }
    }

    @Test
    fun `health is open and reflects database state`() =
        testApplication {
            setUp()

            assertEquals(HttpStatusCode.OK, client.get("/health").status)
            healthy = false
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/health").status)
        }

    @Test
    fun `internal endpoints and mcp require service token`() =
        testApplication {
            setUp()

            assertEquals(HttpStatusCode.Unauthorized, client.get("/internal/v1/users/by-chat/4242").status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/internal/v1/users/by-chat/4242") { bearerAuth("wrong") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/mcp") { bearerAuth("wrong") }.status)
            assertNotEquals(HttpStatusCode.Unauthorized, client.get("/mcp") { bearerAuth(token) }.status)
        }

    @Test
    fun `finds user by chat id`() =
        testApplication {
            setUp()

            val found = client.get("/internal/v1/users/by-chat/4242") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, found.status)
            assertTrue(found.bodyAsText().contains(""""id":7"""), found.bodyAsText())
            assertTrue(found.bodyAsText().contains(""""timezone":"Europe/Moscow""""))

            assertEquals(HttpStatusCode.NotFound, client.get("/internal/v1/users/by-chat/1") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/internal/v1/users/by-chat/abc") { bearerAuth(token) }.status)
        }

    @Test
    fun `mcp client lists tools and creates reminder for user from meta`() =
        testApplication {
            setUp()
            val http = createClient { install(SSE) }
            val mcp = http.mcpStreamableHttp("/mcp") { headers.append(HttpHeaders.Authorization, "Bearer $token") }

            val tools = mcp.listTools().tools
            assertEquals(listOf("create_reminder"), tools.map { it.name })

            val result =
                mcp.callTool(
                    name = "create_reminder",
                    arguments = mapOf("text" to "Выключить духовку", "when" to mapOf("after" to mapOf("minutes" to 5))),
                    meta = mapOf(McpServerFactory.USER_META_KEY to 7),
                )
            val text = (result.content.single() as TextContent).text

            assertTrue(result.isError != true, text)
            assertTrue(text.contains(""""status":"created""""), text)
            assertEquals(
                7L,
                reminders.reminders.values
                    .single()
                    .userId,
            )
        }
}
