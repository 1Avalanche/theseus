package theseus.core.application

import theseus.core.domain.DomainException
import theseus.core.domain.ErrorCode
import theseus.core.domain.reminder.After
import theseus.core.domain.reminder.Reminder
import theseus.core.domain.reminder.When
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** Системные операции уведомлений; принадлежность и изменение проверяются под блокировкой пользователя. */
class DeliveryService(
    private val repository: ReminderRepository,
    private val reminders: ReminderService,
    private val clock: Clock,
) {
    suspend fun byMessage(
        userId: Long,
        messageId: Long,
    ): Pair<DeliveryReference, Reminder> =
        reminders.atomic(userId) {
            val delivery = repository.delivery(userId, messageId = messageId) ?: throw DomainException(ErrorCode.NOT_FOUND)
            delivery to reminders.get(userId, delivery.reminderId)
        }

    suspend fun snooze(
        userId: Long,
        deliveryId: Long,
        option: String,
    ): Reminder =
        reminders.atomic(userId) {
            val delivery = repository.delivery(userId, id = deliveryId) ?: throw DomainException(ErrorCode.NOT_FOUND)
            val reminder = reminders.get(userId, delivery.reminderId)
            val at =
                when (option) {
                    "15m" -> When(after = After(minutes = 15))
                    "1h" -> When(after = After(hours = 1))
                    "tmr" ->
                        When(
                            date = LocalDate.ofInstant(clock.instant(), reminder.timezone).plusDays(1),
                            time = LocalDateTime.ofInstant(delivery.occurrenceAt, reminder.timezone).toLocalTime(),
                        )
                    else -> throw DomainException(ErrorCode.INVALID_ARGUMENTS)
                }
            reminders.snooze(userId, reminder.id, at)
        }
}
