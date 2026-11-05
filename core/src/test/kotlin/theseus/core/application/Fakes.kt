package theseus.core.application

import theseus.core.domain.reminder.DeliveryRecord
import theseus.core.domain.reminder.NewReminder
import theseus.core.domain.reminder.Reminder
import theseus.core.domain.reminder.ReminderStatus
import theseus.core.domain.user.AdminProfile
import theseus.core.domain.user.Gender
import theseus.core.domain.user.User
import theseus.core.domain.user.UserRole
import theseus.core.domain.user.UserStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class MutableClock(
    var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

fun user(
    id: Long = 1,
    chatId: Long = 100,
    status: UserStatus = UserStatus.ACTIVE,
    timezone: String = "Europe/Moscow",
) = User(
    id = id,
    chatId = chatId,
    role = UserRole.ADMIN,
    status = status,
    name = "Аня",
    gender = Gender.FEMALE,
    timezone = ZoneId.of(timezone),
    createdAt = Instant.EPOCH,
    onboardedAt = Instant.EPOCH,
)

class InMemoryUserRepository(
    vararg initial: User,
) : UserRepository {
    val users = initial.associateBy { it.id }.toMutableMap()

    override suspend fun findById(id: Long): User? = users[id]

    override suspend fun findByChatId(chatId: Long): User? = users.values.firstOrNull { it.chatId == chatId }

    override suspend fun ensureAdmin(
        profile: AdminProfile,
        now: Instant,
    ): User = findByChatId(profile.chatId) ?: user(id = users.size + 1L, chatId = profile.chatId).also { users[it.id] = it }
}

class InMemoryReminderRepository : ReminderRepository {
    val reminders = mutableMapOf<Long, Reminder>()
    val deliveries = mutableListOf<Pair<Long, DeliveryRecord>>()

    override suspend fun create(
        reminder: NewReminder,
        now: Instant,
    ): Reminder {
        val created =
            Reminder(
                id = reminders.size + 1L,
                userId = reminder.userId,
                text = reminder.text,
                timezone = reminder.timezone,
                startLocal = reminder.startLocal,
                recurrenceRule = null,
                status = ReminderStatus.ACTIVE,
                nextFireAt = reminder.nextFireAt,
                lastFiredAt = null,
                createdAt = now,
            )
        reminders[created.id] = created
        return created
    }

    override suspend fun findDue(
        now: Instant,
        limit: Int,
    ): List<Reminder> =
        reminders.values
            .filter { it.status == ReminderStatus.ACTIVE && it.nextFireAt != null && !it.nextFireAt.isAfter(now) }
            .sortedBy { it.nextFireAt }
            .take(limit)

    override suspend fun completeOneTime(
        reminder: Reminder,
        delivery: DeliveryRecord?,
        now: Instant,
    ) {
        delivery?.let { deliveries += reminder.id to it }
        reminders[reminder.id] =
            reminder.copy(status = ReminderStatus.DONE, nextFireAt = null, lastFiredAt = delivery?.let { now })
    }
}

class FakeNotifier(
    var result: NotifyResult = NotifyResult.Sent(messageId = 555),
) : Notifier {
    val sent = mutableListOf<Pair<Long, String>>()

    override suspend fun send(
        chatId: Long,
        text: String,
    ): NotifyResult {
        sent += chatId to text
        return result
    }
}
