package theseus.core.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Форматы локального времени во внешних контрактах: дата `YYYY-MM-DD`, время `HH:MM`,
 * момент `YYYY-MM-DDTHH:MM`. Точность — минута, смещение не передаётся никогда.
 */
object LocalFormats {
    private val date: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val moment: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(ResolverStyle.STRICT)

    fun parseDate(value: String): LocalDate? = parseOrNull { LocalDate.parse(value, date) }

    fun parseTime(value: String): LocalTime? = parseOrNull { LocalTime.parse(value, time) }

    fun formatDate(value: LocalDate): String = value.format(date)

    fun formatTime(value: LocalTime): String = value.format(time)

    fun formatMoment(value: LocalDateTime): String = value.format(moment)

    private inline fun <T> parseOrNull(block: () -> T): T? =
        try {
            block()
        } catch (_: DateTimeParseException) {
            null
        }
}
