package theseus.core.adapters.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import theseus.core.application.AccountsService
import theseus.core.application.OperationReply
import theseus.core.application.OperationRequest
import theseus.core.application.Operations
import theseus.core.domain.user.AccountException
import theseus.core.domain.user.Invite
import theseus.core.domain.user.Usage
import theseus.core.domain.user.UserStatus
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Тонкий REST-адаптер системных сценариев; все POST/PATCH проходят через атомарный реестр. */
class AccountsRoutes(
    private val accounts: AccountsService,
    private val operations: Operations,
    private val clock: Clock,
) {
    fun install(route: Route) {
        userMutations(route)
        inviteRoutes(route)
        userList(route)
        usageMutation(route)
        usageStats(route)
        quota(route)
    }

    private fun userMutations(route: Route) =
        with(route) {
            post("/internal/v1/users/activate") {
                mutate(call, "POST /users/activate", null, setOf("chat_id", "invite_code"), authorize = { body ->
                    val current = accounts.users().firstOrNull { it.user.chatId == body.long("chat_id") }?.user
                    if (current?.status == UserStatus.REVOKED) throw AccountException("invalid_state")
                }) { body ->
                    val (user, invite) = accounts.activate(body.long("chat_id"), body.string("invite_code"))
                    reply(
                        HttpStatusCode.Created.value,
                        buildJsonObject {
                            put("user", restJson.encodeToJsonElement(UserDto.serializer(), UserDto.of(user)))
                            put(
                                "invite",
                                buildJsonObject {
                                    put("id", invite.id)
                                    put("label", invite.label?.let(::JsonPrimitive) ?: JsonNull)
                                },
                            )
                        },
                    )
                }
            }
            patch("/internal/v1/users/{id}") {
                val id = call.validId() ?: return@patch
                mutate(
                    call,
                    "PATCH /users/$id",
                    id,
                    setOf("name", "timezone", "gender"),
                    authorize = { accounts.requireAvailable(id) },
                ) { body ->
                    if (body.isEmpty()) throw AccountException("invalid_arguments")
                    userReply(
                        accounts.update(
                            id,
                            body.optionalString("name"),
                            body.optionalString("timezone"),
                            body.optionalString("gender")?.gender(),
                        ),
                    )
                }
            }
            for (command in listOf("revoke", "restore")) {
                post("/internal/v1/users/{id}/$command") {
                    val id = call.validId() ?: return@post
                    mutate(call, "POST /users/$id/$command", id, emptySet()) { userReply(accounts.access(id, command == "restore")) }
                }
            }
        }

    private fun inviteRoutes(route: Route) =
        with(route) {
            post("/internal/v1/invites") {
                mutate(call, "POST /invites", null, setOf("gender", "label")) { body ->
                    val (invite, code) = accounts.createInvite(body.string("gender").gender(), body.nullableString("label"))
                    val public =
                        inviteJson(invite, clock.instant()).toMutableMap().apply {
                            remove("state")
                            remove("used_by_user_id")
                            remove("created_at")
                        }
                    val receipt = JsonObject(public + mapOf("code" to JsonNull, "code_unavailable" to JsonPrimitive(true)))
                    OperationReply(
                        HttpStatusCode.Created.value,
                        JsonObject(public + ("code" to JsonPrimitive(code))).toString(),
                        receipt.toString(),
                        HttpStatusCode.OK.value,
                    )
                }
            }
            post("/internal/v1/invites/{id}/revoke") {
                val id = call.validId() ?: return@post
                mutate(call, "POST /invites/$id/revoke", null, emptySet()) {
                    reply(HttpStatusCode.OK.value, inviteJson(accounts.revokeInvite(id), clock.instant()))
                }
            }
            get("/internal/v1/invites") {
                read(call) {
                    buildJsonObject {
                        put(
                            "invites",
                            JsonArray(
                                accounts.invites(call.request.queryParameters["state"] ?: "active").map { inviteJson(it, clock.instant()) },
                            ),
                        )
                    }
                }
            }
        }

    private fun userList(route: Route) =
        with(route) {
            get("/internal/v1/users") {
                read(call) {
                    buildJsonObject {
                        put(
                            "users",
                            JsonArray(
                                accounts.users().map { summary ->
                                    buildJsonObject {
                                        put("user", restJson.encodeToJsonElement(UserDto.serializer(), UserDto.of(summary.user)))
                                        put("invite_label", summary.inviteLabel?.let(::JsonPrimitive) ?: JsonNull)
                                        put("active_reminders", summary.activeReminders)
                                        put("last_activity_at", summary.lastActivityAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                                    }
                                },
                            ),
                        )
                    }
                }
            }
        }

    private fun usageMutation(route: Route) =
        with(route) {
            post("/internal/v1/usage") {
                mutate(
                    call,
                    "POST /usage",
                    null,
                    setOf(
                        "user_id",
                        "role",
                        "model",
                        "generation_id",
                        "tokens_in",
                        "tokens_out",
                        "tokens_cached",
                        "cost_usd",
                        "latency_ms",
                    ),
                    authorize = { accounts.requireAvailable(it.long("user_id")) },
                ) { body ->
                    val usage =
                        Usage(
                            body.long("user_id"),
                            body.string("role"),
                            body.string("model"),
                            body.nullableString("generation_id"),
                            body.int("tokens_in"),
                            body.int("tokens_out"),
                            if ("tokens_cached" in body) body.int("tokens_cached") else 0,
                            body.string("cost_usd").toBigDecimal(),
                            body["latency_ms"]
                                ?.takeUnless { it == JsonNull }
                                ?.jsonPrimitive
                                ?.content
                                ?.toInt(),
                            clock.instant(),
                        )
                    reply(HttpStatusCode.Created.value, buildJsonObject { put("id", accounts.recordUsage(usage)) })
                }
            }
        }

    private fun usageStats(route: Route) =
        with(route) {
            get("/internal/v1/usage/stats") {
                read(call) {
                    val month =
                        clock
                            .instant()
                            .atZone(ZoneOffset.UTC)
                            .withDayOfMonth(1)
                            .toLocalDate()
                            .atStartOfDay(ZoneOffset.UTC)
                    val from = call.request.queryParameters["from"]?.let(Instant::parse) ?: month.toInstant()
                    val to = call.request.queryParameters["to"]?.let(Instant::parse) ?: month.plusMonths(1).toInstant()
                    val usage = accounts.usage(from, to)
                    val names = accounts.users().associate { it.user.id to it.user.name }
                    buildJsonObject {
                        put("from", from.toString())
                        put("to", to.toString())
                        put("total", totals(usage))
                        put(
                            "by_user",
                            JsonArray(
                                usage.groupBy { it.userId }.map { (id, rows) ->
                                    JsonObject(
                                        totals(rows) +
                                            mapOf("user_id" to JsonPrimitive(id), "name" to (names[id]?.let(::JsonPrimitive) ?: JsonNull)),
                                    )
                                },
                            ),
                        )
                        put(
                            "by_model",
                            JsonArray(
                                usage.groupBy { it.model }.map { (model, rows) ->
                                    JsonObject(totals(rows) + ("model" to JsonPrimitive(model)))
                                },
                            ),
                        )
                    }
                }
            }
        }

    private fun quota(route: Route) =
        with(route) {
            get("/internal/v1/users/{id}/quota") {
                read(call) {
                    val id = call.id()
                    accounts.requireAvailable(id)
                    val from =
                        clock
                            .instant()
                            .atZone(ZoneOffset.UTC)
                            .withDayOfMonth(1)
                            .toLocalDate()
                            .atStartOfDay(ZoneOffset.UTC)
                    val usage = accounts.usage(from.toInstant(), from.plusMonths(1).toInstant()).filter { it.userId == id }
                    buildJsonObject {
                        put("enabled", false)
                        put("limit_usd", JsonNull)
                        put("exceeded", false)
                        put("period_start", from.toInstant().toString())
                        put("used_usd", usage.sumOf { it.costUsd }.toPlainString())
                    }
                }
            }
        }

    private suspend fun mutate(
        call: ApplicationCall,
        operation: String,
        userId: Long?,
        allowed: Set<String>,
        authorize: suspend (JsonObject) -> Unit = {},
        action: suspend (JsonObject) -> OperationReply,
    ) {
        val result =
            safe {
                val rawKey = call.request.headers["Idempotency-Key"] ?: throw AccountException("invalid_arguments")
                val key = UUID.fromString(rawKey)
                validArgument(key.toString().equals(rawKey, true))
                val text = call.receiveText()
                val body = if (text.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(text).jsonObject
                validArgument((body.keys - allowed).isEmpty())
                val hash = AccountsService.hashCode("agent\n$operation\n${canonical(body)}")
                operations.execute(
                    OperationRequest(
                        key,
                        "agent",
                        operation,
                        hash,
                        userId ?: body["user_id"]?.jsonPrimitive?.longOrNull,
                    ),
                    clock.instant(),
                    {
                        authorize(body)
                    },
                ) {
                    safe { action(body) }
                }
            }
        call.respondText(result.body, io.ktor.http.ContentType.Application.Json, HttpStatusCode.fromValue(result.status))
    }

    private suspend fun read(
        call: ApplicationCall,
        action: suspend () -> JsonObject,
    ) {
        val result = safe { reply(HttpStatusCode.OK.value, action()) }
        call.respondText(result.body, io.ktor.http.ContentType.Application.Json, HttpStatusCode.fromValue(result.status))
    }

    private suspend fun safe(action: suspend () -> OperationReply): OperationReply =
        try {
            action()
        } catch (e: AccountException) {
            val status =
                when (e.code) {
                    "not_found", "invite_not_found" -> HttpStatusCode.NotFound.value
                    "invite_expired", "invite_revoked" -> HttpStatusCode.Gone.value
                    "invalid_arguments", "invalid_timezone" -> HttpStatusCode.BadRequest.value
                    else -> HttpStatusCode.Conflict.value
                }
            reply(
                status,
                buildJsonObject {
                    put(
                        "error",
                        buildJsonObject {
                            put("code", e.code)
                            put("message", "Системная операция отклонена")
                        },
                    )
                },
            )
        } catch (_: IllegalArgumentException) {
            reply(
                HttpStatusCode.BadRequest.value,
                buildJsonObject {
                    put(
                        "error",
                        buildJsonObject {
                            put("code", "invalid_arguments")
                            put("message", "Некорректные аргументы")
                        },
                    )
                },
            )
        }
}

internal fun userReply(user: theseus.core.domain.user.User) =
    reply(HttpStatusCode.OK.value, restJson.encodeToJsonElement(UserDto.serializer(), UserDto.of(user)))

internal fun reply(
    status: Int,
    body: JsonElement,
) = OperationReply(status, body.toString())

internal fun inviteJson(
    invite: Invite,
    now: Instant,
) = buildJsonObject {
    put("id", invite.id)
    put("gender", invite.gender.name.lowercase())
    put("state", invite.state(now))
    put("label", invite.label?.let(::JsonPrimitive) ?: JsonNull)
    put("created_at", invite.createdAt.toString())
    put("expires_at", invite.expiresAt.toString())
    put("used_by_user_id", invite.usedByUserId?.let(::JsonPrimitive) ?: JsonNull)
}

internal fun totals(rows: List<Usage>) =
    buildJsonObject {
        put("requests", rows.size)
        put("tokens_in", rows.sumOf { it.tokensIn.toLong() })
        put("tokens_out", rows.sumOf { it.tokensOut.toLong() })
        put("cost_usd", rows.sumOf { it.costUsd }.toPlainString())
    }

private suspend fun ApplicationCall.validId(): Long? {
    val id = parameters["id"]?.toLongOrNull()?.takeIf { it > 0 }
    if (id == null) {
        val body =
            buildJsonObject {
                put(
                    "error",
                    buildJsonObject {
                        put("code", "invalid_arguments")
                        put("message", "Некорректный идентификатор")
                    },
                )
            }
        respondText(body.toString(), io.ktor.http.ContentType.Application.Json, HttpStatusCode.BadRequest)
    }
    return id
}
