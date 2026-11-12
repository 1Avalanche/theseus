package theseus.agent.telegram

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.TooMuchRequestsException
import dev.inmo.tgbotapi.requests.GetUpdates
import dev.inmo.tgbotapi.types.Response
import dev.inmo.tgbotapi.types.RetryAfterError
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import theseus.agent.monitoring.AgentMonitor
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class MonitoredTelegramBotTest {
    @TempDir
    lateinit var directory: Path

    private val clock = Clock.fixed(Instant.parse("2026-11-11T00:00:00Z"), ZoneOffset.UTC)
    private val delegate = mockk<TelegramBot>()

    private fun monitor() = AgentMonitor(clock, directory.resolve("health"), directory.resolve("monitor.json"))

    @Test
    fun `empty getUpdates advances pulse and health timestamp`() =
        runTest {
            coEvery { delegate.execute(any<GetUpdates>()) } returns emptyList()
            val monitor = monitor()
            monitor.publish(true)
            assertFalse(Files.exists(directory.resolve("health")))

            MonitoredTelegramBot(delegate, monitor).execute(GetUpdates())
            monitor.publish(true)
            assertEquals(clock.instant(), monitor.lastSuccessAt)
            assertEquals(clock.instant(), Files.getLastModifiedTime(directory.resolve("health")).toInstant())
            val snapshot = Json.parseToJsonElement(Files.readString(directory.resolve("monitor.json"))).jsonObject
            assertEquals(clock.instant().epochSecond.toString(), snapshot["last_success_at"]!!.jsonPrimitive.content)
        }

    @Test
    fun `429 records only code and respects retry after`() =
        runTest {
            val failure = TooMuchRequestsException(RetryAfterError(900, 0), Response(errorCode = 429), "private text", null, null)
            coEvery { delegate.execute(any<GetUpdates>()) } throws failure
            val monitor = monitor()
            assertThrows<TooMuchRequestsException> { MonitoredTelegramBot(delegate, monitor).execute(GetUpdates()) }
            assertEquals(900_000, testScheduler.currentTime)
            assertNull(monitor.lastSuccessAt)
            assertEquals(
                "429",
                monitor.polling
                    .snapshot()["last_code"]!!
                    .jsonPrimitive.content,
            )
            assertFalse(
                monitor.polling
                    .snapshot()
                    .toString()
                    .contains("private text"),
            )
        }

    @Test
    fun `cancellation does not become dependency failure`() =
        runTest {
            coEvery { delegate.execute(any<GetUpdates>()) } throws CancellationException()
            val monitor = monitor()
            assertThrows<CancellationException> { MonitoredTelegramBot(delegate, monitor).execute(GetUpdates()) }
            assertEquals(
                "0",
                monitor.polling
                    .snapshot()["consecutive_errors"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `poll success preserves errors in other channels`() =
        runTest {
            val monitor = monitor()
            monitor.openRouter.failure("503")
            monitor.telegram.failure("403")
            monitor.pollingSucceeded()
            assertEquals(
                "1",
                monitor.openRouter
                    .snapshot()["consecutive_errors"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(
                "1",
                monitor.telegram
                    .snapshot()["consecutive_errors"]!!
                    .jsonPrimitive.content,
            )
        }
}
