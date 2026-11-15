package theseus.core.domain.reminder

import java.time.Duration
import java.time.Instant
import java.time.Month

/** Кандидаты определяются текстом и календарными днями, без ограничения горизонтом пяти лет. */
class DuplicatePolicy(
    val textThreshold: Double = 0.6,
    val timeWindow: Duration = Duration.ofMinutes(DEFAULT_WINDOW_MINUTES),
    private val schedule: ReminderSchedule = ReminderSchedule(),
) {
    init {
        require(textThreshold in 0.0..1.0 && !timeWindow.isNegative)
    }

    fun similarity(
        left: String,
        right: String,
    ): Double {
        val a = words(left)
        val b = words(right)
        return when {
            a == b -> 1.0
            a.isEmpty() || b.isEmpty() -> 0.0
            else -> a.intersect(b).size.toDouble() / a.union(b).size
        }
    }

    @Suppress("UNUSED_PARAMETER") // Единый шов политики; now не ограничивает пересечение календарных дней.
    fun matches(
        left: Reminder,
        right: Reminder,
        now: Instant,
    ): Boolean = similarity(left.text, right.text) >= textThreshold && intersects(left, right)

    private fun intersects(
        left: Reminder,
        right: Reminder,
    ): Boolean {
        val a = left.recurrenceRule
        val b = right.recurrenceRule
        if (a != null && b != null) return sameSeriesDays(left, right)
        val one = if (a == null) left else right
        val target = one.startLocal.atZone(one.timezone).toInstant()
        val other = if (a == null) right else left
        return schedule
            .planned(other)
            .takeWhile { it.instant <= target.plus(timeWindow) }
            .any { Duration.between(it.instant, target).abs() <= timeWindow }
    }

    /** Интервал, COUNT и дата начала не отменяют совпадения типа/часа/дней из критерия дубля. */
    private fun sameSeriesDays(
        left: Reminder,
        right: Reminder,
    ): Boolean {
        val a = DayFilter(requireNotNull(left.recurrenceRule))
        val b = DayFilter(requireNotNull(right.recurrenceRule))
        if (a.freq != b.freq || left.startLocal.toLocalTime() != right.startLocal.toLocalTime()) return false
        val months = if (a.freq == "YEARLY") a.months.intersect(b.months) else (1..MONTHS).toSet()
        return months.any { month ->
            val lengths = if (month == FEBRUARY) listOf(COMMON_FEBRUARY, LEAP_FEBRUARY) else listOf(Month.of(month).length(false))
            lengths.any { length ->
                (1..WEEK_DAYS).any { firstWeekday ->
                    (1..length).any { day -> a.matches(day, length, firstWeekday) && b.matches(day, length, firstWeekday) }
                }
            }
        }
    }

    private class DayFilter(
        rule: String,
    ) {
        private val fields = rule.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
        val freq: String = fields.getValue("FREQ")
        val months: Set<Int> = fields["BYMONTH"]?.split(',')?.map(String::toInt)?.toSet() ?: (1..MONTHS).toSet()
        private val monthDays = fields["BYMONTHDAY"]?.split(',')?.map(String::toInt).orEmpty()
        private val weekdays = fields["BYDAY"]?.split(',').orEmpty()

        fun matches(
            day: Int,
            length: Int,
            firstWeekday: Int,
        ): Boolean {
            val monthDayMatches = monthDays.isEmpty() || day in monthDays || (day - length - 1) in monthDays
            val weekday = WEEKDAYS[(firstWeekday + day - 2) % WEEK_DAYS]
            val weekdayMatches = weekdays.isEmpty() || weekdays.any { matchesWeekday(it, weekday, day, length) }
            return monthDayMatches && weekdayMatches
        }

        private fun matchesWeekday(
            token: String,
            weekday: String,
            day: Int,
            length: Int,
        ): Boolean {
            if (!token.endsWith(weekday)) return false
            val ordinal = token.dropLast(2).toIntOrNull() ?: return true
            val position = if (ordinal > 0) (day - 1) / WEEK_DAYS + 1 else -((length - day) / WEEK_DAYS + 1)
            return ordinal == position
        }
    }

    private fun words(text: String): Set<String> =
        text
            .lowercase()
            .replace('ё', 'е')
            .split(Regex("[\\p{P}\\p{Z}\\s]+"))
            .filter { it.isNotEmpty() }
            .toSet()

    companion object {
        private const val DEFAULT_WINDOW_MINUTES = 60L
        private const val MONTHS = 12
        private const val FEBRUARY = 2
        private const val COMMON_FEBRUARY = 28
        private const val LEAP_FEBRUARY = 29
        private const val WEEK_DAYS = 7
        private val WEEKDAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    }
}
