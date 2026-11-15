// Позиции параметров SQL, не доменные настройки.
@file:Suppress("MagicNumber")

package theseus.agent.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import theseus.agent.system.BotReply
import theseus.agent.system.EventSnapshot
import theseus.agent.system.Incoming
import theseus.agent.system.Outgoing
import theseus.agent.system.StoredEvent
import theseus.agent.system.value
import java.security.MessageDigest
import java.sql.Connection

/** Очистка своей схемы одной транзакцией; вызывается под общей блокировкой чата. */
internal class AgentErasure(
    private val connection: Connection,
    private val cipher: JournalCipher,
) {
    fun erase(
        userId: Long,
        chatId: Long,
        inviteIds: List<Long>,
    ) {
        val affectedOperations = findOperations(userId, chatId, inviteIds)
        connection.prepareStatement("delete from dialog_sessions where user_id=? or chat_id=?").use {
            it.setLong(1, userId)
            it.setLong(2, chatId)
            it.executeUpdate()
        }
        val events = findEvents(userId, chatId, affectedOperations)
        val operationKeys = findRelatedOperations(affectedOperations, events)
        events.forEach { eraseRecord("EVENT", "incoming_events", "id", it) }
        operationKeys.forEach { eraseRecord("OPERATION", "outgoing_operations", "event_key", it) }
        connection.prepareStatement("delete from onboarding_state where user_id=?").use {
            it.setLong(1, userId)
            it.executeUpdate()
        }
    }

    private fun findOperations(
        userId: Long,
        chatId: Long,
        inviteIds: List<Long>,
    ): List<String> =
        connection.prepareStatement("select * from outgoing_operations").use {
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val operation = outgoing(rows, cipher)
                        val result =
                            rows.getString("response_encrypted")?.let { encrypted ->
                                Json.parseToJsonElement(cipher.decrypt("response:${operation.id}", encrypted)).jsonObject
                            }
                        if (belongs(operation, result, userId, chatId, inviteIds)) add(operation.eventKey)
                    }
                }
            }
        }

    private fun findEvents(
        userId: Long,
        chatId: Long,
        affectedOperations: List<String>,
    ): List<String> =
        connection.prepareStatement("select * from incoming_events").use {
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val snapshot = event(rows, cipher).snapshot
                        val command =
                            snapshot.input.text
                                .orEmpty()
                                .trim()
                                .takeWhile { !it.isWhitespace() }
                                .substringBefore('@')
                        val cachedList = command in setOf("/users", "/stats", "/invites")
                        val related = affectedOperations.any { key -> key.startsWith("${snapshot.input.id}:") }
                        val target = snapshot.user?.id == userId || snapshot.input.chatId == chatId
                        if (target || cachedList || related) {
                            add(snapshot.input.id)
                        }
                    }
                }
            }
        }

    private fun findRelatedOperations(
        affectedOperations: List<String>,
        events: List<String>,
    ): List<String> =
        connection.prepareStatement("select event_key from outgoing_operations").use {
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val key = rows.getString(1)
                        if (key in affectedOperations || events.any { key.startsWith("$it:") }) add(key)
                    }
                }
            }
        }

    private fun belongs(
        operation: Outgoing,
        result: JsonObject?,
        userId: Long,
        chatId: Long,
        invites: List<Long>,
    ): Boolean {
        val profilePaths = setOf("/users/$userId", "/users/$userId/revoke", "/users/$userId/restore")
        val user = operation.userId == userId || operation.path in profilePaths || operation.body.value("user_id") == userId.toString()
        val activation = operation.body.value("chat_id") == chatId.toString()
        val invite = operation.path == "/invites" && result?.value("id") in invites.map(Long::toString)
        return user || activation || invite || invites.any { operation.path == "/invites/$it/revoke" }
    }

    private fun eraseRecord(
        kind: String,
        table: String,
        column: String,
        key: String,
    ) {
        connection.prepareStatement("insert into erased_journal(kind,key_hash) values (?,?) on conflict do nothing").use {
            it.setString(1, kind)
            it.setString(2, journalKeyHash(key))
            it.executeUpdate()
        }
        connection.prepareStatement("delete from $table where $column=?").use {
            it.setString(1, key)
            it.executeUpdate()
        }
    }
}

internal fun Connection.isErased(
    kind: String,
    key: String,
): Boolean =
    prepareStatement("select 1 from erased_journal where kind=? and key_hash=?").use {
        it.setString(1, kind)
        it.setString(2, journalKeyHash(key))
        it.executeQuery().use { row -> row.next() }
    }

internal fun erasedEvent(id: String) = StoredEvent(EventSnapshot(Incoming(id, 0), null, null), BotReply(""), true)

private fun journalKeyHash(key: String) =
    MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
