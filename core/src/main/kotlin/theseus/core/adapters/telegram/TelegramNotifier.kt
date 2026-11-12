package theseus.core.adapters.telegram

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.ApiException
import dev.inmo.tgbotapi.bot.exceptions.BotException
import dev.inmo.tgbotapi.bot.exceptions.RequestException
import dev.inmo.tgbotapi.bot.exceptions.TooMuchRequestsException
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import theseus.core.adapters.monitoring.DeliverySignal
import theseus.core.application.Notifier
import theseus.core.application.NotifyResult
import java.io.IOException
import java.time.Clock

/** Отправка уведомлений через Bot API `sendMessage`. Тот же бот, что у агента (токен стенда). */
class TelegramNotifier(
    private val bot: TelegramBot,
    private val clock: Clock = Clock.systemUTC(),
    private val signal: DeliverySignal = DeliverySignal(clock),
) : Notifier {
    override suspend fun send(
        chatId: Long,
        text: String,
    ): NotifyResult {
        if (clock.instant().epochSecond < signal.retryAt) {
            return NotifyResult.Failed("telegram: ожидание повтора", permanent = false)
        }
        return try {
            val message =
                withTimeoutOrNull(60_000) { bot.sendTextMessage(ChatId(RawChatId(chatId)), text) }
                    ?: throw IOException("Истёк срок отправки Telegram")
            signal.success()
            NotifyResult.Sent(message.messageId.long)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BotException) {
            val code = (e as? RequestException)?.response?.errorCode ?: (e as? ApiException)?.httpResponseCode
            signal.failure(code?.toString() ?: "network", (e as? TooMuchRequestsException)?.retryAfter?.seconds?.toLong() ?: 0)
            NotifyResult.Failed("telegram ${code ?: "?"}: ${e.javaClass.simpleName}", isPermanentFailure(code))
        } catch (e: IOException) {
            signal.failure("network")
            NotifyResult.Failed("network: ${e.javaClass.simpleName}", permanent = false)
        }
    }

    companion object {
        /**
         * Повтор бессмысленен для ошибок запроса (400 — чат не найден, 403 — бот заблокирован).
         * 401 (неверный токен), 429 (лимит) и 5xx — временные: срабатывание не теряем.
         */
        fun isPermanentFailure(code: Int?): Boolean = code == BAD_REQUEST || code == FORBIDDEN

        private const val BAD_REQUEST = 400
        private const val FORBIDDEN = 403
    }
}
