package theseus.agent.pipeline

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import theseus.agent.llm.ChatMessage
import theseus.agent.llm.ContextBudget
import theseus.agent.llm.LlmRequest
import theseus.agent.llm.Role
import theseus.agent.llm.ToolCall

class TurnBudgetTest {
    @Test fun `all roles share three requests and maximum six tools in two rounds`() {
        val budget = TurnBudget()
        val request = LlmRequest(listOf(ChatMessage(Role.USER, "X")), emptyList())
        budget.request(request.copy(role = "summary"))
        budget.request(request)
        budget.request(request)
        assertThrows<BudgetExceeded> { budget.request(request) }
        budget.tools((1..3).map { ToolCall("$it", "get_reminder", "{\"reminder_id\":$it}") }, 2)
        budget.tools((4..6).map { ToolCall("$it", "get_reminder", "{\"reminder_id\":$it}") }, 2)
        assertThrows<BudgetExceeded> { budget.tools(listOf(ToolCall("7", "get_reminder", "{}")), 2) }
    }

    @Test fun `changed call ids and json order cannot bypass repeated call guard`() {
        val budget = TurnBudget()
        budget.tools(listOf(ToolCall("first", "update_reminder", """{"reminder_id":42,"text":"X"}""")), 2)
        assertThrows<BudgetExceeded> {
            budget.tools(listOf(ToolCall("second", "update_reminder", """{"text":"X","reminder_id":42}""")), 2)
        }
    }

    @Test fun `oversized batch is rejected before any execution`() {
        assertThrows<BudgetExceeded> {
            TurnBudget().tools((1..7).map { ToolCall("$it", "create_reminder", "{\"text\":\"$it\"}") }, 2)
        }
    }

    @Test fun `context accounts for utf8 tool results and preserves unicode when trimming`() {
        assertFalse(ContextBudget.fits(LlmRequest(listOf(ChatMessage(Role.TOOL, "Я".repeat(8000))), emptyList())))
        assertTrue(ContextBudget.size(ContextBudget.take("Я😀Я", 6)) <= 6)
        org.junit.jupiter.api.Assertions
            .assertEquals("Я😀", ContextBudget.take("Я😀Я", 6))
    }
}
