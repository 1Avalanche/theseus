package theseus.agent.core

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import theseus.agent.system.RestReply
import theseus.agent.system.SystemCore
import java.util.UUID

/**
 * REST и MCP core по внутренней сети. MCP-клиент один на процесс; сервер без сессий,
 * поэтому после ошибки клиент просто пересоздаётся.
 */
class HttpCoreClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val serviceToken: String,
) : CoreClient,
    SystemCore {
    private val json = Json { ignoreUnknownKeys = true }
    private val mcpLock = Mutex()
    private var mcp: Client? = null

    override suspend fun request(
        method: String,
        path: String,
        body: JsonObject?,
        operationId: UUID?,
    ): RestReply {
        require(path.startsWith("/") && !path.startsWith("//"))
        val response =
            http.request("$baseUrl/internal/v1$path") {
                this.method = HttpMethod.parse(method)
                bearerAuth(serviceToken)
                operationId?.let { header("Idempotency-Key", it.toString()) }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }
        return RestReply(response.status.value, json.parseToJsonElement(response.bodyAsText()).jsonObject)
    }

    override suspend fun findUserByChat(chatId: Long): CoreUser? {
        val response = http.get("$baseUrl/internal/v1/users/by-chat/$chatId") { bearerAuth(serviceToken) }
        return when (response.status) {
            HttpStatusCode.OK -> json.decodeFromString<CoreUser>(response.bodyAsText())
            HttpStatusCode.NotFound -> null
            else -> throw CoreUnavailableException("users/by-chat: ${response.status}")
        }
    }

    override suspend fun listTools(): List<ToolSpec> =
        withMcp { client ->
            client.listTools().tools.map {
                ToolSpec(it.name, it.description.orEmpty(), json.encodeToJsonElement(ToolSchema.serializer(), it.inputSchema).jsonObject)
            }
        }

    override suspend fun callTool(
        userId: Long,
        name: String,
        arguments: JsonObject,
    ): ToolResult =
        withMcp { client ->
            val request =
                CallToolRequest(
                    CallToolRequestParams(
                        name = name,
                        arguments = arguments,
                        meta = RequestMeta(JsonObject(mapOf(USER_META_KEY to JsonPrimitive(userId)))),
                    ),
                )
            val result = client.callTool(request)
            ToolResult(result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }, result.isError == true)
        }

    private suspend fun <T> withMcp(block: suspend (Client) -> T): T {
        val client =
            mcpLock.withLock {
                mcp ?: http
                    .mcpStreamableHttp("$baseUrl/mcp") { headers.append(HttpHeaders.Authorization, "Bearer $serviceToken") }
                    .also { mcp = it }
            }
        return try {
            block(client)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            mcpLock.withLock { if (mcp === client) mcp = null }
            throw CoreUnavailableException("mcp: ${e.javaClass.simpleName}", e)
        }
    }

    companion object {
        /** Ключ `_meta` с id пользователя — контракт `docs/api/mcp.md`. */
        const val USER_META_KEY = "theseus/user_id"
    }
}

class CoreUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
