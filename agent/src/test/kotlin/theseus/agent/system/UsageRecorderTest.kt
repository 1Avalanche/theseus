package theseus.agent.system

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import theseus.agent.llm.ChatMessage
import theseus.agent.llm.LlmException
import theseus.agent.llm.LlmResponse
import theseus.agent.llm.LlmUsage
import theseus.agent.llm.Role
import java.util.UUID

class UsageRecorderTest {
    private val store = InMemoryAgentStore()
    private val writes = mutableListOf<Pair<UUID, JsonObject>>()
    private var quota = "false"
    private val core =
        SystemCore { method, _, body, id ->
            if (method == "GET") {
                RestReply(200, Json.parseToJsonElement("""{"exceeded":$quota}""").jsonObject)
            } else {
                writes += requireNotNull(id) to requireNotNull(body)
                RestReply(201, Json.parseToJsonElement("""{"id":1}""").jsonObject)
            }
        }
    private val recorder = PersistentUsageRecorder(core, OutgoingOperations(store, core))
    private val response =
        LlmResponse(
            ChatMessage(Role.ASSISTANT, "PRIVATE-TEXT"),
            "actual/fallback",
            LlmUsage(100, 10, 75, "0.00012345"),
            "gen-1",
            250,
        )

    @Test fun `usage keeps actual model cache cost and generation without message content`() =
        runTest {
            recorder.before(7)
            recorder.record(7, "invocation-1", response)
            recorder.record(7, "invocation-1", response)
            recorder.record(7, "invocation-2", response.copy(generationId = "gen-2"))
            assertEquals(2, writes.size)
            assertEquals(2, writes.map { it.first }.toSet().size)
            val payload = writes.first().second
            assertEquals("\"actual/fallback\"", payload["model"].toString())
            assertEquals("\"0.00012345\"", payload["cost_usd"].toString())
            assertEquals("75", payload["tokens_cached"].toString())
            assertTrue(writes.none { it.second.toString().contains("PRIVATE-TEXT") })
        }

    @Test fun `missing usage does not invent zero cost and exceeded quota blocks llm entry`() =
        runTest {
            assertThrows<LlmException> { recorder.record(7, "missing", response.copy(usage = null)) }
            assertTrue(writes.isEmpty())
            quota = "true"
            assertThrows<LlmException> { recorder.before(7) }
        }
}
