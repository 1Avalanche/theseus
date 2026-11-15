package theseus.core.adapters.http

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.core.adapters.mcp.McpServerFactory
import theseus.core.adapters.mcp.ReminderTools
import theseus.core.application.DeliveryService
import theseus.core.application.InMemoryOperations
import theseus.core.application.InMemoryReminderRepository
import theseus.core.application.InMemoryUserRepository
import theseus.core.application.MutableClock
import theseus.core.application.ReminderService
import theseus.core.application.UserService
import theseus.core.application.user
import theseus.core.domain.reminder.After
import theseus.core.domain.reminder.DeliveryKind
import theseus.core.domain.reminder.DeliveryRecord
import theseus.core.domain.reminder.DeliveryStatus
import theseus.core.domain.reminder.ReminderStatus
import theseus.core.domain.reminder.When
import theseus.core.domain.reminder.WhenResolver
import theseus.core.texts.Texts
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

class DeliveryRoutesTest {
    @Suppress("LongMethod") // Сквозной REST-сценарий: авторизация, изоляция и повтор после потери ответа.
    @Test
    fun `authenticated delivery routes isolate users validate requests and replay exact snooze`() =
        testApplication {
            val clock = MutableClock(Instant.parse("2026-11-15T09:00:00Z"))
            val repository = InMemoryReminderRepository()
            val users = UserService(InMemoryUserRepository(user(), user(id = 2, chatId = 200)))
            val service = ReminderService(repository, users, WhenResolver(LocalTime.of(9, 0)), clock)
            val operations = InMemoryOperations()
            val reminder = service.createOneTime(1, "Вода", When(after = After(minutes = 1)))
            repository.record(
                reminder.copy(status = ReminderStatus.DONE, nextFireAt = null),
                DeliveryRecord(clock.instant(), DeliveryKind.SCHEDULED, DeliveryStatus.SENT, false, 99, null, 7),
                clock.instant(),
            )
            val token = "t".repeat(40)
            application {
                coreModule(
                    HttpDependencies(
                        token,
                        { true },
                        users,
                        McpServerFactory.create(ReminderTools(service, Texts.load("texts/core.properties"), operations)),
                        deliveries = DeliveryRoutes(DeliveryService(repository, service, clock), service, operations, clock),
                    ),
                )
            }
            assertEquals(HttpStatusCode.Unauthorized, client.get("/internal/v1/deliveries/by-message?user_id=1&message_id=99").status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.get("/internal/v1/deliveries/by-message?user_id=2&message_id=99") { bearerAuth(token) }.status,
            )
            val found = client.get("/internal/v1/deliveries/by-message?user_id=1&message_id=99") { bearerAuth(token) }
            assertTrue(found.bodyAsText().contains("\"reminder_id\":${reminder.id}"))
            val path = "/internal/v1/deliveries/7/snooze"
            val key = UUID.randomUUID().toString()
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .post(path) {
                        bearerAuth(token)
                        setBody("""{"user_id":1,"option":"15m"}""")
                    }.status,
            )
            val first =
                client.post(path) {
                    bearerAuth(token)
                    header("Idempotency-Key", key)
                    setBody("""{"user_id":1,"option":"15m"}""")
                }
            assertEquals(HttpStatusCode.OK, first.status)
            val response = first.bodyAsText()
            clock.now = clock.instant().plusSeconds(60)
            val repeat =
                client.post(path) {
                    bearerAuth(token)
                    header("Idempotency-Key", key)
                    setBody("""{"user_id":1,"option":"15m"}""")
                }
            assertEquals(response, repeat.bodyAsText())
            val wrong =
                client.post(path) {
                    bearerAuth(token)
                    header("Idempotency-Key", UUID.randomUUID().toString())
                    setBody("""{"user_id":2,"option":"15m"}""")
                }
            assertEquals(HttpStatusCode.NotFound, wrong.status)
        }
}
