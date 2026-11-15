package theseus.agent.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import theseus.agent.llm.ContextBudget
import theseus.agent.llm.LlmRequest
import theseus.agent.llm.ToolCall

/** Общий бюджет всех ролей на входящее событие. Повтор аргументов не получает новый ключ изменения. */
internal class TurnBudget {
    private var requests = 0
    private var rounds = 0
    private var calls = 0
    private val seen = mutableSetOf<String>()
    var mutationStarted = false

    fun request(
        request: LlmRequest,
        contextLimit: Int = 16000,
    ) {
        if (requests >= MAX_REQUESTS || !ContextBudget.fits(request, contextLimit)) throw BudgetExceeded()
        requests++
    }

    fun tools(
        tools: List<ToolCall>,
        maxRounds: Int,
    ) {
        if (rounds >= minOf(maxRounds, MAX_ROUNDS) || calls + tools.size > MAX_TOOLS) throw BudgetExceeded()
        val signatures = tools.map { it.name + canonical(runCatching { Json.parseToJsonElement(it.argumentsJson) }.getOrNull()) }
        if (signatures.distinct().size != signatures.size || signatures.any { it in seen }) throw BudgetExceeded()
        seen += signatures
        calls += tools.size
        rounds++
    }

    private fun canonical(value: JsonElement?): String =
        when (value) {
            is JsonObject -> value.toSortedMap().entries.joinToString { it.key + ":" + canonical(it.value) }
            is JsonArray -> value.joinToString { canonical(it) }
            else -> value.toString()
        }

    companion object {
        const val MAX_REQUESTS = 3
        const val MAX_ROUNDS = 2
        const val MAX_TOOLS = 6
        const val TIMEOUT_MS = 90_000L
    }
}

internal class BudgetExceeded : RuntimeException()

internal class SummaryUnavailable(
    cause: Throwable,
) : RuntimeException(cause)
