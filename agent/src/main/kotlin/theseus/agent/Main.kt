package theseus.agent

import dev.inmo.tgbotapi.extensions.api.bot.getMe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import theseus.agent.config.AgentConfig
import theseus.agent.core.HttpCoreClient
import theseus.agent.llm.OpenRouterClient
import theseus.agent.monitoring.AgentMonitor
import theseus.agent.pipeline.MessageHandler
import theseus.agent.pipeline.ReplyRenderer
import theseus.agent.prompt.PromptBuilder
import theseus.agent.storage.JdbcAgentStore
import theseus.agent.system.AdminCommands
import theseus.agent.system.ChatLocks
import theseus.agent.system.HardDelete
import theseus.agent.system.Onboarding
import theseus.agent.system.OutgoingOperations
import theseus.agent.system.PersistentUsageRecorder
import theseus.agent.system.SystemHandler
import theseus.agent.telegram.MonitoredTelegramBot
import theseus.agent.telegram.TelegramBotRunner
import theseus.agent.telegram.TelegramLogging
import theseus.agent.telegram.createTelegramBot
import theseus.agent.texts.Texts
import java.time.Clock
import java.time.Duration

/** Точка входа agent: конфиг → клиенты core и OpenRouter → пайплайн → long polling Telegram. */
@Suppress("LongMethod") // Ручная композиция зависимостей приложения.
fun main() {
    val log = LoggerFactory.getLogger("theseus.agent")
    val config = AgentConfig.fromEnv(System.getenv())
    val clock = Clock.systemUTC()
    val monitor = AgentMonitor(clock, config.healthFile)
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
    val store = JdbcAgentStore.connect(config.databaseUrl, config.databaseUser, config.databasePassword, config.journalKey, clock)
    store.migrate()
    val operations = OutgoingOperations(store, core)
    val mcpOperations = theseus.agent.system.McpOperations(store, core)
    val reminderActions = theseus.agent.pipeline.ReminderActions(core, store, mcpOperations, ReplyRenderer(texts), texts, clock)
    val llm =
        OpenRouterClient(
            http,
            config.openRouterUrl,
            config.openRouterApiKey,
            config.mainModels,
            config.llmTimeout,
            monitor.openRouter,
            summaryModels = config.summaryModels,
            contextTokens = config.contextTokens,
        )
    val handler =
        MessageHandler(
            core,
            llm,
            PromptBuilder.fromResources(texts),
            ReplyRenderer(texts),
            texts,
            clock,
            usage = PersistentUsageRecorder(core, operations),
            actions = reminderActions,
            dialogs = store.dialogs,
            systemCore = core,
            modelContextTokens = config.contextTokens,
        )
    TelegramLogging.install()
    val rawBot = createTelegramBot(config.telegramBotToken)
    val bot = MonitoredTelegramBot(rawBot, monitor)

    runBlocking {
        // Неверный токен — ошибка конфига: падаем сразу, а не крутим long polling в цикле ошибок 401.
        val me = bot.getMe()
        log.info("Telegram-бот: @{}", me.username?.withoutAt ?: me.id.chatId.long)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        startDialogCleanup(scope, store.dialogs, clock)
        log.info("agent запущен, модели: {}", config.mainModels)
        val chatLocks = ChatLocks()
        val system =
            SystemHandler(
                core,
                store,
                operations,
                Onboarding(store, operations, texts, clock, config.timezoneButtons),
                AdminCommands(core, operations, texts),
                config.adminChatId,
                requireNotNull(me.username).withoutAt,
                texts,
                hardDelete = if (config.stand == "test") HardDelete(core, store, operations, chatLocks, texts) else null,
                reminderActions = reminderActions,
                dialogs = store.dialogs,
                clock = clock,
            )
        val pollingJob = TelegramBotRunner(bot, handler, system, store, operations, config.adminChatId, chatLocks).run(scope)
        monitor.start(scope, pollingJob)
        pollingJob.join()
    }
}

private fun startDialogCleanup(
    scope: CoroutineScope,
    dialogs: theseus.agent.dialog.DialogStore,
    clock: Clock,
) = scope.launch {
    while (isActive) {
        try {
            dialogs.cleanup(clock.instant())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            LoggerFactory.getLogger("theseus.agent").warn("Очистка истории отложена: {}", e.javaClass.simpleName)
        }
        kotlinx.coroutines.delay(Duration.ofHours(1).toMillis())
    }
}
