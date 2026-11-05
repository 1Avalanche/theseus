package theseus.core.adapters.telegram

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelegramNotifierTest {
    @Test
    fun `chat not found and blocked bot are permanent failures`() {
        assertTrue(TelegramNotifier.isPermanentFailure(400))
        assertTrue(TelegramNotifier.isPermanentFailure(403))
    }

    @Test
    fun `rate limit, bad token, server errors and unknown are retried`() {
        listOf(401, 429, 500, 502, null).forEach { assertFalse(TelegramNotifier.isPermanentFailure(it), "код $it") }
    }
}
