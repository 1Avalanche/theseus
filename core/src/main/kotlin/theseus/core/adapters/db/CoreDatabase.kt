package theseus.core.adapters.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import theseus.core.application.HealthProbe
import theseus.core.config.DatabaseConfig
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/** Подключение к схеме `core`: пул, миграции Flyway при старте, транзакции Exposed. */
class CoreDatabase(
    private val dataSource: HikariDataSource,
) : HealthProbe,
    AutoCloseable {
    private val database = Database.connect(dataSource)

    fun migrate() {
        Flyway
            .configure()
            .dataSource(dataSource)
            .schemas(SCHEMA)
            .defaultSchema(SCHEMA)
            .locations("classpath:db/migration/core")
            .load()
            .migrate()
    }

    /** Блокирующая транзакция на IO-диспетчере. */
    internal suspend fun <T> tx(block: JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) {
            transaction(database) { block() }
        }

    override suspend fun isHealthy(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                dataSource.connection.use { it.isValid(HEALTH_TIMEOUT_SECONDS) }
            } catch (_: SQLException) {
                false
            }
        }

    /** Прямой доступ к пулу — для интеграционных тестов и служебных проверок. */
    fun dataSource(): DataSource = dataSource

    override fun close() = dataSource.close()

    companion object {
        const val SCHEMA = "core"
        private const val HEALTH_TIMEOUT_SECONDS = 2

        fun connect(config: DatabaseConfig): CoreDatabase {
            val hikari =
                HikariConfig().apply {
                    jdbcUrl = config.url
                    username = config.user
                    password = config.password
                    maximumPoolSize = config.poolSize
                    schema = SCHEMA
                    poolName = "core"
                }
            return CoreDatabase(HikariDataSource(hikari))
        }
    }
}

internal fun Instant.toUtc(): OffsetDateTime = atOffset(ZoneOffset.UTC)
