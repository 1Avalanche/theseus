package theseus.agent.system

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import theseus.agent.llm.LlmException
import theseus.agent.llm.LlmResponse

/** Учёт вынесен из диалога: в тестах без модели и инфраструктуры используется пустой порт. */
interface UsageRecorder {
    suspend fun before(userId: Long)

    suspend fun record(
        userId: Long,
        eventKey: String,
        response: LlmResponse,
    )
}

object NoUsageRecorder : UsageRecorder {
    override suspend fun before(userId: Long) = Unit

    override suspend fun record(
        userId: Long,
        eventKey: String,
        response: LlmResponse,
    ) = Unit
}

class PersistentUsageRecorder(
    private val core: SystemCore,
    private val operations: OutgoingOperations,
) : UsageRecorder {
    override suspend fun before(userId: Long) {
        val result = core.request("GET", "/users/$userId/quota", null, null)
        if (!result.successful || result.body["exceeded"]?.jsonPrimitive?.content != "false") {
            throw LlmException("Квота или доступ не подтверждены")
        }
    }

    override suspend fun record(
        userId: Long,
        eventKey: String,
        response: LlmResponse,
    ) {
        val usage = response.usage?.takeIf { it.costUsd != null } ?: throw LlmException("OpenRouter не вернул usage и стоимость")
        val cost = requireNotNull(usage.costUsd)
        val result =
            operations.perform(
                eventKey,
                "POST",
                "/usage",
                buildJsonObject {
                    put("user_id", userId)
                    put("role", "main")
                    put("model", response.model)
                    response.generationId?.let { put("generation_id", it) }
                    put("tokens_in", usage.tokensIn)
                    put("tokens_out", usage.tokensOut)
                    put("tokens_cached", usage.tokensCached)
                    put("cost_usd", cost)
                    response.latencyMs?.let { put("latency_ms", it) }
                },
                userId,
            )
        if (!result.successful) throw LlmException("Core отклонил запись usage: ${result.status}")
    }
}
