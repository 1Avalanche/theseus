package theseus.agent.system

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Общая блокировка чата для входящих событий, восстановления и удаления его данных. */
class ChatLocks {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withChat(
        chatId: Long,
        action: suspend () -> T,
    ): T = locks.computeIfAbsent(chatId) { Mutex() }.withLock { action() }
}
