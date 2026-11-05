package theseus.core.adapters.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import theseus.core.application.UserRepository
import theseus.core.domain.user.AdminProfile
import theseus.core.domain.user.Gender
import theseus.core.domain.user.User
import theseus.core.domain.user.UserRole
import theseus.core.domain.user.UserStatus
import java.time.Instant
import java.time.ZoneId

class ExposedUserRepository(
    private val db: CoreDatabase,
) : UserRepository {
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
                it[status] = UserStatus.ACTIVE.name
                it[name] = profile.name
                it[gender] = profile.gender.name
                it[timezone] = profile.timezone.id
                it[createdAt] = now.toUtc()
                it[updatedAt] = now.toUtc()
                it[onboardedAt] = now.toUtc()
            }
            UsersTable
                .selectAll()
                .where { UsersTable.telegramChatId eq profile.chatId }
                .single()
                .toUser()
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
}
