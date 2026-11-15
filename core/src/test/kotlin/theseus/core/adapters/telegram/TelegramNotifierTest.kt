package theseus.core.adapters.telegram

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.ApiException
import dev.inmo.tgbotapi.requests.abstracts.Request
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.core.adapters.monitoring.DeliverySignal
import theseus.core.application.MutableClock
import theseus.core.application.NotifyResult
import java.time.Duration
import java.time.Instant

class TelegramNotifierTest {
    private class FailingBot(
        private val code: Int,
    ) : TelegramBot {
        var calls = 0
        var keyboard: dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup? = null

        override suspend fun <T : Any> execute(request: Request<T>): T {
            calls++
            keyboard =
                (request as? dev.inmo.tgbotapi.requests.send.SendTextMessage)?.replyMarkup as?
                    dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
            throw ApiException(code, "private text")
        }

        override fun close() = Unit
    }

    @Test fun `notification carries reserved delivery id in all snooze buttons`() =
        runTest {
            val bot = FailingBot(403)
            TelegramNotifier(bot).send(1, "Вода", 42)
            val wire =
                kotlinx.serialization.json.Json.encodeToString(
                    dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
                        .serializer(),
                    requireNotNull(bot.keyboard),
                )
            listOf("snz:42:15m", "snz:42:1h", "snz:42:tmr").forEach { assertTrue(wire.contains(it)) }
        }

    @Test
    fun `temporary failure waits before another network attempt`() =
        runTest {
            val clock = MutableClock(Instant.parse("2026-11-11T00:00:00Z"))
            val bot = FailingBot(503)
            val signal = DeliverySignal(clock)
            val notifier = TelegramNotifier(bot, clock, signal)
            assertFalse((notifier.send(1, "private text") as NotifyResult.Failed).permanent)
            notifier.send(1, "private text")
            assertEquals(1, bot.calls)
            clock.advance(Duration.ofSeconds(5))
            notifier.send(1, "private text")
            assertEquals(2, bot.calls)
            assertFalse(signal.snapshot().toString().contains("private text"))
        }

    @Test
    fun `blocked recipient does not delay attempts for another recipient`() =
        runTest {
            val bot = FailingBot(403)
            val notifier = TelegramNotifier(bot)
            assertTrue((notifier.send(1, "X") as NotifyResult.Failed).permanent)
            notifier.send(2, "X")
            assertEquals(2, bot.calls)
        }

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
