package theseus.core.application

import theseus.core.domain.user.AccountException
import theseus.core.domain.user.Gender
import theseus.core.domain.user.Invite
import theseus.core.domain.user.Usage
import theseus.core.domain.user.User
import theseus.core.domain.user.UserRole
import theseus.core.domain.user.UserStatus
import theseus.core.domain.user.UserSummary
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Хранилище пользователей, приглашений и расходов. Блокировки удерживаются внешней атомарной операцией. */
interface AccountsRepository :
    AccountUsersRepository,
    InviteRepository,
    UsageRepository

interface AccountUsersRepository : UserRepository {
    suspend fun lockChat(chatId: Long)

    suspend fun lockUser(id: Long): User?

    suspend fun saveUser(
        user: User,
        now: Instant,
    )

    suspend fun createUser(
        chatId: Long,
        gender: Gender,
        now: Instant,
    ): User

    suspend fun listUsers(): List<UserSummary>

    suspend fun skipDue(
        userId: Long,
        now: Instant,
    )
}

interface InviteRepository {
    suspend fun createInvite(
        hash: String,
        gender: Gender,
        label: String?,
        now: Instant,
        expires: Instant,
    ): Invite

    suspend fun lockInvite(hash: String): Invite?

    suspend fun lockInvite(id: Long): Invite?

    suspend fun saveInvite(invite: Invite)

    suspend fun listInvites(): List<Invite>
}

interface UsageRepository {
    suspend fun recordUsage(usage: Usage): Long

    suspend fun usage(
        from: Instant,
        to: Instant,
    ): List<Usage>
}

/** Системные сценарии. HTTP-идемпотентность оборачивает весь сценарий одной транзакцией. */
class AccountsService(
    private val repository: AccountsRepository,
    private val clock: Clock,
    private val inviteTtl: Duration = Duration.ofHours(DEFAULT_INVITE_TTL_HOURS),
    private val newCode: () -> String = {
        val random = SecureRandom()
        buildString { repeat(INVITE_CODE_LENGTH) { append(INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)]) } }
    },
) {
    suspend fun createInvite(
        gender: Gender,
        label: String?,
    ): Pair<Invite, String> {
        if (label != null && label.codePointCount(0, label.length) > MAX_NAME_LENGTH) fail("invalid_arguments")
        val code = newCode()
        val now = clock.instant()
        return repository.createInvite(hashCode(code), gender, label, now, now.plus(inviteTtl)) to code
    }

    suspend fun activate(
        chatId: Long,
        code: String,
    ): Pair<User, Invite> {
        if (chatId <= 0 || code.isBlank() || code.length > MAX_CODE_LENGTH) fail("invalid_arguments")
        repository.lockChat(chatId)
        val invite = repository.lockInvite(hashCode(code)) ?: fail("invite_not_found")
        when (invite.state(clock.instant())) {
            "used" -> fail("invite_used")
            "revoked" -> fail("invite_revoked")
            "expired" -> fail("invite_expired")
        }
        if (repository.findByChatId(chatId) != null) fail("already_registered")
        val user = repository.createUser(chatId, invite.gender, clock.instant())
        val used = invite.copy(usedAt = clock.instant(), usedByUserId = user.id)
        repository.saveInvite(used)
        return user to used
    }

    suspend fun requireAvailable(id: Long): User =
        repository.lockUser(id)?.also {
            if (it.status == UserStatus.REVOKED) fail("invalid_state")
        } ?: fail("not_found")

    suspend fun update(
        id: Long,
        name: String?,
        timezone: String?,
        gender: Gender?,
    ): User {
        val user = requireAvailable(id)
        val trimmed = name?.trim()
        if (trimmed != null && trimmed.codePointCount(0, trimmed.length) !in 1..MAX_NAME_LENGTH) fail("invalid_arguments")
        val zone =
            timezone?.let {
                if (it !in ZoneId.getAvailableZoneIds()) fail("invalid_timezone")
                ZoneId.of(it)
            }
        var updated = user.copy(name = trimmed ?: user.name, timezone = zone ?: user.timezone, gender = gender ?: user.gender)
        if (updated.status == UserStatus.ONBOARDING && updated.name != null && updated.timezone != null) {
            updated = updated.copy(status = UserStatus.ACTIVE, onboardedAt = clock.instant())
        }
        repository.saveUser(updated, clock.instant())
        return updated
    }

    suspend fun access(
        id: Long,
        restore: Boolean,
    ): User {
        val user = repository.lockUser(id) ?: fail("not_found")
        if (restore) {
            if (user.status != UserStatus.REVOKED) fail("invalid_state")
        } else if (user.status == UserStatus.REVOKED || user.role == UserRole.ADMIN) {
            fail("invalid_state")
        }
        // Отзыв и восстановление никогда не создают досыл за время отсутствия доступа.
        repository.skipDue(id, clock.instant())
        val updated =
            user.copy(
                status =
                    if (!restore) {
                        UserStatus.REVOKED
                    } else if (user.onboardedAt != null) {
                        UserStatus.ACTIVE
                    } else {
                        UserStatus.ONBOARDING
                    },
            )
        repository.saveUser(updated, clock.instant())
        return updated
    }

    suspend fun revokeInvite(id: Long): Invite {
        val invite = repository.lockInvite(id) ?: fail("not_found")
        if (invite.usedAt != null || invite.revokedAt != null) fail("invalid_state")
        return invite.copy(revokedAt = clock.instant()).also { repository.saveInvite(it) }
    }

    suspend fun recordUsage(usage: Usage): Long {
        if (requireAvailable(usage.userId).status != UserStatus.ACTIVE) fail("invalid_state")
        val normalized = usage.costUsd.setScale(COST_SCALE, java.math.RoundingMode.HALF_UP)
        val invalidCounts = usage.tokensIn < 0 || usage.tokensOut < 0 || usage.tokensCached !in 0..usage.tokensIn
        val invalidModel =
            usage.role !in setOf("main", "summary", "router") || usage.model.isBlank() || usage.model.length > MAX_MODEL_LENGTH
        val invalidCost = usage.costUsd.signum() < 0 || normalized.precision() - normalized.scale() > COST_INTEGER_DIGITS
        val invalidLatency = usage.latencyMs != null && usage.latencyMs < 0
        if (listOf(invalidCounts, invalidModel, invalidCost, invalidLatency).any { it }) fail("invalid_arguments")
        return repository.recordUsage(usage.copy(costUsd = normalized, createdAt = clock.instant()))
    }

    suspend fun users(): List<UserSummary> = repository.listUsers()

    suspend fun invites(state: String): List<Invite> {
        if (state !in setOf("active", "used", "expired", "revoked", "all")) fail("invalid_arguments")
        return repository.listInvites().filter { state == "all" || it.state(clock.instant()) == state }
    }

    suspend fun usage(
        from: Instant,
        to: Instant,
    ): List<Usage> {
        if (!from.isBefore(to)) fail("invalid_arguments")
        return repository.usage(from, to)
    }

    companion object {
        private const val DEFAULT_INVITE_TTL_HOURS = 72L
        private const val MAX_NAME_LENGTH = 64
        private const val MAX_CODE_LENGTH = 128
        private const val INVITE_CODE_LENGTH = 16
        private const val INVITE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val MAX_MODEL_LENGTH = 256
        private const val COST_SCALE = 8
        private const val COST_INTEGER_DIGITS = 4

        fun hashCode(code: String): String =
            MessageDigest.getInstance("SHA-256").digest(code.toByteArray()).joinToString("") { "%02x".format(it) }

        private fun fail(code: String): Nothing = throw AccountException(code)
    }
}
