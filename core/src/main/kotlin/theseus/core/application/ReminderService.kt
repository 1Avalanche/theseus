package theseus.core.application

import theseus.core.domain.DomainException
import theseus.core.domain.ErrorCode
import theseus.core.domain.reminder.DuplicatePolicy
import theseus.core.domain.reminder.NewReminder
import theseus.core.domain.reminder.Recurrence
import theseus.core.domain.reminder.RecurrenceResolver
import theseus.core.domain.reminder.Reminder
import theseus.core.domain.reminder.ReminderSchedule
import theseus.core.domain.reminder.ReminderStatus
import theseus.core.domain.reminder.When
import theseus.core.domain.reminder.WhenResolver
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Условие исполнения карточки задаёт код агента через метаданные, а не аргументы модели. */
data class Confirmation(
    val fingerprint: String,
    val createdAt: Instant,
)

/** Ответ создания: возможный дубль — завершённый результат без изменения данных. */
data class Creation(
    val reminder: Reminder?,
    val candidates: List<Reminder>,
    val warnings: List<String>,
    val fingerprint: String,
    val proposed: Reminder? = null,
)

data class ReminderList(
    val reminders: List<Reminder>,
    val occurrences: List<Pair<Long, LocalDateTime>>?,
    val truncated: Boolean = false,
    val fingerprint: String? = null,
    val unsupportedCount: Int = 0,
)

data class ReminderChange(
    val reminder: Reminder,
    val series: Reminder? = null,
    val warnings: List<String> = emptyList(),
)

