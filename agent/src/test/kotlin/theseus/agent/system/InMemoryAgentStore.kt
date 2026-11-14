package theseus.agent.system

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

class InMemoryAgentStore : AgentStore {
    val events = linkedMapOf<String, StoredEvent>()
    val proposals = mutableMapOf<Long, ZoneProposal>()
    val operations = linkedMapOf<String, Pair<Outgoing, RestReply?>>()
    private val erasedEvents = mutableSetOf<String>()
    private val erasedOperations = mutableSetOf<String>()

    override suspend fun findEvent(id: String) =
        if (id in erasedEvents) StoredEvent(EventSnapshot(Incoming(id, 0), null, null), BotReply(""), true) else events[id]

    override suspend fun beginEvent(snapshot: EventSnapshot): StoredEvent =
        findEvent(snapshot.input.id) ?: StoredEvent(snapshot, null, false).also { events[snapshot.input.id] = it }

    override suspend fun finishEvent(
        id: String,
        reply: BotReply,
    ) {
        events[id]?.let { events[id] = it.copy(reply = reply) }
    }

    override suspend fun delivered(id: String) {
        events[id]?.let { events[id] = it.copy(delivered = true) }
    }

    override suspend fun pendingEvents() = events.values.filter { !it.delivered }

    override suspend fun proposal(userId: Long) = proposals[userId]

    override suspend fun propose(
        userId: Long,
        proposal: ZoneProposal?,
    ) {
        if (proposal ==
            null
        ) {
            proposals.remove(userId)
        } else {
            proposals[userId] = proposal
        }
    }

    override suspend fun beginOperation(operation: Outgoing): Pair<Outgoing, RestReply?> =
        if (operation.eventKey in erasedOperations) {
            operation to RestReply(410, Json.parseToJsonElement("""{"error":{"code":"user_erased"}}""").jsonObject)
        } else {
            operations.getOrPut(operation.eventKey) { operation to null }
        }

    override suspend fun finishOperation(
        id: UUID,
        reply: RestReply,
    ) {
        val key = operations.entries.singleOrNull { it.value.first.id == id }?.key ?: return
        operations[key] = operations.getValue(key).first to reply
    }

    override suspend fun pendingOperations() = operations.values.filter { it.second == null }.map { it.first }

    override suspend fun eraseUser(
        userId: Long,
        chatId: Long,
        inviteIds: List<Long>,
    ) {
        val ids = events.values.filter { it.snapshot.user?.id == userId || it.snapshot.input.chatId == chatId }.map { it.snapshot.input.id }
        erasedEvents += ids
        ids.forEach(events::remove)
        val keys =
            operations.values
                .map { it.first }
                .filter {
                    it.userId == userId || it.body.value("chat_id") == chatId.toString() || ids.any { id -> it.eventKey.startsWith("$id:") }
                }.map { it.eventKey }
        erasedOperations += keys
        keys.forEach(operations::remove)
        proposals.remove(userId)
    }

    override suspend fun redactDeletion(
        eventKey: String,
        userId: Long,
    ) {
        val previous = operations.getValue(eventKey)
        operations[eventKey] = previous.first to
            RestReply(
                200,
                buildJsonObject {
                    put("id", userId)
                    put("deleted", true)
                },
            )
    }
}
