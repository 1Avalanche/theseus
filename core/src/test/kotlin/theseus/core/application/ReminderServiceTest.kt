package theseus.core.application

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import theseus.core.domain.DomainException
import theseus.core.domain.ErrorCode
import theseus.core.domain.reminder.After
import theseus.core.domain.reminder.When
import theseus.core.domain.reminder.WhenResolver
import theseus.core.domain.user.User
import theseus.core.domain.user.UserStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ReminderServiceTest {
    private val clock = MutableClock(Instant.parse("2026-11-02T07:15:00Z"))
    private val reminders = InMemoryReminderRepository()

    private fun service(vararg users: User) =
        ReminderService(reminders, UserService(InMemoryUserRepository(*users)), WhenResolver(LocalTime.of(9, 0)), clock)

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
