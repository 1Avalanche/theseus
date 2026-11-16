package theseus.agent.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import theseus.agent.core.CoreClient
import theseus.agent.core.CoreUser
import theseus.agent.core.ToolResult
import theseus.agent.system.AgentStore
import theseus.agent.system.BotReply
import theseus.agent.system.Button
import theseus.agent.system.Incoming
import theseus.agent.system.McpOperations
import theseus.agent.system.Outgoing
import theseus.agent.system.RestReply
import theseus.agent.system.value
import theseus.agent.texts.Texts
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

data class ReminderExecution(
    val result: ToolResult?,
    val reply: BotReply? = null,
)

/** Перехват критичных действий до MCP. Модель не может передать метаданные подтверждения или разрешить force. */
@Suppress("TooManyFunctions") // Подготовка, подтверждение и восстановление трёх видов карточек, включая массовое удаление.
class ReminderActions(
    private val core: CoreClient,
    private val store: AgentStore,
    private val operations: McpOperations,
    private val renderer: ReplyRenderer,
    private val texts: Texts,
    private val clock: Clock,
) {
    suspend fun execute(
        user: CoreUser,
        name: String,
        input: JsonObject,
        eventKey: String,
    ): ReminderExecution {
        val args = JsonObject(input - "force")
        val prepared =
            if (name == "delete_reminder" && args.value("scope") == "all") {
                deleteAll(user, args, eventKey)
            } else if (name == "delete_reminder" && args.value("scope") == "selected") {
                deleteSelected(user, args, eventKey)
            } else if (needsSnapshot(name, args)) {
                critical(user, name, args, eventKey)
            } else {
                null
            }
        if (prepared != null) return prepared
        val result =
            if (name in changing) {
                operations.perform(eventKey, user.id, name, args)
            } else {
                core.callTool(user.id, name, args)
            }
        return if (name == "create_reminder" && !result.isError) {
            duplicate(user, args, result, eventKey) ?: ReminderExecution(result)
        } else {
            ReminderExecution(result)
        }
    }

    private fun needsSnapshot(
        name: String,
        args: JsonObject,
    ): Boolean =
        when (name) {
            "delete_reminder" -> true
            "update_reminder" -> args["recurrence"] is JsonObject || "when" in args
            else -> false
        }

    private suspend fun deleteSelected(
        user: CoreUser,
        args: JsonObject,
        eventKey: String,
    ): ReminderExecution {
        if (args.keys != setOf("scope", "reminder_ids")) return error()
        val preview = operations.perform(eventKey, user.id, "delete_reminder", args)
        if (preview.isError) return ReminderExecution(preview)
        val card = Json.parseToJsonElement(preview.text).jsonObject
        if (card.value("status") != "confirmation_required" || card["reminders"] !is JsonArray || card["fingerprint"] !is JsonPrimitive) {
            return error()
        }
        return ReminderExecution(null, propose(user, "delete_reminder", args, card, eventKey))
    }

    private suspend fun deleteAll(
        user: CoreUser,
        args: JsonObject,
        eventKey: String,
    ): ReminderExecution {
        if (args.keys != setOf("scope")) return error()
        val result = core.callTool(user.id, "list_reminders", buildJsonObject { put("include_paused", true) })
        if (result.isError) return ReminderExecution(result)
        val card = Json.parseToJsonElement(result.text).jsonObject
        if (card.value("unsupported_count").toIntOrNull()?.let { it > 0 } == true) {
            return ReminderExecution(null, BotReply(texts.get("reply.error.unsupported_state")))
        }
        val rows = card["reminders"] as? JsonArray ?: return error()
        if (rows.isEmpty()) return ReminderExecution(null, BotReply(texts.get("reminders.empty")))
        if (card["fingerprint"] !is JsonPrimitive) return error()
        return ReminderExecution(null, propose(user, "delete_reminder", args, card, eventKey))
    }

    @Suppress("ReturnCount") // Ранние проверки не допускают неподтверждённое критичное действие в основной путь.
    private suspend fun critical(
        user: CoreUser,
        name: String,
        args: JsonObject,
        eventKey: String,
    ): ReminderExecution? {
        val id = args["reminder_id"] ?: return error()
        val target = core.callTool(user.id, "get_reminder", buildJsonObject { put("reminder_id", id) })
        if (target.isError) return ReminderExecution(target)
        val card = Json.parseToJsonElement(target.text).jsonObject
        val reset =
            name == "update_reminder" &&
                args.value("scope") != "occurrence" &&
                (card.value("excluded_count") != "0" || card.value("pending_children_count") != "0")
        if (reset) {
            val attempted = operations.perform(eventKey, user.id, name, args)
            val data = Json.parseToJsonElement(attempted.text).jsonObject
            val code = (data["error"] as? JsonObject)?.value("code")
            if (code != "confirmation_required") return ReminderExecution(attempted)
        }
        if (name == "delete_reminder" || reset) {
            val exact = args.toMutableMap()
            if (args.value("scope") == "occurrence" && "occurrence_local" !in args) {
                val occurrence = card["next_occurrence_local"] as? JsonPrimitive ?: return error()
                if (!occurrence.isString) return error()
                exact["occurrence_local"] = occurrence
            }
            return ReminderExecution(null, propose(user, name, JsonObject(exact), card, eventKey))
        }

        return null
    }

    private suspend fun duplicate(
        user: CoreUser,
        args: JsonObject,
        result: ToolResult,
        eventKey: String,
    ): ReminderExecution? {
        val data = Json.parseToJsonElement(result.text).jsonObject
        if (data.value("status") == "possible_duplicate") {
            val proposed = data["proposed"] as? JsonObject ?: return error()
            val schedule = proposed["schedule"] as? JsonObject ?: return error()
            val frozen = args.toMutableMap()
            if (schedule["recurrence"] is JsonObject) {
                frozen["recurrence"] = schedule.getValue("recurrence")
            } else {
                val local = schedule.value("at_local")
                frozen["when"] =
                    buildJsonObject {
                        put("date", local.substringBefore('T'))
                        put("time", local.substringAfter('T'))
                    }
            }
            frozen["force"] = JsonPrimitive(true)
            return ReminderExecution(null, propose(user, "create_reminder", JsonObject(frozen), data, eventKey))
        }

        return null
    }

    private suspend fun propose(
        user: CoreUser,
        name: String,
        args: JsonObject,
        data: JsonObject,
        eventKey: String,
    ): BotReply {
        val body =
            buildJsonObject {
                put("name", name)
                put("operation_id", UUID.randomUUID().toString())
                put("arguments", args)
                put(
                    "confirmation",
                    buildJsonObject {
                        put("fingerprint", data.value("fingerprint"))
                        put("created_at", clock.instant().toString())
                    },
                )
                put("card", data)
            }
        val (stored, _) = store.beginOperation(Outgoing(UUID.randomUUID(), "$eventKey:card", "CONFIRM", name, body, user.id))
        return renderCard(user, stored)
    }

    private fun renderCard(
        user: CoreUser,
        stored: Outgoing,
    ): BotReply {
        val saved = stored.body
        return BotReply(
            renderer.confirmation(
                saved.value("name"),
                saved.getValue("arguments").jsonObject,
                saved.getValue("card").jsonObject,
                ZoneId.of(requireNotNull(user.timezone)),
                clock.instant(),
            ),
            listOf(
                listOf(
                    Button(texts.get(acceptKey(saved.value("name"), saved.getValue("arguments").jsonObject)), "rem:yes:${stored.id}"),
                    Button(texts.get("confirmation.cancel"), "rem:no:${stored.id}"),
                ),
            ),
        )
    }

    private fun acceptKey(
        name: String,
        args: JsonObject,
    ): String =
        when {
            name == "create_reminder" -> "confirmation.create_anyway"
            name == "delete_reminder" && args.value("scope") == "all" -> "confirmation.delete_all_button"
            name == "delete_reminder" && args.value("scope") == "selected" -> "confirmation.delete_selected_button"
            name == "delete_reminder" -> "confirmation.delete_button"
            else -> "confirmation.accept"
        }

    @Suppress("ReturnCount", "CyclomaticComplexMethod") // Проверки владельца, ключа и неопределённого исхода выполняются до действия.
    suspend fun callback(
        input: Incoming,
        user: CoreUser,
    ): BotReply? {
        val data = input.callback ?: return null
        if (!data.startsWith("rem:")) return null
        val parts = data.split(':')
        if (parts.size != CALLBACK_PARTS || parts[1] !in setOf("yes", "no")) return stale()
        val id = runCatching { UUID.fromString(parts[2]) }.getOrNull() ?: return stale()
        val (card, previous) = store.findOperation(id) ?: return stale()
        if (card.method != "CONFIRM" || card.userId != user.id || !user.isActive) return stale()
        previous?.let {
            return BotReply(texts.get("confirmation.processed"), clearKeyboardMessageId = input.callbackMessageId)
        }
        val started = store.eventOperations("confirmation:$id").any { it.first.method == "MCP" }
        if (parts[1] == "no" && !started) {
            return BotReply(texts.get("confirmation.cancelled"), clearKeyboardMessageId = input.callbackMessageId).also {
                store.finishOperation(
                    id,
                    RestReply(
                        SUCCESS,
                        buildJsonObject {
                            put("text", it.text)
                            put("resolution", "CANCELLED")
                        },
                    ),
                )
            }
        }
        val body = card.body
        val result =
            operations.perform(
                "confirmation:$id:execute",
                user.id,
                body.value("name"),
                body.getValue("arguments").jsonObject,
                body.getValue("confirmation").jsonObject,
                UUID.fromString(body.value("operation_id")),
            )
        val reply =
            if (result.isError) {
                stale()
            } else {
                BotReply(
                    renderer.render(body.value("name"), result.text, ZoneId.of(requireNotNull(user.timezone)), clock.instant())
                        ?: texts.get("reply.unconfirmed"),
                )
            }
        store.finishOperation(
            id,
            RestReply(
                SUCCESS,
                buildJsonObject {
                    put("text", reply.text)
                    put("resolution", if (result.isError) "EXPIRED" else "CONFIRMED")
                },
            ),
        )
        return reply.copy(clearKeyboardMessageId = input.callbackMessageId)
    }

    @Suppress("CyclomaticComplexMethod") // Восстановление всех исходов сообщения без повторного LLM и потери карточек.
    suspend fun recoverDialogue(
        eventKey: String,
        user: CoreUser,
    ): BotReply? {
        operations.recoverEvent(eventKey)
        val stored = store.eventOperations(eventKey)
        val cardSources = stored.filter { it.first.method == "CONFIRM" }.map { it.first.eventKey.removeSuffix(":card") }.toSet()
        val replies =
            stored.mapNotNull { (operation, result) ->
                when (operation.method) {
                    "CONFIRM" -> result?.let { BotReply(it.body.value("text")) } ?: renderCard(user, operation)
                    "MCP" -> {
                        if (result == null) throw theseus.agent.system.UncertainMcpException()
                        if (operation.eventKey in cardSources) return@mapNotNull null
                        val execution =
                            if (result.body.value("status") in setOf("possible_duplicate", "confirmation_required") ||
                                (result.body["error"] as? JsonObject)?.value("code") == "confirmation_required"
                            ) {
                                execute(user, operation.path, operation.body.getValue("arguments").jsonObject, operation.eventKey)
                            } else {
                                ReminderExecution(ToolResult(result.body.toString(), !result.successful))
                            }
                        execution.reply ?: execution.result?.let {
                            val rendered =
                                if (it.isError) {
                                    null
                                } else {
                                    renderer.render(operation.path, it.text, ZoneId.of(requireNotNull(user.timezone)), clock.instant())
                                }
                            BotReply(rendered ?: texts.get("reply.unconfirmed"))
                        }
                    }
                    else -> null
                }
            }
        if (replies.isEmpty()) return null
        return BotReply(replies.joinToString("\n") { it.text }, replies.flatMap { it.buttons })
    }

    private fun stale() = BotReply(texts.get("confirmation.stale"))

    private fun error() = ReminderExecution(ToolResult(texts.get("tool.invalid_json"), true))

    companion object {
        private const val SUCCESS = 200
        private const val CALLBACK_PARTS = 3
        private val changing =
            setOf("create_reminder", "update_reminder", "delete_reminder", "snooze_reminder", "pause_reminder", "resume_reminder")
    }
}
