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
) {
    override fun toString(): String = "AgentConfig(coreUrl=$coreUrl, mainModels=$mainModels)"

    companion object {
        private const val MIN_TOKEN_LENGTH = 32
        private const val MAX_MODELS = 3
        private val LLM_TIMEOUT_RANGE = 5L..300L

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
            if (serviceToken.isNotEmpty() && serviceToken.length < MIN_TOKEN_LENGTH) {
                errors += "THESEUS_SERVICE_TOKEN: короче $MIN_TOKEN_LENGTH символов"
            }
            val models = required("THESEUS_LLM_MAIN_MODELS").split(',').map(String::trim).filter(String::isNotEmpty)
            if (env["THESEUS_LLM_MAIN_MODELS"].isNullOrBlank().not() && models.size !in 1..MAX_MODELS) {
                errors += "THESEUS_LLM_MAIN_MODELS: от 1 до $MAX_MODELS моделей через запятую"
            }
            val timeout = optional("THESEUS_LLM_TIMEOUT_SECONDS", "60").toLongOrNull()
            if (timeout == null || timeout !in LLM_TIMEOUT_RANGE) errors += "THESEUS_LLM_TIMEOUT_SECONDS: ожидается $LLM_TIMEOUT_RANGE"

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
                )
            if (errors.isNotEmpty()) throw ConfigException("Некорректный конфиг:\n" + errors.joinToString("\n") { " - $it" })
            return config
        }
    }
}

class ConfigException(
    message: String,
) : RuntimeException(message)
