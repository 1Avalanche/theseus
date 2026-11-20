package theseus.agent.eval

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import theseus.agent.llm.ChatMessage
import theseus.agent.llm.LlmClient
import theseus.agent.llm.LlmRequest
import theseus.agent.llm.LlmResponse
import theseus.agent.llm.Role
import theseus.agent.llm.ToolCall
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

class EvalRunnerTest {
    @Test fun `reply changing only time preserves original date through agent and real core`() =
        runBlocking {
            val case =
                objectJson(
                    """{"id":"reply-date-regression","input":"измени время на 16.00",
                "context":{"now_local":"2026-11-20T21:00"},
                "reply_to":{"source":"agent_reply","reminder_id":1,"kind":"one_time","timezone":"Europe/Moscow"},
                "history":[{"role":"user","content":"напомни 30 ноября написать Вадиму про перенос приема в больнице"},
                {"role":"assistant","content":"Поставил: «написать Вадиму про перенос приема в больнице» — 30 ноября в 09:00."}],
                "expect":{"calls":[]}}""",
                )
            val harness = EvalHarness(data(), case)
            val created =
                harness.core.callTool(
                    1,
                    "create_reminder",
                    objectJson("""{"text":"написать Вадиму про перенос приема в больнице","when":{"date":"2026-11-30"}}"""),
                )
            assertFalse(created.isError, created.text)
            val llm =
                object : LlmClient {
                    override suspend fun complete(request: LlmRequest): LlmResponse {
                        assertTrue(
                            request.messages
                                .last()
                                .content!!
                                .contains("\"reminder_id\":1"),
                        )
                        return LlmResponse(
                            ChatMessage(
                                Role.ASSISTANT,
                                null,
                                listOf(ToolCall("update", "update_reminder", """{"reminder_id":1,"when":{"time":"16:00"}}""")),
                            ),
                            "fake",
                            null,
                        )
                    }
                }
            val reply = requireNotNull(harness.run(llm))
            assertTrue(reply.text.contains("30 ноября в 16:00"), reply.text)
            assertFalse(reply.text.contains("завтра"), reply.text)
            assertEquals(1, harness.state.size)
            assertEquals(LocalDateTime.of(2026, 11, 30, 16, 0), harness.state.getValue(1).startLocal)
        }

    @Test fun `text pricing reserves cache and rejects unknown charges`() {
        val rates = textPrice(objectJson("""{"prompt":"0.1","completion":"0.5","input_cache_write":"0.2","web_search":"10"}"""))
        assertEquals(0, rates.first.compareTo(BigDecimal("0.3")))
        assertEquals(0, rates.second.compareTo(BigDecimal("0.5")))
        assertThrows(IllegalArgumentException::class.java) { textPrice(objectJson("""{"new_charge":"1"}""")) }
    }

    @TempDir lateinit var temporary: Path

    private fun data() = EvalData(Path.of("..").toAbsolutePath().normalize())

    @Test fun `all fixture contexts initialize actual core without network`() {
        val data = data()
        assertEquals(114, data.cases.size)
        data.cases.forEach { EvalHarness(data, it) }
    }

    @Test fun `budget survives restart and unknown cost retains reserve`() {
        val file = temporary.resolve("budget.json")
        val first = EvalBudget(file, BigDecimal("5"))
        val id = first.reserve(BigDecimal("4"))
        first.settle(id, null)
        val resumed = EvalBudget(file, BigDecimal("5"))
        assertThrows(IllegalStateException::class.java) { resumed.reserve(BigDecimal("2")) }
        resumed.settle(id, BigDecimal("1"))
        resumed.reserve(BigDecimal("4"))
        assertTrue(Files.readString(file).contains(id))
    }

    @Test fun `absent null arrays and resolved dates use strict matchers`() {
        val matcher = EvalMatcher(data().context(data().cases.first()))
        assertTrue(matcher.matches(objectJson("{\"\$absent\":true}"), null))
        assertFalse(matcher.matches(JsonNull, null))
        assertFalse(matcher.matches(objectJson("{\"\$absent\":true}"), JsonNull))
        assertTrue(matcher.matches(Json.parseToJsonElement("[1,2]"), Json.parseToJsonElement("[2,1]")))
        assertFalse(matcher.matches(Json.parseToJsonElement("[1,1]"), Json.parseToJsonElement("[1,2]")))
        val target = objectJson("{\"\$resolves_to\":\"2026-11-02T10:05\"}")
        assertTrue(matcher.matches(target, objectJson("""{"after":{"minutes":5}}""")))
    }

    @Test fun `production pipeline creates reminder and keeps deletion behind confirmation`() =
        runBlocking {
            val data = data()
            for ((id, name, arguments) in listOf(
                Triple("once-01", "create_reminder", """{"text":"Духовка","when":{"after":{"minutes":5}}}"""),
                Triple("del-01", "delete_reminder", """{"reminder_id":14}"""),
            )) {
                val case = data.cases.single { it.string("id") == id }
                val harness = EvalHarness(data, case)
                val before = harness.state.toMap()
                val llm =
                    object : LlmClient {
                        override suspend fun complete(request: LlmRequest) =
                            LlmResponse(
                                ChatMessage(Role.ASSISTANT, null, listOf(ToolCall("call", name, arguments))),
                                "fake",
                                null,
                            )
                    }
                val reply = harness.run(llm)
                assertFalse(reply?.text.isNullOrBlank())
                if (id == "del-01") {
                    assertEquals(before, harness.state)
                    assertTrue(reply!!.buttons.isNotEmpty())
                } else {
                    assertEquals(1, harness.state.size)
                }
            }
        }
}
