package theseus.agent.telegram

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.bot.settings.limiters.RequestLimiter
import dev.inmo.tgbotapi.utils.defaultKtorEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.encodedPath

private const val POLLING_NETWORK_MARGIN_MILLIS = 15_000L

/** SDK задаёт HTTP-таймаут равным ожиданию Telegram; запас нужен для доставки пустого ответа. */
private val PollingTimeoutMargin =
    createClientPlugin("PollingTimeoutMargin") {
        onRequest { request, _ ->
            if (request.url.encodedPath.endsWith("/getUpdates")) {
                request.getCapabilityOrNull(HttpTimeoutCapability)?.apply {
                    requestTimeoutMillis = requestTimeoutMillis?.plus(POLLING_NETWORK_MARGIN_MILLIS)
                    socketTimeoutMillis = socketTimeoutMillis?.plus(POLLING_NETWORK_MARGIN_MILLIS)
                }
            }
        }
    }

/** Bot API клиент без скрытых повторов: повторы и пульс контролирует наш адаптер. */
internal fun createTelegramBot(
    token: String,
    http: HttpClient = HttpClient(defaultKtorEngine),
): TelegramBot =
    telegramBot(token) {
        client = http.config { install(PollingTimeoutMargin) }
        requestsLimiter =
            object : RequestLimiter {
                override suspend fun <T> limit(block: suspend () -> T): T = block()
            }
    }
