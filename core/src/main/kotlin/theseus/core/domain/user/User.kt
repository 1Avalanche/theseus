package theseus.core.domain.user

import java.time.Instant
import java.time.ZoneId

enum class UserRole { USER, ADMIN }

enum class UserStatus { ONBOARDING, ACTIVE, REVOKED }

/** Грамматический род пользователя: задаётся админом, по имени не угадывается. */
enum class Gender { MALE, FEMALE, UNSPECIFIED }

data class User(
    val id: Long,
    val chatId: Long,
    val role: UserRole,
    val status: UserStatus,
    val name: String?,
    val gender: Gender,
    val timezone: ZoneId?,
    val createdAt: Instant,
    val onboardedAt: Instant?,
)

/** Профиль админа из конфига: создаётся при старте core. */
data class AdminProfile(
    val chatId: Long,
    val name: String,
    val gender: Gender,
    val timezone: ZoneId,
)
