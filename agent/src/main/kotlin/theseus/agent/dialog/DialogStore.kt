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

/** Ссылка на напоминание целиком в успешно отправленном ответе агента. */
data class ReminderReplyTarget(
    val reminderId: Long,
    val kind: String,
    val timezone: String,
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
    /** Записывается после получения message_id от Telegram, без разбора текста ответа. */
    suspend fun rememberReply(
        userId: Long,
        messageId: Long,
        target: ReminderReplyTarget,
        now: Instant,
    )

    /** Связь принадлежит текущему пользователю и живёт не дольше срока хранения истории. */
    suspend fun replyTarget(
        userId: Long,
        messageId: Long,
        now: Instant,
    ): ReminderReplyTarget?

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
