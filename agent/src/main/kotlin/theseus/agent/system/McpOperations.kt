package theseus.agent.system

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import theseus.agent.core.CoreClient
import theseus.agent.core.ToolResult
import java.util.UUID

/** Журнал MCP использует то же зашифрованное хранилище и бессодержательную защиту удалённых ключей, что REST. */
class McpOperations(
    private val store: AgentStore,
    private val core: CoreClient,
) {
    suspend fun perform(
        eventKey: String,
        userId: Long,
        name: String,
        arguments: JsonObject,
        confirmation: JsonObject? = null,
        operationId: UUID? = null,
    ): ToolResult {
        val body =
            buildJsonObject {
                put("arguments", arguments)
                confirmation?.let { put("confirmation", it) }
            }
        val (operation, result) = store.beginOperation(Outgoing(operationId ?: UUID.randomUUID(), eventKey, "MCP", name, body, userId))
        return result?.let { ToolResult(it.body.toString(), it.status != SUCCESS) } ?: execute(operation)
    }

    private suspend fun execute(operation: Outgoing): ToolResult =
        try {
            val result =
                core.callTool(
                    requireNotNull(operation.userId),
                    operation.path,
                    operation.body.getValue("arguments").jsonObject,
                    operation.id,
                    operation.body["confirmation"] as? JsonObject,
                )
            val body = Json.parseToJsonElement(result.text).jsonObject
            val code = (body["error"] as? JsonObject)?.value("code")
            if (code in setOf("internal", "operation_in_progress")) throw UncertainMcpException()
            store.finishOperation(operation.id, RestReply(if (result.isError) FAILURE else SUCCESS, body))
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw UncertainMcpException(e)
        }

    suspend fun recoverEvent(eventKey: String) {
        for ((operation, result) in store.eventOperations(eventKey)) {
            if (operation.method == "MCP" && result == null) execute(operation)
        }
    }

    companion object {
        private const val SUCCESS = 200
        private const val FAILURE = 400
    }
}

/** Исход изменения ещё не выяснен: событие нельзя закрыть или пропустить перед следующим сообщением. */
class UncertainMcpException(
    cause: Throwable? = null,
) : RuntimeException("Исход MCP требует сверки", cause)
