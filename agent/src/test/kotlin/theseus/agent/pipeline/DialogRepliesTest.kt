package theseus.agent.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.agent.core.CoreUser
import theseus.agent.texts.Texts

class DialogRepliesTest {
    private val texts = Texts.load("texts/agent.properties")
    private val replies = DialogReplies(texts)
    private val user = CoreUser(7, 4242, "active", "Настя", "female", "Asia/Yekaterinburg")

    @Test
    fun `start is repeatable and independent of bot username or start payload`() {
        val expected = texts.get("reply.welcome_named", mapOf("name" to "Настя"))
        for (command in listOf("/start", "/start", "/start@theseus_test", "/start@theseus_prod", "/start payload")) {
            assertEquals(expected, replies.commandReply(command, user))
        }
        assertNull(replies.commandReply("/starter", user))
        assertNull(replies.commandReply("кто ты", user))
    }

    @Test
    fun `help describes only available functionality`() {
        assertEquals(texts.get("reply.capabilities"), replies.commandReply("/help", user))
        assertEquals(texts.get("reply.capabilities"), replies.commandReply("/help@theseus_prod", user))
    }

    @Test
    fun `missing profile name does not leak placeholder or infer name`() {
        for (name in listOf(null, "", " ")) {
            assertEquals(texts.get("reply.welcome"), replies.commandReply("/start", user.copy(name = name)))
        }
    }

    @Test
    fun `model selects approved topic without supplying user facing text`() {
        val result = replies.execute(user, Json.parseToJsonElement("""{"topic":"about"}""").jsonObject)

        assertFalse(result.isError)
        assertEquals(texts.get("reply.about"), result.text)
        assertTrue(result.text.contains("ИИ-ассистент"))
    }

    @Test
    fun `model cannot override template profile or confirm imaginary changes`() {
        val invalid =
            listOf(
                "{}",
                """{"topic":"created"}""",
                """{"topic":true}""",
                """{"topic":null}""",
                """{"topic":"about","text":"Календарь создан"}""",
                """{"topic":"greeting","name":"Чужое имя"}""",
            )
        for (arguments in invalid) {
            val result = replies.execute(user, Json.parseToJsonElement(arguments).jsonObject)
            assertTrue(result.isError, arguments)
            assertEquals(texts.get("tool.invalid_dialogue"), result.text)
        }
    }

    @Test
    fun `clarification requests complete input because history is not available`() {
        val result = replies.execute(user, Json.parseToJsonElement("""{"topic":"clarify"}""").jsonObject)

        assertFalse(result.isError)
        assertEquals(texts.get("reply.clarify"), result.text)
        assertTrue(result.text.contains("целиком"))
    }
}
