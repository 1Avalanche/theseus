package theseus.agent.llm

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Duration

/**
 * OpenRouter Chat Completions (OpenAI-совместимый API, tool calling).
 * Модели — из конфига; порядок = приоритет fallback (`models`). Провайдерам запрещён сбор данных.
 */
class OpenRouterClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val models: List<String>,
    private val timeout: Duration,
) : LlmClient {
    override suspend fun complete(request: LlmRequest): LlmResponse {
        val body = json.encodeToString(toWire(request))
        val response =
            http.post("$baseUrl/chat/completions") {
                bearerAuth(apiKey)
                header("X-Title", "Theseus")
                contentType(ContentType.Application.Json)
                setBody(body)
                timeout { requestTimeoutMillis = timeout.toMillis() }
            }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) throw LlmException("OpenRouter ${response.status.value}")
        return parse(text)
    }

    internal fun toWire(request: LlmRequest) =
        WireRequest(
            model = models.first(),
            models = models,
            messages = request.messages.map(::toWire),
            tools = request.tools.map { WireTool(function = WireFunction(it.name, it.description, it.parameters)) }.ifEmpty { null },
            provider = WireProvider(dataCollection = "deny"),
            usage = WireUsageRequest(include = true),
        )

    private fun toWire(message: ChatMessage) =
        WireMessage(
            role = message.role.name.lowercase(),
            content = message.content,
            toolCalls = message.toolCalls.map { WireToolCall(it.id, function = WireCall(it.name, it.argumentsJson)) }.ifEmpty { null },
            toolCallId = message.toolCallId,
        )

    internal fun parse(text: String): LlmResponse {
        val wire =
            try {
                json.decodeFromString<WireResponse>(text)
            } catch (e: IllegalArgumentException) {
                // SerializationException — тоже IllegalArgumentException.
                throw LlmException("Неожиданный ответ OpenRouter", e)
            }
        val message = wire.choices.firstOrNull()?.message ?: throw LlmException("OpenRouter: пустой choices")
        return LlmResponse(
            message =
                ChatMessage(
                    role = Role.ASSISTANT,
                    content = message.content,
                    toolCalls = message.toolCalls.orEmpty().map { ToolCall(it.id, it.function.name, it.function.arguments) },
                ),
            model = wire.model ?: models.first(),
            usage = wire.usage?.let { LlmUsage(it.promptTokens, it.completionTokens) },
        )
    }

    companion object {
        internal val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
    }
}

@Serializable
internal data class WireRequest(
    val model: String,
    val models: List<String>,
    val messages: List<WireMessage>,
    val tools: List<WireTool>?,
    val provider: WireProvider,
    val usage: WireUsageRequest,
)

@Serializable
internal data class WireProvider(
    @SerialName("data_collection") val dataCollection: String,
)

@Serializable
internal data class WireUsageRequest(
    val include: Boolean,
)

@Serializable
internal data class WireMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
internal data class WireTool(
    val type: String = "function",
    val function: WireFunction,
)

@Serializable
internal data class WireFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
internal data class WireToolCall(
    val id: String,
    val type: String = "function",
    val function: WireCall,
)

@Serializable
internal data class WireCall(
    val name: String,
    val arguments: String,
)

@Serializable
internal data class WireResponse(
    val model: String? = null,
    val choices: List<WireChoice> = emptyList(),
    val usage: WireUsage? = null,
)

@Serializable
internal data class WireChoice(
    val message: WireMessage,
)

@Serializable
internal data class WireUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)
