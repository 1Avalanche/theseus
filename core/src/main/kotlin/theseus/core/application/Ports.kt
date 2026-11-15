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
     * Новый админ проходит онбординг; уже существующий профиль сохраняется без изменений.
     */
    suspend fun ensureAdmin(
        profile: AdminProfile,
        now: Instant,
    ): User
}

@Suppress("TooManyFunctions") // Порт напоминаний и их доставок.
interface ReminderRepository {
    /** Резервирует id до отправки Telegram; пропуски последовательности допустимы. */
    suspend fun nextDeliveryId(): Long

    /** Поиск доставки всегда ограничен пользователем. */
    suspend fun delivery(
        userId: Long,
        id: Long? = null,
        messageId: Long? = null,
    ): DeliveryReference?

    /** Блокировка пользователя сериализует планировщик и изменения его напоминаний в одной транзакции. */
    suspend fun <T> withUserLock(
        userId: Long,
        block: suspend () -> T,
    ): T

    suspend fun find(
        userId: Long,
        id: Long,
    ): Reminder?

    suspend fun list(userId: Long): List<Reminder>

    suspend fun save(
        reminder: Reminder,
        now: Instant,
    ): Reminder

    /** Пишет доставку и новое состояние серии одной транзакцией. */
    suspend fun record(
        reminder: Reminder,
        delivery: DeliveryRecord?,
        now: Instant,
    )

    suspend fun findResumable(now: Instant): List<Reminder>

    /** Агрегаты ожидающих доставок без чтения пользовательских текстов и ограничения размером пачки. */
    suspend fun deliveryBacklog(now: Instant): DeliveryBacklog

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

/** Связь уведомления с конкретным срабатыванием, без пользовательского текста. */
data class DeliveryReference(
    val id: Long,
    val reminderId: Long,
    val occurrenceAt: Instant,
)

/** Наступившие активные срабатывания; завершённые и будущие сюда не входят. */
data class DeliveryBacklog(
    val count: Long,
    val oldestDueAt: Instant?,
)

/** Проверка доступности хранилища для healthcheck. */
fun interface HealthProbe {
    suspend fun isHealthy(): Boolean
}

/** Канал доставки уведомлений пользователю. v1 — Telegram. */
interface Notifier {
    suspend fun send(
        chatId: Long,
        text: String,
        deliveryId: Long,
    ): NotifyResult = send(chatId, text)

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
