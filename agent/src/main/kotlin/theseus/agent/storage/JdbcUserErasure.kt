package theseus.agent.storage

import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import theseus.agent.system.UserErasureStore
import java.util.UUID

/** Транзакционные операции очистки журнала; содержательные записи и бессодержательные ключи разделены. */
internal class JdbcUserErasure(
    private val pool: HikariDataSource,
    private val cipher: JournalCipher,
) : UserErasureStore {
    override suspend fun eraseUser(
        userId: Long,
        chatId: Long,
        inviteIds: List<Long>,
    ) = pool.tx { connection ->
        AgentErasure(connection, cipher).erase(userId, chatId, inviteIds)
    }

    override suspend fun redactDeletion(
        eventKey: String,
        userId: Long,
    ) = pool.tx { connection ->
        connection.prepareStatement("select id from outgoing_operations where event_key=?").use {
            it.setString(1, eventKey)
            it.executeQuery().use { row ->
                check(row.next())
                val id = row.getObject(1, UUID::class.java)
                val body =
                    buildJsonObject {
                        put("id", userId)
                        put("deleted", true)
                    }
                connection.prepareStatement("update outgoing_operations set response_encrypted=? where id=?").use { update ->
                    update.setString(1, cipher.encrypt("response:$id", body.toString()))
                    update.setObject(2, id)
                    update.executeUpdate()
                    Unit
                }
            }
        }
    }
}
