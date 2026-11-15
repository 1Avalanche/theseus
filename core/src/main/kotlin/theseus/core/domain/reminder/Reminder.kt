package theseus.core.domain.reminder

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

enum class ReminderStatus { ACTIVE, PAUSED, DONE, DELETED, UNSUPPORTED }

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
    val excludedOccurrences: Set<Instant> = emptySet(),
    val parentId: Long? = null,
    val pausedUntil: Instant? = null,
    val snoozedUntil: Instant? = null,
    val updatedAt: Instant = createdAt,
    val rawStatus: String? = null,
)

data class NewReminder(
    val userId: Long,
    val text: String,
    val timezone: ZoneId,
    val startLocal: LocalDateTime,
    val nextFireAt: Instant,
    val recurrenceRule: String? = null,
    val parentId: Long? = null,
)

enum class DeliveryKind { SCHEDULED, SNOOZED, SCHEDULED_AND_SNOOZED }

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
