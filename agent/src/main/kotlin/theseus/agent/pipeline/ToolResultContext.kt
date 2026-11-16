package theseus.agent.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import theseus.agent.llm.ContextBudget

/**
 * Поисковые карточки для модели: имена одинаковых полей передаются один раз, строки — массивами значений.
 * Поля и значения не теряются; исходный JSON остаётся у кода для показа карточек и подтверждений.
 */
internal object ToolResultContext {
    fun incompleteSelection(
        candidates: Collection<String>,
        calls: List<theseus.agent.llm.ToolCall>,
    ): Boolean {
        val truncated =
            candidates.any { text ->
                runCatching { (Json.parseToJsonElement(text) as? JsonObject)?.get("truncated") == JsonPrimitive(true) }.getOrDefault(false)
            }
        if (!truncated) return false
        return calls.any { call ->
            call.name == "delete_reminder" &&
                runCatching {
                    (Json.parseToJsonElement(call.argumentsJson) as? JsonObject)?.get("scope") == JsonPrimitive("selected")
                }.getOrDefault(false)
        }
    }

    fun compact(
        name: String,
        text: String,
    ): String {
        if (name != "find_reminders") return text
        val result = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return text
        val candidates = result["candidates"] as? JsonArray ?: return text
        val rows = candidates.map { it as? JsonObject ?: return text }
        val first = rows.firstOrNull() ?: return text
        val fields = first.keys.toList()
        if (rows.any { it.keys != fields.toSet() } || result.keys.any { it in setOf("candidate_fields", "candidate_common") }) return text
        val common = first.filter { (key, value) -> rows.all { it[key] == value } }
        val varying = fields.filterNot(common::containsKey)
        val compact =
            JsonObject(
                result +
                    mapOf(
                        "candidate_fields" to JsonArray(varying.map(::JsonPrimitive)),
                        "candidate_common" to JsonObject(common),
                        "candidates" to JsonArray(rows.map { row -> JsonArray(varying.map { row.getValue(it) }) }),
                    ),
            ).toString()
        return if (ContextBudget.size(compact) < ContextBudget.size(text)) compact else text
    }
}
