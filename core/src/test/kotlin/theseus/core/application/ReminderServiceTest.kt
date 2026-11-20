package theseus.core.application

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import theseus.core.domain.DomainException
import theseus.core.domain.ErrorCode
import theseus.core.domain.reminder.After
import theseus.core.domain.reminder.PartOfDay
import theseus.core.domain.reminder.When
import theseus.core.domain.reminder.WhenResolver
import theseus.core.domain.user.User
import theseus.core.domain.user.UserStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ReminderServiceTest {
    private val clock = MutableClock(Instant.parse("2026-11-02T07:15:00Z"))
    private val reminders = InMemoryReminderRepository()

    private fun service(vararg users: User) =
        ReminderService(reminders, UserService(InMemoryUserRepository(*users)), WhenResolver(LocalTime.of(9, 0)), clock)

    @Test
    fun `changing only clock time preserves one time reminder date and timezone`() =
        runTest {
            clock.now = Instant.parse("2026-11-20T18:00:00Z")
            for (timezone in listOf("Asia/Yekaterinburg", "Europe/Moscow", "America/New_York")) {
                val service = service(user(timezone = timezone))
                val old = service.createOneTime(1, "Написать Вадиму", When(date = LocalDate.of(2026, 11, 30)))
                val changed = service.update(1, old.id, null, null, null, When(time = LocalTime.of(16, 0)), null).reminder

                assertEquals(old.id, changed.id)
                assertEquals(old.text, changed.text)
                assertEquals(old.timezone, changed.timezone)
                assertEquals(LocalDateTime.of(2026, 11, 30, 16, 0), changed.startLocal)
                assertEquals(changed.startLocal.atZone(old.timezone).toInstant(), changed.nextFireAt)
            }
        }

    @Test
    fun `changing only part of day preserves one time reminder date`() =
        runTest {
            val service = service(user())
            val old = service.createOneTime(1, "Написать Вадиму", When(date = LocalDate.of(2026, 11, 30)))
            val changed = service.update(1, old.id, null, null, null, When(partOfDay = PartOfDay.EVENING), null).reminder

            assertEquals(LocalDateTime.of(2026, 11, 30, 19, 0), changed.startLocal)
        }

    @Test
    fun `time change into past on original day is rejected without rolling to tomorrow`() =
        runTest {
            val service = service(user())
            val old = service.createOneTime(1, "Написать Вадиму", When(time = LocalTime.of(18, 0)))
            val error =
                assertThrows<DomainException> {
                    service.update(1, old.id, null, null, null, When(time = LocalTime.of(9, 0)), null)
                }

            assertEquals(ErrorCode.TIME_IN_PAST, error.error)
            assertEquals(old, reminders.reminders.getValue(old.id))
        }

    @Test
    fun `explicit date relative shift and new reminder still resolve independently of old date`() =
        runTest {
            val service = service(user())
            val old = service.createOneTime(1, "Написать Вадиму", When(date = LocalDate.of(2026, 11, 30)))
            val explicit = When(date = LocalDate.of(2026, 12, 2), time = LocalTime.of(16, 0))
            val changed = service.update(1, old.id, null, null, null, explicit, null).reminder
            assertEquals(LocalDateTime.of(2026, 12, 2, 16, 0), changed.startLocal)

            val relative = service.update(1, old.id, null, null, null, When(after = After(hours = 1)), null).reminder
            assertEquals(clock.now.plusSeconds(3600), relative.nextFireAt)

            val created = service.createOneTime(1, "Другая задача", When(time = LocalTime.of(9, 0)))
            assertEquals(LocalDateTime.of(2026, 11, 3, 9, 0), created.startLocal)
        }

    @Test
    fun `creates reminder in user timezone`() =
        runTest {
            val created =
                service(
                    user(timezone = "Asia/Yekaterinburg"),
                ).createOneTime(1, "  Позвонить маме ", When(time = LocalTime.of(18, 0)))

            assertEquals("Позвонить маме", created.text)
            assertEquals(ZoneId.of("Asia/Yekaterinburg"), created.timezone)
            assertEquals(LocalDateTime.of(2026, 11, 2, 18, 0), created.startLocal)
            assertEquals(Instant.parse("2026-11-02T13:00:00Z"), created.nextFireAt)
        }

    @Test
    fun `rejects inactive user`() =
        runTest {
            val error =
                assertThrows<DomainException> {
                    service(user(status = UserStatus.REVOKED)).createOneTime(1, "X", When(after = After(minutes = 5)))
                }

            assertEquals(ErrorCode.USER_INACTIVE, error.error)
        }

    @Test
    fun `rejects unknown user`() =
        runTest {
            val error = assertThrows<DomainException> { service().createOneTime(42, "X", When(after = After(minutes = 5))) }

            assertEquals(ErrorCode.USER_INACTIVE, error.error)
        }

    @Test
    fun `rejects blank and too long text`() =
        runTest {
            val service = service(user())

            listOf("   ", "x".repeat(ReminderService.MAX_TEXT_LENGTH + 1)).forEach { text ->
                val error = assertThrows<DomainException> { service.createOneTime(1, text, When(after = After(minutes = 5))) }
                assertEquals(ErrorCode.INVALID_ARGUMENTS, error.error)
            }
        }
}
