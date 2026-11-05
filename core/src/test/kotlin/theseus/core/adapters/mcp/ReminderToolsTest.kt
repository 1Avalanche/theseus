package theseus.core.adapters.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.core.application.InMemoryReminderRepository
import theseus.core.application.InMemoryUserRepository
import theseus.core.application.MutableClock
import theseus.core.application.ReminderService
import theseus.core.application.UserService
import theseus.core.application.user
import theseus.core.domain.reminder.WhenResolver
import theseus.core.texts.Texts
import java.time.Instant
import java.time.LocalTime

class ReminderToolsTest {
    private val clock = MutableClock(Instant.parse("2026-11-02T07:15:42Z"))
    private val tools =
        ReminderTools(
            ReminderService(
                InMemoryReminderRepository(),
                UserService(InMemoryUserRepository(user())),
                WhenResolver(LocalTime.of(9, 0)),
                clock,
            ),
            Texts.load("texts/core.properties"),
        )

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun errorCode(outcome: ToolOutcome): String {
        assertTrue(outcome.isError, outcome.json)
        return Json
            .parseToJsonElement(outcome.json)
            .jsonObject["error"]!!
            .jsonObject["code"]!!
            .jsonPrimitive.content
    }

    @Test
    fun `creates one time reminder from after`() =
        runTest {
            val outcome = tools.createReminder(1, args("""{"text":"Выключить духовку","when":{"after":{"minutes":5}}}"""))

            assertFalse(outcome.isError, outcome.json)
            assertEquals(
                """{"status":"created","reminder":{"id":1,"text":"Выключить духовку","kind":"one_time","status":"active",""" +
                    """"schedule":{"at_local":"2026-11-02T10:20"},"next_at_local":"2026-11-02T10:20",""" +
                    """"paused_until_local":null,"snoozed_until_local":null},"warnings":[]}""",
                outcome.json,
            )
        }

    @Test
    fun `missing user in meta is user inactive`() =
        runTest {
            assertEquals("user_inactive", errorCode(tools.createReminder(null, args("""{"text":"X","when":{"time":"18:00"}}"""))))
        }

    @Test
    fun `unknown argument is invalid arguments`() =
        runTest {
            val outcome = tools.createReminder(1, args("""{"text":"X","when":{"time":"18:00"},"user_id":2}"""))

            assertEquals("invalid_arguments", errorCode(outcome))
        }

    @Test
    fun `malformed date is invalid arguments`() =
        runTest {
            assertEquals("invalid_arguments", errorCode(tools.createReminder(1, args("""{"text":"X","when":{"date":"2026-13-01"}}"""))))
        }

    @Test
    fun `missing when is invalid arguments`() =
        runTest {
            assertEquals("invalid_arguments", errorCode(tools.createReminder(1, args("""{"text":"X"}"""))))
            assertEquals("invalid_arguments", errorCode(tools.createReminder(1, JsonObject(emptyMap()))))
        }

    @Test
    fun `recurrence is not supported yet`() =
        runTest {
            val outcome = tools.createReminder(1, args("""{"text":"X","recurrence":{"freq":"daily"}}"""))

            assertEquals("invalid_recurrence", errorCode(outcome))
        }

    @Test
    fun `both when and recurrence is invalid arguments`() =
        runTest {
            val outcome = tools.createReminder(1, args("""{"text":"X","when":{"time":"18:00"},"recurrence":{"freq":"daily"}}"""))

            assertEquals("invalid_arguments", errorCode(outcome))
        }

    @Test
    fun `time in past error carries russian message for model`() =
        runTest {
            val outcome = tools.createReminder(1, args("""{"text":"X","when":{"date":"2026-11-02","time":"09:00"}}"""))

            assertEquals("time_in_past", errorCode(outcome))
            val message =
                Json
                    .parseToJsonElement(outcome.json)
                    .jsonObject["error"]!!
                    .jsonObject["message"]!!
                    .jsonPrimitive.content
            assertEquals(
                "Время 2026-11-02T09:00 уже прошло, сейчас 2026-11-02T10:15. Уточни у пользователя, какое время он имел в виду.",
                message,
            )
        }

    @Test
    fun `tool schema and description load from resources`() {
        val tool = McpServerFactory.loadTool("create_reminder")

        assertEquals(listOf("text", "when"), tool.inputSchema.required)
        assertTrue(tool.description!!.startsWith("Создать напоминание"))
    }
}
