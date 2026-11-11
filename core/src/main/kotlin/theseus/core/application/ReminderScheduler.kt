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
 * Этап 2: только разовые напоминания; серии — этап 4.
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

    private suspend fun fire(reminder: Reminder): Boolean {
        val occurrenceAt = requireNotNull(reminder.nextFireAt)
        val now = clock.instant()
        val user = users.findById(reminder.userId)
        if (user == null || user.status != UserStatus.ACTIVE) {
            // Отозванным пользователям не отправляем и не копим: после восстановления не досылается.
            reminders.completeOneTime(reminder, null, now)
            return true
        }
        val late = Duration.between(occurrenceAt, now) > lateThreshold
        val result = notifier.send(user.chatId, render(reminder, late))
        val record =
            when (result) {
                is NotifyResult.Sent ->
                    DeliveryRecord(occurrenceAt, DeliveryKind.SCHEDULED, DeliveryStatus.SENT, late, result.messageId, null)
                is NotifyResult.Failed if result.permanent ->
                    DeliveryRecord(occurrenceAt, DeliveryKind.SCHEDULED, DeliveryStatus.FAILED, late, null, result.reason)
                is NotifyResult.Failed -> {
                    log.warn("Напоминание {} не доставлено, повтор на следующем тике: {}", reminder.id, result.reason)
                    return false
                }
            }
        reminders.completeOneTime(reminder, record, now)
        return true
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
