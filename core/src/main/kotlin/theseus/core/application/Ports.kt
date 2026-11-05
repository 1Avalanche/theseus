package theseus.core.application

import theseus.core.domain.reminder.DeliveryRecord
import theseus.core.domain.reminder.NewReminder
import theseus.core.domain.reminder.Reminder
import theseus.core.domain.user.AdminProfile
import theseus.core.domain.user.User
import java.time.Instant

interface UserRepository {
    suspend fun findById(id: Long): User?

    suspend fun findByChatId(chatId: Long): User?

    /**
     * Создаёт админа из конфига, если пользователя с его chatId ещё нет. Существующего не меняет.
     * Этап 2: админ сразу `ACTIVE` с именем и таймзоной из конфига (онбординга ещё нет).
     */
    suspend fun ensureAdmin(
        profile: AdminProfile,
        now: Instant,
    ): User
}

interface ReminderRepository {
    suspend fun create(
        reminder: NewReminder,
        now: Instant,
    ): Reminder

    /** Активные напоминания с `next_fire_at <= now`, по возрастанию `next_fire_at`. */
    suspend fun findDue(
        now: Instant,
        limit: Int,
    ): List<Reminder>

    /**
     * Закрывает срабатывание разового напоминания одной транзакцией: пишет [delivery] в журнал
     * (если передан; повтор того же срабатывания игнорируется) и переводит напоминание в `DONE`.
     */
    suspend fun completeOneTime(
        reminder: Reminder,
        delivery: DeliveryRecord?,
        now: Instant,
    )
}

/** Проверка доступности хранилища для healthcheck. */
fun interface HealthProbe {
    suspend fun isHealthy(): Boolean
}

/** Канал доставки уведомлений пользователю. v1 — Telegram. */
interface Notifier {
    suspend fun send(
        chatId: Long,
        text: String,
    ): NotifyResult
}

sealed interface NotifyResult {
    data class Sent(
        val messageId: Long,
    ) : NotifyResult

    /**
     * Не доставлено. [permanent] — повтор бессмысленен (бот заблокирован, чат не найден);
     * иначе срабатывание повторится на следующем тике планировщика.
     */
    data class Failed(
        val reason: String,
        val permanent: Boolean,
    ) : NotifyResult
}
