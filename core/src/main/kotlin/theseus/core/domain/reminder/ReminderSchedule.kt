package theseus.core.domain.reminder

import java.time.Instant

/** Общая календарная логика для инструментов, списков и планировщика. */
class ReminderSchedule(
    private val recurrence: RecurrenceResolver = RecurrenceResolver(),
) {
    fun planned(reminder: Reminder): Sequence<ResolvedTime> =
        reminder.recurrenceRule?.let { recurrence.occurrences(reminder.startLocal, it, reminder.timezone) }
            ?: sequenceOf(ResolvedTime(reminder.startLocal, reminder.startLocal.atZone(reminder.timezone).toInstant()))

    fun nextPlanned(
        reminder: Reminder,
        after: Instant,
    ): Instant? = planned(reminder).firstOrNull { it.instant > after && it.instant !in reminder.excludedOccurrences }?.instant

    fun next(
        reminder: Reminder,
        after: Instant,
    ): Instant? = listOfNotNull(nextPlanned(reminder, after), reminder.snoozedUntil?.takeIf { it > after }).minOrNull()

    /** Изменение будущего исключения не погашает уже ожидающие основания доставки. */
    fun pending(
        reminder: Reminder,
        now: Instant,
    ): Instant? = listOfNotNull(latestDue(reminder, now), nextPlanned(reminder, now), reminder.snoozedUntil).minOrNull()

    /** Только последнее пропущенное плановое срабатывание; предыдущие не досылаются. */
    fun latestDue(
        reminder: Reminder,
        now: Instant,
    ): Instant? {
        val first = reminder.nextFireAt ?: return null
        return planned(reminder)
            .takeWhile { it.instant <= now }
            .lastOrNull { it.instant >= first && it.instant !in reminder.excludedOccurrences }
            ?.instant
    }
}
