package theseus.core.application

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.core.domain.reminder.DeliveryStatus
import theseus.core.domain.reminder.NewReminder
import theseus.core.domain.reminder.ReminderStatus
import theseus.core.domain.user.UserStatus
import theseus.core.texts.Texts
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderSchedulerTest {
    private val fireAt = Instant.parse("2026-11-02T07:20:00Z")
    private val clock = MutableClock(fireAt.minusSeconds(60))
    private val reminders = InMemoryReminderRepository()
    private val notifier = FakeNotifier()
    private val texts = Texts.load("texts/core.properties")

    private fun scheduler(users: InMemoryUserRepository = InMemoryUserRepository(user())) =
        ReminderScheduler(reminders, users, notifier, texts, clock, lateThreshold = Duration.ofMinutes(5))

    private suspend fun createReminder() =
        reminders.create(
            NewReminder(1, "Выключить духовку", ZoneId.of("Europe/Moscow"), LocalDateTime.of(2026, 11, 2, 10, 20), fireAt),
            clock.instant(),
        )

    @Test
    fun `does not send before fire time`() =
        runTest {
            createReminder()

            assertEquals(0, scheduler().tick())
            assertTrue(notifier.sent.isEmpty())
        }

    @Test
    fun `sends due reminder and marks it done`() =
        runTest {
            val reminder = createReminder()
            clock.advance(Duration.ofSeconds(90))

            assertEquals(1, scheduler().tick())

            assertEquals(listOf(100L to "Напоминаю: Выключить духовку"), notifier.sent)
            val stored = reminders.reminders.getValue(reminder.id)
            assertEquals(ReminderStatus.DONE, stored.status)
            assertNull(stored.nextFireAt)
            val delivery = reminders.deliveries.single().second
            assertEquals(DeliveryStatus.SENT, delivery.status)
            assertEquals(555L, delivery.telegramMessageId)
            assertFalse(delivery.late)
        }

    @Test
    fun `delivers overdue reminder after downtime marked as late`() =
        runTest {
            createReminder()
            clock.advance(Duration.ofHours(2))

            scheduler().tick()

            assertEquals("Напоминаю с опозданием — должен был в 10:20: Выключить духовку", notifier.sent.single().second)
            assertTrue(
                reminders.deliveries
                    .single()
                    .second.late,
            )
        }

    @Test
    fun `sends only once across ticks`() =
        runTest {
            createReminder()
            clock.advance(Duration.ofMinutes(2))
            val scheduler = scheduler()

            scheduler.tick()
            scheduler.tick()

            assertEquals(1, notifier.sent.size)
        }

    @Test
    fun `transient failure keeps reminder for next tick`() =
        runTest {
            val reminder = createReminder()
            clock.advance(Duration.ofMinutes(2))
            notifier.result = NotifyResult.Failed("network", permanent = false)

            assertEquals(0, scheduler().tick())

            assertEquals(ReminderStatus.ACTIVE, reminders.reminders.getValue(reminder.id).status)
            assertTrue(reminders.deliveries.isEmpty())
        }

    @Test
    fun `permanent failure is recorded and reminder closed`() =
        runTest {
            val reminder = createReminder()
            clock.advance(Duration.ofMinutes(2))
            notifier.result = NotifyResult.Failed("telegram 403", permanent = true)

            scheduler().tick()

            assertEquals(ReminderStatus.DONE, reminders.reminders.getValue(reminder.id).status)
            assertEquals(
                DeliveryStatus.FAILED,
                reminders.deliveries
                    .single()
                    .second.status,
            )
        }

    @Test
    fun `revoked user gets nothing and reminder is closed`() =
        runTest {
            val reminder = createReminder()
            clock.advance(Duration.ofMinutes(2))

            scheduler(InMemoryUserRepository(user(status = UserStatus.REVOKED))).tick()

            assertTrue(notifier.sent.isEmpty())
            assertEquals(ReminderStatus.DONE, reminders.reminders.getValue(reminder.id).status)
            assertTrue(reminders.deliveries.isEmpty())
        }
}
