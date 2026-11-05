package theseus.core.domain.reminder

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

enum class ReminderStatus { ACTIVE, PAUSED, DONE, DELETED }

/**
 * Напоминание или серия. Расписание — [startLocal] в [timezone] (+ [recurrenceRule] для серии);
 * [nextFireAt] — производное ближайшее срабатывание в UTC, по нему работает планировщик.
 */
data class Reminder(
    val id: Long,
    val userId: Long,
    val text: String,
    val timezone: ZoneId,
    val startLocal: LocalDateTime,
    val recurrenceRule: String?,
    val status: ReminderStatus,
    val nextFireAt: Instant?,
    val lastFiredAt: Instant?,
    val createdAt: Instant,
)

data class NewReminder(
    val userId: Long,
    val text: String,
    val timezone: ZoneId,
    val startLocal: LocalDateTime,
    val nextFireAt: Instant,
)

enum class DeliveryKind { SCHEDULED, SNOOZED }

enum class DeliveryStatus { SENT, FAILED }

/** Итог одного срабатывания для журнала `reminder_deliveries`. */
data class DeliveryRecord(
    val occurrenceAt: Instant,
    val kind: DeliveryKind,
    val status: DeliveryStatus,
    val late: Boolean,
    val telegramMessageId: Long?,
    val error: String?,
)
