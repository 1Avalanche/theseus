// Числа в JDBC-вызовах — позиции параметров SQL, не доменные настройки.
@file:Suppress("MagicNumber")

package theseus.agent.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.flywaydb.core.Flyway
import theseus.agent.system.AgentStore
import theseus.agent.system.BotReply
import theseus.agent.system.EventSnapshot
import theseus.agent.system.Outgoing
import theseus.agent.system.RestReply
import theseus.agent.system.StoredEvent
import theseus.agent.system.ZoneProposal
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/** Собственная схема agent, отдельная роль БД. Секреты и содержимое событий — только ciphertext. */
class JdbcAgentStore(
    private val pool: HikariDataSource,
    private val cipher: JournalCipher,
    private val clock: Clock,
) : AgentStore,
    AutoCloseable by pool {
    fun migrate() {
        Flyway
            .configure()
            .dataSource(pool)
            .schemas("agent")
            .defaultSchema("agent")
            .locations("classpath:db/migration/agent")
            .load()
            .migrate()
    }

    override suspend fun beginEvent(snapshot: EventSnapshot): StoredEvent =
        pool.tx { connection ->
            val id = snapshot.input.id
            connection
                .prepareStatement(
                    """insert into incoming_events(id,user_id,chat_id,payload_encrypted,created_at) values (?,?,?,?,?)
            on conflict(id) do nothing""",
                ).use {
                    it.setString(1, id)
                    it.setObject(2, snapshot.user?.id)
                    it.setLong(3, snapshot.input.chatId)
                    it.setString(4, cipher.encrypt("event:$id", Json.encodeToString(snapshot)))
                    it.setObject(5, clock.instant().atOffset(ZoneOffset.UTC))
                    it.executeUpdate()
                }
            connection.prepareStatement("select * from incoming_events where id=?").use {
                it.setString(1, id)
                it.executeQuery().use { row ->
                    check(row.next())
                    event(row, cipher)
                }
            }
        }

    override suspend fun findEvent(id: String): StoredEvent? =
        pool.tx { connection ->
            connection.prepareStatement("select * from incoming_events where id=?").use {
                it.setString(1, id)
                it.executeQuery().use { row -> if (row.next()) event(row, cipher) else null }
            }
        }

    override suspend fun finishEvent(
        id: String,
        reply: BotReply,
    ) = pool.tx { connection ->
        connection.prepareStatement("update incoming_events set reply_encrypted=? where id=? and reply_encrypted is null").use {
            it.setString(1, cipher.encrypt("reply:$id", Json.encodeToString(reply)))
            it.setString(2, id)
            it.executeUpdate()
            Unit
        }
    }

    override suspend fun delivered(id: String) =
        pool.tx { connection ->
            connection.prepareStatement("update incoming_events set delivered_at=? where id=?").use {
                it.setObject(1, clock.instant().atOffset(ZoneOffset.UTC))
                it.setString(2, id)
                it.executeUpdate()
                Unit
            }
        }

    override suspend fun pendingEvents(): List<StoredEvent> =
        pool.tx { connection ->
            connection.prepareStatement("select * from incoming_events where delivered_at is null order by created_at, id").use {
                it.executeQuery().use { row -> buildList { while (row.next()) add(event(row, cipher)) } }
            }
        }

    override suspend fun proposal(userId: Long): ZoneProposal? =
        pool.tx { connection ->
            connection.prepareStatement("select data::text from onboarding_state where user_id=?").use {
                it.setLong(1, userId)
                it.executeQuery().use { row ->
                    if (row.next()) Json.decodeFromString<ZoneProposal>(row.getString(1)) else null
                }
            }
        }

    override suspend fun propose(
        userId: Long,
        proposal: ZoneProposal?,
    ) = pool.tx { connection ->
        if (proposal == null) {
            connection.prepareStatement("delete from onboarding_state where user_id=?").use {
                it.setLong(1, userId)
                it.executeUpdate()
                Unit
            }
        } else {
            connection
                .prepareStatement(
                    """insert into onboarding_state(user_id,step,data,updated_at) values (?,'AWAIT_TIMEZONE_CONFIRM',?::jsonb,?)
            on conflict(user_id) do update set step=excluded.step,data=excluded.data,updated_at=excluded.updated_at""",
                ).use {
                    it.setLong(1, userId)
                    it.setString(2, Json.encodeToString(proposal))
                    it.setObject(3, clock.instant().atOffset(ZoneOffset.UTC))
                    it.executeUpdate()
                    Unit
                }
        }
    }

    override suspend fun beginOperation(operation: Outgoing): Pair<Outgoing, RestReply?> =
        pool.tx { connection ->
            connection
                .prepareStatement(
                    """insert into outgoing_operations(id,event_key,user_id,method,path,request_encrypted,created_at)
            values (?,?,?,?,?,?,?) on conflict(event_key) do nothing""",
                ).use {
                    it.setObject(1, operation.id)
                    it.setString(2, operation.eventKey)
                    it.setObject(3, operation.userId)
                    it.setString(4, operation.method)
                    it.setString(5, operation.path)
                    it.setString(6, cipher.encrypt("request:${operation.id}", operation.body.toString()))
                    it.setObject(7, clock.instant().atOffset(ZoneOffset.UTC))
                    it.executeUpdate()
                }
            connection.prepareStatement("select * from outgoing_operations where event_key=?").use {
                it.setString(1, operation.eventKey)
                it.executeQuery().use { row ->
                    check(row.next())
                    val stored = outgoing(row, cipher)
                    val result =
                        row.getString("response_encrypted")?.let { encrypted ->
                            RestReply(
                                row.getInt("response_status"),
                                Json.parseToJsonElement(cipher.decrypt("response:${stored.id}", encrypted)).jsonObject,
                            )
                        }
                    stored to result
                }
            }
        }

    override suspend fun finishOperation(
        id: UUID,
        reply: RestReply,
    ) = pool.tx { connection ->
        connection
            .prepareStatement(
                """update outgoing_operations set response_status=?,response_encrypted=?,completed_at=?
            where id=? and completed_at is null""",
            ).use {
                it.setInt(1, reply.status)
                it.setString(2, cipher.encrypt("response:$id", reply.body.toString()))
                it.setObject(3, clock.instant().atOffset(ZoneOffset.UTC))
                it.setObject(4, id)
                it.executeUpdate()
                Unit
            }
    }

    override suspend fun pendingOperations(): List<Outgoing> =
        pool.tx { connection ->
            connection.prepareStatement("select * from outgoing_operations where completed_at is null order by created_at,id").use {
                it.executeQuery().use { row -> buildList { while (row.next()) add(outgoing(row, cipher)) } }
            }
        }

    companion object {
        fun connect(
            url: String,
            user: String,
            password: String,
            key: String,
            clock: Clock,
        ): JdbcAgentStore {
            val pool =
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = url
                        username = user
                        this.password = password
                        schema = "agent"
                        maximumPoolSize = 3
                        poolName = "agent"
                    },
                )
            return JdbcAgentStore(pool, JournalCipher(key), clock)
        }
    }
}

private suspend fun <T> HikariDataSource.tx(block: (Connection) -> T): T =
    withContext(Dispatchers.IO) {
        connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                connection.rollback()
                throw e
            }
        }
    }

private fun event(
    row: ResultSet,
    cipher: JournalCipher,
): StoredEvent {
    val id = row.getString("id")
    return StoredEvent(
        Json.decodeFromString(cipher.decrypt("event:$id", row.getString("payload_encrypted"))),
        row.getString("reply_encrypted")?.let { Json.decodeFromString<BotReply>(cipher.decrypt("reply:$id", it)) },
        row.getObject("delivered_at") != null,
    )
}

private fun outgoing(
    row: ResultSet,
    cipher: JournalCipher,
): Outgoing {
    val id = row.getObject("id", UUID::class.java)
    return Outgoing(
        id,
        row.getString("event_key"),
        row.getString("method"),
        row.getString("path"),
        Json.parseToJsonElement(cipher.decrypt("request:$id", row.getString("request_encrypted"))).jsonObject,
        row.getObject("user_id")?.let { (it as Number).toLong() },
    )
}
