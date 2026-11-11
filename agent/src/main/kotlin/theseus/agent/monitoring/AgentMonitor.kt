package theseus.agent.monitoring

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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant

/** Ошибки одного внешнего канала; успешный polling не сбрасывает ошибки отправки и OpenRouter. */
class DependencySignal(
    private val clock: Clock,
) {
    private var failingSince: Long? = null
    private var lastErrorAt: Long? = null
    private var lastCode: String? = null
    private var consecutiveErrors = 0L
    private val errors = mutableMapOf<String, Long>()

    @Synchronized
    fun success() {
        failingSince = null
        consecutiveErrors = 0
    }

    @Synchronized
    fun failure(code: String) {
        val now = clock.instant().epochSecond
        if (failingSince == null) failingSince = now
        lastErrorAt = now
        lastCode = code
        consecutiveErrors++
        errors[code] = errors.getOrDefault(code, 0) + 1
    }

    @Synchronized
    fun snapshot(): JsonObject =
        buildJsonObject {
            put("failing_since", failingSince)
            put("last_error_at", lastErrorAt)
            put("last_code", lastCode)
            put("consecutive_errors", consecutiveErrors)
            put("errors", buildJsonObject { errors.forEach { (code, count) -> put(code, count) } })
        }
}

/** Диагностика агента без HTTP-сервера. Пульс обновляет только успешный `getUpdates`. */
class AgentMonitor(
    private val clock: Clock,
    private val healthFile: Path,
    private val file: Path = Path.of("/tmp/theseus-agent.monitor.json"),
) {
    val polling = DependencySignal(clock)
    val telegram = DependencySignal(clock)
    val openRouter = DependencySignal(clock)
    private val startedAt = clock.instant().epochSecond
    private val log = LoggerFactory.getLogger(AgentMonitor::class.java)

    @Volatile
    var lastSuccessAt: Instant? = null
        private set

    fun pollingSucceeded() {
        polling.success()
        lastSuccessAt = clock.instant()
    }

    fun start(
        scope: CoroutineScope,
        pollingJob: Job,
    ): Job =
        scope.launch {
            while (isActive) {
                try {
                    publish(pollingJob.isActive)
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    log.warn("Не записана диагностика agent: {}", e.javaClass.simpleName)
                }
                delay(SNAPSHOT_INTERVAL_MILLIS)
            }
        }

    fun publish(taskActive: Boolean) {
        val snapshot =
            buildJsonObject {
                put("version", 1)
                put("started_at", startedAt)
                put("observed_at", clock.instant().epochSecond)
                put("task_active", taskActive)
                put("last_success_at", lastSuccessAt?.epochSecond)
                put("polling", polling.snapshot())
                put("telegram", telegram.snapshot())
                put("openrouter", openRouter.snapshot())
            }
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, Json.encodeToString(snapshot))
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        lastSuccessAt?.let { pulse ->
            if (Files.notExists(healthFile)) Files.createFile(healthFile)
            Files.setLastModifiedTime(healthFile, FileTime.from(pulse))
        }
    }

    companion object {
        private const val SNAPSHOT_INTERVAL_MILLIS = 30_000L
    }
}
