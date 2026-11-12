package theseus.agent.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import theseus.agent.core.CoreUser
import theseus.agent.core.ToolResult
import theseus.agent.llm.ToolDefinition
import theseus.agent.system.BuildInfo
import theseus.agent.texts.Texts

/** Готовые ответы: модель выбирает тему, код проверяет её и подставляет ресурсный текст. */
class DialogReplies(
    private val texts: Texts,
) {
    private val replyKeys =
        mapOf(
            "greeting" to "reply.welcome",
            "about" to "reply.about",
            "capabilities" to "reply.capabilities",
            "clarify" to "reply.clarify",
            "unsupported" to "reply.unsupported",
        )

    val definition =
        ToolDefinition(
            TOOL_NAME,
            texts.get("tool.dialogue.description"),
            Json.parseToJsonElement(Texts.resourceText("llm/assistant_reply.schema.json")).jsonObject,
        )

    /** Telegram-команды обрабатываются до LLM, но после проверки доступа пользователя. */
    fun commandReply(
        text: String,
        user: CoreUser,
    ): String? =
        when (text.trim().takeWhile { !it.isWhitespace() }.substringBefore('@')) {
            "/start" -> welcome(user)
            "/help" -> texts.get("reply.capabilities")
            "/version" -> texts.get("reply.version", mapOf("version" to BuildInfo.version))
            else -> null
        }

    /** Произвольные текст, имя пользователя и новые темы от модели не принимаются. */
    fun execute(
        user: CoreUser,
        arguments: JsonObject,
    ): ToolResult {
        val topic = (arguments["topic"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val key = if (arguments.keys == setOf("topic")) topic?.let(replyKeys::get) else null
        return when {
            key == null -> ToolResult(texts.get("tool.invalid_dialogue"), isError = true)
            topic == "greeting" -> ToolResult(welcome(user), isError = false)
            else -> ToolResult(texts.get(key), isError = false)
        }
    }

    private fun welcome(user: CoreUser): String =
        user.name
            ?.takeIf { it.isNotBlank() }
            ?.let { texts.get("reply.welcome_named", mapOf("name" to it)) }
            ?: texts.get("reply.welcome")

    companion object {
        const val TOOL_NAME = "assistant_reply"
    }
}
