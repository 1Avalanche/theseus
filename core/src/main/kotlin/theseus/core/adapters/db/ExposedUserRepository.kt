package theseus.core.adapters.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import theseus.core.application.HardDeleteRepository
import theseus.core.domain.user.AdminProfile
import theseus.core.domain.user.Gender
import theseus.core.domain.user.User
import theseus.core.domain.user.UserRole
import theseus.core.domain.user.UserStatus
import theseus.core.domain.user.UserSummary
import java.time.Instant
import java.time.ZoneId

class ExposedUserRepository(
    private val db: CoreDatabase,
) : HardDeleteRepository,
    theseus.core.application.InviteRepository by ExposedInviteRepository(db),
    theseus.core.application.UsageRepository by ExposedUsageRepository(db) {
    override suspend fun findById(id: Long): User? =
        db.tx {
            UsersTable
                .selectAll()
                .where { UsersTable.id eq id }
                .singleOrNull()
                ?.toUser()
        }

    override suspend fun findByChatId(chatId: Long): User? =
        db.tx {
            UsersTable
                .selectAll()
                .where { UsersTable.telegramChatId eq chatId }
                .singleOrNull()
                ?.toUser()
        }

    override suspend fun ensureAdmin(
        profile: AdminProfile,
        now: Instant,
    ): User =
        db.tx {
            UsersTable.insertIgnore {
                it[telegramChatId] = profile.chatId
                it[role] = UserRole.ADMIN.name
                it[status] = UserStatus.ONBOARDING.name
                it[name] = null
                it[gender] = profile.gender.name
                it[timezone] = null
                it[createdAt] = now.toUtc()
                it[updatedAt] = now.toUtc()
                it[onboardedAt] = null
            }
            UsersTable
                .selectAll()
                .where { UsersTable.telegramChatId eq profile.chatId }
                .single()
                .toUser()
        }

    override suspend fun lockChat(chatId: Long) =
        db.tx {
            (connection.connection as java.sql.Connection).prepareStatement("select pg_advisory_xact_lock($CHAT_LOCK_NAMESPACE, ?)").use {
                it.setInt(1, chatId.hashCode())
                it.execute()
                Unit
            }
        }

    override suspend fun lockUser(id: Long): User? =
        db.tx {
            UsersTable
                .selectAll()
                .where { UsersTable.id eq id }
                .forUpdate()
                .singleOrNull()
                ?.toUser()
        }

    override suspend fun saveUser(
        user: User,
        now: Instant,
    ) = db.tx {
        UsersTable.update({ UsersTable.id eq user.id }) {
            it[status] = user.status.name
            it[name] = user.name
            it[gender] = user.gender.name
            it[timezone] = user.timezone?.id
            it[onboardedAt] = user.onboardedAt?.toUtc()
            it[revokedAt] = if (user.status == UserStatus.REVOKED) now.toUtc() else null
            it[updatedAt] = now.toUtc()
        }
        Unit
    }

    override suspend fun createUser(
        chatId: Long,
        gender: Gender,
        now: Instant,
    ): User =
        db.tx {
            val id =
                UsersTable.insert {
                    it[telegramChatId] = chatId
                    it[role] = UserRole.USER.name
                    it[status] = UserStatus.ONBOARDING.name
                    it[UsersTable.gender] = gender.name
                    it[createdAt] = now.toUtc()
                    it[updatedAt] = now.toUtc()
                }[UsersTable.id]
            UsersTable
                .selectAll()
                .where { UsersTable.id eq id }
                .single()
                .toUser()
        }

    override suspend fun listUsers(): List<UserSummary> =
        db.tx {
            UsersTable.selectAll().orderBy(UsersTable.createdAt).map { row ->
                val user = row.toUser()
                UserSummary(
                    user,
                    InvitesTable
                        .selectAll()
                        .where { InvitesTable.usedByUserId eq user.id }
                        .singleOrNull()
                        ?.get(InvitesTable.label),
                    RemindersTable
                        .selectAll()
                        .where {
                            (RemindersTable.userId eq user.id) and (RemindersTable.status eq "ACTIVE")
                        }.count(),
                    UsageTable.selectAll().where { UsageTable.userId eq user.id }.maxOfOrNull { it[UsageTable.createdAt].toInstant() },
                )
            }
        }

    override suspend fun skipDue(
        userId: Long,
        now: Instant,
    ) = db.atomic {
        val reminders = ExposedReminderRepository(this@ExposedUserRepository.db)
        val schedule =
            theseus.core.domain.reminder
                .ReminderSchedule()
        for (reminder in reminders.list(userId)) {
            if (reminder.status != theseus.core.domain.reminder.ReminderStatus.ACTIVE) continue
            val next =
                if (reminder.recurrenceRule == null) {
                    reminder.nextFireAt?.takeIf { it > now }
                } else {
                    schedule.nextPlanned(reminder, now)
                }
            reminders.save(
                reminder.copy(
                    status =
                        if (next == null) {
                            theseus.core.domain.reminder.ReminderStatus.DONE
                        } else {
                            theseus.core.domain.reminder.ReminderStatus.ACTIVE
                        },
                    nextFireAt = next,
                    snoozedUntil = null,
                ),
                now,
            )
        }
        Unit
    }

    @Suppress("MagicNumber") // Позиции параметров SQL.
    override suspend fun eraseUser(id: Long) =
        db.tx {
            val jdbc = connection.connection as java.sql.Connection
            // В квитанции тело хранится JSON-строкой; активация до назначения user_id тоже подлежит очистке.
            val affected = """select id from operation_results where user_id=?
            or (operation='POST /users/activate' and (result->>'body')::jsonb #>> '{user,id}'=?)
            or (operation like 'POST /invites%' and (result->>'body')::jsonb ->> 'id' in
                (select id::text from invites where used_by_user_id=?))
            or operation='POST /users/' || ? || '/hard-delete'"""
            jdbc.prepareStatement("insert into erased_operations(id) $affected on conflict do nothing").use {
                it.setLong(1, id)
                it.setString(2, id.toString())
                it.setLong(3, id)
                it.setString(4, id.toString())
                it.executeUpdate()
            }
            val statements =
                listOf(
                    "delete from operation_results where id in (select id from erased_operations)",
                    "delete from reminder_deliveries where user_id=?",
                    "delete from reminders where user_id=?",
                    "delete from llm_usage where user_id=?",
                    "delete from invites where used_by_user_id=?",
                    "delete from users where id=?",
                )
            statements.forEach { sql ->
                jdbc.prepareStatement(sql).use {
                    if ('?' in sql) it.setLong(1, id)
                    it.executeUpdate()
                }
            }
            Unit
        }

    private fun ResultRow.toUser() =
        User(
            id = this[UsersTable.id],
            chatId = this[UsersTable.telegramChatId],
            role = UserRole.valueOf(this[UsersTable.role]),
            status = UserStatus.valueOf(this[UsersTable.status]),
            name = this[UsersTable.name],
            gender = Gender.valueOf(this[UsersTable.gender]),
            timezone = this[UsersTable.timezone]?.let(ZoneId::of),
            createdAt = this[UsersTable.createdAt].toInstant(),
            onboardedAt = this[UsersTable.onboardedAt]?.toInstant(),
        )

    companion object {
        private const val CHAT_LOCK_NAMESPACE = 7243
    }
}
