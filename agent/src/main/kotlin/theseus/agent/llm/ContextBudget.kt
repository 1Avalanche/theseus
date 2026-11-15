package theseus.agent.llm

/**
 * Консервативная верхняя оценка: один токен на байт UTF-8 плюс запас на протокол.
 * Не требует токенизатора конкретного fallback; при сокращении история удаляется целыми сообщениями.
 */
object ContextBudget {
    private const val LIMIT = 16000
    private const val MESSAGE_OVERHEAD = 32
    private const val PROTOCOL_RESERVE = 512

    fun size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    fun fits(
        request: LlmRequest,
        limit: Int = LIMIT,
    ): Boolean =
        PROTOCOL_RESERVE +
            request.messages.sumOf { message ->
                MESSAGE_OVERHEAD + size(message.content.orEmpty()) + message.toolCalls.sumOf { size(it.id + it.name + it.argumentsJson) } +
                    size(message.toolCallId.orEmpty())
            } + request.tools.sumOf { MESSAGE_OVERHEAD + size(it.name + it.description + it.parameters.toString()) } <= limit

    fun take(
        text: String,
        limit: Int,
    ): String {
        var bytes = 0
        val end =
            text
                .codePoints()
                .takeWhile { point ->
                    bytes += String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
                    bytes <= limit
                }.toArray()
        return String(end, 0, end.size)
    }
}

class LlmBudgetException : RuntimeException("Превышен бюджет контекста")
