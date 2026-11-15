package theseus.agent.system

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.agent.core.CoreUser
import theseus.agent.texts.Texts

class DeliveryActionsTest {
    private val user = CoreUser(1, 10, "active", "Анна", "female", "UTC")
    private val texts = Texts.load("texts/agent.properties")

    @Test fun `snooze callback uses durable key authenticated user and removes original keyboard`() =
        runTest {
            val store = InMemoryAgentStore()
            val calls = mutableListOf<String>()
            val core =
                SystemCore { method, path, body, id ->
                    assertEquals("POST", method)
                    assertEquals("/deliveries/7/snooze", path)
                    assertEquals("{\"user_id\":1,\"option\":\"tmr\"}", body.toString())
                    calls += requireNotNull(id).toString()
                    RestReply(
                        200,
                        Json
                            .parseToJsonElement(
                                """{"snoozed_until_local":"2026-11-16T09:00","timezone":"Europe/Moscow"}""",
                            ).jsonObject,
                    )
                }
            val actions = DeliveryActions(OutgoingOperations(store, core), texts)
            val input = Incoming("callback:1", 10, callback = "snz:7:tmr", callbackMessageId = 99)
            val first = actions.callback(input, user)
            assertEquals(99L, first.clearKeyboardMessageId)
            assertTrue(first.text.contains("Europe/Moscow"))
            assertEquals(first, actions.callback(input, user))
            assertEquals(1, calls.size)
        }

    @Test fun `invalid callback never reaches core and domain rejection has no success claim`() =
        runTest {
            val store = InMemoryAgentStore()
            var calls = 0
            val actions =
                DeliveryActions(
                    OutgoingOperations(
                        store,
                        SystemCore { _, _, _, _ ->
                            calls++
                            RestReply(409, Json.parseToJsonElement("""{"error":{"code":"invalid_state"}}""").jsonObject)
                        },
                    ),
                    texts,
                )
            for (data in listOf("snz:1", "snz:no:15m", "snz:1:15m:extra", "snz:1:bad", "snz:-1:1h")) {
                assertEquals(texts.get("reply.snooze_unavailable"), actions.callback(Incoming(data, 10, callback = data), user).text)
            }
            assertEquals(0, calls)
            assertEquals(texts.get("reply.snooze_unavailable"), actions.callback(Incoming("valid", 10, callback = "snz:7:1h"), user).text)
            assertEquals(1, calls)
        }
}
