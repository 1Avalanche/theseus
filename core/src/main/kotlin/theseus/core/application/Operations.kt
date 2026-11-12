package theseus.core.application

import java.time.Instant
import java.util.UUID

/** Снимок ответа адаптера. Секретный результат допускает отдельную квитанцию для повторов. */
data class OperationReply(
    val status: Int,
    val body: String,
    val receipt: String = body,
    val receiptStatus: Int = status,
)

/** Субъект, операция и канонический хэш не позволяют повтором получить результат чужого запроса. */
data class OperationRequest(
    val id: UUID,
    val subject: String,
    val operation: String,
    val requestHash: String,
    val userId: Long?,
)

/** Реализация удерживает транзакцию от авторизации до сохранения результата, включая вложенные репозитории. */
interface Operations {
    suspend fun execute(
        request: OperationRequest,
        now: Instant,
        authorize: suspend () -> Unit,
        action: suspend () -> OperationReply,
    ): OperationReply
}
