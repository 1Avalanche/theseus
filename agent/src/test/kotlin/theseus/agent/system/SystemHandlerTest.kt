package theseus.agent.system

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.agent.core.CoreClient
import theseus.agent.core.CoreUser
import theseus.agent.core.ToolResult
import theseus.agent.core.ToolSpec
import theseus.agent.texts.Texts
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SystemHandlerTest {
    private val texts = Texts.load("texts/agent.properties")
    private val clock = Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC)
    private val zones = listOf("Москва" to "Europe/Moscow", "Екатеринбург" to "Asia/Yekaterinburg")
    private val store = InMemoryAgentStore()
    private val core = FakeCore()
    private val operations = OutgoingOperations(store, core)
    private val onboarding = Onboarding(store, operations, texts, clock, zones)

    private fun handler() = SystemHandler(core, store, operations, onboarding, AdminCommands(core, operations, texts), 1, "test_bot", texts)

    private val active = CoreUser(7, 7, "active", "Аня", "female", "Europe/Moscow")

    private class FakeCore :
        CoreClient,
        SystemCore {
        var user: CoreUser? = null
        val requests = mutableListOf<Outgoing>()
        var response = RestReply(200, JsonObject(emptyMap()))

        override suspend fun findUserByChat(chatId: Long) = user

        override suspend fun listTools(): List<ToolSpec> = error("Системные события не используют MCP")

        override suspend fun callTool(
            userId: Long,
            name: String,
            arguments: JsonObject,
        ): ToolResult = error("Системные события не используют MCP")

        override suspend fun request(
            method: String,
            path: String,
            body: JsonObject?,
            operationId: UUID?,
        ): RestReply {
            requests += Outgoing(operationId ?: UUID.randomUUID(), "", method, path, body ?: JsonObject(emptyMap()), user?.id)
            return response
        }
    }

    @Test
    fun `unfinished dialogue is not replayed and completed reply survives duplicate update`() =
        runTest {
            core.user = active
            val input = Incoming("ordinary-message", 7, "PRIVATE-REMINDER-TEXT")
            val event = handler().beginDialogue(input)
            assertTrue(event.snapshot.dialogue)
            assertNull(event.snapshot.input.text)
            assertEquals(texts.get("reply.unconfirmed"), handler().handle(input)?.text)
            assertTrue(core.requests.isEmpty())
            store.finishEvent(input.id, BotReply("Подтверждённый ответ"))
            assertEquals("Подтверждённый ответ", handler().handle(input)?.text)
            store.delivered(input.id)
            assertEquals("", handler().handle(input)?.text)
            core.user = active.copy(status = "revoked")
            assertEquals(texts.get("access.revoked"), handler().handle(input)?.text)
        }

    @Test
    fun `unknown and revoked chats never reach ordinary dialogue or system mutations`() =
        runTest {
            for (user in listOf(null, active.copy(status = "revoked"), active.copy(status = "future"))) {
                core.user = user
                val result = handler().handle(Incoming("access:${user?.status}", 7, "напомни завтра 👍"))
                assertNotNull(result)
                assertTrue(core.requests.isEmpty())
            }
        }

    @Test
    fun `media with no text is rejected before dialogue and emoji text remains ordinary text`() =
        runTest {
            core.user = active
            assertEquals(texts.get("reply.text_only"), handler().handle(Incoming("media", 7))?.text)
            assertNull(handler().handle(Incoming("emoji", 7, "Напомни завтра 👍")))
            assertTrue(core.requests.isEmpty())
        }

    @Test
    fun `version uses packaged build version and still checks access`() =
        runTest {
            core.user = active
            assertEquals(
                texts.get("reply.version", mapOf("version" to BuildInfo.version)),
                handler().handle(Incoming("version", 7, "/version@test_bot"))?.text,
            )
            core.user = active.copy(status = "revoked")
            assertEquals(texts.get("access.revoked"), handler().handle(Incoming("version", 7, "/version"))?.text)
            assertTrue(core.requests.isEmpty())
        }

    @Test
    fun `non admin cannot invoke any administrative operation`() =
        runTest {
            core.user = active
            for (command in AdminCommands.commands) {
                assertEquals(texts.get("admin.forbidden"), handler().handle(Incoming(command, 7, "$command 42"))?.text)
            }
            assertTrue(core.requests.isEmpty())
        }

    @Test
    fun `owner creates invitation with explicit gender and receives deep link`() =
        runTest {
            core.user = active.copy(id = 1, chatId = 1)
            core.response =
                RestReply(
                    201,
                    Json
                        .parseToJsonElement(
                            """{"id":12,"code":"0123456789ABCDEF","expires_at":"2026-11-15T10:00:00Z"}""",
                        ).jsonObject,
                )
            val result = handler().handle(Incoming("owner-invite", 1, "/invite female маме"))!!
            assertTrue(result.text.contains("https://t.me/test_bot?start=0123456789ABCDEF"))
            assertEquals(
                "\"female\"",
                core.requests
                    .single()
                    .body["gender"]
                    .toString(),
            )
            assertEquals(
                "\"маме\"",
                core.requests
                    .single()
                    .body["label"]
                    .toString(),
            )
        }

    @Test
    fun `administrative lists render domain states and gender in Russian`() =
        runTest {
            core.user = active.copy(id = 1, chatId = 1)
            core.response =
                RestReply(
                    200,
                    Json
                        .parseToJsonElement(
                            """{"invites":[{"id":12,"state":"active","gender":"female","label":"маме","expires_at":"2026-11-15"}]}""",
                        ).jsonObject,
                )
            val result = handler().handle(Incoming("owner-invites", 1, "/invites"))!!
            assertTrue(result.text.contains("действует"))
            assertTrue(result.text.contains("женский род"))
            assertFalse(result.text.contains("female"))
        }

    @Test
    fun `activation prompts name and notifies admin without storing plaintext code in outgoing result`() =
        runTest {
            core.response =
                RestReply(
                    201,
                    Json
                        .parseToJsonElement(
                            """{"user":{"id":7,"chat_id":7,"status":"onboarding","name":null,"gender":"female",""" +
                                """"timezone":null},"invite":{"id":12,"label":"маме"}}""",
                        ).jsonObject,
                )
            val result = handler().handle(Incoming("activate", 7, "/start SECRET-CODE", "Аня"))!!
            assertEquals(texts.get("onboarding.name"), result.text)
            assertTrue(result.adminNotice!!.contains("12"))
            assertFalse(result.adminNotice!!.contains("SECRET-CODE"))
            assertEquals("POST", core.requests.single().method)
            assertEquals("/users/activate", core.requests.single().path)
        }

    @Test
    fun `name event resumes original step after core committed before event reply was saved`() =
        runTest {
            val initial = active.copy(status = "onboarding", name = null, timezone = null)
            core.user = initial
            store.beginEvent(EventSnapshot(Incoming("name", 7, "Анна"), initial, null))
            core.user = initial.copy(name = "Анна")
            val result = handler().handle(Incoming("name", 7, "Анна"))!!
            assertEquals(texts.get("onboarding.timezone"), result.text)
            assertTrue(
                core.requests
                    .single()
                    .body
                    .containsKey("name"),
            )
            val key = core.requests.single().id
            handler().handle(Incoming("name", 7, "Анна"))
            assertEquals(1, core.requests.size)
            assertEquals(
                key,
                store.operations.values
                    .single()
                    .first.id,
            )
        }

    @Test
    fun `timezone requires own current proposal and explicit confirmation`() =
        runTest {
            core.user = active.copy(status = "onboarding", timezone = null)
            val offered = handler().handle(Incoming("city", 7, "Екатеринбург"))!!
            assertTrue(offered.text.contains("Asia/Yekaterinburg"))
            assertTrue(offered.text.contains("15:00"))
            assertTrue(core.requests.isEmpty())
            assertEquals(texts.get("onboarding.stale"), handler().handle(Incoming("stale", 7, callback = "ob:yes:wrong"))?.text)
            val callback =
                offered.buttons
                    .first()
                    .first()
                    .data
            assertEquals(texts.get("onboarding.done"), handler().handle(Incoming("confirm", 7, callback = callback))?.text)
            assertEquals(
                "Asia/Yekaterinburg",
                core.requests
                    .single()
                    .body["timezone"]!!
                    .toString()
                    .trim('"'),
            )
            assertNull(store.proposal(7))
        }

    @Test
    fun `raw approval and geography do not confirm timezone`() =
        runTest {
            core.user = active.copy(status = "onboarding", timezone = null)
            store.propose(7, ZoneProposal("old", "Europe/Moscow"))
            for ((id, text) in listOf("yes" to "да", "emoji" to "👍", "offset" to "+03:00")) {
                assertEquals(texts.get("onboarding.unknown_zone"), handler().handle(Incoming(id, 7, text))?.text)
            }
            assertTrue(core.requests.isEmpty())
            assertEquals("Europe/Berlin", onboarding.resolve("Берлин"))
            assertEquals("America/New_York", onboarding.resolve("America/New_York"))
        }
}
