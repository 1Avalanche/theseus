package theseus.core.adapters.http

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import theseus.core.application.AccountsService
import theseus.core.application.DeliveryService
import theseus.core.application.OperationReply
import theseus.core.application.OperationRequest
import theseus.core.application.Operations
import theseus.core.application.ReminderService
import theseus.core.domain.DomainException
import theseus.core.domain.ErrorCode
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

/** REST для кнопок и reply; инструменты LLM здесь не публикуются. */
class DeliveryRoutes(
    private val deliveries: DeliveryService,
    private val reminders: ReminderService,
    private val operations: Operations,
    private val clock: Clock,
) {
    fun install(route: Route) {
        byMessage(route)
        snooze(route)
    }

    private fun byMessage(route: Route) =
        with(route) {
            get("/internal/v1/deliveries/by-message") {
                val result =
                    safe {
                        val userId = requireNotNull(call.request.queryParameters["user_id"]).toLong()
                        val messageId = requireNotNull(call.request.queryParameters["message_id"]).toLong()
                        val (delivery, reminder) = deliveries.byMessage(userId, messageId)
                        reply(
                            200,
                            buildJsonObject {
                                put("delivery_id", delivery.id)
                                put("reminder_id", delivery.reminderId)
                                put("occurrence_at", delivery.occurrenceAt.toString())
                                put("occurrence_local", LocalDateTime.ofInstant(delivery.occurrenceAt, reminder.timezone).toString())
                                put("timezone", reminder.timezone.id)
                            },
                        )
                    }
                call.respondText(result.body, ContentType.Application.Json, HttpStatusCode.fromValue(result.status))
            }
        }

    private fun snooze(route: Route) =
        with(route) {
            post("/internal/v1/deliveries/{delivery_id}/snooze") {
                val result =
                    safe {
                        val deliveryId = requireNotNull(call.parameters["delivery_id"]).toLong()
                        require(deliveryId > 0)
                        val body = Json.parseToJsonElement(call.receiveText()).jsonObject
                        require(body.keys == setOf("user_id", "option"))
                        val userId =
                            body
                                .getValue("user_id")
                                .jsonPrimitive
                                .also { require(!it.isString) }
                                .content
                                .toLong()
                        require(userId > 0)
                        val option =
                            body
                                .getValue("option")
                                .jsonPrimitive
                                .also { require(it.isString) }
                                .content
                        val key = UUID.fromString(requireNotNull(call.request.headers["Idempotency-Key"]))
                        val operation = "POST /deliveries/$deliveryId/snooze"
                        operations.execute(
                            OperationRequest(key, "agent", operation, AccountsService.hashCode("$userId\n$option\n$operation"), userId),
                            clock.instant(),
                            { reminders.authorize(userId) },
                        ) {
                            safe {
                                val reminder = deliveries.snooze(userId, deliveryId, option)
                                val at = requireNotNull(reminder.snoozedUntil ?: reminder.nextFireAt)
                                reply(
                                    200,
                                    buildJsonObject {
                                        put("reminder_id", reminder.id)
                                        put("snoozed_until", at.toString())
                                        put("snoozed_until_local", LocalDateTime.ofInstant(at, reminder.timezone).toString())
                                        put("timezone", reminder.timezone.id)
                                    },
                                )
                            }
                        }
                    }
                call.respondText(result.body, ContentType.Application.Json, HttpStatusCode.fromValue(result.status))
            }
        }

    private suspend fun safe(action: suspend () -> OperationReply): OperationReply =
        try {
            action()
        } catch (e: DomainException) {
            error(
                e.error.code,
                when (e.error) {
                    ErrorCode.NOT_FOUND -> HttpStatusCode.NotFound.value
                    ErrorCode.INVALID_ARGUMENTS -> HttpStatusCode.BadRequest.value
                    else -> HttpStatusCode.Conflict.value
                },
            )
        } catch (e: theseus.core.domain.user.AccountException) {
            error(e.code, HttpStatusCode.Conflict.value)
        } catch (_: IllegalArgumentException) {
            error("invalid_arguments", HttpStatusCode.BadRequest.value)
        }

    private fun error(
        code: String,
        status: Int,
    ) = reply(
        status,
        buildJsonObject {
            put("error", buildJsonObject { put("code", code) })
        },
    )
}
