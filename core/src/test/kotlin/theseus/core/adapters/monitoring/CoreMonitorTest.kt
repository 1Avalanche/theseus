package theseus.core.adapters.monitoring

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import theseus.core.application.FakeNotifier
import theseus.core.application.InMemoryReminderRepository
import theseus.core.application.InMemoryUserRepository
import theseus.core.application.MutableClock
import theseus.core.application.ReminderRepository
import theseus.core.application.ReminderScheduler
import theseus.core.texts.Texts
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class CoreMonitorTest {
    @TempDir
    lateinit var directory: Path

    private val clock = MutableClock(Instant.parse("2026-11-11T00:00:00Z"))

    private fun scheduler(repository: ReminderRepository) =
        ReminderScheduler(
            repository,
            InMemoryUserRepository(),
            FakeNotifier(),
            Texts.load("texts/core.properties"),
            clock,
            Duration.ofMinutes(5),
        )

    @Test
    fun `empty successful tick advances pulse but publishing does not`() =
        runTest {
            val repository = InMemoryReminderRepository()
            val scheduler = scheduler(repository)
            val file = directory.resolve("core.json")
            val monitor = CoreMonitor(clock, { true }, repository, scheduler, Job(), DeliverySignal(clock), file)
            monitor.publish()
            assertNull(scheduler.lastSuccessAt)

            scheduler.tick()
            val last = scheduler.lastSuccessAt
            clock.advance(Duration.ofMinutes(20))
            monitor.publish()
            val snapshot = Json.parseToJsonElement(Files.readString(file)).jsonObject
            assertEquals(last!!.epochSecond.toString(), snapshot["last_success_at"]!!.jsonPrimitive.content)
            assertEquals(clock.instant().epochSecond.toString(), snapshot["observed_at"]!!.jsonPrimitive.content)
            assertEquals("0", snapshot["overdue_count"]!!.jsonPrimitive.content)
        }

    @Test
    fun `database failure leaves pulse unset and publishes database failure`() =
        runTest {
            val repository = mockk<ReminderRepository>()
            coEvery { repository.findDue(any(), any()) } throws IllegalStateException("private text")
            coEvery { repository.deliveryBacklog(any()) } throws IllegalStateException("private text")
            val scheduler = scheduler(repository)
            try {
                scheduler.tick()
            } catch (_: IllegalStateException) {
                // Ошибка прохода не должна считаться пульсом.
            }
            val file = directory.resolve("core.json")
            CoreMonitor(clock, { true }, repository, scheduler, Job(), DeliverySignal(clock), file).publish()
            val snapshot = Files.readString(file)
            assertNull(scheduler.lastSuccessAt)
            assertEquals(
                "false",
                Json
                    .parseToJsonElement(snapshot)
                    .jsonObject["database_ok"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(false, snapshot.contains("private text"))
        }

    @Test
    fun `retry after overrides exponential backoff and success resets failure`() {
        val signal = DeliverySignal(clock)
        signal.failure("429", 900)
        assertEquals(clock.instant().epochSecond + 900, signal.retryAt)
        assertEquals("1", signal.snapshot()["consecutive_errors"]!!.jsonPrimitive.content)
        signal.success()
        assertEquals(0, signal.retryAt)
        assertEquals(
            "1",
            signal
                .snapshot()["errors"]!!
                .jsonObject["429"]!!
                .jsonPrimitive.content,
        )
        assertEquals("null", signal.snapshot()["failing_since"].toString())
    }
}
