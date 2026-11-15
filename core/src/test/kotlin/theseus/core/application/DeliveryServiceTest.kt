package theseus.core.application

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import theseus.core.domain.DomainException
import theseus.core.domain.ErrorCode
import theseus.core.domain.reminder.DeliveryKind
import theseus.core.domain.reminder.DeliveryRecord
import theseus.core.domain.reminder.DeliveryStatus
import theseus.core.domain.reminder.Recurrence
import theseus.core.domain.reminder.ReminderStatus
import theseus.core.domain.reminder.When
import theseus.core.domain.reminder.WhenResolver
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class DeliveryServiceTest {
    private val clock = MutableClock(Instant.parse("2026-03-28T08:00:00Z"))
    private val repository = InMemoryReminderRepository()
    private val users = InMemoryUserRepository(user(timezone = "Europe/Berlin"), user(id = 2, chatId = 200))
    private val reminders = ReminderService(repository, UserService(users), WhenResolver(LocalTime.of(9, 0)), clock)
    private val deliveries = DeliveryService(repository, reminders, clock)

    private suspend fun delivered(series: Boolean = false): Long {
        val reminder =
            if (series) {
                reminders.create(1, "Вода", null, Recurrence("daily", time = LocalTime.of(9, 0))).reminder!!
            } else {
                reminders.createOneTime(1, "Вода", When(date = LocalDate.parse("2026-03-28"), time = LocalTime.of(10, 0)))
            }
        repository.record(
            reminder.copy(
                status = if (series) ReminderStatus.ACTIVE else ReminderStatus.DONE,
                nextFireAt = if (series) reminder.nextFireAt else null,
            ),
            DeliveryRecord(clock.instant(), DeliveryKind.SCHEDULED, DeliveryStatus.SENT, false, 99, null, 7),
            clock.instant(),
        )
        return reminder.id
    }

    @Test fun `reply and snooze cannot address another user delivery`() =
        runTest {
            delivered()
            assertEquals(ErrorCode.NOT_FOUND, assertThrows<DomainException> { deliveries.byMessage(2, 99) }.error)
            assertEquals(ErrorCode.NOT_FOUND, assertThrows<DomainException> { deliveries.snooze(2, 7, "15m") }.error)
            assertEquals(7L, deliveries.byMessage(1, 99).first.id)
        }

    @Test fun `tomorrow keeps delivery local hour across daylight saving change`() =
        runTest {
            val id = delivered()
            val changed = deliveries.snooze(1, 7, "tmr")
            assertEquals(id, changed.id)
            assertEquals(LocalDateTime.parse("2026-03-29T09:00"), changed.startLocal)
            assertEquals(Instant.parse("2026-03-29T07:00:00Z"), changed.nextFireAt)
        }

    @Test fun `relative snooze uses click time and replaces previous snooze`() =
        runTest {
            delivered(series = true)
            assertEquals(clock.instant().plusSeconds(900), deliveries.snooze(1, 7, "15m").snoozedUntil)
            clock.now = clock.instant().plusSeconds(60)
            assertEquals(clock.instant().plusSeconds(3600), deliveries.snooze(1, 7, "1h").snoozedUntil)
        }

    @Test fun `paused deleted and inactive users cannot snooze`() =
        runTest {
            val id = delivered(series = true)
            reminders.pause(1, id, null, null)
            assertEquals(ErrorCode.INVALID_STATE, assertThrows<DomainException> { deliveries.snooze(1, 7, "15m") }.error)
            repository.save(repository.reminders.getValue(id).copy(status = ReminderStatus.DELETED), clock.instant())
            assertEquals(ErrorCode.INVALID_STATE, assertThrows<DomainException> { deliveries.snooze(1, 7, "15m") }.error)
            users.users[1] = users.users.getValue(1).copy(status = theseus.core.domain.user.UserStatus.REVOKED)
            assertEquals(ErrorCode.USER_INACTIVE, assertThrows<DomainException> { deliveries.byMessage(1, 99) }.error)
        }
}
