package theseus.core.adapters.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.core.application.InMemoryOperations
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
    @Test
    fun `invalid clock time is specific for one time recurrence and occurrence and preserves target`() =
        runTest {
            for (time in listOf("25:00", "15:75", "15 вечера")) {
                assertEquals("invalid_time", errorCode(tools.createReminder(1, args("""{"text":"X","when":{"time":"$time"}}"""))))
                assertEquals(
                    "invalid_time",
                    errorCode(tools.createReminder(1, args("""{"text":"X","recurrence":{"freq":"daily","time":"$time"}}"""))),
                )
            }
            tools.createReminder(1, args("""{"text":"Билеты","recurrence":{"freq":"daily","time":"20:00"}}"""))
            assertEquals("invalid_time", errorCode(change("""{"reminder_id":1,"scope":"series","recurrence":{"time":"25:00"}}""")))
            assertEquals(
                "invalid_time",
                errorCode(
                    change("""{"reminder_id":1,"scope":"occurrence","occurrence_local":"2026-11-03T25:00","when":{"time":"21:00"}}"""),
                ),
            )
            val unchanged = tools.call("get_reminder", 1, args("""{"reminder_id":1}"""), null)
            assertTrue(unchanged.json.contains("20:00"), unchanged.json)
            assertFalse(unchanged.json.contains("21:00"), unchanged.json)
        }

    @Test
    fun `time only when updates series without losing calendar`() =
        runTest {
            tools.createReminder(1, args("""{"text":"Пить воду","recurrence":{"freq":"weekly","by_weekday":["MO","FR"],"time":"18:45"}}"""))
            val result = change("""{"reminder_id":1,"scope":"series","when":{"time":"19:00"}}""")
            assertFalse(result.isError, result.json)
            val recurrence =
                args(
                    result.json,
                ).getValue("reminder").jsonObject.getValue("schedule").jsonObject.getValue("recurrence").jsonObject
            assertEquals("19:00", recurrence.getValue("time").jsonPrimitive.content)
            assertEquals("weekly", recurrence.getValue("freq").jsonPrimitive.content)
            assertEquals(
                setOf("MO", "FR"),
                recurrence
                    .getValue("by_weekday")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
                    .toSet(),
            )
        }

    @Test
    fun `date only occurrence and new time preserve selected day and other occurrences`() =
        runTest {
            tools.createReminder(1, args("""{"text":"Пить воду","recurrence":{"freq":"daily","time":"18:45"}}"""))
            val result = change("""{"reminder_id":1,"scope":"occurrence","occurrence_local":"2026-11-03","when":{"time":"20:00"}}""")
            assertFalse(result.isError, result.json)
            assertTrue(result.json.contains("2026-11-03T20:00"), result.json)
            val tomorrow = tools.call("list_reminders", 1, args("""{"from":"2026-11-03","to":"2026-11-03"}"""), null)
            assertTrue(tomorrow.json.contains("2026-11-03T20:00"), tomorrow.json)
            assertFalse(tomorrow.json.contains("\"at_local\":\"2026-11-03T18:45\""), tomorrow.json)
            val dayAfter = tools.call("list_reminders", 1, args("""{"from":"2026-11-04","to":"2026-11-04"}"""), null)
            assertTrue(dayAfter.json.contains("\"at_local\":\"2026-11-04T18:45\""), dayAfter.json)
        }

    @Test
    fun `impossible dates in list recurrence and occurrence get specific error`() =
        runTest {
            assertEquals(
                "invalid_date",
                errorCode(tools.call("list_reminders", 1, args("""{"from":"2026-11-31","to":"2026-11-31"}"""), null)),
            )
            assertEquals(
                "invalid_date",
                errorCode(tools.createReminder(1, args("""{"text":"X","recurrence":{"freq":"daily","until":"2026-11-31"}}"""))),
            )
            tools.createReminder(1, args("""{"text":"Пить воду","recurrence":{"freq":"daily","time":"18:45"}}"""))
            assertEquals(
                "invalid_date",
                errorCode(change("""{"reminder_id":1,"scope":"occurrence","occurrence_local":"2026-11-31","when":{"time":"20:00"}}""")),
            )
        }

    @Test
    fun `delete all rejects target fields and requires confirmation`() =
        runTest {
            val key =
                java.util.UUID
                    .randomUUID()
                    .toString()
            assertEquals("invalid_arguments", errorCode(tools.call("delete_reminder", 1, args("""{"scope":"all","reminder_id":1}"""), key)))
            assertEquals(
                "confirmation_required",
                errorCode(
                    tools.call(
                        "delete_reminder",
                        1,
                        args("""{"scope":"all"}"""),
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    ),
                ),
            )
        }

    private suspend fun change(value: String) =
        tools.call(
            "update_reminder",
            1,
            args(value),
            java.util.UUID
                .randomUUID()
                .toString(),
        )

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
            InMemoryOperations(),
        )

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `rejects unknown null recurrence field before merging patch`() =
        runTest {
            tools.createReminder(1, args("""{"text":"X","recurrence":{"freq":"daily"}}"""))
            val result =
                tools.call(
                    "update_reminder",
                    1,
                    args("""{"reminder_id":1,"scope":"series","recurrence":{"unknown_field":null}}"""),
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                )
            assertEquals("invalid_arguments", errorCode(result))
        }

    @Test
    fun `mutating MCP requires operation key while reads do not`() =
        runTest {
            val result = tools.call("create_reminder", 1, args("""{"text":"X","when":{"after":{"minutes":5}}}"""), null)
            assertEquals("invalid_arguments", errorCode(result))
            assertFalse(tools.call("list_reminders", 1, args("{}"), null).isError)
        }

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
                    """"timezone":"Europe/Moscow","schedule":{"at_local":"2026-11-02T10:20"},"next_at_local":"2026-11-02T10:20",""" +
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
    fun `malformed date has specific error`() =
        runTest {
            assertEquals("invalid_date", errorCode(tools.createReminder(1, args("""{"text":"X","when":{"date":"2026-13-01"}}"""))))
        }

    @Test
    fun `missing when is invalid arguments`() =
        runTest {
            assertEquals("invalid_arguments", errorCode(tools.createReminder(1, args("""{"text":"X"}"""))))
            assertEquals("invalid_arguments", errorCode(tools.createReminder(1, JsonObject(emptyMap()))))
        }

    @Test
    fun `creates recurring reminder`() =
        runTest {
            val outcome = tools.createReminder(1, args("""{"text":"X","recurrence":{"freq":"daily"}}"""))

            assertFalse(outcome.isError, outcome.json)
            assertTrue(outcome.json.contains(""""kind":"recurring""""))
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

        assertEquals(listOf("text"), tool.inputSchema.required)
        assertTrue(tool.description!!.startsWith("Создать напоминание"))
    }
}
