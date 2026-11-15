package theseus.core.domain.reminder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import theseus.core.domain.DomainException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class RecurrenceResolverTest {
    @Test
    fun `resolves configured part of day and keeps normalized series independent of later settings`() {
        val morning = java.time.LocalTime.of(8, 15)
        val configured = RecurrenceResolver(dayTimes = DayTimes(morning = morning))
        val schedule = configured.normalize(Recurrence("daily", partOfDay = PartOfDay.MORNING), LocalDate.parse("2026-11-01"))
        assertEquals(morning, schedule.startLocal.toLocalTime())
        assertThrows(DomainException::class.java) {
            configured.normalize(
                Recurrence("daily", time = java.time.LocalTime.NOON, partOfDay = PartOfDay.MORNING),
                LocalDate.parse("2026-11-01"),
            )
        }
        val moment =
            WhenResolver(java.time.LocalTime.of(9, 0), dayTimes = DayTimes(day = java.time.LocalTime.of(13, 20)))
                .resolve(When(partOfDay = PartOfDay.DAY), Instant.parse("2026-11-01T10:00:00Z"), ZoneId.of("UTC"))
        assertEquals("2026-11-01T13:20", moment.local.toString())
    }

    private val resolver = RecurrenceResolver()
    private val zone = ZoneId.of("Europe/Berlin")

    private fun times(
        value: Recurrence,
        date: String,
        count: Int,
    ): List<ResolvedTime> {
        val schedule = resolver.normalize(value, LocalDate.parse(date))
        return resolver.occurrences(schedule.startLocal, schedule.rule, zone).take(count).toList()
    }

    @Test
    fun `keeps wall time through spring gap and autumn overlap`() {
        val spring = times(Recurrence("daily", time = LocalTime.of(2, 30)), "2026-03-28", 3)
        assertEquals(
            listOf("2026-03-28T01:30:00Z", "2026-03-29T01:30:00Z", "2026-03-30T00:30:00Z"),
            spring.map { it.instant.toString() },
        )
        val autumn = times(Recurrence("daily", time = LocalTime.of(2, 30)), "2026-10-24", 3)
        assertEquals(
            listOf("2026-10-24T00:30:00Z", "2026-10-25T00:30:00Z", "2026-10-26T01:30:00Z"),
            autumn.map { it.instant.toString() },
        )
    }

    @Test
    fun `monthly skips short months instead of clipping dates`() {
        assertEquals(
            listOf("2026-01-31", "2026-03-31", "2026-05-31"),
            times(Recurrence("monthly"), "2026-01-31", 3).map { it.local.toLocalDate().toString() },
        )
    }

    @Test
    fun `yearly leap day skips non leap years`() {
        assertEquals(listOf(2024, 2028, 2032), times(Recurrence("yearly"), "2024-02-29", 3).map { it.local.year })
    }

    @Test
    fun `supports ordinal weekdays negative month days and interval`() {
        assertEquals(
            listOf("2026-11-27", "2026-12-25"),
            times(Recurrence("monthly", byWeekday = listOf("-1FR")), "2026-11-01", 2).map { it.local.toLocalDate().toString() },
        )
        assertEquals(
            listOf("2026-11-30", "2027-01-31"),
            times(Recurrence("monthly", interval = 2, byMonthDay = listOf(-1)), "2026-11-01", 2)
                .map { it.local.toLocalDate().toString() },
        )
    }

    @Test
    fun `count and inclusive until bound calendar occurrences`() {
        assertEquals(2, times(Recurrence("daily", count = 2), "2026-11-01", 10).size)
        assertEquals(3, times(Recurrence("daily", until = LocalDate.parse("2026-11-03")), "2026-11-01", 10).size)
        assertThrows(DomainException::class.java) {
            resolver.normalize(Recurrence("daily", count = 2, until = LocalDate.parse("2026-11-03")), LocalDate.parse("2026-11-01"))
        }
    }

    @Test
    fun `count includes excluded and past occurrences`() {
        val schedule = resolver.normalize(Recurrence("daily", count = 3), LocalDate.parse("2026-11-01"))
        val next =
            resolver.next(
                schedule.startLocal,
                schedule.rule,
                zone,
                Instant.parse("2026-11-01T12:00:00Z"),
                setOf(Instant.parse("2026-11-02T08:00:00Z")),
            )
        assertEquals(Instant.parse("2026-11-03T08:00:00Z"), next?.instant)
    }
}
