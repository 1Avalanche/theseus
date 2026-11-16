package theseus.agent.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolResultContextTest {
    @Test fun `any incomplete search prevents a later selected deletion`() {
        val calls = listOf(theseus.agent.llm.ToolCall("delete", "delete_reminder", """{"scope":"selected","reminder_ids":[1]}"""))
        assertTrue(ToolResultContext.incompleteSelection(listOf("""{"candidates":[],"truncated":true}""", """{"candidates":[]}"""), calls))
    }

    @Test fun `search compression preserves every candidate field and value including nulls`() {
        val original =
            Json
                .parseToJsonElement(
                    """{"candidates":[
            {"id":1,"text":"Пить воду","timezone":"UTC","schedule":{"time":"10:00"},"paused":null},
            {"id":2,"text":"Купить хлеб","timezone":"UTC","schedule":{"time":"11:00"},"paused":null},
            {"id":3,"text":"Купить воду","timezone":"UTC","schedule":{"time":"12:00"},"paused":null}
        ],"extra":"сохранить"}""",
                ).jsonObject
        val packed = Json.parseToJsonElement(ToolResultContext.compact("find_reminders", original.toString())).jsonObject
        val fields = packed.getValue("candidate_fields").jsonArray.map { it.jsonPrimitive.content }
        val common = packed.getValue("candidate_common").jsonObject
        val unpacked =
            packed.getValue("candidates").jsonArray.map { row ->
                JsonObject(common + fields.zip(row.jsonArray))
            }
        assertEquals(original.getValue("candidates"), JsonArray(unpacked))
        assertEquals(original["extra"], packed["extra"])
        assertTrue(packed.toString().length < original.toString().length)
    }

    @Test fun `compression never changes tools arguments malformed results or mixed candidate shapes`() {
        for (text in listOf("invalid", "{}", """{"candidates":[]}""", """{"candidates":[{"id":1},{"id":2,"text":null}]}""")) {
            assertEquals(text, ToolResultContext.compact("find_reminders", text))
        }
        val mutation = """{"reminder_id":1,"recurrence":{"until":null}}"""
        assertEquals(mutation, ToolResultContext.compact("update_reminder", mutation))
    }
}
