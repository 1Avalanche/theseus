// Числа JDBC — позиции столбцов и параметров SQL.
@file:Suppress("MagicNumber")

package theseus.core.adapters.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import theseus.core.application.OperationReply
import theseus.core.application.OperationRequest
import theseus.core.application.Operations
import theseus.core.domain.user.AccountException
import java.sql.Connection
import java.time.Instant

/** Advisory lock и запись результата находятся в той же транзакции, что изменения вложенных репозиториев. */
class DatabaseOperations(
    private val db: CoreDatabase,
) : Operations {
    override suspend fun execute(
        request: OperationRequest,
        now: Instant,
        authorize: suspend () -> Unit,
        action: suspend () -> OperationReply,
    ): OperationReply =
        db.atomic {
            val jdbc = connection.connection as Connection
            jdbc.prepareStatement("select pg_advisory_xact_lock(?)").use {
                it.setLong(1, request.id.mostSignificantBits xor request.id.leastSignificantBits)
                it.execute()
            }
            authorize()
            val existing =
                jdbc
                    .prepareStatement(
                        "select subject, channel, operation, request_hash, result::text from operation_results where id = ?",
                    ).use {
                        it.setObject(1, request.id)
                        it.executeQuery().use { row ->
                            if (!row.next()) {
                                null
                            } else {
                                val identity = listOf(row.getString(1), row.getString(2), row.getString(3), row.getString(4))
                                if (identity != listOf(request.subject, "REST", request.operation, request.requestHash)) {
                                    throw AccountException("idempotency_conflict")
                                }
                                val value = Json.parseToJsonElement(row.getString(5)).jsonObject
                                OperationReply(value.getValue("status").jsonPrimitive.int, value.getValue("body").jsonPrimitive.content)
                            }
                        }
                    }
            existing ?: action().also { reply ->
                val result =
                    buildJsonObject {
                        put("status", reply.receiptStatus)
                        put("body", reply.receipt)
                    }.toString()
                jdbc
                    .prepareStatement(
                        """insert into operation_results(id,user_id,subject,channel,operation,request_hash,result,created_at)
                values (?,?,?,'REST',?,?,?::jsonb,?)""",
                    ).use {
                        it.setObject(1, request.id)
                        it.setObject(2, request.userId)
                        it.setString(3, request.subject)
                        it.setString(4, request.operation)
                        it.setString(5, request.requestHash)
                        it.setString(6, result)
                        it.setObject(7, now.toUtc())
                        it.executeUpdate()
                    }
            }
        }
}
