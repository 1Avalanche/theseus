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
    val maxOutputTokens: Int = 2000,
    val role: String = "main",
) {
    init {
        require(maxOutputTokens in 1..MAX_OUTPUT_TOKENS)
    }

    companion object {
        private const val MAX_OUTPUT_TOKENS = 2000
    }
}

data class LlmUsage(
    val tokensIn: Int,
    val tokensOut: Int,
    val tokensCached: Int = 0,
    val costUsd: String? = null,
)

data class LlmResponse(
    val message: ChatMessage,
    /** Фактическая модель после fallback. */
    val model: String,
    val usage: LlmUsage?,
    val generationId: String? = null,
    val latencyMs: Int? = null,
    val role: String = "main",
)

/** Порт к LLM. В юнит-тестах — только фейк с заготовленными ответами. */
interface LlmClient {
    suspend fun complete(request: LlmRequest): LlmResponse
}

class LlmException(
    message: String,
    cause: Throwable? = null,
    val reason: LlmFailure = LlmFailure.UNKNOWN,
    val httpStatus: Int? = null,
) : RuntimeException(message, cause)

/** Безопасная причина для диагностики: без тела ответа провайдера. */
enum class LlmFailure { UNKNOWN, HTTP, INVALID_RESPONSE, EMPTY_CHOICES }
