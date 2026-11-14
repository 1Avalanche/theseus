package theseus.agent.system

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import theseus.agent.core.CoreUser
import theseus.agent.texts.Texts
import java.util.UUID

class HardDeleteTest {
    private val texts = Texts.load("texts/agent.properties")
    private val store = InMemoryAgentStore()
    private val core = FakeCore()
    private val locks = ChatLocks()
    private val operations = OutgoingOperations(store, core)

    private fun deletion(journal: AgentStore = store) = HardDelete(core, journal, operations, locks, texts)

    private val command = Incoming("owner-delete", 1, "/hard_delete 7")
    private val old = EventSnapshot(Incoming("old-start", 77, "/start OLD-CODE"), null, null)

    private class FakeCore : SystemCore {
        var user: CoreUser? = CoreUser(7, 77, "active", "PRIVATE-NAME", "female", "UTC")
        var loseCompleteResponse = false
        var completed = 0
        var calls = 0
        private val receipts = mutableMapOf<UUID, RestReply>()

        override suspend fun request(
            method: String,
            path: String,
            body: JsonObject?,
            operationId: UUID?,
        ): RestReply {
            calls++
            if (path == "/users/by-chat/77") {
                return user?.let { RestReply(200, Json.encodeToJsonElement(CoreUser.serializer(), it).jsonObject) }
                    ?: RestReply(404, JsonObject(emptyMap()))
            }
            return receipts[operationId] ?: newReply(path, requireNotNull(operationId))
        }

        private fun newReply(
            path: String,
            operationId: UUID,
        ): RestReply {
            val result =
                if (path == "/users/7/hard-delete") {
                    user = user!!.copy(status = "revoked")
                    RestReply(200, Json.parseToJsonElement("""{"id":7,"chat_id":77,"invite_ids":[12]}""").jsonObject)
                } else {
                    assertEquals("/users/7/hard-delete/complete", path)
                    user = null
                    completed++
                    RestReply(200, Json.parseToJsonElement("""{"id":7,"deleted":true}""").jsonObject)
                }
            receipts[requireNotNull(operationId)] = result
            if (loseCompleteResponse && path.endsWith("/complete")) {
                loseCompleteResponse = false
                error("Ответ потерян после коммита")
            }
            return result
        }
    }

    @Test fun `arguments and administrator protection reject without mutations`() =
        runTest {
            for (text in listOf("/hard_delete", "/hard_delete 0", "/hard_delete -1", "/hard_delete abc", "/hard_delete 7 extra")) {
                assertEquals(texts.get("hard_delete.usage"), deletion().handle(command.copy(text = text), 1).text)
            }
            assertEquals(texts.get("hard_delete.admin"), deletion().handle(command.copy(text = "/hard_delete 1"), 1).text)
            assertEquals(0, core.calls)
        }

    @Test fun `delete erases old events and proposals but retains technical duplicate protection`() =
        runTest {
            store.beginEvent(old)
            store.propose(7, ZoneProposal("old", "UTC"))
            val reply = deletion().handle(command, 1)
            assertTrue(reply.text.contains("№7"))
            assertNull(core.user)
            assertNull(store.proposal(7))
            assertTrue(store.events.isEmpty())
            assertTrue(store.beginEvent(old).delivered)
            assertEquals(
                "true",
                store.operations
                    .getValue("${command.id}:delete-prepare")
                    .second!!
                    .body
                    .value("deleted"),
            )
            assertFalse(store.operations.values.any { it.second?.body?.containsKey("chat_id") == true })
            assertEquals(reply, deletion().handle(command, 1))
            assertEquals(1, core.completed)
        }

    @Test fun `agent purge failure leaves user blocked and retry completes both phases`() =
        runTest {
            store.beginEvent(old)
            val failing =
                object : AgentStore by store {
                    override suspend fun eraseUser(
                        userId: Long,
                        chatId: Long,
                        inviteIds: List<Long>,
                    ) = error("БД agent недоступна")
                }
            assertThrows<IllegalStateException> { deletion(failing).handle(command, 1) }
            assertEquals("revoked", core.user!!.status)
            assertEquals(0, core.completed)
            deletion().handle(command, 1)
            assertEquals(1, core.completed)
            assertTrue(store.events.isEmpty())
        }

    @Test fun `lost complete reply never erases data from fresh registration on retry`() =
        runTest {
            store.beginEvent(old)
            core.loseCompleteResponse = true
            assertThrows<IllegalStateException> { deletion().handle(command, 1) }
            assertEquals(1, core.completed)
            core.user = CoreUser(8, 77, "onboarding", null, "female", null)
            val fresh = old.copy(input = Incoming("fresh-start", 77, "/start NEW-CODE"), user = core.user)
            store.beginEvent(fresh)
            deletion().handle(command, 1)
            assertEquals(fresh, store.findEvent("fresh-start")!!.snapshot)
            assertEquals(8L, core.user!!.id)
            assertEquals(1, core.completed)
        }

    @Test fun `purge waits for target chat work before deleting`() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val ongoing =
                async {
                    locks.withChat(77) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
            entered.await()
            val delete = async { deletion().handle(command, 1) }
            runCurrent()
            assertEquals("revoked", core.user!!.status)
            assertEquals(0, core.completed)
            release.complete(Unit)
            ongoing.await()
            delete.await()
            assertEquals(1, core.completed)
        }
}
