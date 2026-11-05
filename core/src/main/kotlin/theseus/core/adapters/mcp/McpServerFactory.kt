package theseus.core.adapters.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * MCP-сервер core: инструменты для LLM. Описания и схемы — ресурсы `mcp/<инструмент>.md|.schema.json`.
 * Сервер один на процесс; транспорт — Streamable HTTP без сессий (каждый запрос самодостаточен).
 */
object McpServerFactory {
    /** Ключ `_meta`, в котором агент передаёт id пользователя. */
    const val USER_META_KEY = "theseus/user_id"

    fun create(tools: ReminderTools): Server {
        val server =
            Server(
                Implementation(name = "theseus-core", version = "1"),
                ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
            )
        server.addTool(loadTool("create_reminder")) { request ->
            tools.createReminder(request.userId(), request.arguments).toResult()
        }
        return server
    }

    internal fun loadTool(name: String): Tool {
        val description = resource("mcp/$name.md").trim()
        val schema = Json.parseToJsonElement(resource("mcp/$name.schema.json")).jsonObject
        return Tool(
            name = name,
            description = description,
            inputSchema =
                ToolSchema(
                    properties = schema["properties"]?.jsonObject,
                    required = (schema["required"] as? JsonArray)?.map { it.jsonPrimitive.content },
                ),
        )
    }

    private fun CallToolRequest.userId(): Long? =
        meta
            ?.json
            ?.get(USER_META_KEY)
            ?.jsonPrimitive
            ?.longOrNull

    private fun ToolOutcome.toResult() = CallToolResult(content = listOf(TextContent(json)), isError = isError)

    private fun resource(path: String): String =
        McpServerFactory::class.java.classLoader
            .getResource(path)
            ?.readText()
            ?: error("Ресурс '$path' не найден")
}
