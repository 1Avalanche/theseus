package theseus.core.domain.reminder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import theseus.core.domain.DomainException
import theseus.core.domain.ErrorCode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class WhenResolverTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val resolver = WhenResolver(defaultTime = LocalTime.of(9, 0))

    // 2026-11-02 10:15:42 по Москве (UTC+3).
    private val now = Instant.parse("2026-11-02T07:15:42Z")

    @Test
    fun `after minutes shifts from now and drops seconds`() {
        val result = resolver.resolve(When(after = After(minutes = 5)), now, moscow)

        assertEquals(LocalDateTime.of(2026, 11, 2, 10, 20), result.local)
        assertEquals(Instant.parse("2026-11-02T07:20:00Z"), result.instant)
    }

    @Test
    fun `time later today resolves to today`() {
        val result = resolver.resolve(When(time = LocalTime.of(18, 30)), now, moscow)

        assertEquals(LocalDateTime.of(2026, 11, 2, 18, 30), result.local)
    }

    @Test
    fun `time already passed today resolves to tomorrow`() {
        val result = resolver.resolve(When(time = LocalTime.of(9, 0)), now, moscow)

        assertEquals(LocalDateTime.of(2026, 11, 3, 9, 0), result.local)
    }

    @Test
    fun `date without time uses default time`() {
        val result = resolver.resolve(When(date = LocalDate.of(2026, 11, 5)), now, moscow)

        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 0), result.local)
    }

    @Test
    fun `after days with time uses calendar days`() {
        val result = resolver.resolve(When(after = After(days = 7), time = LocalTime.of(10, 0)), now, moscow)

        assertEquals(LocalDateTime.of(2026, 11, 9, 10, 0), result.local)
    }

    @Test
    fun `after days crosses end of month`() {
        val endOfMonth = Instant.parse("2026-01-31T07:00:00Z")

        val result = resolver.resolve(When(after = After(days = 1)), endOfMonth, moscow)

        assertEquals(LocalDateTime.of(2026, 2, 1, 9, 0), result.local)
    }

    @Test
    fun `february 29 of leap year is accepted`() {
        val result = resolver.resolve(When(date = LocalDate.of(2028, 2, 29), time = LocalTime.of(12, 0)), now, moscow)

        assertEquals(Instant.parse("2028-02-29T09:00:00Z"), result.instant)
    }

    @Test
    fun `same local time differs in instant across user timezones`() {
        val yekaterinburg = ZoneId.of("Asia/Yekaterinburg")
        val at = When(date = LocalDate.of(2026, 11, 3), time = LocalTime.of(10, 0))

        assertEquals(Instant.parse("2026-11-03T07:00:00Z"), resolver.resolve(at, now, moscow).instant)
        assertEquals(Instant.parse("2026-11-03T05:00:00Z"), resolver.resolve(at, now, yekaterinburg).instant)
    }

    @Test
    fun `nonexistent local time in spring gap shifts forward`() {
        val beforeGap = Instant.parse("2026-03-28T12:00:00Z")

        val result = resolver.resolve(When(date = LocalDate.of(2026, 3, 29), time = LocalTime.of(2, 30)), beforeGap, berlin)

        assertEquals(LocalDateTime.of(2026, 3, 29, 3, 30), result.local)
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), result.instant)
    }

    @Test
    fun `ambiguous local time in autumn overlap takes earlier offset`() {
        val beforeOverlap = Instant.parse("2026-10-24T12:00:00Z")

        val result = resolver.resolve(When(date = LocalDate.of(2026, 10, 25), time = LocalTime.of(2, 30)), beforeOverlap, berlin)

        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), result.instant)
    }

    @Test
    fun `after hours across spring gap keeps elapsed duration`() {
        val beforeGap = Instant.parse("2026-03-29T00:30:00Z")

        val result = resolver.resolve(When(after = After(hours = 2)), beforeGap, berlin)

        assertEquals(Instant.parse("2026-03-29T02:30:00Z"), result.instant)
        assertEquals(LocalDateTime.of(2026, 3, 29, 4, 30), result.local)
    }

    @Test
    fun `date and time in the past is rejected`() {
        val error =
            assertThrows<DomainException> {
                resolver.resolve(When(date = LocalDate.of(2026, 11, 2), time = LocalTime.of(9, 0)), now, moscow)
            }

        assertEquals(ErrorCode.TIME_IN_PAST, error.error)
        assertEquals("2026-11-02T10:15", error.details["now_local"])
    }

    @Test
    fun `more than five years ahead is rejected`() {
        val error = assertThrows<DomainException> { resolver.resolve(When(date = LocalDate.of(2032, 1, 1)), now, moscow) }

        assertEquals(ErrorCode.TOO_FAR, error.error)
    }

    @Test
    fun `contradictory fields are rejected as invalid when`() {
        val invalid =
            listOf(
                When(),
                When(after = After()),
                When(after = After(days = 1), date = LocalDate.of(2026, 11, 5)),
                When(after = After(hours = 1), time = LocalTime.of(10, 0)),
                When(after = After(minutes = -5)),
            )

        invalid.forEach { value ->
            val error = assertThrows<DomainException> { resolver.resolve(value, now, moscow) }
            assertEquals(ErrorCode.INVALID_WHEN, error.error, "для $value")
        }
    }
}
