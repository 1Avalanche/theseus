package theseus.core.domain.reminder

import theseus.core.domain.DomainException
import theseus.core.domain.ErrorCode
import theseus.core.domain.LocalFormats
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Сдвиг от текущего момента: «через 2 часа», «через неделю». */
data class After(
    val days: Int = 0,
    val hours: Int = 0,
    val minutes: Int = 0,
) {
    val hasClockPart: Boolean get() = hours != 0 || minutes != 0
    val isEmpty: Boolean get() = days == 0 && !hasClockPart
}

/** Момент срабатывания так, как его сказал пользователь (контракт `When` в `docs/api/mcp.md`). */
data class When(
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val after: After? = null,
)

/** Разрешённый момент: локальное «настенное» время в таймзоне и соответствующий момент UTC. */
data class ResolvedTime(
    val local: LocalDateTime,
    val instant: Instant,
)

/**
 * Переводит [When] в конкретный момент в таймзоне пользователя. Всё вычисление времени — здесь, не в модели.
 *
 * - `time` без `date`/`after` — ближайшее будущее `HH:MM` (сегодня или завтра);
 * - `date` без `time` — [defaultTime];
 * - `after` с часами/минутами — сдвиг по шкале времени, без `date`/`time`;
 * - `after` только с днями — календарные дни, время — `time` или [defaultTime].
 *
 * Несуществующее локальное время (переход на летнее) сдвигается вперёд на величину перехода,
 * неоднозначное (переход на зимнее) — берётся более раннее смещение: это поведение
 * [ZonedDateTime.ofLocal] без предпочтительного смещения. Точность — минута (секунды отбрасываются).
 */
class WhenResolver(
    private val defaultTime: LocalTime,
    private val maxYearsAhead: Long = MAX_YEARS_AHEAD,
) {
    fun resolve(
        value: When,
        now: Instant,
        zone: ZoneId,
    ): ResolvedTime {
        validate(value)
        val nowLocal = ZonedDateTime.ofInstant(now, zone)
        val after = value.after
        val resolved =
            when {
                after != null && after.hasClockPart -> fromClockShift(nowLocal, after)
                after != null -> atLocal(nowLocal.toLocalDate().plusDays(after.days.toLong()), value.time, zone)
                value.date != null -> atLocal(value.date, value.time, zone)
                else -> nearestTime(nowLocal, value.time!!, zone)
            }
        checkBounds(resolved, now, zone)
        return resolved
    }

    private fun validate(value: When) {
        val after = value.after
        val consistent =
            when {
                after == null -> value.date != null || value.time != null
                after.days < 0 || after.hours < 0 || after.minutes < 0 -> false
                after.isEmpty -> false
                value.date != null -> false
                after.hasClockPart -> value.time == null
                else -> true
            }
        if (!consistent) throw DomainException(ErrorCode.INVALID_WHEN)
    }

    private fun fromClockShift(
        nowLocal: ZonedDateTime,
        after: After,
    ): ResolvedTime {
        val target =
            nowLocal
                .plusDays(after.days.toLong())
                .plusHours(after.hours.toLong())
                .plusMinutes(after.minutes.toLong())
                .truncatedTo(ChronoUnit.MINUTES)
        return ResolvedTime(target.toLocalDateTime(), target.toInstant())
    }

    private fun atLocal(
        date: LocalDate,
        time: LocalTime?,
        zone: ZoneId,
    ): ResolvedTime {
        val zoned = ZonedDateTime.ofLocal(LocalDateTime.of(date, time ?: defaultTime), zone, null)
        return ResolvedTime(zoned.toLocalDateTime(), zoned.toInstant())
    }

    private fun nearestTime(
        nowLocal: ZonedDateTime,
        time: LocalTime,
        zone: ZoneId,
    ): ResolvedTime {
        val today = atLocal(nowLocal.toLocalDate(), time, zone)
        return if (today.instant.isAfter(nowLocal.toInstant())) today else atLocal(nowLocal.toLocalDate().plusDays(1), time, zone)
    }

    private fun checkBounds(
        resolved: ResolvedTime,
        now: Instant,
        zone: ZoneId,
    ) {
        val nowLocal = LocalFormats.formatMoment(LocalDateTime.ofInstant(now, zone))
        if (!resolved.instant.isAfter(now)) {
            throw DomainException(
                ErrorCode.TIME_IN_PAST,
                mapOf("at_local" to LocalFormats.formatMoment(resolved.local), "now_local" to nowLocal),
            )
        }
        val limit = ZonedDateTime.ofInstant(now, zone).plusYears(maxYearsAhead).toInstant()
        if (resolved.instant.isAfter(limit)) {
            throw DomainException(ErrorCode.TOO_FAR, mapOf("max_years" to maxYearsAhead.toString()))
        }
    }

    companion object {
        const val MAX_YEARS_AHEAD = 5L
    }
}
