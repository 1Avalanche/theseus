package theseus.core.domain.reminder

import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule
import theseus.core.domain.DomainException
import theseus.core.domain.ErrorCode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Структура повторения от клиента; RRULE и календарную арифметику строит код. */
data class Recurrence(
    val freq: String,
    val interval: Int = 1,
    val byWeekday: List<String> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val time: LocalTime? = null,
    val startDate: LocalDate? = null,
    val until: LocalDate? = null,
    val count: Int? = null,
    val partOfDay: PartOfDay? = null,
)

data class RecurringSchedule(
    val startLocal: LocalDateTime,
    val rule: String,
    val warnings: List<String>,
)

/**
 * RRULE вычисляется в локальном календаре lib-recur, затем переводится в UTC через ZoneRules JVM.
 * Несуществующий час сдвигается вперёд, двойной час исполняется один раз с ранним смещением.
 * COUNT считает срабатывания от DTSTART, включая уже прошедшие и исключённые.
 */
@Suppress("MagicNumber") // 09:00 — утверждённое время по умолчанию.
class RecurrenceResolver(
    private val defaultTime: LocalTime = LocalTime.of(9, 0),
    private val dayTimes: DayTimes = DayTimes(),
) {
    @Suppress("CyclomaticComplexMethod") // Нормализация независимых необязательных полей RRULE.
    fun normalize(
        value: Recurrence,
        today: LocalDate,
    ): RecurringSchedule {
        validate(value)
        val start = value.startDate ?: today
        if (value.until != null && value.until < start) invalid()
        val days =
            value.byWeekday.ifEmpty {
                if (value.freq == "weekly") listOf(start.dayOfWeek.name.take(2)) else emptyList()
            }
        val monthDays =
            value.byMonthDay.ifEmpty {
                if (value.freq in setOf("monthly", "yearly") && days.isEmpty()) listOf(start.dayOfMonth) else emptyList()
            }
        val months = value.byMonth.ifEmpty { if (value.freq == "yearly") listOf(start.monthValue) else emptyList() }
        val parts = mutableListOf("FREQ=${value.freq.uppercase()}", "INTERVAL=${value.interval}")
        if (days.isNotEmpty()) parts += "BYDAY=${days.distinct().sorted().joinToString(",")}"
        if (monthDays.isNotEmpty()) parts += "BYMONTHDAY=${monthDays.distinct().sorted().joinToString(",")}"
        if (months.isNotEmpty()) parts += "BYMONTH=${months.distinct().sorted().joinToString(",")}"
        value.until?.let { parts += "UNTIL=${it.format(DATE)}T235959" }
        value.count?.let { parts += "COUNT=$it" }
        val rule = parts.joinToString(";")
        parse(rule)
        return RecurringSchedule(
            LocalDateTime.of(start, value.time ?: value.partOfDay?.let(dayTimes::resolve) ?: defaultTime),
            rule,
            if (value.freq == "monthly" && monthDays.any { it >= SHORT_MONTH_DAY }) listOf("short_months_skipped") else emptyList(),
        )
    }

    /** Ленивое перечисление: верхнюю границу и число результатов задаёт вызывающий код. */
    fun occurrences(
        start: LocalDateTime,
        rule: String,
        zone: ZoneId,
    ): Sequence<ResolvedTime> =
        sequence {
            val iterator = parse(rule).iterator(DateTime.parse(start.format(MOMENT)))
            while (iterator.hasNext()) {
                val local = LocalDateTime.parse(iterator.nextDateTime().toString(), MOMENT)
                yield(ResolvedTime(local, local.atZone(zone).toInstant()))
            }
        }

    fun next(
        start: LocalDateTime,
        rule: String,
        zone: ZoneId,
        after: Instant,
        excluded: Set<Instant> = emptySet(),
    ): ResolvedTime? = occurrences(start, rule, zone).firstOrNull { it.instant > after && it.instant !in excluded }

    @Suppress("CyclomaticComplexMethod") // Взаимные ограничения полей одного контракта проверяются вместе.
    private fun validate(value: Recurrence) {
        if (value.time != null && value.partOfDay != null) invalid()
        if (value.freq !in setOf("daily", "weekly", "monthly", "yearly") || value.interval !in 1..MAX_INTERVAL) invalid()
        if (value.count != null && (value.count < 1 || value.until != null)) invalid()
        if (value.time != null && (value.time.second != 0 || value.time.nano != 0)) invalid()
        if (value.byMonthDay.any { it == 0 || it !in -MAX_MONTH_DAY..MAX_MONTH_DAY }) invalid()
        if (value.byMonth.any { it !in 1..MONTHS }) invalid()
        if (value.byMonth.isNotEmpty() && value.freq != "yearly") invalid()
        if (value.byMonthDay.isNotEmpty() && value.freq == "weekly") invalid()
        if (value.byWeekday.any { !WEEKDAY.matches(it) }) invalid()
        if (value.freq in setOf("daily", "weekly") && value.byWeekday.any { it.length != 2 }) invalid()
    }

    private fun parse(rule: String): RecurrenceRule =
        try {
            RecurrenceRule(rule)
        } catch (e: IllegalArgumentException) {
            throw DomainException(ErrorCode.INVALID_RECURRENCE, cause = e)
        } catch (e: org.dmfs.rfc5545.recur.InvalidRecurrenceRuleException) {
            throw DomainException(ErrorCode.INVALID_RECURRENCE, cause = e)
        }

    private fun invalid(): Nothing = throw DomainException(ErrorCode.INVALID_RECURRENCE)

    companion object {
        private const val MAX_INTERVAL = 365
        private const val MAX_MONTH_DAY = 31
        private const val SHORT_MONTH_DAY = 29
        private const val MONTHS = 12
        private val WEEKDAY = Regex("(?:[1-5]|-[1-5])?(?:MO|TU|WE|TH|FR|SA|SU)")
        private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val MOMENT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    }
}
