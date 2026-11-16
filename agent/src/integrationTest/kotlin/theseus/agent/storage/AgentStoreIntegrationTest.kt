package theseus.agent.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import theseus.agent.system.Outgoing
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
    @Test fun `sent reply targets survive restart remain user scoped expire and erase with user`() =
        runTest {
            val now = clock.instant()
            val target = theseus.agent.dialog.ReminderReplyTarget(17, "recurring", "Europe/Moscow")
            store(pool()).use { journal ->
                journal.dialogs.rememberReply(961, 500, target, now)
                journal.dialogs.rememberReply(962, 500, target.copy(reminderId = 18), now)
            }
            val saved = pool()
            store(saved).use { journal ->
                val dialogs = journal.dialogs
                assertEquals(target, dialogs.replyTarget(961, 500, now.plusSeconds(4 * 3600)))
                assertEquals(target.copy(reminderId = 18), dialogs.replyTarget(962, 500, now))
                assertEquals(null, dialogs.replyTarget(963, 500, now))
                dialogs.rememberReply(961, 500, target.copy(reminderId = 99), now.plusSeconds(60))
                assertEquals(target, dialogs.replyTarget(961, 500, now.plusSeconds(60)))
                val expired = now.plusSeconds(30L * 86400 + 1)
                assertEquals(null, dialogs.replyTarget(961, 500, expired))
                dialogs.cleanup(expired)
                saved.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("select count(*) from reminder_message_links where user_id in (961,962)").use { row ->
                            assertTrue(row.next())
                            assertEquals(0, row.getInt(1))
                        }
                    }
                }
                dialogs.rememberReply(961, 501, target, now)
                dialogs.rememberReply(962, 501, target.copy(reminderId = 18), now)
                journal.eraseUser(961, 9961, emptyList())
                assertEquals(null, dialogs.replyTarget(961, 501, now))
                assertEquals(target.copy(reminderId = 18), dialogs.replyTarget(962, 501, now))
            }
        }

    @Test fun `recovered outcome appends to original session once without opening a new session`() =
        runTest {
            store(pool()).use { journal ->
                val dialogs = journal.dialogs
                val session = dialogs.open(952, 9952, clock.instant())
                dialogs.append(session.id, 952, "recovered-dialog", theseus.agent.llm.Role.USER, "Перенеси", clock.instant())
                dialogs.completeEvent(952, "recovered-dialog", "Изменил", clock.instant().plusSeconds(1))
                dialogs.completeEvent(952, "recovered-dialog", "Не должен заменить", clock.instant().plusSeconds(2))
                val resumed = dialogs.open(952, 9952, clock.instant().plusSeconds(3))
                assertEquals(listOf("Перенеси", "Изменил"), resumed.entries.map { it.content })
                journal.eraseUser(952, 9952, emptyList())
            }
        }

    @Test fun `history summary and reference survive restart expire independently and erase with user`() =
        runTest {
            val now = clock.instant()
            var sessionId = 0L
            store(pool()).use { journal ->
                val dialogs = journal.dialogs
                val session = dialogs.open(951, 9951, now)
                sessionId = session.id
                dialogs.append(session.id, 951, "dialog-first", theseus.agent.llm.Role.USER, "PRIVATE-HISTORY", now)
                dialogs.append(session.id, 951, "dialog-first", theseus.agent.llm.Role.USER, "DUPLICATE", now)
                val saved = dialogs.open(951, 9951, now.plusSeconds(1))
                assertEquals(1, saved.entries.size)
                dialogs.summarize(session.id, "PRIVATE-SUMMARY", saved.entries.single().id)
                dialogs.references(session.id, listOf(theseus.agent.dialog.ListReference(42, "2026-11-13T09:00")))
            }
            val secondPool = pool()
            store(secondPool).use { journal ->
                val dialogs = journal.dialogs
                val resumed = dialogs.open(951, 9951, now.plusSeconds(2))
                assertEquals(sessionId, resumed.id)
                assertEquals("PRIVATE-SUMMARY", resumed.summary)
                assertTrue(resumed.entries.isEmpty())
                assertEquals(42L, resumed.references.single().reminderId)
                secondPool.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("select summary from dialog_sessions where user_id=951").use { row ->
                            assertTrue(row.next())
                            assertFalse(row.getString(1).contains("PRIVATE-SUMMARY"))
                        }
                        statement.executeQuery("select content from dialog_messages where user_id=951").use { row ->
                            assertTrue(row.next())
                            assertFalse(row.getString(1).contains("PRIVATE-HISTORY"))
                        }
                    }
                }
                val next = dialogs.open(951, 9951, now.plusSeconds(4 * 3600 + 2))
                assertTrue(next.id != sessionId)
                assertEquals(null, next.summary)
                assertTrue(next.references.isEmpty())
                dialogs.cleanup(now.plusSeconds(30L * 86400 + 3))
                secondPool.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("select count(*) from dialog_messages where user_id=951").use { row ->
                            assertTrue(row.next())
                            assertEquals(0, row.getInt(1))
                        }
                        statement.executeQuery("select summary from dialog_sessions where id=$sessionId").use { row ->
                            assertTrue(row.next())
                            assertFalse(row.getString(1).isNullOrBlank())
                        }
                    }
                }
                dialogs.cleanup(now.plusSeconds(31L * 86400))
                assertTrue(dialogs.open(951, 9951, now.plusSeconds(31L * 86400)).summary == null)
                journal.eraseUser(951, 9951, emptyList())
                secondPool.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("select count(*) from dialog_sessions where user_id=951").use { row ->
                            assertTrue(row.next())
                            assertEquals(0, row.getInt(1))
                        }
                    }
                }
            }
        }

    @Test
    fun `confirmation metadata and fixed execution key survive restart and user erasure`() =
        runTest {
            val id = UUID.randomUUID()
            val executionId = UUID.randomUUID()
            val body =
                Json
                    .parseToJsonElement(
                        """{"name":"delete_reminder","operation_id":"$executionId","arguments":{"reminder_id":42},
                "confirmation":{"fingerprint":"PRIVATE-CARD","created_at":"2026-11-12T10:00:00Z"}}""",
                    ).jsonObject
            val outgoing = Outgoing(id, "confirmation-index:card", "CONFIRM", "delete_reminder", body, 777)
            store(pool()).use { it.beginOperation(outgoing) }
            val secondPool = pool()
            store(secondPool).use { journal ->
                assertEquals(outgoing, journal.findOperation(id)!!.first)
                assertEquals(1, journal.eventOperations("confirmation-index").size)
                secondPool.connection.use { connection ->
                    connection.prepareStatement("select operation_id,expires_at-created_at from pending_actions where id=?").use {
                        it.setObject(1, id)
                        it.executeQuery().use { row ->
                            assertTrue(row.next())
                            assertEquals(executionId, row.getObject(1, UUID::class.java))
                            assertEquals("01:00:00", row.getString(2))
                        }
                    }
                }
                journal.finishOperation(
                    id,
                    RestReply(
                        200,
                        Json.parseToJsonElement("""{"text":"Оставил как есть.","resolution":"CANCELLED"}""").jsonObject,
                    ),
                )
                assertEquals(
                    "CANCELLED",
                    journal
                        .findOperation(id)!!
                        .second!!
                        .body["resolution"]!!
                        .toString()
                        .trim('"'),
                )
                journal.eraseUser(777, 777, emptyList())
                assertEquals(null, journal.findOperation(id))
                secondPool.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("select count(*) from pending_actions where user_id=777").use { row ->
                            assertTrue(row.next())
                            assertEquals(0, row.getInt(1))
                        }
                    }
                }
            }
        }

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

    @Test fun `erase removes payloads and linked admin caches while hashes survive restart`() =
        runTest {
            val target = CoreUser(701, 7701, "active", "ERASE-PRIVATE", "female", "UTC")
            val snapshot = EventSnapshot(Incoming("erase-start", 7701, "/start ERASE-CODE"), null, null)
            val activation =
                Outgoing(
                    UUID.randomUUID(),
                    "erase-start:activate",
                    "POST",
                    "/users/activate",
                    Json.parseToJsonElement("""{"chat_id":7701,"invite_code":"ERASE-CODE"}""").jsonObject,
                    null,
                )
            val saved = pool()
            store(saved).use { journal ->
                journal.beginEvent(snapshot)
                journal.beginEvent(EventSnapshot(Incoming("erase-profile", 7701, "ERASE-PRIVATE"), target, null))
                journal.beginEvent(
                    EventSnapshot(Incoming("erase-owner-list", 1, "/users@test_bot\nignored"), target.copy(id = 1, chatId = 1), null),
                )
                journal.finishEvent("erase-owner-list", BotReply("ERASE-PRIVATE"))
                journal.beginEvent(EventSnapshot(Incoming("keep-other", 8801, "KEEP-PRIVATE"), target.copy(id = 801, chatId = 8801), null))
                journal.propose(701, ZoneProposal("proposal", "UTC"))
                journal.beginOperation(activation)
                journal.beginOperation(
                    Outgoing(
                        UUID.randomUUID(),
                        "erase-usage",
                        "POST",
                        "/usage",
                        Json.parseToJsonElement("""{"user_id":701}""").jsonObject,
                        null,
                    ),
                )
                journal.beginEvent(EventSnapshot(Incoming("erase-invite", 1, "/invite female ERASE-LABEL"), null, null))
                val inviteOp = Outgoing(UUID.randomUUID(), "erase-invite:admin", "POST", "/invites", JsonObject(emptyMap()), null)
                journal.beginOperation(inviteOp)
                journal.finishOperation(
                    inviteOp.id,
                    RestReply(201, Json.parseToJsonElement("""{"id":123,"code":"ERASE-CODE"}""").jsonObject),
                )
                journal.eraseUser(701, 7701, listOf(123))
                assertEquals(null, journal.proposal(701))
                assertTrue(journal.findEvent("erase-start")!!.delivered)
                assertTrue(journal.findEvent("erase-owner-list")!!.delivered)
                assertTrue(journal.findEvent("erase-invite")!!.delivered)
                assertEquals(
                    "KEEP-PRIVATE",
                    journal
                        .findEvent("keep-other")!!
                        .snapshot.input.text,
                )
                assertTrue(journal.pendingOperations().none { it.eventKey.startsWith("erase-") })
                saved.connection.use { connection ->
                    connection.prepareStatement("select count(*) from incoming_events where chat_id=7701 or user_id=701").use {
                        it.executeQuery().use { row ->
                            row.next()
                            assertEquals(0, row.getInt(1))
                        }
                    }
                    connection.prepareStatement("select key_hash from erased_journal").use {
                        it.executeQuery().use { row -> while (row.next()) assertTrue(row.getString(1).matches(Regex("[0-9a-f]{64}"))) }
                    }
                }
            }
            store(pool()).use { journal ->
                assertTrue(journal.beginEvent(snapshot).delivered)
                assertEquals(410, journal.beginOperation(activation).second!!.status)
                assertTrue(journal.pendingEvents().none { it.snapshot.input.chatId == 7701L })
            }
        }

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
