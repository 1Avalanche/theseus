package theseus.core.application

import theseus.core.domain.user.AccountException
import theseus.core.domain.user.User
import theseus.core.domain.user.UserRole
import theseus.core.domain.user.UserStatus
import java.time.Clock

/** Порт полной очистки данных пользователя в схеме core. Вызывается внутри атомарной REST-операции. */
interface HardDeleteRepository : AccountsRepository {
    suspend fun eraseUser(id: Long)
}

/** Тестовое удаление обычного пользователя: блокировка → очистка agent → окончательная очистка core. */
class HardDeleteService(
    private val repository: HardDeleteRepository,
    private val clock: Clock,
    private val adminChatId: Long,
) {
    suspend fun requireOwner() {
        val owner = repository.findByChatId(adminChatId) ?: throw AccountException("invalid_state")
        if (owner.role != UserRole.ADMIN || owner.status != UserStatus.ACTIVE) throw AccountException("invalid_state")
    }

    suspend fun prepare(id: Long): Pair<User, List<Long>> {
        val user = target(id)
        val blocked = user.copy(status = UserStatus.REVOKED)
        repository.saveUser(blocked, clock.instant())
        return blocked to repository.listInvites().filter { it.usedByUserId == id }.map { it.id }
    }

    suspend fun complete(id: Long) {
        val user = target(id)
        if (user.status != UserStatus.REVOKED) throw AccountException("invalid_state")
        repository.lockChat(user.chatId)
        repository.eraseUser(id)
    }

    private suspend fun target(id: Long): User {
        val user = repository.lockUser(id) ?: throw AccountException("not_found")
        if (user.role == UserRole.ADMIN || user.chatId == adminChatId) throw AccountException("invalid_state")
        return user
    }
}
