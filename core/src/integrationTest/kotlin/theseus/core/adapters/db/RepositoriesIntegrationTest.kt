package theseus.core.adapters.db

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import theseus.core.config.DatabaseConfig
import theseus.core.domain.reminder.DeliveryKind
import theseus.core.domain.reminder.DeliveryRecord
import theseus.core.domain.reminder.DeliveryStatus
import theseus.core.domain.reminder.NewReminder
import theseus.core.domain.reminder.ReminderStatus
import theseus.core.domain.user.AdminProfile
import theseus.core.domain.user.Gender
import theseus.core.domain.user.UserRole
import theseus.core.domain.user.UserStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RepositoriesIntegrationTest {
    private val postgres = PostgreSQLContainer("postgres:17-alpine")
    private lateinit var db: CoreDatabase
    private lateinit var users: ExposedUserRepository
    private lateinit var reminders: ExposedReminderRepository

    private val now = Instant.parse("2026-11-02T07:15:00Z")
    private val admin = AdminProfile(4242, "Аня", Gender.FEMALE, ZoneId.of("Asia/Yekaterinburg"))

    @BeforeAll
    fun start() {
        postgres.start()
        db = CoreDatabase.connect(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password, poolSize = 2))
        db.migrate()
        users = ExposedUserRepository(db)
        reminders = ExposedReminderRepository(db)
    }

    @AfterAll
    fun stop() {
        db.close()
        postgres.stop()
    }

    @Test
    fun `ensure admin is idempotent and creates active admin`() =
        runTest {
            val first = users.ensureAdmin(admin, now)
            val second = users.ensureAdmin(admin.copy(name = "Другое имя"), now.plusSeconds(60))

            assertEquals(first.id, second.id)
            assertEquals("Аня", second.name)
            assertEquals(UserRole.ADMIN, second.role)
            assertEquals(UserStatus.ACTIVE, second.status)
            assertEquals(ZoneId.of("Asia/Yekaterinburg"), second.timezone)
            assertEquals(first, users.findByChatId(4242))
        }

    @Test
    fun `reminder round trips local time timezone and due instant`() =
        runTest {
            val user = users.ensureAdmin(admin, now)
            val fireAt = Instant.parse("2026-11-02T13:00:00Z")
            val created =
                reminders.create(
                    NewReminder(user.id, "Позвонить маме", ZoneId.of("Asia/Yekaterinburg"), LocalDateTime.of(2026, 11, 2, 18, 0), fireAt),
                    now,
                )

            assertEquals(LocalDateTime.of(2026, 11, 2, 18, 0), created.startLocal)
            assertEquals(fireAt, created.nextFireAt)
            assertEquals(ReminderStatus.ACTIVE, created.status)
            assertTrue(reminders.findDue(fireAt.minusSeconds(1), 10).none { it.id == created.id })
            assertTrue(reminders.findDue(fireAt, 10).any { it.id == created.id })
        }

    @Test
    fun `completing occurrence twice records one delivery and closes reminder`() =
        runTest {
            val user = users.ensureAdmin(admin, now)
            val fireAt = now.plusSeconds(300)
            val reminder =
                reminders.create(NewReminder(user.id, "X", ZoneId.of("Europe/Moscow"), LocalDateTime.of(2026, 11, 2, 10, 20), fireAt), now)
            val record =
                DeliveryRecord(fireAt, DeliveryKind.SCHEDULED, DeliveryStatus.SENT, late = false, telegramMessageId = 77, error = null)

            reminders.completeOneTime(reminder, record, fireAt)
            reminders.completeOneTime(reminder, record, fireAt.plusSeconds(30))

            assertTrue(reminders.findDue(fireAt.plusSeconds(3600), 100).none { it.id == reminder.id })
            val deliveries =
                db.dataSource().connection.use { connection ->
                    connection
                        .prepareStatement("select count(*), max(telegram_message_id) from reminder_deliveries where reminder_id = ?")
                        .apply { setLong(1, reminder.id) }
                        .executeQuery()
                        .run {
                            next()
                            getInt(1) to getLong(2)
                        }
                }
            assertEquals(1 to 77L, deliveries)
            val status =
                db.dataSource().connection.use { connection ->
                    connection
                        .prepareStatement("select status, next_fire_at from reminders where id = ?")
                        .apply { setLong(1, reminder.id) }
                        .executeQuery()
                        .run {
                            next()
                            getString(1) to getObject(2)
                        }
                }
            assertEquals("DONE", status.first)
            assertNull(status.second)
        }
}
