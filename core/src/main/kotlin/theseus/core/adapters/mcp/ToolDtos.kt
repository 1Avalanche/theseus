package theseus.core.adapters.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import theseus.core.domain.LocalFormats
import theseus.core.domain.reminder.Recurrence
import theseus.core.domain.reminder.Reminder
import java.time.LocalDate
import java.time.LocalDateTime

@Serializable
internal data class WhenDto(
    val date: String? = null,
    val time: String? = null,
    val after: AfterDto? = null,
    @SerialName("part_of_day") val partOfDay: String? = null,
)

@Serializable
internal data class AfterDto(
    val days: Int = 0,
    val hours: Int = 0,
    val minutes: Int = 0,
)

@Serializable
internal data class RecurrenceDto(
    val freq: String,
    val interval: Int = 1,
    @SerialName("by_weekday") val byWeekday: List<String> = emptyList(),
    @SerialName("by_month_day") val byMonthDay: List<Int> = emptyList(),
    @SerialName("by_month") val byMonth: List<Int> = emptyList(),
    val time: String? = null,
    @SerialName("part_of_day") val partOfDay: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    val until: String? = null,
    val count: Int? = null,
)

@Serializable
internal data class ErrorBody(
    val error: ErrorPayload,
)

@Serializable
internal data class ErrorPayload(
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

/** Нормализованный RRULE, собранный core, возвращается структурой исходного контракта. */
internal fun recurrenceOf(reminder: Reminder): Recurrence {
    val fields = requireNotNull(reminder.recurrenceRule).split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
    return Recurrence(
        freq = fields.getValue("FREQ").lowercase(),
        interval = fields["INTERVAL"]?.toInt() ?: 1,
        byWeekday = fields["BYDAY"]?.split(',').orEmpty(),
        byMonthDay = fields["BYMONTHDAY"]?.split(',')?.map(String::toInt).orEmpty(),
        byMonth = fields["BYMONTH"]?.split(',')?.map(String::toInt).orEmpty(),
        time = reminder.startLocal.toLocalTime(),
        startDate = reminder.startLocal.toLocalDate(),
        until = fields["UNTIL"]?.substringBefore('T')?.let { LocalDate.parse(it, java.time.format.DateTimeFormatter.BASIC_ISO_DATE) },
        count = fields["COUNT"]?.toInt(),
    )
}

internal fun recurrenceJson(value: Recurrence): JsonObject =
    buildJsonObject {
        put("freq", value.freq)
        put("interval", value.interval)
        put("by_weekday", buildJsonArray { value.byWeekday.forEach { add(JsonPrimitive(it)) } })
        put("by_month_day", buildJsonArray { value.byMonthDay.forEach { add(JsonPrimitive(it)) } })
        put("by_month", buildJsonArray { value.byMonth.forEach { add(JsonPrimitive(it)) } })
        put("time", value.time?.let(LocalFormats::formatTime))
        put("start_date", value.startDate?.toString())
        put("until", value.until?.toString())
        put("count", value.count)
    }

/** Зона относится к напоминанию и не меняется при изменении профиля. */
internal object ReminderView {
    fun of(reminder: Reminder): JsonObject =
        buildJsonObject {
            put("id", reminder.id)
            put("text", reminder.text)
            put("kind", if (reminder.recurrenceRule == null) "one_time" else "recurring")
            put("status", reminder.rawStatus ?: reminder.status.name.lowercase())
            put("timezone", reminder.timezone.id)
            put(
                "schedule",
                buildJsonObject {
                    if (reminder.recurrenceRule == null) {
                        put("at_local", LocalFormats.formatMoment(reminder.startLocal))
                    } else {
                        put("recurrence", recurrenceJson(recurrenceOf(reminder)))
                    }
                },
            )

            fun local(value: java.time.Instant?): String? =
                value?.let { LocalFormats.formatMoment(LocalDateTime.ofInstant(it, reminder.timezone)) }
            put("next_at_local", local(reminder.nextFireAt))
            put("paused_until_local", local(reminder.pausedUntil))
            put("snoozed_until_local", local(reminder.snoozedUntil))
        }
}
