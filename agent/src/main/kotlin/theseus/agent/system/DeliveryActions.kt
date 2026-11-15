package theseus.agent.system

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import theseus.agent.core.CoreUser
import theseus.agent.texts.Texts

/** Кнопки уведомлений вызывают системный REST без модели; повторы используют прежний UUID журнала. */
class DeliveryActions(
    private val operations: OutgoingOperations,
    private val texts: Texts,
) {
    suspend fun callback(
        input: Incoming,
        user: CoreUser,
    ): BotReply {
        val parts = input.callback.orEmpty().split(':')
        val id = parts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
        if (parts.first() != "snz" || id == null) return BotReply(texts.get("reply.snooze_unavailable"))
        if (parts.size != CALLBACK_PARTS || parts[2] !in setOf("15m", "1h", "tmr")) {
            return BotReply(texts.get("reply.snooze_unavailable"))
        }
        val result =
            operations.perform(
                "${input.id}:snooze",
                "POST",
                "/deliveries/$id/snooze",
                buildJsonObject {
                    put("user_id", user.id)
                    put("option", parts[2])
                },
                user.id,
            )
        if (!result.successful) return BotReply(texts.get("reply.snooze_unavailable"))
        val timezone =
            result.body
                .getValue("timezone")
                .jsonPrimitive.content
        val zone = if (timezone == user.timezone) "" else texts.get("reminders.zone", mapOf("zone" to timezone))
        return BotReply(
            texts.get(
                "reply.snooze_button",
                mapOf(
                    "when" to
                        result.body
                            .getValue("snoozed_until_local")
                            .jsonPrimitive.content
                            .replace('T', ' '),
                    "zone" to zone,
                ),
            ),
            clearKeyboardMessageId = input.callbackMessageId,
        )
    }

    companion object {
        private const val CALLBACK_PARTS = 3
    }
}
