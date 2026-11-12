package theseus.agent.system

import java.util.UUID

class InMemoryAgentStore : AgentStore {
    val events = linkedMapOf<String, StoredEvent>()
    val proposals = mutableMapOf<Long, ZoneProposal>()
    val operations = linkedMapOf<String, Pair<Outgoing, RestReply?>>()

    override suspend fun findEvent(id: String) = events[id]

    override suspend fun beginEvent(snapshot: EventSnapshot): StoredEvent =
        events.getOrPut(snapshot.input.id) { StoredEvent(snapshot, null, false) }

    override suspend fun finishEvent(
        id: String,
        reply: BotReply,
    ) {
        events[id] = events.getValue(id).copy(reply = reply)
    }

    override suspend fun delivered(id: String) {
        events[id] = events.getValue(id).copy(delivered = true)
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

    override suspend fun beginOperation(operation: Outgoing) = operations.getOrPut(operation.eventKey) { operation to null }

    override suspend fun finishOperation(
        id: UUID,
        reply: RestReply,
    ) {
        val key = operations.entries.single { it.value.first.id == id }.key
        operations[key] = operations.getValue(key).first to reply
    }

    override suspend fun pendingOperations() = operations.values.filter { it.second == null }.map { it.first }
}
