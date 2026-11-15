@file:Suppress("MagicNumber") // Позиции JDBC и утверждённые сроки хранения.

package theseus.agent.storage

import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import theseus.agent.dialog.DialogEntry
import theseus.agent.dialog.DialogSession
import theseus.agent.dialog.DialogStore
import theseus.agent.dialog.ListReference
import theseus.agent.llm.Role
import java.time.Instant
import java.time.ZoneOffset

/** История живёт отдельно от бессрочного журнала операций и удаляется по собственному сроку. */
class JdbcDialogStore(
    private val pool: HikariDataSource,
    private val cipher: JournalCipher,
) : DialogStore {
    override suspend fun open(
        userId: Long,
        chatId: Long,
        now: Instant,
    ): DialogSession =
        pool.tx { connection ->
            connection
                .prepareStatement(
                    """update dialog_sessions set closed_at=last_activity_at+interval '4 hours'
                        where user_id=? and closed_at is null and last_activity_at<=?""",
                ).use {
                    it.setLong(1, userId)
                    it.setObject(2, now.minusSeconds(4 * 3600).atOffset(ZoneOffset.UTC))
                    it.executeUpdate()
                }
            val id =
                connection
                    .prepareStatement(
                        """insert into dialog_sessions(user_id,chat_id,started_at,last_activity_at) values (?,?,?,?)
                            on conflict(user_id) where closed_at is null
                            do update set last_activity_at=excluded.last_activity_at returning id""",
                    ).use {
                        it.setLong(1, userId)
                        it.setLong(2, chatId)
                        it.setObject(3, now.atOffset(ZoneOffset.UTC))
                        it.setObject(4, now.atOffset(ZoneOffset.UTC))
                        it.executeQuery().use { row ->
                            check(row.next())
                            row.getLong(1)
                        }
                    }
            connection.prepareStatement("select summary,summary_until_message_id,state::text from dialog_sessions where id=?").use {
                it.setLong(1, id)
                it.executeQuery().use { row ->
                    check(row.next())
                    val summary = row.getString(1)?.let { cipher.decrypt("summary:$id", it) }
                    val until = row.getLong(2)
                    val state = row.getString(3)
                    val refs =
                        Json.parseToJsonElement(state).let { value ->
                            (value as kotlinx.serialization.json.JsonObject)["references"]
                                ?.let {
                                    Json.decodeFromJsonElement(ListSerializer(ListReference.serializer()), it)
                                }.orEmpty()
                        }
                    val entries = entries(connection, userId, id, until)
                    DialogSession(id, summary, entries, refs)
                }
            }
        }

    private fun entries(
        connection: java.sql.Connection,
        userId: Long,
        id: Long,
        until: Long,
    ): List<DialogEntry> =
        connection
            .prepareStatement(
                "select id,role,content,event_id from dialog_messages where session_id=? and id>? order by id",
            ).use { query ->
                query.setLong(1, id)
                query.setLong(2, until)
                query.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val role = rows.getString(2)
                            add(
                                DialogEntry(
                                    rows.getLong(1),
                                    Role.valueOf(role),
                                    cipher.decrypt("dialog:$userId:${rows.getString(4)}:$role", rows.getString(3)),
                                ),
                            )
                        }
                    }
                }
            }

    override suspend fun append(
        sessionId: Long,
        userId: Long,
        eventId: String,
        role: Role,
        content: String,
        now: Instant,
    ) = pool.tx { connection ->
        connection
            .prepareStatement(
                """insert into dialog_messages(session_id,user_id,event_id,role,content,created_at) values (?,?,?,?,?,?)
                    on conflict(user_id,event_id,role) do nothing""",
            ).use {
                it.setLong(1, sessionId)
                it.setLong(2, userId)
                it.setString(3, eventId)
                it.setString(4, role.name)
                it.setString(5, cipher.encrypt("dialog:$userId:$eventId:${role.name}", content))
                it.setObject(6, now.atOffset(ZoneOffset.UTC))
                it.executeUpdate()
                Unit
            }
    }

    override suspend fun summarize(
        sessionId: Long,
        summary: String,
        untilId: Long,
    ) = pool.tx { connection ->
        connection
            .prepareStatement(
                "update dialog_sessions set summary=?,summary_until_message_id=? where id=? and closed_at is null",
            ).use {
                it.setString(1, cipher.encrypt("summary:$sessionId", summary))
                it.setLong(2, untilId)
                it.setLong(3, sessionId)
                it.executeUpdate()
                Unit
            }
    }

    override suspend fun references(
        sessionId: Long,
        references: List<ListReference>,
    ) = pool.tx { connection ->
        connection.prepareStatement("update dialog_sessions set state=?::jsonb where id=? and closed_at is null").use {
            it.setString(
                1,
                kotlinx.serialization.json
                    .buildJsonObject {
                        put("references", Json.encodeToJsonElement(ListSerializer(ListReference.serializer()), references))
                    }.toString(),
            )
            it.setLong(2, sessionId)
            it.executeUpdate()
            Unit
        }
    }

    override suspend fun completeEvent(
        userId: Long,
        eventId: String,
        content: String,
        now: Instant,
    ) = pool.tx { connection ->
        connection
            .prepareStatement(
                """insert into dialog_messages(session_id,user_id,event_id,role,content,created_at)
                select session_id,user_id,event_id,'ASSISTANT',?,? from dialog_messages
                where user_id=? and event_id=? and role='USER'
                on conflict(user_id,event_id,role) do nothing""",
            ).use {
                it.setString(1, cipher.encrypt("dialog:$userId:$eventId:ASSISTANT", content))
                it.setObject(2, now.atOffset(ZoneOffset.UTC))
                it.setLong(3, userId)
                it.setString(4, eventId)
                it.executeUpdate()
                Unit
            }
    }

    override suspend fun cleanup(now: Instant) =
        pool.tx { connection ->
            connection
                .prepareStatement(
                    """update dialog_sessions set closed_at=last_activity_at+interval '4 hours'
                        where closed_at is null and last_activity_at<=?""",
                ).use {
                    it.setObject(1, now.minusSeconds(4 * 3600).atOffset(ZoneOffset.UTC))
                    it.executeUpdate()
                }
            connection.prepareStatement("delete from dialog_messages where created_at<?").use {
                it.setObject(1, now.minusSeconds(30 * 86400).atOffset(ZoneOffset.UTC))
                it.executeUpdate()
            }
            connection.prepareStatement("delete from dialog_sessions where closed_at<?").use {
                it.setObject(1, now.minusSeconds(30 * 86400).atOffset(ZoneOffset.UTC))
                it.executeUpdate()
                Unit
            }
        }
}
