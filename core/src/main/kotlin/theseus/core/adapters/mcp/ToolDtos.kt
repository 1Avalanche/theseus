package theseus.core.adapters.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import theseus.core.domain.LocalFormats
import theseus.core.domain.reminder.Reminder
import java.time.LocalDateTime

// DTO контракта MCP (`docs/api/mcp.md`): поля snake_case, перечисления lower_snake.

@Serializable
internal data class CreateReminderArgs(
    val text: String,
    @SerialName("when") val at: WhenDto? = null,
    val recurrence: JsonElement? = null,
    val force: Boolean = false,
)

@Serializable
internal data class WhenDto(
    val date: String? = null,
    val time: String? = null,
    val after: AfterDto? = null,
)

@Serializable
internal data class AfterDto(
    val days: Int = 0,
    val hours: Int = 0,
    val minutes: Int = 0,
)

@Serializable
internal data class CreatedResult(
    val status: String,
    val reminder: ReminderView,
    val warnings: List<String> = emptyList(),
)

@Serializable
internal data class ReminderView(
    val id: Long,
    val text: String,
    val kind: String,
    val status: String,
    val schedule: ScheduleView,
    @SerialName("next_at_local") val nextAtLocal: String?,
    @SerialName("paused_until_local") val pausedUntilLocal: String? = null,
    @SerialName("snoozed_until_local") val snoozedUntilLocal: String? = null,
) {
    companion object {
        /** Этап 2: только разовые напоминания (`kind = one_time`). */
        fun of(reminder: Reminder) =
            ReminderView(
                id = reminder.id,
                text = reminder.text,
                kind = "one_time",
                status = reminder.status.name.lowercase(),
                schedule = ScheduleView(atLocal = LocalFormats.formatMoment(reminder.startLocal)),
                nextAtLocal = reminder.nextFireAt?.let { LocalFormats.formatMoment(LocalDateTime.ofInstant(it, reminder.timezone)) },
            )
    }
}

@Serializable
internal data class ScheduleView(
    @SerialName("at_local") val atLocal: String,
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