/** Все проверки и изменения выполняются под блокировкой пользователя в одной транзакции. */
@Suppress("TooManyFunctions") // Единая application-граница девяти сценариев и их атомарных проверок.
class ReminderService(
    private val reminders: ReminderRepository,
    private val users: UserService,
    private val resolver: WhenResolver,
    private val clock: Clock,
    private val recurrence: RecurrenceResolver = RecurrenceResolver(),
    private val duplicates: DuplicatePolicy = DuplicatePolicy(),
) {
    private val schedule = ReminderSchedule(recurrence)

    fun now(): Instant = clock.instant()

    suspend fun authorize(userId: Long) {
        reminders.withUserLock(userId) { users.requireActive(userId) }
    }

    suspend fun <T> atomic(
        userId: Long,
        action: suspend () -> T,
    ): T =
        reminders.withUserLock(userId) {
            users.requireActive(userId)
            action()
        }

    suspend fun createOneTime(
        userId: Long,
        text: String,
        at: When,
    ): Reminder =
        atomic(userId) {
            val user = users.requireActive(userId)
            val now = clock.instant()
            val zone = requireNotNull(user.timezone)
            val resolved = resolver.resolve(at, now, zone)
            reminders.create(NewReminder(userId, normalizeText(text), zone, resolved.local, resolved.instant), now)
        }

    suspend fun create(
        userId: Long,
        text: String,
        at: When?,
        repeated: Recurrence?,
        force: Boolean = false,
        confirmation: Confirmation? = null,
    ): Creation =
        atomic(userId) {
            if ((at == null) == (repeated == null)) invalid()
            val user = users.requireActive(userId)
            val now = clock.instant()
            val zone = requireNotNull(user.timezone)
            val normalized = repeated?.let { recurrence.normalize(it, LocalDate.ofInstant(now, zone)) }
            val resolved =
                if (at != null) {
                    resolver.resolve(at, now, zone)
                } else {
                    recurrence.next(normalized!!.startLocal, normalized.rule, zone, now.minusNanos(1))
                        ?: throw DomainException(ErrorCode.INVALID_RECURRENCE)
                }
            if (resolved.instant > now.atZone(zone).plusYears(WhenResolver.MAX_YEARS_AHEAD).toInstant()) {
                throw DomainException(ErrorCode.TOO_FAR, mapOf("max_years" to WhenResolver.MAX_YEARS_AHEAD.toString()))
            }
            val candidate =
                Reminder(
                    0,
                    userId,
                    normalizeText(text),
                    zone,
                    normalized?.startLocal ?: resolved.local,
                    normalized?.rule,
                    ReminderStatus.ACTIVE,
                    resolved.instant,
                    null,
                    now,
                )
            val existing = active(userId)
            val matches = existing.filter { duplicates.matches(candidate, it, now) }
            val fingerprint = AccountsService.hashCode("${zone.id}\n${fingerprint(existing)}")
            if (force) verify(confirmation, fingerprint)
            if (matches.isNotEmpty() && !force) return@atomic Creation(null, matches, emptyList(), fingerprint, candidate)
            val created =
                reminders.create(
                    NewReminder(userId, candidate.text, zone, candidate.startLocal, resolved.instant, normalized?.rule),
                    now,
                )
            Creation(created, emptyList(), normalized?.warnings.orEmpty(), fingerprint)
        }

    suspend fun get(
        userId: Long,
        id: Long,
    ): Reminder = atomic(userId) { requireReminder(userId, id) }

    suspend fun upcoming(
        userId: Long,
        id: Long,
    ): List<LocalDateTime> =
        atomic(userId) {
            val reminder = requireReminder(userId, id)
            if (reminder.status != ReminderStatus.ACTIVE) return@atomic emptyList()
            val now = clock.instant()
            val ordinary =
                schedule
                    .planned(reminder)
                    .filter { it.instant > now && it.instant !in reminder.excludedOccurrences }
                    .take(UPCOMING_LIMIT)
                    .map { it.instant }
                    .toList()
            (ordinary + listOfNotNull(reminder.snoozedUntil?.takeIf { it > now }))
                .distinct()
                .sorted()
                .take(UPCOMING_LIMIT)
                .map { LocalDateTime.ofInstant(it, reminder.timezone) }
        }

    suspend fun nextOccurrence(
        userId: Long,
        id: Long,
    ): LocalDateTime? =
        atomic(userId) {
            val reminder = requireReminder(userId, id)
            schedule.nextPlanned(reminder, clock.instant())?.let { LocalDateTime.ofInstant(it, reminder.timezone) }
        }

    suspend fun list(
        userId: Long,
        from: LocalDate? = null,
        to: LocalDate? = null,
        includePaused: Boolean = true,
    ): ReminderList =
        atomic(userId) {
            if ((from == null) != (to == null)) invalid()
            if (from != null) {
                if (to!! < from || Duration.between(from.atStartOfDay(), to.atStartOfDay()).toDays() >= MAX_PERIOD_DAYS) invalid()
            }
            val stored = reminders.list(userId)
            val unsupportedCount = stored.count { it.status == ReminderStatus.UNSUPPORTED }
            val rows =
                stored
                    .filter { it.status in setOf(ReminderStatus.ACTIVE, ReminderStatus.PAUSED) }
                    .filter { includePaused || it.status != ReminderStatus.PAUSED }
                    .sortedWith(
                        compareBy<Reminder> { it.status == ReminderStatus.PAUSED }.thenBy(nullsLast()) { it.nextFireAt }.thenBy { it.id },
                    )
            if (from == null) return@atomic ReminderList(rows, null, fingerprint = fingerprint(rows), unsupportedCount = unsupportedCount)
            val occurrences =
                rows
                    .flatMap { reminder ->
                        val start = from.atStartOfDay(reminder.timezone).toInstant()
                        val end = to!!.plusDays(1).atStartOfDay(reminder.timezone).toInstant()
                        val planned =
                            schedule
                                .planned(reminder)
                                .takeWhile { it.instant < end }
                                .filter { it.instant >= start && it.instant !in reminder.excludedOccurrences }
                                .filter {
                                    reminder.status != ReminderStatus.PAUSED ||
                                        (reminder.pausedUntil != null && it.instant >= reminder.pausedUntil)
                                }.map { it.instant }
                                .take(MAX_OCCURRENCES + 1)
                                .toList()
                        (planned + listOfNotNull(reminder.snoozedUntil?.takeIf { it >= start && it < end }))
                            .distinct()
                            .map { reminder.id to LocalDateTime.ofInstant(it, reminder.timezone) }
                    }.sortedWith(compareBy<Pair<Long, LocalDateTime>> { it.second }.thenBy { it.first })
            val limited = occurrences.take(MAX_OCCURRENCES)
            ReminderList(
                rows.filter { row -> limited.any { it.first == row.id } },
                limited,
                occurrences.size > MAX_OCCURRENCES,
                unsupportedCount = unsupportedCount,
            )
        }

    suspend fun find(
        userId: Long,
        query: String,
    ): List<Reminder> =
        atomic(userId) {
            if (query.isBlank() || query.length > MAX_TEXT_LENGTH) invalid()
            active(userId).sortedByDescending { duplicates.similarity(query, it.text) }.take(FIND_LIMIT)
        }

    suspend fun snapshot(
        userId: Long,
        id: Long,
    ): String =
        atomic(userId) {
            val target = requireReminder(userId, id)
            fingerprint(listOf(target) + children(userId, id))
        }

    suspend fun consequences(
        userId: Long,
        id: Long,
    ): Pair<Int, Int> =
        atomic(userId) {
            val target = requireReminder(userId, id)
            target.excludedOccurrences.size to children(userId, id).size
        }

    @Suppress("CyclomaticComplexMethod") // Атомарное сочетание замены календаря, текста и подтверждённого сброса исключений.
    suspend fun update(
        userId: Long,
        id: Long,
        scope: String?,
        occurrenceLocal: LocalDateTime?,
        text: String?,
        at: When?,
        repeated: Recurrence?,
        confirmation: Confirmation? = null,
    ): ReminderChange =
        atomic(userId) {
            val old = requireReminder(userId, id)
            if (old.status !in setOf(ReminderStatus.ACTIVE, ReminderStatus.PAUSED)) invalidState()
            if (text == null && at == null && repeated == null) invalid()
            validateScope(old, scope, occurrenceLocal)
            val now = clock.instant()
            if (scope == "occurrence") {
                if (repeated != null) invalid()
                return@atomic moveOccurrence(old, occurrenceLocal, text, at, now)
            }
            if (old.recurrenceRule != null && at != null) invalid()
            val normalized = repeated?.let { recurrence.normalize(it, LocalDate.ofInstant(now, old.timezone)) }
            val scheduleChanged = normalized != null && (normalized.startLocal != old.startLocal || normalized.rule != old.recurrenceRule)
            val effects = children(userId, id)
            if (scheduleChanged && (old.excludedOccurrences.isNotEmpty() || effects.isNotEmpty())) {
                verify(confirmation, fingerprint(listOf(old) + effects))
                if (old.nextFireAt != null && old.nextFireAt <= now) throw DomainException(ErrorCode.CONFIRMATION_STALE)
                effects.forEach {
                    reminders.save(
                        it.copy(status = ReminderStatus.DELETED, nextFireAt = null, snoozedUntil = null, pausedUntil = null),
                        now,
                    )
                }
            }
            val resolved = at?.let { resolver.resolve(it, now, old.timezone) }
            var changed =
                old.copy(
                    text = text?.let(::normalizeText) ?: old.text,
                    startLocal = normalized?.startLocal ?: resolved?.local ?: old.startLocal,
                    recurrenceRule = normalized?.rule ?: old.recurrenceRule,
                    excludedOccurrences = if (scheduleChanged) emptySet() else old.excludedOccurrences,
                )
            if (scheduleChanged || resolved != null) {
                val next =
                    if (changed.status == ReminderStatus.PAUSED) {
                        null
                    } else {
                        listOfNotNull(schedule.nextPlanned(changed, now), changed.snoozedUntil).minOrNull()
                    }
                if (next == null && changed.status == ReminderStatus.ACTIVE) throw DomainException(ErrorCode.INVALID_RECURRENCE)
                if (next != null && next > now.atZone(old.timezone).plusYears(WhenResolver.MAX_YEARS_AHEAD).toInstant()) {
                    throw DomainException(ErrorCode.TOO_FAR, mapOf("max_years" to WhenResolver.MAX_YEARS_AHEAD.toString()))
                }
                changed = changed.copy(nextFireAt = next)
            }
            ReminderChange(reminders.save(changed, now), warnings = normalized?.warnings.orEmpty())
        }

    private suspend fun moveOccurrence(
        old: Reminder,
        local: LocalDateTime?,
        text: String?,
        at: When?,
        now: Instant,
    ): ReminderChange {
        val occurrence = occurrence(old, local, now)
        val target =
            at?.let {
                val hasTime = it.time != null || it.partOfDay != null
                val dated =
                    if (it.date == null && it.after == null && hasTime) {
                        it.copy(date = LocalDateTime.ofInstant(occurrence, old.timezone).toLocalDate())
                    } else {
                        it
                    }
                resolver.resolve(dated, now, old.timezone)
            }
        val newLocal = target?.local ?: LocalDateTime.ofInstant(occurrence, old.timezone)
        val child =
            reminders.create(
                NewReminder(
                    old.userId,
                    text?.let(::normalizeText) ?: old.text,
                    old.timezone,
                    newLocal,
                    target?.instant ?: occurrence,
                    parentId = old.id,
                ),
                now,
            )
        val changed = old.copy(excludedOccurrences = old.excludedOccurrences + occurrence)
        val saved =
            reminders.save(
                changed.copy(
                    nextFireAt =
                        if (old.status ==
                            ReminderStatus.ACTIVE
                        ) {
                            schedule.pending(changed, now)
                        } else {
                            null
                        },
                ),
                now,
            )

        return ReminderChange(child, saved)
    }

    suspend fun delete(
        userId: Long,
        id: Long,
        scope: String?,
        occurrenceLocal: LocalDateTime?,
        confirmation: Confirmation?,
    ): Reminder =
        atomic(userId) {
            val old = requireReminder(userId, id)
            if (old.status !in setOf(ReminderStatus.ACTIVE, ReminderStatus.PAUSED)) invalidState()
            validateScope(old, scope, occurrenceLocal)
            verify(confirmation, fingerprint(listOf(old) + children(userId, id)))
            val now = clock.instant()
            if (scope != "occurrence" &&
                old.nextFireAt != null &&
                old.nextFireAt <= now
            ) {
                throw DomainException(ErrorCode.CONFIRMATION_STALE)
            }
            if (scope == "occurrence") {
                if (occurrenceLocal != null && occurrenceLocal.atZone(old.timezone).toInstant() <= now) {
                    throw DomainException(ErrorCode.CONFIRMATION_STALE)
                }
                val at = occurrence(old, occurrenceLocal, now)
                val changed = old.copy(excludedOccurrences = old.excludedOccurrences + at)
                return@atomic reminders.save(
                    changed.copy(
                        nextFireAt =
                            if (old.status ==
                                ReminderStatus.ACTIVE
                            ) {
                                schedule.pending(changed, now)
                            } else {
                                null
                            },
                    ),
                    now,
                )
            }
            children(
                userId,
                id,
            ).forEach {
                reminders.save(
                    it.copy(status = ReminderStatus.DELETED, nextFireAt = null, snoozedUntil = null, pausedUntil = null),
                    now,
                )
            }
            reminders.save(old.copy(status = ReminderStatus.DELETED, nextFireAt = null, snoozedUntil = null, pausedUntil = null), now)
        }

    /** Удаляет весь подтверждённый набор атомарно; новые или изменённые записи делают карточку устаревшей. */
    suspend fun deleteAll(
        userId: Long,
        confirmation: Confirmation?,
    ): Int =
        atomic(userId) {
            val all = reminders.list(userId)
            if (all.any { it.status == ReminderStatus.UNSUPPORTED }) throw DomainException(ErrorCode.UNSUPPORTED_STATE)
            val targets = all.filter { it.status in setOf(ReminderStatus.ACTIVE, ReminderStatus.PAUSED) }
            verify(confirmation, fingerprint(targets))
            val now = clock.instant()
            if (targets.any { it.nextFireAt != null && it.nextFireAt <= now }) throw DomainException(ErrorCode.CONFIRMATION_STALE)
            targets.forEach {
                reminders.save(it.copy(status = ReminderStatus.DELETED, nextFireAt = null, snoozedUntil = null, pausedUntil = null), now)
            }
            targets.size
        }

    suspend fun snooze(
        userId: Long,
        id: Long,
        at: When,
    ): Reminder =
        atomic(userId) {
            val old = requireReminder(userId, id)
            if (old.status !in setOf(ReminderStatus.ACTIVE, ReminderStatus.DONE)) invalidState()
            val now = clock.instant()
            val resolved = resolver.resolve(at, now, old.timezone)
            val changed =
                if (old.recurrenceRule == null) {
                    old.copy(
                        status = ReminderStatus.ACTIVE,
                        startLocal = resolved.local,
                        nextFireAt = resolved.instant,
                        snoozedUntil = null,
                    )
                } else {
                    old.copy(snoozedUntil = resolved.instant)
                }
            val planned =
                if (old.recurrenceRule == null) {
                    resolved.instant
                } else {
                    schedule.latestDue(old, now) ?: schedule.nextPlanned(old, now)
                }
            val next = listOfNotNull(planned, changed.snoozedUntil).minOrNull()
            reminders.save(changed.copy(nextFireAt = next, status = ReminderStatus.ACTIVE), now)
        }

    suspend fun pause(
        userId: Long,
        id: Long,
        resumeOn: LocalDate?,
        resumeAfter: LocalDate?,
    ): Reminder =
        atomic(userId) {
            val old = requireReminder(userId, id)
            if (old.recurrenceRule == null) throw DomainException(ErrorCode.NOT_RECURRING)
            if (old.status != ReminderStatus.ACTIVE) invalidState()
            if (resumeOn != null && resumeAfter != null) invalid()
            val now = clock.instant()
            val resume = (resumeOn ?: resumeAfter?.plusDays(1))?.atStartOfDay(old.timezone)?.toInstant()
            if (resume != null && resume <= now) throw DomainException(ErrorCode.TIME_IN_PAST)
            reminders.save(old.copy(status = ReminderStatus.PAUSED, nextFireAt = null, snoozedUntil = null, pausedUntil = resume), now)
        }

    suspend fun resume(
        userId: Long,
        id: Long,
    ): Reminder =
        atomic(userId) {
            val old = requireReminder(userId, id)
            if (old.recurrenceRule == null) throw DomainException(ErrorCode.NOT_RECURRING)
            if (old.status != ReminderStatus.PAUSED) invalidState()
            val now = clock.instant()
            val next = schedule.nextPlanned(old, now)
            reminders.save(
                old.copy(
                    status = if (next == null) ReminderStatus.DONE else ReminderStatus.ACTIVE,
                    nextFireAt = next,
                    pausedUntil = null,
                    snoozedUntil = null,
                ),
                now,
            )
        }

    private suspend fun active(userId: Long): List<Reminder> =
        reminders.list(userId).filter { it.status in setOf(ReminderStatus.ACTIVE, ReminderStatus.PAUSED) }

    private suspend fun children(
        userId: Long,
        id: Long,
    ): List<Reminder> {
        val rows = reminders.list(userId)
        val descendants = mutableSetOf(id)
        var added: Boolean
        do {
            added = false
            for (row in rows) {
                if (row.parentId in descendants && descendants.add(row.id)) added = true
            }
        } while (added)
        return rows.filter { it.id != id && it.id in descendants && it.status in setOf(ReminderStatus.ACTIVE, ReminderStatus.PAUSED) }
    }

    private suspend fun requireReminder(
        userId: Long,
        id: Long,
    ): Reminder {
        val result = reminders.find(userId, id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (result.status == ReminderStatus.UNSUPPORTED) throw DomainException(ErrorCode.UNSUPPORTED_STATE)
        return result
    }

    private fun validateScope(
        reminder: Reminder,
        scope: String?,
        occurrence: LocalDateTime?,
    ) {
        if (scope != null && scope !in setOf("series", "occurrence")) invalid()
        if (reminder.recurrenceRule != null && scope == null) invalid()
        if (reminder.recurrenceRule == null && scope != null) throw DomainException(ErrorCode.NOT_RECURRING)
        if (scope != "occurrence" && occurrence != null) invalid()
    }

    private fun occurrence(
        reminder: Reminder,
        local: LocalDateTime?,
        now: Instant,
    ): Instant {
        val target = local?.atZone(reminder.timezone)?.toInstant() ?: schedule.nextPlanned(reminder, now)
        if (target == null || target <= now || target in reminder.excludedOccurrences) {
            throw DomainException(ErrorCode.OCCURRENCE_NOT_FOUND)
        }
        if (schedule.planned(reminder).takeWhile { it.instant <= target }.none { it.instant == target }) {
            throw DomainException(ErrorCode.OCCURRENCE_NOT_FOUND)
        }
        return target
    }

    private fun verify(
        confirmation: Confirmation?,
        expected: String,
    ) {
        if (confirmation == null) throw DomainException(ErrorCode.CONFIRMATION_REQUIRED)
        val now = clock.instant()
        if (confirmation.createdAt > now || now >= confirmation.createdAt.plus(CONFIRMATION_TTL) || confirmation.fingerprint != expected) {
            throw DomainException(ErrorCode.CONFIRMATION_STALE)
        }
    }

    private fun fingerprint(rows: List<Reminder>): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                rows
                    .sortedBy { it.id }
                    .joinToString("\n") { row ->
                        listOf(
                            row.id,
                            row.text,
                            row.timezone,
                            row.startLocal,
                            row.recurrenceRule,
                            row.status,
                            row.excludedOccurrences.sorted(),
                            row.snoozedUntil,
                            row.pausedUntil,
                            row.parentId,
                            row.nextFireAt,
                        ).toString()
                    }.toByteArray(),
            ).joinToString("") { "%02x".format(it) }

    private fun normalizeText(text: String): String =
        text.trim().also {
            if (it.isEmpty() || it.length > MAX_TEXT_LENGTH) invalid()
        }

    private fun invalid(): Nothing = throw DomainException(ErrorCode.INVALID_ARGUMENTS)

    private fun invalidState(): Nothing = throw DomainException(ErrorCode.INVALID_STATE)

    companion object {
        const val MAX_TEXT_LENGTH = 1000
        private const val MAX_PERIOD_DAYS = 92L
        private const val MAX_OCCURRENCES = 200
        private const val UPCOMING_LIMIT = 5
        private const val FIND_LIMIT = 30
        val CONFIRMATION_TTL: Duration = Duration.ofHours(1)
    }
}
