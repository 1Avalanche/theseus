package theseus.agent.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import theseus.agent.texts.Texts
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ReplyRendererTest {
    private val renderer = ReplyRenderer(Texts.load("texts/agent.properties"))
    private val today = LocalDate.of(2026, 11, 2)

    @Test
    fun `describes today tomorrow and other dates in russian`() {
        assertEquals("сегодня в 10:20", renderer.describe(LocalDateTime.of(2026, 11, 2, 10, 20), today))
        assertEquals("завтра в 09:00", renderer.describe(LocalDateTime.of(2026, 11, 3, 9, 0), today))
        assertEquals("5 ноября в 09:00", renderer.describe(LocalDateTime.of(2026, 11, 5, 9, 0), today))
        assertEquals("1 января 2027 в 12:00", renderer.describe(LocalDateTime.of(2027, 1, 1, 12, 0), today))
    }

    @Test
    fun `today is taken in user timezone`() {
        val json = """{"status":"created","reminder":{"text":"X","next_at_local":"2026-11-03T01:00"}}"""
        // 2026-11-02T20:30Z: в Москве ещё 2 ноября (23:30), в Екатеринбурге уже 3 ноября (01:30).
        val now = Instant.parse("2026-11-02T20:30:00Z")

        assertEquals("Поставил: «X» — завтра в 01:00.", renderer.render("create_reminder", json, ZoneId.of("Europe/Moscow"), now))
        assertEquals("Поставил: «X» — сегодня в 01:00.", renderer.render("create_reminder", json, ZoneId.of("Asia/Yekaterinburg"), now))
    }

    @Test
    fun `unknown result or tool is left to the model`() {
        val zone = ZoneId.of("Europe/Moscow")
        val now = Instant.parse("2026-11-02T07:00:00Z")

        assertNull(renderer.render("create_reminder", """{"status":"possible_duplicate","candidates":[]}""", zone, now))
        assertNull(renderer.render("create_reminder", "не json", zone, now))
        assertNull(renderer.render("list_reminders", """{"reminders":[]}""", zone, now))
    }
}
