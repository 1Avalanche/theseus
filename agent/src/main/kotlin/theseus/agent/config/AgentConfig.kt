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
    val stand: String = "prod",
    val summaryModels: List<String> = mainModels,
    val contextTokens: Int = 18000,
    val timezoneButtons: List<Pair<String, String>> = listOf("Москва" to "Europe/Moscow", "Екатеринбург" to "Asia/Yekaterinburg"),
) {
    override fun toString(): String = "AgentConfig(coreUrl=$coreUrl, mainModels=$mainModels)"

    companion object {
        private const val MIN_TOKEN_LENGTH = 32
        private const val MAX_MODELS = 3
        private const val MIN_CONTEXT_TOKENS = 4000
        private const val DEFAULT_CONTEXT_TOKENS = 18000
        private val LLM_TIMEOUT_RANGE = 5L..300L

        private const val MAX_ZONE_BUTTONS = 6
        private const val MAX_ZONE_LABEL_LENGTH = 32

        private fun readStand(
            env: Map<String, String>,
            errors: MutableList<String>,
        ): String {
            val stand = env["THESEUS_STAND"] ?: "prod"
            if (stand !in setOf("test", "prod")) errors += "THESEUS_STAND: ожидается test | prod"
            return stand
        }

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

        private fun readLlmSettings(
            env: Map<String, String>,
            models: List<String>,
            errors: MutableList<String>,
        ): Pair<List<String>, Int> {
            val summary = env["THESEUS_LLM_SUMMARY_MODELS"]?.takeIf { it.isNotBlank() } ?: models.joinToString(",")
            val summaryModels = summary.split(',').map(String::trim).filter(String::isNotEmpty)
            if (summaryModels.size !in 1..MAX_MODELS) errors += "THESEUS_LLM_SUMMARY_MODELS: от 1 до 3 моделей"
            val contextTokens = (env["THESEUS_LLM_CONTEXT_TOKENS"] ?: "18000").toIntOrNull()
            if (contextTokens == null || contextTokens < MIN_CONTEXT_TOKENS) {
                errors += "THESEUS_LLM_CONTEXT_TOKENS: целое число от $MIN_CONTEXT_TOKENS"
            }
            return summaryModels to (contextTokens ?: DEFAULT_CONTEXT_TOKENS)
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
            val (summaryModels, contextTokens) = readLlmSettings(env, models, errors)
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
                    summaryModels = summaryModels,
                    contextTokens = contextTokens,
                    llmTimeout = Duration.ofSeconds(timeout ?: 60),
                    healthFile = Path.of(optional("THESEUS_HEALTH_FILE", "/tmp/theseus-agent.health")),
                    adminChatId = adminId ?: 0,
                    databaseUrl = required("THESEUS_DB_URL"),
                    databaseUser = required("THESEUS_DB_USER"),
                    databasePassword = required("THESEUS_DB_PASSWORD"),
                    journalKey = journalKey,
                    stand = readStand(env, errors),
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
