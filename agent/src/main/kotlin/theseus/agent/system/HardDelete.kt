package theseus.agent.system

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import theseus.agent.texts.Texts

/** Восстанавливаемая очистка двух схем. Core остаётся заблокированным до успешной очистки agent. */
class HardDelete(
    private val core: SystemCore,
    private val store: AgentStore,
    private val operations: OutgoingOperations,
    private val locks: ChatLocks,
    private val texts: Texts,
) {
    suspend fun handle(
        input: Incoming,
        ownerId: Long,
    ): BotReply {
        val words =
            input.text
                .orEmpty()
                .trim()
                .split(Regex("\\s+"))
        val id = words.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
        if (words.size != 2 || id == null) return BotReply(texts.get("hard_delete.usage"))
        if (id == ownerId) return BotReply(texts.get("hard_delete.admin"))
        val key = "${input.id}:delete-prepare"
        val prepared = operations.perform(key, "POST", "/users/$id/hard-delete", JsonObject(emptyMap()))
        if (!prepared.successful) return errorReply(prepared, texts)
        if (prepared.body.value("deleted") == "true") return done(id)
        val chatId =
            prepared.body
                .getValue("chat_id")
                .jsonPrimitive.long
        val invites =
            prepared.body
                .getValue("invite_ids")
                .jsonArray
                .map { it.jsonPrimitive.long }
        return locks.withChat(chatId) {
            val current = core.request("GET", "/users/by-chat/$chatId", null, null)
            if (current.successful && current.body.value("id") == id.toString()) {
                if (current.body.value("status") != "revoked") return@withChat BotReply(texts.get("hard_delete.interrupted"))
                store.eraseUser(id, chatId, invites)
            } else {
                // После потери ответа complete пользователь мог уже зарегистрироваться заново.
                check(current.successful || current.status == NOT_FOUND) { "Не удалось проверить состояние удаляемого пользователя" }
            }
            val result =
                operations.perform(
                    "${input.id}:delete-complete",
                    "POST",
                    "/users/$id/hard-delete/complete",
                    JsonObject(emptyMap()),
                )
            if (!result.successful) return@withChat errorReply(result, texts)
            store.redactDeletion(key, id)
            done(id)
        }
    }

    private fun done(id: Long) = BotReply(texts.get("hard_delete.done", mapOf("id" to id.toString())))

    companion object {
        private const val NOT_FOUND = 404
    }
}
