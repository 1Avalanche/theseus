package theseus.core.application

import theseus.core.domain.DomainException
import theseus.core.domain.ErrorCode
import theseus.core.domain.reminder.NewReminder
import theseus.core.domain.reminder.Reminder
import theseus.core.domain.reminder.When
import theseus.core.domain.reminder.WhenResolver
import java.time.Clock

class ReminderService(
    private val reminders: ReminderRepository,
    private val users: UserService,
    private val resolver: WhenResolver,
    private val clock: Clock,
) {
    /** Разовое напоминание. Время разрешается в таймзоне пользователя на момент создания. */
    suspend fun createOneTime(
        userId: Long,
        text: String,
        at: When,
    ): Reminder {
        val user = users.requireActive(userId)
        val normalized = text.trim()
        if (normalized.isEmpty() || normalized.length > MAX_TEXT_LENGTH) {
            throw DomainException(ErrorCode.INVALID_ARGUMENTS, mapOf("field" to "text"))
        }
        val zone = requireNotNull(user.timezone)
        val now = clock.instant()
        val resolved = resolver.resolve(at, now, zone)
        return reminders.create(
            NewReminder(
                userId = user.id,
                text = normalized,
                timezone = zone,
                startLocal = resolved.local,
                nextFireAt = resolved.instant,
            ),
            now,
        )
    }

    companion object {
        const val MAX_TEXT_LENGTH = 1000
    }
}
