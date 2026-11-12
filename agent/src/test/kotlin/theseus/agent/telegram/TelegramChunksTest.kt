package theseus.agent.telegram

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelegramChunksTest {
    @Test fun `long text keeps emoji pairs intact across telegram messages`() {
        val text = "x".repeat(3999) + "👍" + "y".repeat(4000)
        val chunks = telegramChunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 4000 })
        assertFalse(chunks.any { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }
}
