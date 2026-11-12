package theseus.agent.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import theseus.agent.core.CoreUser
import theseus.agent.system.BotReply
import theseus.agent.system.EventSnapshot
import theseus.agent.system.Incoming
import theseus.agent.system.OutgoingOperations
import theseus.agent.system.RestReply
import theseus.agent.system.SystemCore
import theseus.agent.system.ZoneProposal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentStoreIntegrationTest {
    private val postgres = PostgreSQLContainer("postgres:17-alpine")
    private val clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC)
    private val key = "ab".repeat(32)

    @BeforeAll fun start() {
        postgres.start()
    }

    @AfterAll fun stop() {
        postgres.stop()
    }

    private fun pool() =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                schema = "agent"
                maximumPoolSize = 2
            },
        )

    private fun store(pool: HikariDataSource) = JdbcAgentStore(pool, JournalCipher(key), clock).also { it.migrate() }

    @Test fun `restart reuses key and original secret request after core committed but response was lost`() =
        runTest {
            val body = Json.parseToJsonElement("""{"chat_id":7,"invite_code":"SECRET-PRIVATE-CODE"}""").jsonObject
            val responses = mutableMapOf<UUID, RestReply>()
            val keys = mutableListOf<UUID>()
            var first = true
            val core =
                SystemCore { _, _, request, id ->
                    keys += requireNotNull(id)
                    assertEquals(body, request)
                    val response = responses.getOrPut(id) { RestReply(201, Json.parseToJsonElement("""{"user_id":7}""").jsonObject) }
                    if (first) {
                        first = false
                        error("Ответ потерян после коммита")
                    }
                    response
                }
            val firstPool = pool()
            store(firstPool).use { journal ->
                assertThrows<IllegalStateException> {
                    OutgoingOperations(
                        journal,
                        core,
                    ).perform("activation", "POST", "/users/activate", body)
                }
                firstPool.connection.use { connection ->
                    connection.prepareStatement("select request_encrypted from outgoing_operations where event_key='activation'").use {
                        it.executeQuery().use { row ->
                            assertTrue(row.next())
                            assertFalse(row.getString(1).contains("SECRET-PRIVATE-CODE"))
                        }
                    }
                }
            }
            store(pool()).use { journal ->
                OutgoingOperations(journal, core).recover()
                val result =
                    OutgoingOperations(
                        journal,
                        core,
                    ).perform("activation", "POST", "/users/activate", Json.parseToJsonElement("{}").jsonObject)
                assertEquals(201, result.status)
                assertEquals(1, keys.toSet().size)
                assertEquals(1, responses.size)
                assertTrue(journal.pendingOperations().isEmpty())
            }
        }

    @Test fun `event profile proposal and encrypted reply survive separate connections and acknowledgement`() =
        runTest {
            val snapshot =
                EventSnapshot(Incoming("name-event", 55, "PRIVATE-NAME"), CoreUser(55, 55, "onboarding", null, "female", null), null)
            store(pool()).use { journal ->
                journal.beginEvent(snapshot)
                journal.propose(55, ZoneProposal("proposal", "Europe/Berlin"))
                journal.finishEvent("name-event", BotReply("PRIVATE-REPLY"))
            }
            val secondPool = pool()
            store(secondPool).use { journal ->
                val event = journal.findEvent("name-event")!!
                assertEquals(snapshot, event.snapshot)
                assertEquals("PRIVATE-REPLY", event.reply?.text)
                assertEquals(ZoneProposal("proposal", "Europe/Berlin"), journal.proposal(55))
                secondPool.connection.use { connection ->
                    connection.prepareStatement("select payload_encrypted,reply_encrypted from incoming_events where id='name-event'").use {
                        it.executeQuery().use { row ->
                            assertTrue(row.next())
                            assertFalse(row.getString(1).contains("PRIVATE-NAME"))
                            assertFalse(row.getString(2).contains("PRIVATE-REPLY"))
                        }
                    }
                }
                journal.delivered("name-event")
                assertTrue(journal.findEvent("name-event")!!.delivered)
                assertTrue(journal.pendingEvents().none { it.snapshot.input.id == "name-event" })
            }
        }
}
