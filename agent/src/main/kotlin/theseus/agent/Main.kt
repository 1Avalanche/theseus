package theseus.agent

import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import theseus.agent.config.AgentConfig
import theseus.agent.core.HttpCoreClient
import theseus.agent.llm.OpenRouterClient
import theseus.agent.pipeline.MessageHandler
import theseus.agent.pipeline.ReplyRenderer
import theseus.agent.prompt.PromptBuilder
import theseus.agent.telegram.HealthHeartbeat
import theseus.agent.telegram.TelegramBotRunner
import theseus.agent.telegram.TelegramLogging
import theseus.agent.texts.Texts
import java.time.Clock
import java.time.Duration

/** Интервал пульса; healthcheck в compose считает контейнер больным, если файл старше 120 с. */
private val HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(30)

/** Точка входа agent: конфиг → клиенты core и OpenRouter → пайплайн → long polling Telegram. */
fun main() {
    val log = LoggerFactory.getLogger("theseus.agent")
    val config = AgentConfig.fromEnv(System.getenv())
    val texts = Texts.load("texts/agent.properties")
    val http =
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = Duration.ofSeconds(30).toMillis()
                connectTimeoutMillis = Duration.ofSeconds(10).toMillis()
            }
            install(SSE)
        }
    val core = HttpCoreClient(http, config.coreUrl, config.serviceToken)
    val llm = OpenRouterClient(http, config.openRouterUrl, config.openRouterApiKey, config.mainModels, config.llmTimeout)
    val handler =
        MessageHandler(core, llm, PromptBuilder.fromResources(texts), ReplyRenderer(texts), texts, Clock.systemUTC())
    TelegramLogging.install()
    val bot = telegramBot(config.telegramBotToken)

    runBlocking {
        // Неверный токен — ошибка конфига: падаем сразу, а не крутим long polling в цикле ошибок 401.
        val me = bot.getMe()
        log.info("Telegram-бот: @{}", me.username?.withoutAt ?: me.id.chatId.long)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        HealthHeartbeat(bot, config.healthFile, HEARTBEAT_INTERVAL).start(scope)
        log.info("agent запущен, модели: {}", config.mainModels)
        TelegramBotRunner(bot, handler).run(scope).join()
    }
}
