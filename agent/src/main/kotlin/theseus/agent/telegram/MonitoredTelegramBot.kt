package theseus.agent.telegram

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.ApiException
import dev.inmo.tgbotapi.bot.exceptions.BotException
import dev.inmo.tgbotapi.bot.exceptions.TooMuchRequestsException
import dev.inmo.tgbotapi.requests.GetUpdates
import dev.inmo.tgbotapi.requests.abstracts.Request
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import theseus.agent.monitoring.AgentMonitor
import java.io.IOException

/** Наблюдает реальные Bot API вызовы. Повторяет только polling, не отправку сообщений. */
class MonitoredTelegramBot(
    private val delegate: TelegramBot,
    private val monitor: AgentMonitor,
) : TelegramBot by delegate {
    private var pollingBackoff = INITIAL_BACKOFF_MILLIS

    override suspend fun <T : Any> execute(request: Request<T>): T {
        val polling = request is GetUpdates
        try {
            val result = executeBounded(request)
            if (polling) {
                monitor.pollingSucceeded()
                pollingBackoff = INITIAL_BACKOFF_MILLIS
            } else {
                monitor.telegram.success()
            }
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            currentCoroutineContext().ensureActive()
            val code = (e as? BotException)?.response?.errorCode ?: (e as? ApiException)?.httpResponseCode
            (if (polling) monitor.polling else monitor.telegram).failure(code?.toString() ?: "network")
            if (polling) {
                val retryAfter = (e as? TooMuchRequestsException)?.retryAfter?.seconds?.times(1000L) ?: 0
                delay(maxOf(pollingBackoff, retryAfter))
                pollingBackoff = minOf(MAX_BACKOFF_MILLIS, pollingBackoff * 2)
            }
            throw e
        }
    }

    private suspend fun <T : Any> executeBounded(request: Request<T>): T =
        withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) { delegate.execute(request) }
            ?: throw IOException("Истёк срок Telegram-запроса")

    companion object {
        private const val INITIAL_BACKOFF_MILLIS = 5_000L
        private const val MAX_BACKOFF_MILLIS = 600_000L
        private const val REQUEST_TIMEOUT_MILLIS = 90_000L
    }
}
