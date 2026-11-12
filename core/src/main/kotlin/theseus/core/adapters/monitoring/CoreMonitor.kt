package theseus.core.adapters.monitoring

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import theseus.core.application.HealthProbe
import theseus.core.application.ReminderRepository
import theseus.core.application.ReminderScheduler
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock

/** Состояние доставки Telegram. Сохраняет только коды, счётчики и время, без данных запросов. */
class DeliverySignal(
    private val clock: Clock,
) {
    private var failingSince: Long? = null
    private var lastErrorAt: Long? = null
    private var lastCode: String? = null
    private var consecutiveErrors = 0L
    private val errors = mutableMapOf<String, Long>()

    @Volatile
    var retryAt: Long = 0
        private set

    @Synchronized
    fun success() {
        failingSince = null
        consecutiveErrors = 0
        retryAt = 0
    }

    @Synchronized
    fun failure(
        code: String,
        retryAfterSeconds: Long = 0,
    ) {
        val now = clock.instant().epochSecond
        if (failingSince == null) failingSince = now
        lastErrorAt = now
        lastCode = code
        consecutiveErrors++
        errors[code] = errors.getOrDefault(code, 0) + 1
        val backoff = minOf(600L, 5L shl minOf(consecutiveErrors - 1, 7).toInt())
        retryAt = if (code == "400" || code == "403") 0 else now + maxOf(backoff, retryAfterSeconds)
    }

    @Synchronized
    fun snapshot(): JsonObject =
        buildJsonObject {
            put("failing_since", failingSince)
            put("last_error_at", lastErrorAt)
            put("last_code", lastCode)
            put("consecutive_errors", consecutiveErrors)
            put("retry_at", retryAt)
            put("errors", buildJsonObject { errors.forEach { (code, count) -> put(code, count) } })
        }
}

/** Локальный диагностический снимок core. Пульс берётся только из планировщика. */
class CoreMonitor(
    private val clock: Clock,
    private val database: HealthProbe,
    private val reminders: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val schedulerJob: Job,
    private val telegram: DeliverySignal,
    private val file: Path = Path.of("/tmp/theseus-core.monitor.json"),
) {
    private val startedAt = clock.instant().epochSecond
    private val log = LoggerFactory.getLogger(CoreMonitor::class.java)

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                try {
                    publish()
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    log.warn("Не записана диагностика core: {}", e.javaClass.simpleName)
                }
                delay(SNAPSHOT_INTERVAL_MILLIS)
            }
        }

    suspend fun publish() {
        val now = clock.instant()
        var databaseOk = database.isHealthy()
        val backlog =
            try {
                if (databaseOk) reminders.deliveryBacklog(now) else null
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                databaseOk = false
                null
            }
        val snapshot =
            buildJsonObject {
                put("version", 1)
                put("started_at", startedAt)
                put("observed_at", clock.instant().epochSecond)
                put("task_active", schedulerJob.isActive)
                put("last_success_at", scheduler.lastSuccessAt?.epochSecond)
                put("database_ok", databaseOk)
                put("overdue_count", backlog?.count)
                put("oldest_due_at", backlog?.oldestDueAt?.epochSecond)
                put("telegram", telegram.snapshot())
            }
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, Json.encodeToString(snapshot))
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private const val SNAPSHOT_INTERVAL_MILLIS = 30_000L
    }
}
