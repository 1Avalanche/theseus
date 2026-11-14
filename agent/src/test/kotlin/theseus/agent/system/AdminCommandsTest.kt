package theseus.agent.system

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import theseus.agent.texts.Texts

class AdminCommandsTest {
    private val texts = Texts.load("texts/agent.properties")
    private val store = InMemoryAgentStore()
    private val paths = mutableListOf<String>()
    private var response = userReply("Анна")
    private val core =
        SystemCore { method, path, _, _ ->
            assertEquals("POST", method)
            paths += path
            response
        }
    private val commands = AdminCommands(core, OutgoingOperations(store, core), texts)

    private fun userReply(name: String?) =
        RestReply(
            200,
            buildJsonObject {
                put("id", 7)
                put("name", name?.let(::JsonPrimitive) ?: JsonNull)
            },
        )

    @Test
    fun `revoke names affected user and id from confirmed result`() =
        runTest {
            val reply = commands.handle(Incoming("revoke", 1, "/revoke 7"), "test_bot")

            assertEquals("Закрыл доступ: Анна (№7).", reply.text)
            assertEquals(listOf("/users/7/revoke"), paths)
        }

    @Test
    fun `restore names affected user and id from confirmed result`() =
        runTest {
            val reply = commands.handle(Incoming("restore", 1, "/restore 7"), "test_bot")

            assertEquals("Восстановил доступ: Анна (№7).", reply.text)
            assertEquals(listOf("/users/7/restore"), paths)
        }

    @Test
    fun `access confirmation identifies user without completed name`() =
        runTest {
            for (name in listOf(null, "", " ")) {
                response = userReply(name)

                val reply = commands.handle(Incoming("restore:$name", 1, "/restore 7"), "test_bot")

                assertEquals("Восстановил доступ: пользователь №7.", reply.text)
            }
        }

    @Test
    fun `technical retry preserves original confirmed identity without another request`() =
        runTest {
            val input = Incoming("same-event", 1, "/revoke 7")
            val first = commands.handle(input, "test_bot")
            response = userReply("Другое имя")

            assertEquals(first, commands.handle(input, "test_bot"))
            assertEquals("Закрыл доступ: Анна (№7).", first.text)
            assertEquals(1, paths.size)
        }

    @Test
    fun `profile name is not interpreted as another template parameter`() =
        runTest {
            response = userReply("Анна {id}")

            assertEquals("Закрыл доступ: Анна {id} (№7).", commands.handle(Incoming("name", 1, "/revoke 7"), "test_bot").text)
        }

    @Test
    fun `failed access change stays an error rather than a user confirmation`() =
        runTest {
            response = RestReply(409, Json.parseToJsonElement("""{"error":{"code":"invalid_state"}}""").jsonObject)

            for (command in listOf("/revoke", "/restore")) {
                assertEquals(texts.get("system.invalid_state"), commands.handle(Incoming(command, 1, "$command 7"), "test_bot").text)
            }
        }

    @Test
    fun `invite revocation keeps its own response`() =
        runTest {
            response = RestReply(200, JsonObject(emptyMap()))

            assertEquals(texts.get("admin.updated"), commands.handle(Incoming("invite", 1, "/invite_revoke 12"), "test_bot").text)
            assertEquals(listOf("/invites/12/revoke"), paths)
        }
}
