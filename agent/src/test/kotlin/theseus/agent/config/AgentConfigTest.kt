package theseus.agent.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AgentConfigTest {
    @Test fun `summary defaults to main and context window validates`() {
        val defaults = AgentConfig.fromEnv(valid)
        assertEquals(defaults.mainModels, defaults.summaryModels)
        assertEquals(18000, defaults.contextTokens)
        assertEquals(listOf("summary/model"), AgentConfig.fromEnv(valid + ("THESEUS_LLM_SUMMARY_MODELS" to "summary/model")).summaryModels)
        assertThrows<ConfigException> { AgentConfig.fromEnv(valid + ("THESEUS_LLM_CONTEXT_TOKENS" to "3999")) }
        assertThrows<ConfigException> { AgentConfig.fromEnv(valid + ("THESEUS_LLM_SUMMARY_MODELS" to ",,")) }
    }

    @Test
    fun `stand defaults to prod and rejects unknown values`() {
        assertEquals("prod", AgentConfig.fromEnv(valid).stand)
        assertEquals("test", AgentConfig.fromEnv(valid + ("THESEUS_STAND" to "test")).stand)
        assertEquals("prod", AgentConfig.fromEnv(valid + ("THESEUS_STAND" to "prod")).stand)
        assertThrows<ConfigException> { AgentConfig.fromEnv(valid + ("THESEUS_STAND" to "")) }
    }

    private val valid =
        mapOf(
            "THESEUS_TELEGRAM_BOT_TOKEN" to "123:abc",
            "THESEUS_CORE_URL" to "http://theseus-test-core:8080/",
            "THESEUS_SERVICE_TOKEN" to "s".repeat(40),
            "THESEUS_OPENROUTER_API_KEY" to "or-secret",
            "THESEUS_LLM_MAIN_MODELS" to "anthropic/claude-haiku-5.5, qwen/qwen3.8-flash ,google/gemini-3.1-flash-lite",
            "THESEUS_ADMIN_CHAT_ID" to "4242",
            "THESEUS_DB_URL" to "jdbc:postgresql://localhost/agent",
            "THESEUS_DB_USER" to "agent",
            "THESEUS_DB_PASSWORD" to "db-secret",
            "THESEUS_JOURNAL_KEY" to "a".repeat(64),
        )

    @Test
    fun `reads models in priority order and normalizes urls`() {
        val config = AgentConfig.fromEnv(valid)

        assertEquals(listOf("anthropic/claude-haiku-5.5", "qwen/qwen3.8-flash", "google/gemini-3.1-flash-lite"), config.mainModels)
        assertEquals("http://theseus-test-core:8080", config.coreUrl)
        assertEquals("https://openrouter.ai/api/v1", config.openRouterUrl)
    }

    @Test
    fun `reports missing values and too many models without secrets`() {
        val broken = valid - "THESEUS_CORE_URL" + ("THESEUS_LLM_MAIN_MODELS" to "a,b,c,d")

        val message = assertThrows<ConfigException> { AgentConfig.fromEnv(broken) }.message!!

        assertTrue("THESEUS_CORE_URL" in message && "THESEUS_LLM_MAIN_MODELS" in message, message)
        assertFalse("or-secret" in message)
    }

    @Test
    fun `toString does not leak secrets`() {
        assertFalse(AgentConfig.fromEnv(valid).toString().contains("or-secret"))
    }
}
