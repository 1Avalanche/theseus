package theseus.agent.pipeline

import kotlinx.serialization.json.Json
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
        return when (tool) {
            "create_reminder" -> renderCreated(result, zone, now)
            else -> null
        }
    }

    private fun renderCreated(
        result: JsonObject,
        zone: ZoneId,
        now: Instant,
    ): String? {
        if (result.string("status") != "created") return null
        val reminder = result["reminder"] as? JsonObject ?: return null
        val text = reminder.string("text") ?: return null
        val at = reminder.string("next_at_local")?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: return null
        return texts.get("reply.created", mapOf("text" to text, "when" to describe(at, LocalDate.ofInstant(now, zone))))
    }

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
