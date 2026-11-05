package theseus.core.config

import theseus.core.domain.LocalFormats
import theseus.core.domain.user.AdminProfile
import theseus.core.domain.user.Gender
import java.time.DateTimeException
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val poolSize: Int,
)

/**
 * Конфиг core из переменных окружения. Все ошибки собираются и выдаются разом при старте;
 * значения секретов в сообщения не попадают. Список переменных — `docs/operations.md`.
 */
data class CoreConfig(
    val httpPort: Int,
    val database: DatabaseConfig,
    val serviceToken: String,
    val telegramBotToken: String,
    val admin: AdminProfile,
    val defaultTime: LocalTime,
    val schedulerInterval: Duration,
    val lateThreshold: Duration,
) {
    override fun toString(): String = "CoreConfig(httpPort=$httpPort, db=${database.url}, admin=${admin.chatId})"

    companion object {
        const val MIN_TOKEN_LENGTH = 32

        fun fromEnv(env: Map<String, String>): CoreConfig {
            val reader = EnvReader(env)
            val config =
                CoreConfig(
                    httpPort = reader.int("THESEUS_HTTP_PORT", default = 8080, range = 1..65535),
                    database =
                        DatabaseConfig(
                            url = reader.required("THESEUS_DB_URL"),
                            user = reader.required("THESEUS_DB_USER"),
                            password = reader.required("THESEUS_DB_PASSWORD"),
                            poolSize = reader.int("THESEUS_DB_POOL_SIZE", default = 5, range = 1..50),
                        ),
                    serviceToken = reader.secret("THESEUS_SERVICE_TOKEN", MIN_TOKEN_LENGTH),
                    telegramBotToken = reader.required("THESEUS_TELEGRAM_BOT_TOKEN"),
                    admin =
                        AdminProfile(
                            chatId = reader.long("THESEUS_ADMIN_CHAT_ID"),
                            name = reader.required("THESEUS_ADMIN_NAME"),
                            gender = reader.gender("THESEUS_ADMIN_GENDER"),
                            timezone = reader.zone("THESEUS_ADMIN_TIMEZONE"),
                        ),
                    defaultTime = reader.time("THESEUS_DEFAULT_TIME", LocalTime.of(9, 0)),
                    schedulerInterval =
                        Duration.ofSeconds(reader.int("THESEUS_SCHEDULER_INTERVAL_SECONDS", 30, 5..300).toLong()),
                    lateThreshold = Duration.ofMinutes(reader.int("THESEUS_LATE_THRESHOLD_MINUTES", 5, 1..1440).toLong()),
                )
            reader.throwIfErrors()
            return config
        }
    }
}

class ConfigException(
    message: String,
) : RuntimeException(message)

/** Читает env, копит ошибки; при ошибке возвращает заглушку, чтобы собрать все проблемы за один проход. */
private class EnvReader(
    private val env: Map<String, String>,
) {
    private val errors = mutableListOf<String>()

    fun required(name: String): String {
        val value = env[name]?.trim()
        if (value.isNullOrEmpty()) errors += "$name: не задана"
        return value.orEmpty()
    }

    fun secret(
        name: String,
        minLength: Int,
    ): String {
        val value = required(name)
        if (value.isNotEmpty() && value.length < minLength) errors += "$name: короче $minLength символов"
        return value
    }

    fun int(
        name: String,
        default: Int,
        range: IntRange,
    ): Int {
        val raw = env[name]?.trim()
        if (raw.isNullOrEmpty()) return default
        val value = raw.toIntOrNull()
        if (value == null || value !in range) {
            errors += "$name: ожидается целое в диапазоне $range"
            return default
        }
        return value
    }

    fun long(name: String): Long {
        val raw = required(name)
        if (raw.isEmpty()) return 0
        return raw.toLongOrNull() ?: 0L.also { errors += "$name: ожидается целое число" }
    }

    fun zone(name: String): ZoneId {
        val raw = required(name)
        if (raw.isEmpty()) return ZoneId.of("UTC")
        return try {
            ZoneId.of(raw)
        } catch (_: DateTimeException) {
            errors += "$name: неизвестная таймзона IANA"
            ZoneId.of("UTC")
        }
    }

    fun time(
        name: String,
        default: LocalTime,
    ): LocalTime {
        val raw = env[name]?.trim()
        if (raw.isNullOrEmpty()) return default
        return LocalFormats.parseTime(raw) ?: default.also { errors += "$name: ожидается HH:MM" }
    }

    fun gender(name: String): Gender {
        val raw = env[name]?.trim()
        if (raw.isNullOrEmpty()) return Gender.UNSPECIFIED
        return Gender.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: Gender.UNSPECIFIED.also { errors += "$name: ожидается male | female | unspecified" }
    }

    fun throwIfErrors() {
        if (errors.isNotEmpty()) throw ConfigException("Некорректный конфиг:\n" + errors.joinToString("\n") { " - $it" })
    }
}
