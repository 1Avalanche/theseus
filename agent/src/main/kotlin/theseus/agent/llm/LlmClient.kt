package theseus.agent.llm

import kotlinx.serialization.json.JsonObject

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ToolCall(
    val id: String,
    val name: String,
    /** Аргументы как их вернула модель — строка JSON, может быть невалидной. */
    val argumentsJson: String,
)

data class ChatMessage(
    val role: Role,
    val content: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class LlmRequest(
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>,
)

data class LlmUsage(
    val tokensIn: Int,
    val tokensOut: Int,
)

data class LlmResponse(
    val message: ChatMessage,
    /** Фактическая модель после fallback. */
    val model: String,
    val usage: LlmUsage?,
)

/** Порт к LLM. В юнит-тестах — только фейк с заготовленными ответами. */
interface LlmClient {
    suspend fun complete(request: LlmRequest): LlmResponse
}

class LlmException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
