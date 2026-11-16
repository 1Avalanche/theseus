package theseus.agent.system

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import theseus.agent.dialog.DialogStore
import theseus.agent.dialog.ReminderReplyTarget
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

/** Привязывает фактически отправленный ответ к единственной подтверждённой цели из журнала MCP. */
@Suppress("ReturnCount") // Неполные и неоднозначные данные отбрасываются до записи ссылки.
class ReminderReplyLinks(
    private val store: AgentStore,
    private val dialogs: DialogStore,
    private val clock: Clock,
) {
    suspend fun sent(
        eventId: String,
        chatId: Long,
        messageId: Long,
        reply: BotReply,
    ) {
        if (reply.buttons.isNotEmpty() || reply.text.isBlank()) return
        val event = store.findEvent(eventId) ?: return
        val user = event.snapshot.user ?: return
        if (user.chatId != chatId || event.snapshot.input.chatId != chatId) return
        val source = source(event, user.id) ?: return
        val target = resolvedTarget(user.id, store.eventOperations(source)) ?: return
        dialogs.rememberReply(user.id, messageId, target, clock.instant())
    }

    private fun resolvedTarget(
        userId: Long,
        operations: List<Pair<Outgoing, RestReply?>>,
    ): ReminderReplyTarget? {
        if (operations.any { it.first.method == "CONFIRM" }) return null
        val calls = operations.filter { it.first.method == "MCP" }
        if (calls.isEmpty()) return null
        val targets =
            calls
                .map { (operation, result) ->
                    if (operation.userId != userId || operation.path !in TARGET_TOOLS || result?.successful != true) return null
                    target(result.body) ?: return null
                }.distinct()
        return targets.singleOrNull()
    }

    private suspend fun source(
        event: StoredEvent,
        userId: Long,
    ): String? {
        val callback = event.snapshot.input.callback ?: return event.snapshot.input.id
        if (!callback.startsWith("rem:yes:")) return null
        val id = runCatching { UUID.fromString(callback.removePrefix("rem:yes:")) }.getOrNull() ?: return null
        val (card, result) = store.findOperation(id) ?: return null
        if (card.method != "CONFIRM" || card.userId != userId || result?.body?.value("resolution") != "CONFIRMED") return null
        return "confirmation:$id"
    }

    private fun target(result: JsonObject): ReminderReplyTarget? {
        if (result.value("status") !in SUCCESS_STATUSES) return null
        val reminder = result["reminder"] as? JsonObject ?: return null
        val id = (reminder["id"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 } ?: return null
        val kind = (reminder["kind"] as? JsonPrimitive)?.contentOrNull ?: return null
        val timezone = (reminder["timezone"] as? JsonPrimitive)?.contentOrNull ?: return null
        if (kind !in setOf("one_time", "recurring") || runCatching { ZoneId.of(timezone) }.isFailure) return null
        return ReminderReplyTarget(id, kind, timezone)
    }

    companion object {
        private val TARGET_TOOLS = setOf("create_reminder", "update_reminder", "snooze_reminder", "pause_reminder", "resume_reminder")
        private val SUCCESS_STATUSES = setOf("created", "updated", "snoozed", "paused", "resumed")
    }
}
