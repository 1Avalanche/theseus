package theseus.agent.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import theseus.agent.core.ToolResult
import theseus.agent.llm.ToolDefinition
import theseus.agent.system.BuildInfo
import theseus.agent.texts.Texts
import kotlin.random.Random

/** Готовые ответы: модель выбирает тему, код проверяет её и подставляет ресурсный текст. */
class DialogReplies(
    private val texts: Texts,
    private val variantIndex: () -> Int = { Random.nextInt(INTRODUCTION_VARIANTS) },
) {
    private val replyKeys =
        mapOf(
            "greeting" to "reply.greeting",
            "introduction" to "reply.about",
            "unclassified" to "reply.unclassified",
            "about" to "reply.about",
            "capabilities" to "reply.capabilities",
            "clarify" to "reply.clarify",
            "unsupported" to "reply.unsupported",
            "unsupported_recurrence" to "reply.unsupported_recurrence",
            "invalid_date" to "reply.error.invalid_date",
            "invalid_time" to "reply.error.invalid_time",
            "time_in_past" to "reply.error.time_in_past",
            "missing_text" to "reply.missing_text",
            "choose_reminder" to "reply.choose_reminder",
            "missing_time" to "reply.missing_time",
        )

    val definition =
        ToolDefinition(
            TOOL_NAME,
            texts.get("tool.dialogue.description"),
            Json.parseToJsonElement(Texts.resourceText("llm/assistant_reply.schema.json")).jsonObject,
        )

    /** Telegram-команды обрабатываются до LLM, но после проверки доступа пользователя. */
    fun commandReply(text: String): String? =
        when (text.trim().takeWhile { !it.isWhitespace() }.substringBefore('@')) {
            "/start" -> texts.get("reply.greeting")
            "/help" -> texts.get("reply.capabilities")
            "/version" -> texts.get("reply.version", mapOf("version" to BuildInfo.version))
            else -> null
        }

    /** Произвольные текст, имя пользователя и новые темы от модели не принимаются. */
    fun execute(arguments: JsonObject): ToolResult {
        val topic = (arguments["topic"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val key = if (arguments.keys == setOf("topic")) topic?.let(replyKeys::get) else null
        return when {
            key == null -> ToolResult(texts.get("tool.invalid_dialogue"), isError = true)
            topic == "introduction" -> ToolResult(introduction(), isError = false)
            else -> ToolResult(texts.get(key), isError = false)
        }
    }

    /** Для логов разрешены только темы строгого локального контракта. */
    internal fun approvedTopic(call: theseus.agent.llm.ToolCall): String? {
        if (call.name != TOOL_NAME) return null
        val arguments = runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject }.getOrNull() ?: return null
        val topic = (arguments["topic"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return topic?.takeIf { arguments.keys == setOf("topic") && it in replyKeys }
    }

    private fun introduction(): String =
        listOf(
            texts.get("reply.about"),
            texts.get("reply.about.variant.${variantIndex() + 1}"),
            texts.get("reply.capabilities"),
        ).joinToString("\n\n")

    companion object {
        private const val INTRODUCTION_VARIANTS = 6
        const val TOOL_NAME = "assistant_reply"
    }
}
