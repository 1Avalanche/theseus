package theseus.agent.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import theseus.agent.llm.ToolCall
import theseus.core.domain.reminder.After
import theseus.core.domain.reminder.PartOfDay
import theseus.core.domain.reminder.RecurrenceResolver
import theseus.core.domain.reminder.When
import theseus.core.domain.reminder.WhenResolver
import java.time.LocalDate
import java.time.LocalTime

internal class EvalMatcher(
    private val context: JsonObject,
) {
    @Suppress("CyclomaticComplexMethod") // Рекурсивный диспетчер независимых матчеров JSON.
    fun matches(
        expected: JsonElement,
        actual: JsonElement?,
    ): Boolean =
        when {
            expected is JsonObject && "\$absent" in expected -> actual == null
            expected is JsonObject && "\$one_of" in expected -> expected.rows("\$one_of").any { matches(it, actual) }
            actual == null -> false
            expected is JsonObject && "\$contains_any" in expected ->
                actual is JsonPrimitive &&
                    actual.isString &&
                    expected.rows("\$contains_any").any { actual.content.contains(it.jsonPrimitive.content, true) }
            expected is JsonObject && "\$resolves_to" in expected ->
                runCatching {
                    val value = actual.jsonObject
                    val after = value["after"] as? JsonObject
                    val at =
                        When(
                            value["date"]?.jsonPrimitive?.content?.let(LocalDate::parse),
                            value["time"]?.jsonPrimitive?.content?.let(LocalTime::parse),
                            after?.let {
                                After(
                                    it["days"]?.jsonPrimitive?.int ?: 0,
                                    it["hours"]?.jsonPrimitive?.int ?: 0,
                                    it["minutes"]?.jsonPrimitive?.int ?: 0,
                                )
                            },
                            value["part_of_day"]?.jsonPrimitive?.content?.let { PartOfDay.valueOf(it.uppercase()) },
                        )
                    val resolved =
                        WhenResolver(
                            LocalTime.of(9, 0),
                        ).resolve(at, now(context).atZone(zone(context)).toInstant(), zone(context)).local.toString()
                    val targets = expected.getValue("\$resolves_to")
                    (targets as? JsonArray ?: JsonArray(listOf(targets))).any { it.jsonPrimitive.content == resolved }
                }.getOrDefault(false)
            expected is JsonObject && "\$normalizes_to" in expected ->
                runCatching {
                    val normalized = RecurrenceResolver().normalize(recurrence(actual.jsonObject), now(context).toLocalDate())
                    val fields = normalized.rule.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
                    val result =
                        buildJsonObject {
                            put("freq", fields.getValue("FREQ").lowercase())
                            put("interval", fields.getValue("INTERVAL").toInt())
                            put("time", normalized.startLocal.toLocalTime().toString())
                            put("start_date", normalized.startLocal.toLocalDate().toString())
                            put(
                                "until",
                                fields["UNTIL"]
                                    ?.take(
                                        8,
                                    )?.let { LocalDate.parse(it, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toString() },
                            )
                            put("count", fields["COUNT"]?.toInt())
                            put("by_weekday", JsonArray(fields["BYDAY"]?.split(',').orEmpty().map(::JsonPrimitive)))
                            put("by_month_day", JsonArray(fields["BYMONTHDAY"]?.split(',').orEmpty().map { JsonPrimitive(it.toInt()) }))
                            put("by_month", JsonArray(fields["BYMONTH"]?.split(',').orEmpty().map { JsonPrimitive(it.toInt()) }))
                        }
                    matches(expected.getValue("\$normalizes_to"), result)
                }.getOrDefault(false)
            expected is JsonObject -> actual is JsonObject && expected.all { (key, value) -> matches(value, actual[key]) }
            expected is JsonArray -> actual is JsonArray && matchUnordered(expected, actual)
            else -> expected == actual
        }

    private fun matchUnordered(
        expected: List<JsonElement>,
        actual: List<JsonElement>,
    ): Boolean {
        if (expected.isEmpty()) return actual.isEmpty()
        return actual.indices.any { index ->
            matches(expected.first(), actual[index]) &&
                matchUnordered(expected.drop(1), actual.filterIndexed { i, _ -> i != index })
        }
    }

    fun assess(
        case: JsonObject,
        calls: List<ToolCall>,
        response: String?,
        changed: Boolean,
    ): JsonObject {
        val alternatives = (case["expect"] as? JsonObject)?.let(::listOf) ?: case.rows("expect_any").map { it.jsonObject }
        val passed =
            alternatives.any { expected ->
                val wanted = expected.rows("calls")
                val relevant =
                    calls.filter {
                        (it.name !in reads && !(wanted.isEmpty() && it.name == "assistant_reply")) ||
                            wanted.any { row -> row.jsonObject.string("tool") == it.name }
                    }
                val actual =
                    relevant.map { call ->
                        buildJsonObject {
                            put("tool", call.name)
                            put("args", runCatching { Json.parseToJsonElement(call.argumentsJson) }.getOrDefault(JsonNull))
                        }
                    }
                val callsMatch =
                    if (expected["order"]?.jsonPrimitive?.content == "any") {
                        matchUnordered(wanted, actual)
                    } else {
                        wanted.size == actual.size && wanted.zip(actual).all { (e, a) -> matches(e, a) }
                    }
                val noMutation = expected["no_mutation"] == JsonPrimitive(true)
                callsMatch && (!noMutation || (!changed && calls.none { it.name in changing }))
            }
        val texts =
            theseus.agent.texts.Texts
                .load("texts/agent.properties")
        val useful =
            !response.isNullOrBlank() &&
                listOf("reply.failure", "reply.budget", "reply.unconfirmed").none { response.contains(texts.get(it)) }
        return buildJsonObject {
            put("automatic_pass", passed && useful)
            put("expectations_match", passed)
            put("useful_response", useful)
            put("manual_review", true)
            put("manual_status", "pending")
        }
    }

    companion object {
        val reads = setOf("find_reminders", "get_reminder", "list_reminders", "resolve_reference")
        val changing =
            setOf("create_reminder", "update_reminder", "delete_reminder", "pause_reminder", "resume_reminder", "snooze_reminder")
    }
}
