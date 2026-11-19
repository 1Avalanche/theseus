package theseus.agent.llm

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import theseus.agent.monitoring.DependencySignal
import java.time.Clock
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
    private val signal: DependencySignal = DependencySignal(Clock.systemUTC()),
    private val clock: Clock = Clock.systemUTC(),
    private val summaryModels: List<String> = models,
    private val contextTokens: Int = 18000,
) : LlmClient {
    override suspend fun complete(request: LlmRequest): LlmResponse {
        checkBudget(request)
        var failureCode = "network_or_response"
        val started = clock.instant()
        try {
            val body = json.encodeToString(toWire(request))
            val response =
                http.post("$baseUrl/chat/completions") {
                    bearerAuth(apiKey)
                    header("X-Title", "Theseus")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                    timeout { requestTimeoutMillis = timeout.toMillis() }
                }
            if (!response.status.isSuccess()) failureCode = response.status.value.toString()
            val text = response.bodyAsText()
            checkStatus(response.status)
            return parse(
                text,
                if (request.role == "summary") summaryModels.first() else models.first(),
            ).copy(
                role = request.role,
                latencyMs =
                    Duration
                        .between(started, clock.instant())
                        .toMillis()
                        .coerceIn(0, Int.MAX_VALUE.toLong())
                        .toInt(),
            ).also { signal.success() }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            signal.failure(failureCode)
            throw e
        }
    }

    private fun checkBudget(request: LlmRequest) {
        if (!ContextBudget.fits(request, minOf(MAX_CONTEXT_TOKENS, contextTokens - request.maxOutputTokens))) {
            throw LlmBudgetException()
        }
    }

    private fun checkStatus(status: HttpStatusCode) {
        if (!status.isSuccess()) throw LlmException("OpenRouter HTTP ${status.value}", reason = LlmFailure.HTTP, httpStatus = status.value)
    }

    internal fun toWire(request: LlmRequest) =
        WireRequest(
            model = (if (request.role == "summary") summaryModels else models).first(),
            models = if (request.role == "summary") summaryModels else models,
            maxTokens = request.maxOutputTokens,
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

    internal fun parse(
        text: String,
        defaultModel: String = models.first(),
    ): LlmResponse {
        val wire =
            try {
                json.decodeFromString<WireResponse>(text)
            } catch (e: IllegalArgumentException) {
                // SerializationException — тоже IllegalArgumentException.
                throw LlmException("Неожиданный ответ OpenRouter", e, reason = LlmFailure.INVALID_RESPONSE)
            }
        val message =
            wire.choices.firstOrNull()?.message ?: throw LlmException("OpenRouter: пустой choices", reason = LlmFailure.EMPTY_CHOICES)
        return LlmResponse(
            message =
                ChatMessage(
                    role = Role.ASSISTANT,
                    content = message.content,
                    toolCalls = message.toolCalls.orEmpty().map { ToolCall(it.id, it.function.name, it.function.arguments) },
                ),
            model = wire.model ?: defaultModel,
            usage =
                wire.usage?.let {
                    LlmUsage(
                        it.promptTokens,
                        it.completionTokens,
                        it.promptTokensDetails?.cachedTokens ?: 0,
                        it.cost?.content,
                    )
                },
            generationId = wire.id,
        )
    }

    companion object {
        private const val MAX_CONTEXT_TOKENS = 16000
        internal val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                // Обязательное type=function должно передаваться и при значении по умолчанию.
                encodeDefaults = true
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
    @SerialName("max_tokens") val maxTokens: Int,
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
    val id: String? = null,
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
    val cost: JsonPrimitive? = null,
    @SerialName("prompt_tokens_details") val promptTokensDetails: WirePromptDetails? = null,
)

@Serializable
internal data class WirePromptDetails(
    @SerialName("cached_tokens") val cachedTokens: Int = 0,
)
