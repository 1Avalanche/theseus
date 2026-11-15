package theseus.agent.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import theseus.agent.texts.Texts
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Ответ пользователю после успешного инструмента — кодом по шаблону, без второго вызова LLM.
 * Нераспознанный результат → `null`: подтверждения нет, пайплайн выбирает безопасный ответ.
 */
@Suppress("TooManyFunctions") // Форматирование результатов девяти инструментов и связанных карточек.
class ReplyRenderer(
    private val texts: Texts,
) {
    fun render(
        tool: String,
        resultJson: String,
        zone: ZoneId,
        now: Instant,
    ): String? {
        val result = parseObject(resultJson) ?: return null
        val reminder = result["reminder"] as? JsonObject
        return when (tool) {
            "create_reminder" ->
                if (result.string("status") == "created" && reminder != null) {
                    texts.get(
                        "reply.created",
                        mapOf(
                            "text" to reminder.string("text").orEmpty(),
                            "when" to schedule(reminder, zone, now),
                        ),
                    ) + warnings(result)
                } else {
                    null
                }
            "update_reminder", "snooze_reminder", "pause_reminder", "resume_reminder", "delete_reminder" ->
                if (reminder != null &&
                    result.string("status") in setOf("updated", "snoozed", "paused", "resumed", "deleted", "occurrence_skipped")
                ) {
                    texts.get(
                        "reply.${result.string("status")}",
                        mapOf(
                            "text" to reminder.string("text").orEmpty(),
                            "when" to (if (tool == "snooze_reminder") snoozed(reminder, zone, now) else schedule(reminder, zone, now)),
                        ),
                    ) + warnings(result)
                } else {
                    null
                }
            "get_reminder" -> reminder?.let { item(it, zone, now) }
            "list_reminders", "find_reminders" -> list(result, zone, now)
            else -> null
        }
    }

    private fun list(
        result: JsonObject,
        zone: ZoneId,
        now: Instant,
    ): String? {
        val rows = (result["reminders"] ?: result["candidates"]) as? JsonArray ?: return null
        return if (rows.isEmpty()) {
            texts.get("reminders.empty")
        } else {
            val occurrences = result["occurrences"] as? JsonArray
            val rendered =
                if (occurrences != null) {
                    val byId = rows.mapNotNull { it as? JsonObject }.associateBy { it.string("id") }
                    occurrences.mapIndexedNotNull { index, value ->
                        val occurrence = value as? JsonObject ?: return@mapIndexedNotNull null
                        val row = byId[occurrence.string("reminder_id")] ?: return@mapIndexedNotNull null
                        val at = occurrence.string("at_local") ?: return@mapIndexedNotNull null
                        val reminderZone = row.string("timezone")?.let(ZoneId::of) ?: zone
                        val whenText =
                            describe(LocalDateTime.parse(at), LocalDate.ofInstant(now, reminderZone)) + zoneSuffix(row, zone)
                        texts.get(
                            "reminders.item",
                            mapOf(
                                "number" to (index + 1).toString(),
                                "text" to row.string("text").orEmpty(),
                                "when" to whenText,
                            ),
                        )
                    }
                } else {
                    rows.mapIndexed { index, row ->
                        texts.get(
                            "reminders.numbered",
                            mapOf(
                                "number" to (index + 1).toString(),
                                "item" to item(row.jsonObject, zone, now),
                            ),
                        )
                    }
                }
            rendered.joinToString("\n") + if (result.string("truncated") == "true") "\n" + texts.get("reminders.truncated") else ""
        }
    }

    fun confirmation(
        name: String,
        args: JsonObject,
        data: JsonObject,
        zone: ZoneId,
        now: Instant,
    ): String {
        val key =
            when (name) {
                "create_reminder" -> "confirmation.duplicate"
                "delete_reminder" -> if (args.string("scope") == "occurrence") "confirmation.skip" else "confirmation.delete"
                else -> "confirmation.reset"
            }
        val reminder = (data["reminder"] ?: data["proposed"]) as? JsonObject
        val target = reminder?.let { item(it, zone, now) }.orEmpty()
        val occurrence = args.string("occurrence_local")?.let { "\n" + texts.get("confirmation.occurrence", mapOf("at" to it)) }.orEmpty()
        val delta = recurrenceDelta(args)
        val candidates =
            (data["candidates"] as? JsonArray)
                ?.joinToString("\n") {
                    item(it.jsonObject, zone, now)
                }?.let { "\n" + texts.get("confirmation.candidates") + "\n$it" }
                .orEmpty()
        return texts.get(
            key,
            mapOf(
                "target" to target,
                "excluded" to data.string("excluded_count").orEmpty(),
                "children" to data.string("pending_children_count").orEmpty(),
            ),
        ) +
            occurrence + delta + candidates + "\n" + texts.get("confirmation.ttl")
    }

    private fun recurrenceDelta(args: JsonObject): String =
        (args["recurrence"] as? JsonObject)
            ?.entries
            ?.joinToString("\n") { (field, value) ->
                val label = texts.get("recurrence.field.$field")
                val shown =
                    when {
                        value == kotlinx.serialization.json.JsonNull -> texts.get("recurrence.default")
                        field == "freq" -> texts.get("recurrence.freq.${(value as JsonPrimitive).content}")
                        field == "part_of_day" -> texts.get("recurrence.part.${(value as JsonPrimitive).content}")
                        value is JsonArray ->
                            value.joinToString(", ") { element ->
                                val s = (element as JsonPrimitive).content
                                if (field == "by_weekday") weekday(s) else s
                            }
                        else -> (value as? JsonPrimitive)?.content.orEmpty()
                    }
                "$label: $shown"
            }?.let { "\n$it" }
            .orEmpty()

    private fun item(
        reminder: JsonObject,
        zone: ZoneId,
        now: Instant,
    ): String = texts.get("reminders.card", mapOf("text" to reminder.string("text").orEmpty(), "when" to schedule(reminder, zone, now)))

    private fun schedule(
        reminder: JsonObject,
        zone: ZoneId,
        now: Instant,
    ): String {
        val reminderZone = reminder.string("timezone")?.let(ZoneId::of) ?: zone
        val schedule = reminder["schedule"] as? JsonObject
        val recurring = schedule?.get("recurrence") as? JsonObject
        val next = reminder.string("next_at_local") ?: schedule?.string("at_local")
        val whenText =
            if (recurring == null) {
                next?.let { describe(LocalDateTime.parse(it), LocalDate.ofInstant(now, reminderZone)) } ?: texts.get("reminders.finished")
            } else {
                val freq = recurring.string("freq").orEmpty()
                val interval = recurring.string("interval") ?: "1"
                val days = (recurring["by_weekday"] as? JsonArray)?.map { weekday((it as JsonPrimitive).content) }.orEmpty()
                val monthDays = (recurring["by_month_day"] as? JsonArray)?.joinToString(", ") { (it as JsonPrimitive).content }.orEmpty()
                val months = (recurring["by_month"] as? JsonArray)?.joinToString(", ") { (it as JsonPrimitive).content }.orEmpty()
                texts.get(
                    "recurrence.schedule",
                    mapOf(
                        "frequency" to texts.get("recurrence.freq.$freq"),
                        "interval" to interval,
                        "time" to recurring.string("time").orEmpty(),
                    ),
                ) +
                    ifNotEmpty(days.joinToString(", "), "recurrence.weekdays") +
                    ifNotEmpty(monthDays, "recurrence.month_days") + ifNotEmpty(months, "recurrence.months") +
                    recurring
                        .string(
                            "until",
                        )?.takeUnless { it == "null" }
                        ?.let { texts.get("recurrence.until", mapOf("value" to it)) }
                        .orEmpty() +
                    recurring
                        .string(
                            "count",
                        )?.takeUnless { it == "null" }
                        ?.let { texts.get("recurrence.count", mapOf("value" to it)) }
                        .orEmpty()
            }
        val status =
            when (reminder.string("status")) {
                "paused" ->
                    texts.get(
                        "reminders.paused",
                        mapOf(
                            "until" to (
                                reminder.string("paused_until_local")?.takeUnless { it == "null" }
                                    ?: texts.get("reminders.indefinite")
                            ),
                        ),
                    )
                "done" -> texts.get("reminders.done")
                "deleted" -> texts.get("reminders.deleted")
                else -> ""
            }
        return whenText + status + zoneSuffix(reminder, zone)
    }

    private fun snoozed(
        reminder: JsonObject,
        zone: ZoneId,
        now: Instant,
    ): String {
        val local = reminder.string("snoozed_until_local") ?: reminder.string("next_at_local") ?: return schedule(reminder, zone, now)
        val reminderZone = reminder.string("timezone")?.let(ZoneId::of) ?: zone
        return describe(LocalDateTime.parse(local), LocalDate.ofInstant(now, reminderZone)) + zoneSuffix(reminder, zone)
    }

    private fun zoneSuffix(
        reminder: JsonObject,
        zone: ZoneId,
    ): String =
        reminder
            .string("timezone")
            ?.takeIf { it != zone.id }
            ?.let { texts.get("reminders.zone", mapOf("zone" to it)) }
            .orEmpty()

    private fun weekday(value: String): String {
        val suffix = value.takeLast(2)
        val prefix = value.dropLast(2)
        return prefix + texts.get("recurrence.weekday.$suffix")
    }

    private fun ifNotEmpty(
        value: String,
        key: String,
    ): String = if (value.isEmpty()) "" else texts.get(key, mapOf("value" to value))

    private fun warnings(result: JsonObject): String =
        (result["warnings"] as? JsonArray)
            ?.joinToString("") {
                "\n" + texts.get("warning.${(it as JsonPrimitive).content}")
            }.orEmpty()

    /** «сегодня в 10:20», «завтра в 09:00», «5 ноября в 09:00»; другой год — с годом. */
    internal fun describe(
        at: LocalDateTime,
        today: LocalDate,
    ): String {
        val time = at.toLocalTime().format(TIME)
        return when (at.toLocalDate()) {
            today -> texts.get("when.today", mapOf("time" to time))
            today.plusDays(1) -> texts.get("when.tomorrow", mapOf("time" to time))
            else -> {
                val format = if (at.year == today.year) DAY_MONTH else DAY_MONTH_YEAR
                texts.get("when.date", mapOf("date" to at.format(format), "time" to time))
            }
        }
    }

    private fun parseObject(text: String): JsonObject? = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        private val RU: Locale = Locale.of("ru")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", RU)
        private val DAY_MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", RU)
    }
}
