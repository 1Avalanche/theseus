package theseus.core.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import theseus.core.domain.user.Gender
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

class CoreConfigTest {
    private val valid =
        mapOf(
            "THESEUS_DB_URL" to "jdbc:postgresql://db:5432/theseus_test",
            "THESEUS_DB_USER" to "theseus_test_core",
            "THESEUS_DB_PASSWORD" to "db-secret",
            "THESEUS_SERVICE_TOKEN" to "s".repeat(40),
            "THESEUS_TELEGRAM_BOT_TOKEN" to "123:abc",
            "THESEUS_ADMIN_CHAT_ID" to "123456789",
            "THESEUS_ADMIN_NAME" to "Аня",
            "THESEUS_ADMIN_TIMEZONE" to "Asia/Yekaterinburg",
        )

    @Test
    fun `reads required values and applies defaults`() {
        val config = CoreConfig.fromEnv(valid)

        assertEquals(8080, config.httpPort)
        assertEquals(123456789L, config.admin.chatId)
        assertEquals(ZoneId.of("Asia/Yekaterinburg"), config.admin.timezone)
        assertEquals(Gender.UNSPECIFIED, config.admin.gender)
        assertEquals(LocalTime.of(9, 0), config.defaultTime)
        assertEquals(Duration.ofSeconds(30), config.schedulerInterval)
    }

    @Test
    fun `reports all problems at once without secret values`() {
        val broken =
            valid - "THESEUS_DB_URL" +
                mapOf(
                    "THESEUS_SERVICE_TOKEN" to "short-secret",
                    "THESEUS_ADMIN_TIMEZONE" to "Mars/Olympus",
                    "THESEUS_HTTP_PORT" to "99999",
                )

        val error = assertThrows<ConfigException> { CoreConfig.fromEnv(broken) }

        val message = error.message!!
        listOf("THESEUS_DB_URL", "THESEUS_SERVICE_TOKEN", "THESEUS_ADMIN_TIMEZONE", "THESEUS_HTTP_PORT").forEach {
            assertTrue(it in message, "нет $it в: $message")
        }
        assertFalse("short-secret" in message)
    }

    @Test
    fun `toString does not leak secrets`() {
        val text = CoreConfig.fromEnv(valid).toString()

        assertFalse("db-secret" in text || "123:abc" in text || "s".repeat(40) in text)
    }
}
