package theseus.agent.config

import java.nio.file.Path
import java.time.Duration

/**
 * Конфиг agent из переменных окружения. Все ошибки собираются и выдаются разом при старте;
 * значения секретов в сообщения не попадают. Список переменных — `docs/operations.md`.
 */
data class AgentConfig(
    val telegramBotToken: String,
    val coreUrl: String,
    val serviceToken: String,
    val openRouterUrl: String,
    val openRouterApiKey: String,
    /** Модели роли `main` в порядке приоритета (fallback OpenRouter `models`). */
    val mainModels: List<String>,
    val llmTimeout: Duration,
    val healthFile: Path,
    val adminChatId: Long = 0,
    val databaseUrl: String = "",
    val databaseUser: String = "",
    val databasePassword: String = "",
    val journalKey: String = "",
    val timezoneButtons: List<Pair<String, String>> = listOf("Москва" to "Europe/Moscow", "Екатеринбург" to "Asia/Yekaterinburg"),
) {
    override fun toString(): String = "AgentConfig(coreUrl=$coreUrl, mainModels=$mainModels)"

    companion object {
        private const val MIN_TOKEN_LENGTH = 32
        private const val MAX_MODELS = 3
        private val LLM_TIMEOUT_RANGE = 5L..300L

        private const val MAX_ZONE_BUTTONS = 6
        private const val MAX_ZONE_LABEL_LENGTH = 32

        private fun invalidZoneButton(pair: Pair<String, String>): Boolean =
            pair.first.isBlank() || pair.first.length > MAX_ZONE_LABEL_LENGTH || pair.second !in java.time.ZoneId.getAvailableZoneIds()

        private fun readAdminId(
            raw: String,
            errors: MutableList<String>,
        ): Long? {
            val id = raw.toLongOrNull()
            if (id == null || id <= 0) errors += "THESEUS_ADMIN_CHAT_ID: ожидается положительное число"
            return id
        }

        private fun readZones(
            raw: String,
            errors: MutableList<String>,
        ): List<Pair<String, String>> {
            val zones =
                raw.split(',').map {
                    it.substringBefore('=').trim() to it.substringAfter('=', "").trim()
                }
            if (zones.size !in 1..MAX_ZONE_BUTTONS ||
                zones.any { invalidZoneButton(it) }
            ) {
                errors += "THESEUS_TIMEZONE_BUTTONS: от 1 до 6 пар Название=IANA через запятую"
            }

            return zones
        }

        fun fromEnv(env: Map<String, String>): AgentConfig {
            val errors = mutableListOf<String>()

            fun required(name: String): String {
                val value = env[name]?.trim()
                if (value.isNullOrEmpty()) errors += "$name: не задана"
                return value.orEmpty()
            }

            fun optional(
                name: String,
                default: String,
            ): String = env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: default

            val serviceToken = required("THESEUS_SERVICE_TOKEN")
            if (serviceToken.length < MIN_TOKEN_LENGTH) {
                errors += "THESEUS_SERVICE_TOKEN: короче $MIN_TOKEN_LENGTH символов"
            }
            val models = required("THESEUS_LLM_MAIN_MODELS").split(',').map(String::trim).filter(String::isNotEmpty)
            if (env["THESEUS_LLM_MAIN_MODELS"].isNullOrBlank().not() && models.size !in 1..MAX_MODELS) {
                errors += "THESEUS_LLM_MAIN_MODELS: от 1 до $MAX_MODELS моделей через запятую"
            }
            val timeout = optional("THESEUS_LLM_TIMEOUT_SECONDS", "60").toLongOrNull()
            if (timeout == null || timeout !in LLM_TIMEOUT_RANGE) errors += "THESEUS_LLM_TIMEOUT_SECONDS: ожидается $LLM_TIMEOUT_RANGE"
            val adminId = readAdminId(required("THESEUS_ADMIN_CHAT_ID"), errors)
            val journalKey = required("THESEUS_JOURNAL_KEY")
            if (!journalKey.matches(Regex("[0-9a-fA-F]{64}"))) errors += "THESEUS_JOURNAL_KEY: ожидаются 64 hex-символа"
            val zones = readZones(optional("THESEUS_TIMEZONE_BUTTONS", "Москва=Europe/Moscow,Екатеринбург=Asia/Yekaterinburg"), errors)

            val config =
                AgentConfig(
                    telegramBotToken = required("THESEUS_TELEGRAM_BOT_TOKEN"),
                    coreUrl = required("THESEUS_CORE_URL").trimEnd('/'),
                    serviceToken = serviceToken,
                    openRouterUrl = optional("THESEUS_OPENROUTER_URL", "https://openrouter.ai/api/v1").trimEnd('/'),
                    openRouterApiKey = required("THESEUS_OPENROUTER_API_KEY"),
                    mainModels = models,
                    llmTimeout = Duration.ofSeconds(timeout ?: 60),
                    healthFile = Path.of(optional("THESEUS_HEALTH_FILE", "/tmp/theseus-agent.health")),
                    adminChatId = adminId ?: 0,
                    databaseUrl = required("THESEUS_DB_URL"),
                    databaseUser = required("THESEUS_DB_USER"),
                    databasePassword = required("THESEUS_DB_PASSWORD"),
                    journalKey = journalKey,
                    timezoneButtons = zones,
                )
            if (errors.isNotEmpty()) throw ConfigException("Некорректный конфиг:\n" + errors.joinToString("\n") { " - $it" })
            return config
        }
    }
}

class ConfigException(
    message: String,
) : RuntimeException(message)
