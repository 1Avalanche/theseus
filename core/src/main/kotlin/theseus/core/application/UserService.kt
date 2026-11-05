package theseus.core.application

import theseus.core.domain.DomainException
import theseus.core.domain.ErrorCode
import theseus.core.domain.user.User
import theseus.core.domain.user.UserStatus

class UserService(
    private val users: UserRepository,
) {
    suspend fun findByChatId(chatId: Long): User? = users.findByChatId(chatId)

    /** Пользователь из контекста запроса агента: должен существовать и быть `ACTIVE`. */
    suspend fun requireActive(userId: Long): User {
        val user = users.findById(userId)
        if (user == null || user.status != UserStatus.ACTIVE || user.timezone == null) {
            throw DomainException(ErrorCode.USER_INACTIVE)
        }
        return user
    }
}
