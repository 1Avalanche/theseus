package theseus.agent.system

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import theseus.agent.texts.Texts

/** Команды владельца разбираются детерминированно; права проверяет входной обработчик по chatId конфига. */
class AdminCommands(
    private val core: SystemCore,
    private val operations: OutgoingOperations,
    private val texts: Texts,
) {
    suspend fun handle(
        input: Incoming,
        botUsername: String,
    ): BotReply {
        val words =
            input.text
                .orEmpty()
                .trim()
                .split(Regex("\\s+"), limit = 3)
        val command = words.first().substringBefore('@')
        return when (command) {
            "/invite" -> createInvite(input, words, botUsername)
            "/users" ->
                renderList(core.request("GET", "/users", null, null), "users") { row ->
                    val user = row.getValue("user").jsonObject
                    texts.get(
                        "admin.user_row",
                        mapOf(
                            "id" to user.value("id"),
                            "name" to user.value("name"),
                            "status" to texts.get("admin.user_status.${user.value("status")}"),
                            "count" to row.value("active_reminders"),
                            "activity" to row.value("last_activity_at"),
                            "label" to row.value("invite_label"),
                        ),
                    )
                }
            "/invites" -> listInvites(words)
            "/revoke", "/restore", "/invite_revoke" -> changeAccess(input, command, words)
            "/stats" -> stats(core.request("GET", "/usage/stats", null, null))
            else -> BotReply(texts.get("admin.help"))
        }
    }

    private suspend fun createInvite(
        input: Incoming,
        words: List<String>,
        botUsername: String,
    ): BotReply {
        val gender = words.getOrNull(1)
        if (gender !in setOf("male", "female", "unspecified")) return BotReply(texts.get("admin.help"))
        val result =
            operations.perform(
                "${input.id}:admin",
                "POST",
                "/invites",
                buildJsonObject {
                    put("gender", gender)
                    words.getOrNull(2)?.let { put("label", it) }
                },
            )
        return if (!result.successful) {
            errorReply(result, texts)
        } else {
            val body = result.body
            val code = body["code"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            BotReply(
                if (code == null) {
                    texts.get("admin.code_unavailable", mapOf("id" to body.value("id")))
                } else {
                    texts.get(
                        "admin.invite",
                        mapOf(
                            "id" to body.value("id"),
                            "expires" to body.value("expires_at"),
                            "link" to "https://t.me/$botUsername?start=$code",
                        ),
                    )
                },
            )
        }
    }

    private suspend fun listInvites(words: List<String>): BotReply {
        val state = words.getOrNull(1) ?: "active"
        if (state !in setOf("active", "used", "expired", "revoked", "all")) return BotReply(texts.get("admin.help"))
        return renderList(core.request("GET", "/invites?state=$state", null, null), "invites") { row ->
            texts.get(
                "admin.invite_row",
                mapOf(
                    "id" to row.value("id"),
                    "state" to texts.get("admin.invite_state.${row.value("state")}"),
                    "gender" to texts.get("admin.gender.${row.value("gender")}"),
                    "label" to row.value("label"),
                    "expires" to row.value("expires_at"),
                ),
            )
        }
    }

    private suspend fun changeAccess(
        input: Incoming,
        command: String,
        words: List<String>,
    ): BotReply {
        val id = words.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 } ?: return BotReply(texts.get("admin.help"))
        val path = if (command == "/invite_revoke") "/invites/$id/revoke" else "/users/$id/${command.drop(1)}"
        val result = operations.perform("${input.id}:admin", "POST", path, JsonObject(emptyMap()))
        return if (result.successful) BotReply(texts.get("admin.updated")) else errorReply(result, texts)
    }

    private fun renderList(
        result: RestReply,
        field: String,
        render: (JsonObject) -> String,
    ): BotReply {
        if (!result.successful) return errorReply(result, texts)
        return BotReply(
            result.body
                .getValue(field)
                .jsonArray
                .map { render(it.jsonObject) }
                .joinToString("\n")
                .ifBlank { texts.get("admin.empty") },
        )
    }

    private fun stats(result: RestReply): BotReply {
        if (!result.successful) return errorReply(result, texts)
        val body = result.body
        val total = body.getValue("total").jsonObject
        return BotReply(
            texts.get(
                "admin.stats",
                mapOf(
                    "from" to body.value("from"),
                    "to" to body.value("to"),
                    "requests" to total.value("requests"),
                    "tokens_in" to total.value("tokens_in"),
                    "tokens_out" to total.value("tokens_out"),
                    "cost_usd" to total.value("cost_usd"),
                    "users" to
                        body
                            .getValue("by_user")
                            .jsonArray
                            .joinToString("\n") { row ->
                                row.jsonObject.let {
                                    texts.get(
                                        "admin.stats_user",
                                        mapOf(
                                            "id" to it.value("user_id"),
                                            "name" to it.value("name"),
                                            "requests" to it.value("requests"),
                                            "cost" to it.value("cost_usd"),
                                        ),
                                    )
                                }
                            }.ifBlank { texts.get("admin.empty") },
                    "models" to
                        body
                            .getValue("by_model")
                            .jsonArray
                            .joinToString("\n") { row ->
                                row.jsonObject.let {
                                    texts.get(
                                        "admin.stats_model",
                                        mapOf(
                                            "model" to it.value("model"),
                                            "requests" to it.value("requests"),
                                            "cost" to it.value("cost_usd"),
                                        ),
                                    )
                                }
                            }.ifBlank { texts.get("admin.empty") },
                ),
            ),
        )
    }

    companion object {
        val commands = setOf("/invite", "/invites", "/invite_revoke", "/users", "/revoke", "/restore", "/stats")
    }
}

internal fun JsonObject.value(key: String): String =
    get(key)
        ?.takeUnless { it == JsonNull }
        ?.jsonPrimitive
        ?.content
        .orEmpty()

internal fun errorReply(
    result: RestReply,
    texts: Texts,
): BotReply {
    val code = result.body["error"]?.jsonObject?.value("code")
    val supported =
        setOf(
            "invalid_arguments",
            "invalid_timezone",
            "invite_not_found",
            "invite_expired",
            "invite_used",
            "invite_revoked",
            "already_registered",
            "not_found",
            "invalid_state",
            "idempotency_conflict",
            "unsupported_state",
        )
    return BotReply(texts.get(if (code in supported) "system.$code" else "reply.failure"))
}
