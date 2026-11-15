package theseus.agent.dialog

import kotlinx.serialization.Serializable
import theseus.agent.llm.ChatMessage
import theseus.agent.llm.Role
import java.time.Instant

@Serializable
data class ListReference(
    val reminderId: Long,
    val occurrenceLocal: String? = null,
)

data class DialogEntry(
    val id: Long,
    val role: Role,
    val content: String,
) {
    fun message(): ChatMessage = ChatMessage(role, content)
}

data class DialogSession(
    val id: Long,
    val summary: String?,
    val entries: List<DialogEntry>,
    val references: List<ListReference>,
)

/** Память одной сессии. Вызывается под общей блокировкой chatId, события пишутся идемпотентно. */
interface DialogStore {
    suspend fun open(
        userId: Long,
        chatId: Long,
        now: Instant,
    ): DialogSession

    suspend fun append(
        sessionId: Long,
        userId: Long,
        eventId: String,
        role: Role,
        content: String,
        now: Instant,
    )

    suspend fun summarize(
        sessionId: Long,
        summary: String,
        untilId: Long,
    )

    suspend fun references(
        sessionId: Long,
        references: List<ListReference>,
    )

    suspend fun cleanup(now: Instant)

    /** Дописывает восстановленный исход к исходному ходу, не открывая новую сессию. */
    suspend fun completeEvent(
        userId: Long,
        eventId: String,
        content: String,
        now: Instant,
    )
}
