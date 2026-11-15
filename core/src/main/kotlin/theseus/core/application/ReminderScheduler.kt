package theseus.core.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import theseus.core.domain.LocalFormats
import theseus.core.domain.reminder.DeliveryKind
import theseus.core.domain.reminder.DeliveryRecord
import theseus.core.domain.reminder.DeliveryStatus
import theseus.core.domain.reminder.Reminder
import theseus.core.domain.reminder.ReminderSchedule
import theseus.core.domain.reminder.ReminderStatus
import theseus.core.domain.user.UserStatus
import theseus.core.texts.Texts
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/**
 * Планировщик: раз в интервал отправляет наступившие напоминания.
 *
 * Доставка «как минимум один раз»: уведомление отправляется до записи в журнал, поэтому падение
 * процесса между отправкой и записью даст повтор после рестарта. Просроченные за время простоя
 * срабатывания досылаются на первом тике с пометкой об опоздании (старше [lateThreshold]).
 * Для серии после простоя досылается последнее плановое срабатывание и ожидающий snooze.
 */
class ReminderScheduler(
    private val reminders: ReminderRepository,
    private val users: UserRepository,
    private val notifier: Notifier,
    private val texts: Texts,
    private val clock: Clock,
    private val lateThreshold: Duration,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    private val log = LoggerFactory.getLogger(ReminderScheduler::class.java)
    private val schedule = ReminderSchedule()

    /** Последний завершённый проход, включая пустую очередь; не обновляется отдельным таймером. */
    @Volatile
    var lastSuccessAt: Instant? = null
        private set

    fun start(
        scope: CoroutineScope,
        interval: Duration,
    ): Job =
        scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    log.error("Тик планировщика упал", e)
                }
                delay(interval.toMillis())
            }
        }

    /** Один проход. Возвращает число обработанных срабатываний (отправленных и закрытых без отправки). */
    suspend fun tick(): Int {
        for (paused in reminders.findResumable(clock.instant())) {
            reminders.withUserLock(paused.userId) {
                val current = reminders.find(paused.userId, paused.id) ?: return@withUserLock
                val now = clock.instant()
                if (current.status != ReminderStatus.PAUSED || current.pausedUntil == null || current.pausedUntil > now) return@withUserLock
                val next = schedule.nextPlanned(current, now)
                reminders.save(
                    current.copy(
                        status = if (next == null) ReminderStatus.DONE else ReminderStatus.ACTIVE,
                        pausedUntil = null,
                        snoozedUntil = null,
                        nextFireAt = next,
                    ),
                    now,
                )
            }
        }
        val due = reminders.findDue(clock.instant(), batchSize)
        var processed = 0
        for (reminder in due) {
            try {
                if (fire(reminder)) processed++
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                log.error("Не удалось обработать напоминание {}", reminder.id, e)
            }
        }
        lastSuccessAt = clock.instant()
        return processed
    }

    private suspend fun fire(snapshot: Reminder): Boolean =
        reminders.withUserLock(snapshot.userId) {
            val reminder = reminders.find(snapshot.userId, snapshot.id) ?: return@withUserLock false
            val now = clock.instant()
            if (reminder.status != ReminderStatus.ACTIVE || reminder.nextFireAt == null || reminder.nextFireAt > now) {
                return@withUserLock false
            }
            val user = users.findById(reminder.userId)
            if (user == null || user.status != UserStatus.ACTIVE) {
                val next = if (reminder.recurrenceRule == null) null else schedule.nextPlanned(reminder, now)
                reminders.record(
                    reminder.copy(
                        status = if (next == null) ReminderStatus.DONE else ReminderStatus.ACTIVE,
                        nextFireAt = next,
                        snoozedUntil = null,
                    ),
                    null,
                    now,
                )
                return@withUserLock true
            }
            val scheduled = schedule.latestDue(reminder, now)
            val snoozed = reminder.snoozedUntil?.takeIf { it <= now }
            val occurrenceAt = listOfNotNull(scheduled, snoozed).minOrNull() ?: return@withUserLock false
            val kind = deliveryKind(scheduled, snoozed, occurrenceAt)
            val late = Duration.between(occurrenceAt, now) > lateThreshold
            val record = deliver(user.chatId, reminder, occurrenceAt, kind, late) ?: return@withUserLock false
            advance(reminder, record, scheduled, occurrenceAt, now)
            true
        }

    private fun deliveryKind(
        scheduled: Instant?,
        snoozed: Instant?,
        at: Instant,
    ): DeliveryKind =
        when {
            scheduled == at && snoozed == at -> DeliveryKind.SCHEDULED_AND_SNOOZED
            snoozed == at -> DeliveryKind.SNOOZED
            else -> DeliveryKind.SCHEDULED
        }

    private suspend fun advance(
        reminder: Reminder,
        record: DeliveryRecord,
        scheduled: Instant?,
        occurrenceAt: Instant,
        now: Instant,
    ) {
        val nextScheduled =
            if (scheduled == occurrenceAt) {
                if (reminder.recurrenceRule == null) null else schedule.nextPlanned(reminder, now)
            } else {
                scheduled ?: schedule.nextPlanned(reminder, now)
            }
        val remainingSnooze = reminder.snoozedUntil?.takeUnless { it == occurrenceAt }
        val next = listOfNotNull(nextScheduled, remainingSnooze).minOrNull()
        reminders.record(
            reminder.copy(
                status = if (next == null) ReminderStatus.DONE else ReminderStatus.ACTIVE,
                nextFireAt = next,
                snoozedUntil = remainingSnooze,
                lastFiredAt = occurrenceAt,
            ),
            record,
            now,
        )
    }

    private suspend fun deliver(
        chatId: Long,
        reminder: Reminder,
        occurrenceAt: Instant,
        kind: DeliveryKind,
        late: Boolean,
    ): DeliveryRecord? {
        val result = notifier.send(chatId, render(reminder.copy(nextFireAt = occurrenceAt), late))
        return when (result) {
            is NotifyResult.Sent -> DeliveryRecord(occurrenceAt, kind, DeliveryStatus.SENT, late, result.messageId, null)
            is NotifyResult.Failed if result.permanent ->
                DeliveryRecord(occurrenceAt, kind, DeliveryStatus.FAILED, late, null, result.reason)
            is NotifyResult.Failed -> {
                log.warn("Напоминание {} не доставлено, повтор на следующем тике: {}", reminder.id, result.reason)
                return null
            }
        }
    }

    private fun render(
        reminder: Reminder,
        late: Boolean,
    ): String =
        if (late) {
            val local = LocalDateTime.ofInstant(requireNotNull(reminder.nextFireAt), reminder.timezone)
            texts.get(
                "notification.reminder.late",
                mapOf("text" to reminder.text, "time" to LocalFormats.formatTime(local.toLocalTime())),
            )
        } else {
            texts.get("notification.reminder", mapOf("text" to reminder.text))
        }

    companion object {
        const val DEFAULT_BATCH_SIZE = 100
    }
}
