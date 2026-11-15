package theseus.agent.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.agent.texts.Texts
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ReplyRendererTest {
    @Test
    fun `recurrence uses plain frequency and hides technical interval`() {
        for ((frequency, interval, expected) in listOf(
            Triple("daily", 1, "ежедневно"),
            Triple("weekly", 2, "раз в 2 недели"),
            Triple("monthly", 5, "раз в 5 месяцев"),
            Triple("yearly", 21, "раз в 21 год"),
            Triple("daily", 11, "раз в 11 дней"),
        )) {
            val row = """{"reminder":{"text":"Пить воду","schedule":
                {"recurrence":{"freq":"$frequency","interval":$interval,"time":"18:45"}}}}"""
            val reply = requireNotNull(renderer.render("get_reminder", row, zone, now))
            assertEquals("«Пить воду» — $expected в 18:45", reply)
        }
    }

    @Test
    fun `duplicate card focuses on existing reminder and decision without technical fields`() {
        val reminder = """{"text":"Пить воду","schedule":{"recurrence":{"freq":"daily","interval":1,"time":"18:45"}}}"""
        val data = Json.parseToJsonElement("""{"proposed":$reminder,"candidates":[$reminder]}""").jsonObject
        val args =
            Json
                .parseToJsonElement(
                    """{"recurrence":{"freq":"daily","interval":1,"by_weekday":[],"by_month_day":[],"by_month":[],
                        "time":"18:45","start_date":"2026-11-15","until":null,"count":null}}""",
                ).jsonObject
        val card = renderer.confirmation("create_reminder", args, data, zone, now)
        assertEquals(
            "Похоже, такое напоминание уже есть:\n«Пить воду» — ежедневно в 18:45\n\nСоздать ещё одно?",
            card,
        )
        assertFalse(card.contains("Интервал"))
        assertFalse(card.contains("по умолчанию"))
        assertTrue(card.length < 200)
    }

    @Test
    fun `different duplicate proposal keeps new schedule visible`() {
        val data =
            Json
                .parseToJsonElement(
                    """{"proposed":{"text":"Пить воду","next_at_local":"2026-11-15T19:00"},
                "candidates":[{"text":"Пить воду","next_at_local":"2026-11-15T18:45"}]}""",
                ).jsonObject
        val card = renderer.confirmation("create_reminder", Json.parseToJsonElement("{}").jsonObject, data, zone, now)
        assertTrue(card.contains("18:45"), card)
        assertTrue(card.contains("Новое: «Пить воду» — сегодня в 19:00"), card)
    }

    private val zone = ZoneId.of("Europe/Moscow")
    private val now = Instant.parse("2026-11-15T10:00:00Z")

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
        assertEquals("Напоминаний пока нет.", renderer.render("list_reminders", """{"reminders":[]}""", zone, now))
    }
}
