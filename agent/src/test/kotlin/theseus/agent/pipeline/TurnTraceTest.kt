package theseus.agent.pipeline

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.agent.llm.LlmException
import theseus.agent.llm.LlmFailure
import theseus.agent.llm.ToolCall
import theseus.agent.texts.Texts

class TurnTraceTest {
    @Test fun `trace correlates stages and provider status without exception body or tool arguments`() {
        val lines = mutableListOf<String>()
        val trace = TurnTrace(lines::add)
        trace.started(7)
        trace.selected(ToolCall("id", "create_reminder", "private arguments"), null)
        trace.model("main")
        trace.failed(LlmException("private response", IllegalArgumentException("private cause"), LlmFailure.HTTP, 429))
        trace.stopped("deadline")
        val output = lines.joinToString("\n")
        assertTrue(output.contains("user_id=7"))
        assertTrue(output.contains("tool=create_reminder"))
        assertTrue(output.contains("stage=llm"))
        assertTrue(output.contains("llm_reason=HTTP, http_status=429"))
        assertTrue(output.contains("reason=deadline"))
        assertFalse(output.contains("private"))
        assertTrue(lines.map { Regex("trace_id=([^,]+)").find(it)!!.groupValues[1] }.distinct().size == 1)
    }

    @Test fun `model names and malformed topics cannot leak user text into trace`() {
        val lines = mutableListOf<String>()
        val trace = TurnTrace(lines::add)
        val replies = DialogReplies(Texts.load("texts/agent.properties"))
        for (call in listOf(
            ToolCall("private id", "private name", "private arguments"),
            ToolCall("id", "assistant_reply", """{"topic":"private topic"}"""),
            ToolCall("id", "assistant_reply", """{"topic":"about","text":"private text"}"""),
            ToolCall("id", "assistant_reply", """{"topic":"capabilities"}"""),
            ToolCall("id", "assistant_reply", """{"topic":"invalid_time"}"""),
        )) {
            trace.selected(call, replies.approvedTopic(call))
        }
        assertFalse(lines.joinToString().contains("private"))
        assertTrue(lines.first().contains("tool=unknown"))
        assertTrue(lines[3].contains("topic=capabilities"))
        assertTrue(lines[4].contains("topic=invalid_time"))
    }
}
