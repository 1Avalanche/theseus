package theseus.core.adapters.db

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import theseus.core.adapters.http.AccountsRoutes
import theseus.core.application.AccountsService
import theseus.core.application.OperationReply
import theseus.core.application.OperationRequest
import theseus.core.config.DatabaseConfig
import theseus.core.domain.user.Gender
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountsIntegrationTest {
    private val postgres = PostgreSQLContainer("postgres:17-alpine")
    private lateinit var db: CoreDatabase
    private lateinit var repository: ExposedUserRepository
    private lateinit var service: AccountsService
    private lateinit var operations: DatabaseOperations
    private val now = Instant.parse("2026-11-12T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @BeforeAll fun start() {
        postgres.start()
        db = CoreDatabase.connect(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password, 5))
        db.migrate()
        repository = ExposedUserRepository(db)
        service = AccountsService(repository, clock)
        operations = DatabaseOperations(db)
    }

    @AfterAll fun stop() {
        db.close()
        postgres.stop()
    }

    private fun ApplicationTestBuilder.setUp() {
        application { routing { AccountsRoutes(service, operations, clock).install(this) } }
    }

    private suspend fun ApplicationTestBuilder.mutate(
        path: String,
        body: String,
        key: UUID = UUID.randomUUID(),
        method: HttpMethod = HttpMethod.Post,
    ) = client.request("/internal/v1$path") {
        this.method = method
        header("Idempotency-Key", key)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun ApplicationTestBuilder.invite(): JsonObject =
        Json.parseToJsonElement(mutate("/invites", """{"gender":"female","label":"маме"}""").bodyAsText()).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @Test fun `rest invite replay hides secret and canonical request hash rejects conflicts`() =
        testApplication {
            setUp()
            val key = UUID.randomUUID()
            val first = mutate("/invites", """{"gender":"female","label":"маме"}""", key)
            assertEquals(HttpStatusCode.Created, first.status)
            val firstJson = Json.parseToJsonElement(first.bodyAsText()).jsonObject
            val replay = mutate("/invites", """{"label":"маме","gender":"female"}""", key)
            assertEquals(HttpStatusCode.OK, replay.status)
            val json = Json.parseToJsonElement(replay.bodyAsText()).jsonObject
            assertEquals(firstJson["id"], json["id"])
            assertEquals(JsonNull, json["code"])
            assertEquals(JsonPrimitive(true), json["code_unavailable"])
            assertEquals(HttpStatusCode.Conflict, mutate("/invites", """{"gender":"male","label":"маме"}""", key).status)
            db.dataSource().connection.use { connection ->
                connection.prepareStatement("select result::text from operation_results where id=?").use {
                    it.setObject(1, key)
                    it.executeQuery().use { row ->
                        assertTrue(row.next())
                        assertFalse(row.getString(1).contains(firstJson.str("code")))
                    }
                }
            }
        }

    @Test fun `activation onboarding usage revoke restore and replay form one safe lifecycle`() =
        testApplication {
            setUp()
            val invite = invite()
            val activationKey = UUID.randomUUID()
            val request = """{"chat_id":789,"invite_code":"${invite.str("code")}"}"""
            val activation = mutate("/users/activate", request, activationKey)
            assertEquals(HttpStatusCode.Created, activation.status)
            val activated = activation.bodyAsText()
            val id =
                Json
                    .parseToJsonElement(activated)
                    .jsonObject
                    .getValue("user")
                    .jsonObject
                    .str("id")
            assertEquals(activated, mutate("/users/activate", request, activationKey).bodyAsText())
            assertEquals(HttpStatusCode.Conflict, mutate("/users/activate", request.replace("789", "790")).status)
            assertEquals(
                "onboarding",
                repository
                    .findByChatId(789)!!
                    .status.name
                    .lowercase(),
            )
            assertEquals(HttpStatusCode.OK, mutate("/users/$id", """{"name":" Аня "}""", method = HttpMethod.Patch).status)
            val profileKey = UUID.randomUUID()
            assertEquals(HttpStatusCode.OK, mutate("/users/$id", """{"timezone":"Europe/Moscow"}""", profileKey, HttpMethod.Patch).status)
            assertEquals("ACTIVE", repository.findByChatId(789)!!.status.name)
            val usageKey = UUID.randomUUID()
            val usage =
                """{"user_id":$id,"role":"main","model":"actual/model","generation_id":"gen-1",""" +
                    """"tokens_in":100,"tokens_out":10,"tokens_cached":50,"cost_usd":"0.00012345"}"""
            val receipt = mutate("/usage", usage, usageKey).bodyAsText()
            assertEquals(receipt, mutate("/usage", usage, usageKey).bodyAsText())
            assertEquals(1, service.usage(now.minusSeconds(1), now.plusSeconds(1)).count { it.userId == id.toLong() })
            assertEquals(HttpStatusCode.OK, mutate("/users/$id/revoke", "{}").status)
            assertEquals(
                HttpStatusCode.Conflict,
                mutate("/users/$id", """{"timezone":"Europe/Moscow"}""", profileKey, HttpMethod.Patch).status,
            )
            assertEquals(HttpStatusCode.Conflict, mutate("/usage", usage, usageKey).status)
            assertEquals(HttpStatusCode.OK, mutate("/users/$id/restore", "{}").status)
            assertEquals("ACTIVE", repository.findByChatId(789)!!.status.name)
        }

    @Test fun `parallel activation and parallel replay create one user per invitation`() =
        testApplication {
            setUp()
            val invite = invite()
            val key = UUID.randomUUID()
            val body = """{"chat_id":791,"invite_code":"${invite.str("code")}"}"""
            val replies = coroutineScope { (1..4).map { async { mutate("/users/activate", body, key).bodyAsText() } }.awaitAll() }
            assertEquals(1, replies.toSet().size)
            assertNotNull(repository.findByChatId(791))
        }

    @Test fun `failed transaction rolls back both domain write and operation result`() =
        runTest {
            val key = UUID.randomUUID()
            val request = OperationRequest(key, "agent", "test rollback", "hash", null)
            var inviteId = 0L
            assertThrows<IllegalStateException> {
                operations.execute(request, now, {}) {
                    inviteId = service.createInvite(Gender.MALE, "rollback").first.id
                    error("Сбой перед записью результата")
                }
            }
            assertTrue(service.invites("all").none { it.id == inviteId })
            var calls = 0
            operations.execute(request, now, {}) {
                calls++
                OperationReply(200, "{}")
            }
            operations.execute(request, now, {}) {
                calls++
                OperationReply(200, "{}")
            }
            assertEquals(1, calls)
        }

    @Test fun `mutations require canonical uuid and preserve missing versus null`() =
        testApplication {
            setUp()
            assertEquals(HttpStatusCode.BadRequest, client.post("/internal/v1/invites") { setBody("{}") }.status)
            val key = UUID.randomUUID()
            assertEquals(HttpStatusCode.Created, mutate("/invites", """{"gender":"unspecified"}""", key).status)
            assertEquals(HttpStatusCode.Conflict, mutate("/invites", """{"gender":"unspecified","label":null}""", key).status)
            assertEquals(HttpStatusCode.BadRequest, mutate("/invites", """{"gender":"female","extra":true}""").status)
        }
}
