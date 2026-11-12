package theseus.core.domain.user

import java.math.BigDecimal
import java.time.Instant

/** Код приглашения существует открытым только до отправки создавшему его администратору. */
data class Invite(
    val id: Long,
    val codeHash: String,
    val gender: Gender,
    val label: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant? = null,
    val usedByUserId: Long? = null,
    val revokedAt: Instant? = null,
) {
    fun state(now: Instant): String =
        when {
            usedAt != null -> "used"
            revokedAt != null -> "revoked"
            !expiresAt.isAfter(now) -> "expired"
            else -> "active"
        }
}

/** Учёт расхода без текстов запроса и ответа. Стоимость сохраняется без округления через Double. */
data class Usage(
    val userId: Long,
    val role: String,
    val model: String,
    val generationId: String?,
    val tokensIn: Int,
    val tokensOut: Int,
    val tokensCached: Int,
    val costUsd: BigDecimal,
    val latencyMs: Int?,
    val createdAt: Instant,
)

data class UserSummary(
    val user: User,
    val inviteLabel: String?,
    val activeReminders: Long,
    val lastActivityAt: Instant?,
)

/** Ошибка системной операции; её код адаптер переводит в HTTP и ресурсный текст. */
class AccountException(
    val code: String,
) : RuntimeException(code)
