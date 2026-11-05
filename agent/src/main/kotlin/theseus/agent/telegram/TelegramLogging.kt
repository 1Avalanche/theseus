package theseus.agent.telegram

import dev.inmo.kslog.common.KSLog
import dev.inmo.kslog.common.LogLevel
import dev.inmo.tgbotapi.utils.DefaultKTgBotAPIKSLog
import org.slf4j.LoggerFactory

/**
 * Логгер ktgbotapi по умолчанию пишет запросы и апдейты целиком — с текстами сообщений пользователей.
 * Заменяем его: в SLF4J уходят только уровень и класс исключения (`.claude/rules/security.md`).
 */
object TelegramLogging {
    private val log = LoggerFactory.getLogger("telegram")

    fun install() {
        val safe =
            KSLog { level, _, _, throwable ->
                if (level >= LogLevel.WARNING) log.warn("ktgbotapi {}: {}", level, throwable?.javaClass?.simpleName ?: "-")
            }
        DefaultKTgBotAPIKSLog = safe
        KSLog.default = safe
    }
}
