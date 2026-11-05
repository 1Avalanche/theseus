package theseus.agent.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Пользователь из core (`GET /internal/v1/users/by-chat/{chat_id}`). */
@Serializable
data class CoreUser(
    val id: Long,
    @SerialName("chat_id") val chatId: Long,
    val status: String,
    val name: String?,
    val gender: String,
    val timezone: String?,
) {
    val isActive: Boolean get() = status == "active" && timezone != null
}

/** Инструмент MCP в виде, пригодном для передачи модели. */
data class ToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/** Результат инструмента: JSON-текст из core и признак ошибки. */
data class ToolResult(
    val text: String,
    val isError: Boolean,
)

/**
 * Порт к core. Пользовательские данные — только через MCP (инструменты) и internal REST;
 * в БД core агент не ходит. `userId` уходит в `_meta`, а не в аргументы модели.
 */
interface CoreClient {
    suspend fun findUserByChat(chatId: Long): CoreUser?

    suspend fun listTools(): List<ToolSpec>

    suspend fun callTool(
        userId: Long,
        name: String,
        arguments: JsonObject,
    ): ToolResult
}
