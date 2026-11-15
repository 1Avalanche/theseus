package theseus.agent.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import theseus.agent.dialog.DialogSession
import theseus.agent.dialog.DialogStore
import theseus.agent.dialog.ListReference
import theseus.agent.llm.ChatMessage
import theseus.agent.llm.ContextBudget
import theseus.agent.llm.LlmRequest
import theseus.agent.llm.Role
import theseus.agent.llm.ToolDefinition
import theseus.agent.texts.Texts

/** Сжатие текстовой истории и точное разрешение номеров последнего списка. Tool-протокол между ходами не сохраняется. */
class ConversationMemory(
    private val store: DialogStore,
    private val texts: Texts,
) {
    val referenceTool =
        ToolDefinition(
            "resolve_reference",
            texts.get("tool.reference.description"),
            Json.parseToJsonElement(Texts.resourceText("llm/resolve_reference.schema.json")).jsonObject,
        )

    fun resolve(
        session: DialogSession?,
        arguments: JsonObject,
    ): ReminderExecution {
        val number = (arguments["number"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val ref =
            if (arguments.keys == setOf("number") &&
                number != null &&
                number > 0
            ) {
                session?.references?.getOrNull(number - 1)
            } else {
                null
            }
        return if (ref == null) {
            ReminderExecution(null, theseus.agent.system.BotReply(texts.get("reply.reference_missing")))
        } else {
            ReminderExecution(
                theseus.agent.core.ToolResult(
                    buildJsonObject {
                        put("reminder_id", ref.reminderId)
                        ref.occurrenceLocal?.let { put("occurrence_local", it) }
                    }.toString(),
                    false,
                ),
            )
        }
    }

    suspend fun rememberList(
        session: DialogSession?,
        text: String,
    ) {
        if (session == null) return
        val result = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val rows = result["reminders"] as? JsonArray ?: return
        val occurrences = result["occurrences"] as? JsonArray
        val refs =
            if (occurrences != null) {
                occurrences.map { value ->
                    val row = value.jsonObject
                    ListReference(requireNotNull(row["reminder_id"]?.jsonPrimitive?.longOrNull), row["at_local"]?.jsonPrimitive?.content)
                }
            } else {
                rows.map { ListReference(requireNotNull(it.jsonObject["id"]?.jsonPrimitive?.longOrNull)) }
            }
        store.references(session.id, refs)
    }

    suspend fun history(
        session: DialogSession,
        complete: suspend (LlmRequest) -> String?,
    ): List<ChatMessage> {
        val recent = session.entries.takeLast(HISTORY_LIMIT - 1).dropWhile { it.role != Role.USER }
        val old = session.entries.takeWhile { it.id < (recent.firstOrNull()?.id ?: Long.MAX_VALUE) }
        var summary = session.summary
        if (old.isNotEmpty()) {
            val input = mutableListOf(ChatMessage(Role.SYSTEM, Texts.resourceText("prompts/summary.md")))
            summary?.let { input += ChatMessage(Role.USER, it) }
            val included =
                old.takeWhile { entry ->
                    val request = LlmRequest(input + entry.message(), emptyList(), SUMMARY_LIMIT, "summary")
                    if (!ContextBudget.fits(request, SUMMARY_INPUT_LIMIT)) {
                        false
                    } else {
                        input += entry.message()
                        true
                    }
                }
            if (included.isNotEmpty()) {
                complete(LlmRequest(input, emptyList(), SUMMARY_LIMIT, "summary"))?.takeIf { it.isNotBlank() }?.let {
                    summary = ContextBudget.take(it, SUMMARY_LIMIT)
                    store.summarize(session.id, requireNotNull(summary), included.last().id)
                }
            }
        }
        // Саммари — данные пользователя, не дополнительные system-инструкции.
        return listOfNotNull(summary?.let { ChatMessage(Role.USER, ContextBudget.take(it, SUMMARY_LIMIT)) }) +
            recent.map { it.message() }
    }

    companion object {
        const val HISTORY_LIMIT = 20
        const val SUMMARY_LIMIT = 1000
        private const val SUMMARY_INPUT_LIMIT = 15000 // Ещё 1000 — резерв динамического профиля.
    }
}
