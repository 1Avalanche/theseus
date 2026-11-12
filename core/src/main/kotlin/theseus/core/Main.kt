package theseus.core

import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.bot.settings.limiters.RequestLimiter
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import theseus.core.adapters.db.CoreDatabase
import theseus.core.adapters.db.DatabaseOperations
import theseus.core.adapters.db.ExposedReminderRepository
import theseus.core.adapters.db.ExposedUserRepository
import theseus.core.adapters.http.AccountsRoutes
import theseus.core.adapters.http.HttpDependencies
import theseus.core.adapters.http.coreModule
import theseus.core.adapters.mcp.McpServerFactory
import theseus.core.adapters.mcp.ReminderTools
import theseus.core.adapters.monitoring.CoreMonitor
import theseus.core.adapters.monitoring.DeliverySignal
import theseus.core.adapters.telegram.TelegramLogging
import theseus.core.adapters.telegram.TelegramNotifier
import theseus.core.application.AccountsService
import theseus.core.application.ReminderScheduler
import theseus.core.application.ReminderService
import theseus.core.application.UserService
import theseus.core.config.CoreConfig
import theseus.core.domain.reminder.WhenResolver
import theseus.core.texts.Texts
import java.time.Clock

/** Точка входа core: конфиг → миграции → админ из конфига → планировщик → HTTP (MCP + REST). */
fun main() {
    val log = LoggerFactory.getLogger("theseus.core")
    val config = CoreConfig.fromEnv(System.getenv())
    val clock = Clock.systemUTC()
    val texts = Texts.load("texts/core.properties")

    val database = CoreDatabase.connect(config.database)
    database.migrate()
    val userRepository = ExposedUserRepository(database)
    val reminderRepository = ExposedReminderRepository(database)
    val admin = runBlocking { userRepository.ensureAdmin(config.admin, clock.instant()) }
    log.info("Админ: user_id={}", admin.id)

    val users = UserService(userRepository)
    val reminders = ReminderService(reminderRepository, users, WhenResolver(config.defaultTime), clock)
    TelegramLogging.install()
    val deliverySignal = DeliverySignal(clock)
    val bot =
        telegramBot(config.telegramBotToken) {
            requestsLimiter =
                object : RequestLimiter {
                    override suspend fun <T> limit(block: suspend () -> T): T = block()
                }
        }
    val notifier = TelegramNotifier(bot, clock, deliverySignal)
    val scheduler = ReminderScheduler(reminderRepository, userRepository, notifier, texts, clock, config.lateThreshold)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val schedulerJob = scheduler.start(scope, config.schedulerInterval)
    CoreMonitor(clock, database, reminderRepository, scheduler, schedulerJob, deliverySignal).start(scope)

    val deps =
        HttpDependencies(
            serviceToken = config.serviceToken,
            health = database,
            users = users,
            mcpServer = McpServerFactory.create(ReminderTools(reminders, texts)),
            accounts = AccountsRoutes(AccountsService(userRepository, clock, config.inviteTtl), DatabaseOperations(database), clock),
        )
    log.info("core слушает порт {}", config.httpPort)
    embeddedServer(Netty, port = config.httpPort) { coreModule(deps) }.start(wait = true)
}
