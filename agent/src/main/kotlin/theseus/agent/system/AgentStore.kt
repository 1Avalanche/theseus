package theseus.agent.system

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import theseus.agent.core.CoreUser
import java.util.UUID

/** Системное событие Telegram; пользовательский ввод и снимок профиля сохраняются зашифрованными. */
@Serializable
data class Incoming(
    val id: String,
    val chatId: Long,
    val text: String? = null,
    val firstName: String? = null,
    val callback: String? = null,
)

@Serializable
data class EventSnapshot(
    val input: Incoming,
    val user: CoreUser?,
    val proposal: ZoneProposal?,
    val dialogue: Boolean = false,
)

@Serializable
data class Button(
    val text: String,
    val data: String,
)

@Serializable
data class BotReply(
    val text: String,
    val buttons: List<List<Button>> = emptyList(),
    val adminNotice: String? = null,
)

@Serializable
data class ZoneProposal(
    val id: String,
    val timezone: String,
)

data class StoredEvent(
    val snapshot: EventSnapshot,
    val reply: BotReply?,
    val delivered: Boolean,
)

data class Outgoing(
    val id: UUID,
    val eventKey: String,
    val method: String,
    val path: String,
    val body: JsonObject,
    val userId: Long?,
)

data class RestReply(
    val status: Int,
    val body: JsonObject,
) {
    val successful: Boolean get() = status in 200..299
}

/** Долговечный журнал агента. Методы begin возвращают сохранённую запись при техническом повторе. */
interface AgentStore {
    suspend fun findEvent(id: String): StoredEvent?

    suspend fun beginEvent(snapshot: EventSnapshot): StoredEvent

    suspend fun finishEvent(
        id: String,
        reply: BotReply,
    )

    suspend fun delivered(id: String)

    suspend fun pendingEvents(): List<StoredEvent>

    suspend fun proposal(userId: Long): ZoneProposal?

    suspend fun propose(
        userId: Long,
        proposal: ZoneProposal?,
    )

    suspend fun beginOperation(operation: Outgoing): Pair<Outgoing, RestReply?>

    suspend fun finishOperation(
        id: UUID,
        reply: RestReply,
    )

    suspend fun pendingOperations(): List<Outgoing>
}

/** Только internal REST; сервисный токен добавляется транспортом, в журнал не записывается. */
fun interface SystemCore {
    suspend fun request(
        method: String,
        path: String,
        body: JsonObject?,
        operationId: UUID?,
    ): RestReply
}

/** Одна запись на шаг исходного Telegram-события, включая восстановление процесса. */
class OutgoingOperations(
    private val store: AgentStore,
    private val core: SystemCore,
) {
    suspend fun perform(
        eventKey: String,
        method: String,
        path: String,
        body: JsonObject,
        userId: Long? = null,
    ): RestReply {
        val (operation, result) = store.beginOperation(Outgoing(UUID.randomUUID(), eventKey, method, path, body, userId))
        return result ?: execute(operation)
    }

    private suspend fun execute(operation: Outgoing): RestReply {
        val result = core.request(operation.method, operation.path, operation.body, operation.id)
        // Незавершённые/серверные ошибки не превращаем в окончательный результат.
        if (result.status >= SERVER_ERROR_STATUS || result.body.toString().contains("operation_in_progress")) {
            throw IllegalStateException("Системная операция временно недоступна")
        }
        store.finishOperation(operation.id, result)
        return result
    }

    suspend fun recover() {
        for (operation in store.pendingOperations()) execute(operation)
    }

    companion object {
        private const val SERVER_ERROR_STATUS = 500
    }
}
