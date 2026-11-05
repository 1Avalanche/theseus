package theseus.agent.prompt

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.agent.core.CoreUser
import theseus.agent.texts.Texts
import java.time.Instant

class PromptBuilderTest {
    private val texts = Texts.load("texts/agent.properties")
    private val user = CoreUser(id = 7, chatId = 1, status = "active", name = "Аня", gender = "female", timezone = "Asia/Yekaterinburg")
    private val now = Instant.parse("2026-11-02T07:15:00Z")

    @Test
    fun `stable blocks go first and comments are stripped`() {
        val builder = PromptBuilder(listOf("Персона <!-- заметка -->", "Правила"), "Контекст {name}", texts)

        assertEquals("Персона\n\nПравила\n\nКонтекст Аня", builder.system(user, now))
    }

    @Test
    fun `context has local time weekday timezone and gender`() {
        val prompt = PromptBuilder.fromResources(texts).system(user, now)

        assertTrue(prompt.contains("2026-11-02 12:15, понедельник"), prompt)
        assertTrue(prompt.contains("Asia/Yekaterinburg"))
        assertTrue(prompt.contains("Грамматический род: женский"))
        assertFalse(prompt.contains("<!--"))
        assertTrue(prompt.indexOf("# Сири") < prompt.indexOf("# Контекст"))
    }

    @Test
    fun `unspecified gender asks to avoid gendered forms`() {
        val prompt = PromptBuilder.fromResources(texts).system(user.copy(gender = "unspecified"), now)

        assertTrue(prompt.contains("избегай форм с родом"))
    }
}
