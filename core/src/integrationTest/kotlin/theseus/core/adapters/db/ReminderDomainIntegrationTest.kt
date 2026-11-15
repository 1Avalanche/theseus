package theseus.core.adapters.db

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import theseus.core.adapters.mcp.ReminderTools
import theseus.core.adapters.mcp.ToolOutcome
import theseus.core.application.Notifier
import theseus.core.application.NotifyResult
import theseus.core.application.OperationReply
import theseus.core.application.OperationRequest
import theseus.core.application.ReminderScheduler
import theseus.core.application.ReminderService
import theseus.core.application.UserService
import theseus.core.config.DatabaseConfig
import theseus.core.domain.reminder.DeliveryKind
import theseus.core.domain.reminder.DeliveryRecord
import theseus.core.domain.reminder.DeliveryStatus
import theseus.core.domain.reminder.Recurrence
import theseus.core.domain.reminder.Reminder
import theseus.core.domain.reminder.ReminderStatus
import theseus.core.domain.reminder.When
import theseus.core.domain.reminder.WhenResolver
import theseus.core.domain.user.AccountException
import theseus.core.domain.user.Gender
import theseus.core.domain.user.User
import theseus.core.domain.user.UserStatus
import theseus.core.texts.Texts
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReminderDomainIntegrationTest {
    @Test fun `reserved delivery id reaches keyboard and is scoped for reply lookup`() =
        runTest {
            val user = activeUser()
            val other = activeUser(200)
            val reminder =
                service.createOneTime(
                    user.id,
                    "Доставка",
                    When(
                        after =
                            theseus.core.domain.reminder
                                .After(minutes = 1),
                    ),
                )
            clock.now = clock.instant().plusSeconds(120)
            var keyboardId: Long? = null
            val notifier =
                object : Notifier {
                    override suspend fun send(
                        chatId: Long,
                        text: String,
                    ): NotifyResult = error("Нужен id доставки")

                    override suspend fun send(
                        chatId: Long,
                        text: String,
                        deliveryId: Long,
                    ): NotifyResult {
                        keyboardId = deliveryId
                        return NotifyResult.Sent(12345)
                    }
                }
            val scheduler = ReminderScheduler(reminders, users, notifier, texts, clock, Duration.ofMinutes(5))
            assertEquals(1, scheduler.tick())
            val found = reminders.delivery(user.id, messageId = 12345)!!
            assertEquals(keyboardId, found.id)
            assertEquals(reminder.id, found.reminderId)
            assertEquals(null, reminders.delivery(other.id, id = found.id))
            assertEquals(null, reminders.delivery(other.id, messageId = 12345))
            val deliveryService = theseus.core.application.DeliveryService(reminders, service, clock)
            val key = UUID.randomUUID()
            val request = OperationRequest(key, "agent", "snooze-test", "hash", user.id)
            val first =
                operations.execute(request, clock.instant(), { service.authorize(user.id) }) {
                    OperationReply(200, "{\"at\":\"${deliveryService.snooze(user.id, found.id, "15m").nextFireAt}\"}")
                }
            clock.now = clock.instant().plusSeconds(60)
            val repeated = operations.execute(request, clock.instant(), { service.authorize(user.id) }) { error("Не исполнять повторно") }
            assertEquals(first, repeated)
        }

    @Test
    fun `confirmed delete all is atomic user scoped and replays original count after response loss`() =
        runTest {
            val user = activeUser()
            val other = activeUser(200)
            val root = series(user.id)
            service.pause(user.id, root.id, null, null)
            service.create(user.id, "Проверить почту", When(time = LocalTime.of(18, 0)), null)
            series(other.id)
            val preview = tools.call("list_reminders", user.id, args("{}"), null).body()
            val confirmation =
                buildJsonObject {
                    put("fingerprint", preview.getValue("fingerprint"))
                    put("created_at", clock.instant().toString())
                }
            val key = UUID.randomUUID().toString()
            val first = tools.call("delete_reminder", user.id, args("""{"scope":"all"}"""), key, confirmation)
            assertFalse(first.isError, first.json)
            assertEquals(
                "2",
                first
                    .body()
                    .getValue("deleted_count")
                    .jsonPrimitive.content,
            )
            assertTrue(service.list(user.id).reminders.isEmpty())
            assertEquals(1, service.list(other.id).reminders.size)
            clock.now = clock.now.plus(Duration.ofHours(2))
            service.create(user.id, "Новое после удаления", When(time = LocalTime.of(18, 0)), null)
            val replay = tools.call("delete_reminder", user.id, args("""{"scope":"all"}"""), key, confirmation)
            assertEquals(first, replay)
            assertEquals(1, service.list(user.id).reminders.size)
            assertEquals(1, journalCount(UUID.fromString(key)))
        }

    @Test
    fun `delete all snapshot changed by new reminder rolls back entire batch`() =
        runTest {
            val user = activeUser()
            series(user.id)
            val snapshot = requireNotNull(service.list(user.id).fingerprint)
            service.create(user.id, "Проверить почту", When(time = LocalTime.of(18, 0)), null)
            val confirmation =
                buildJsonObject {
                    put("fingerprint", snapshot)
                    put("created_at", clock.instant().toString())
                }
            val result = tools.call("delete_reminder", user.id, args("""{"scope":"all"}"""), UUID.randomUUID().toString(), confirmation)
            assertEquals("confirmation_stale", result.code())
            assertEquals(2, service.list(user.id).reminders.size)
        }

    private val postgres = PostgreSQLContainer("postgres:17-alpine")
    private val initialNow = Instant.parse("2026-11-02T07:15:00Z")
    private val clock = TestClock(initialNow)
    private val texts = Texts.load("texts/core.properties")
    private lateinit var db: CoreDatabase
    private lateinit var users: ExposedUserRepository
    private lateinit var reminders: ExposedReminderRepository
    private lateinit var operations: DatabaseOperations
    private lateinit var service: ReminderService
    private lateinit var tools: ReminderTools

    @BeforeAll
    fun start() {
        postgres.start()
        db = CoreDatabase.connect(DatabaseConfig(postgres.jdbcUrl, postgres.username, postgres.password, 5))
        db.migrate()
        users = ExposedUserRepository(db)
        reminders = ExposedReminderRepository(db)
        operations = DatabaseOperations(db)
        service = ReminderService(reminders, UserService(users), WhenResolver(LocalTime.of(9, 0)), clock)
        tools = ReminderTools(service, texts, operations)
    }

    @BeforeEach
    fun reset() {
        clock.now = initialNow
        db.dataSource().connection.use { connection ->
            connection.createStatement().use {
                it.execute("truncate operation_results, reminder_deliveries, reminders, users restart identity cascade")
            }
        }
    }

    @AfterAll
    fun stop() {
        db.close()
        postgres.stop()
    }

    private suspend fun activeUser(chatId: Long = 100): User {
        val created = users.createUser(chatId, Gender.UNSPECIFIED, clock.instant())
        users.saveUser(
            created.copy(
                status = UserStatus.ACTIVE,
                name = "Тест",
                timezone = ZoneId.of("Europe/Moscow"),
                onboardedAt = clock.instant(),
            ),
            clock.instant(),
        )
        return requireNotNull(users.findById(created.id))
    }

    private fun args(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private fun ToolOutcome.body(): JsonObject = args(json)

    private fun ToolOutcome.code(): String {
        assertTrue(isError, json)
        return body()
            .getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content
    }

    private fun count(table: String): Long =
        db.dataSource().connection.use { connection ->
            connection.createStatement().use {
                it.executeQuery("select count(*) from $table").use { row ->
                    check(row.next())
                    row.getLong(1)
                }
            }
        }

    private fun journalCount(id: UUID): Long =
        db.dataSource().connection.use { connection ->
            connection.prepareStatement("select count(*) from operation_results where id=?").use {
                it.setObject(1, id)
                it.executeQuery().use { row ->
                    check(row.next())
                    row.getLong(1)
                }
            }
        }

    private suspend fun series(userId: Long): Reminder =
        requireNotNull(service.create(userId, "Принять таблетки", null, Recurrence("daily", time = LocalTime.of(11, 0))).reminder)

    private suspend fun card(
        userId: Long,
        reminderId: Long,
    ): JsonObject =
        buildJsonObject {
            put("fingerprint", service.snapshot(userId, reminderId))
            put("created_at", clock.instant().toString())
        }

    @Test
    fun `concurrent mcp requests with one uuid create one reminder and one result`() =
        runTest {
            val user = activeUser()
            val key = UUID.randomUUID()
            val request = args("""{"text":"Выключить духовку","when":{"after":{"minutes":5}}}""")

            val replies =
                coroutineScope {
                    (1..4).map { async { tools.call("create_reminder", user.id, request, key.toString()) } }.awaitAll()
                }

            replies.forEach { assertFalse(it.isError, it.json) }
            assertEquals(1, replies.map { it.json }.toSet().size)
            assertEquals(1, count("reminders"))
            assertEquals(1, journalCount(key))
        }

    @Test
    fun `canonical replay returns original result without resolving relative time again`() =
        runTest {
            val user = activeUser()
            val key = UUID.randomUUID()
            val first =
                tools.call(
                    "create_reminder",
                    user.id,
                    args("""{"text":"Выключить духовку","when":{"after":{"minutes":5}}}"""),
                    key.toString(),
                )
            assertFalse(first.isError, first.json)
            clock.now = initialNow.plus(Duration.ofHours(2))

            val replay =
                tools.call(
                    "create_reminder",
                    user.id,
                    args("""{"when":{"after":{"minutes":5}},"text":"Выключить духовку"}"""),
                    key.toString(),
                )

            assertFalse(replay.isError, replay.json)
            assertEquals(first.json, replay.json)
            assertEquals(
                "2026-11-02T10:20",
                replay
                    .body()
                    .getValue("reminder")
                    .jsonObject
                    .getValue("next_at_local")
                    .jsonPrimitive.content,
            )
            assertEquals(1, count("reminders"))
        }

    @Test
    fun `uuid cannot change request subject operation or rest channel`() =
        runTest {
            val owner = activeUser()
            val other = activeUser(200)
            val key = UUID.randomUUID()
            val request = args("""{"text":"Выключить духовку","when":{"after":{"minutes":5}}}""")
            val first = tools.call("create_reminder", owner.id, request, key.toString())
            assertFalse(first.isError, first.json)

            assertEquals(
                "idempotency_conflict",
                tools
                    .call(
                        "create_reminder",
                        owner.id,
                        args("""{"text":"Другой текст","when":{"after":{"minutes":5}}}"""),
                        key.toString(),
                    ).code(),
            )
            assertEquals("idempotency_conflict", tools.call("create_reminder", other.id, request, key.toString()).code())
            assertEquals(
                "idempotency_conflict",
                tools
                    .call(
                        "snooze_reminder",
                        owner.id,
                        args("""{"reminder_id":1,"when":{"after":{"minutes":5}}}"""),
                        key.toString(),
                    ).code(),
            )
            val conflict =
                assertThrows<AccountException> {
                    operations.execute(
                        OperationRequest(key, "user:${owner.id}", "create_reminder", "same", owner.id, "REST"),
                        clock.instant(),
                        {},
                    ) { error("Чужой канал не должен исполнять действие") }
                }
            assertEquals("idempotency_conflict", conflict.code)
            assertEquals(1, count("reminders"))
            assertEquals(1, journalCount(key))
        }

    @Test
    fun `revoked user cannot read cached mcp result`() =
        runTest {
            val user = activeUser()
            val key = UUID.randomUUID()
            val request = args("""{"text":"Выключить духовку","when":{"after":{"minutes":5}}}""")
            assertFalse(tools.call("create_reminder", user.id, request, key.toString()).isError)
            users.saveUser(user.copy(status = UserStatus.REVOKED), clock.instant())

            val replay = tools.call("create_reminder", user.id, request, key.toString())

            assertEquals("user_inactive", replay.code())
            assertFalse(replay.json.contains("Выключить духовку"))
            assertEquals(1, count("reminders"))
        }

    @Test
    fun `failed result insertion rolls back reminder created in nested service transaction`() =
        runTest {
            val user = activeUser()
            val key = UUID.randomUUID()

            assertThrows<Exception> {
                operations.execute(
                    OperationRequest(key, "user:${user.id}", "test result rollback", "hash", Long.MAX_VALUE, "MCP"),
                    clock.instant(),
                    { service.authorize(user.id) },
                ) {
                    service.createOneTime(user.id, "Откат", When(time = LocalTime.of(17, 0)))
                    OperationReply(200, """{"status":"created"}""")
                }
            }

            assertEquals(0, count("reminders"))
            assertEquals(0, journalCount(key))
        }

    @Test
    fun `domain failure rolls back cancelled child and preserves exclusions`() =
        runTest {
            val user = activeUser()
            val original = series(user.id)
            val moved =
                service.update(
                    user.id,
                    original.id,
                    "occurrence",
                    LocalDateTime.parse("2026-11-02T11:00"),
                    null,
                    When(date = LocalDate.parse("2026-11-03"), time = LocalTime.of(13, 0)),
                    null,
                )
            val snapshot = requireNotNull(reminders.find(user.id, original.id))
            val key = UUID.randomUUID()

            val result =
                tools.call(
                    "update_reminder",
                    user.id,
                    args("""{"reminder_id":${original.id},"scope":"series","recurrence":{"time":"12:00","count":0}}"""),
                    key.toString(),
                    card(user.id, original.id),
                )

            assertEquals("invalid_recurrence", result.code())
            assertEquals(ReminderStatus.ACTIVE, reminders.find(user.id, moved.reminder.id)!!.status)
            assertEquals(snapshot.excludedOccurrences, reminders.find(user.id, original.id)!!.excludedOccurrences)
            assertEquals(0, journalCount(key))
        }

    @Test
    fun `exclusions child and combined delivery survive postgres roundtrip`() =
        runTest {
            val user = activeUser()
            val original = series(user.id)
            val moved =
                service.update(
                    user.id,
                    original.id,
                    "occurrence",
                    LocalDateTime.parse("2026-11-02T11:00"),
                    "Другое содержание",
                    When(date = LocalDate.parse("2026-11-03"), time = LocalTime.of(13, 0)),
                    null,
                )
            val stored = requireNotNull(reminders.find(user.id, original.id))
            val child = requireNotNull(reminders.find(user.id, moved.reminder.id))

            assertEquals(setOf(Instant.parse("2026-11-02T08:00:00Z")), stored.excludedOccurrences)
            assertEquals(original.id, child.parentId)
            assertEquals("Другое содержание", child.text)
            val occurrence = requireNotNull(stored.nextFireAt)
            reminders.record(
                stored.copy(lastFiredAt = occurrence, nextFireAt = occurrence.plus(Duration.ofDays(1))),
                DeliveryRecord(occurrence, DeliveryKind.SCHEDULED_AND_SNOOZED, DeliveryStatus.SENT, false, 555, null),
                clock.instant(),
            )

            db.dataSource().connection.use { connection ->
                connection.prepareStatement("select kind,telegram_message_id from reminder_deliveries where reminder_id=?").use {
                    it.setLong(1, original.id)
                    it.executeQuery().use { row ->
                        assertTrue(row.next())
                        assertEquals("SCHEDULED_AND_SNOOZED", row.getString(1))
                        assertEquals(555, row.getLong(2))
                        assertFalse(row.next())
                    }
                }
            }
        }

    @Test
    fun `expired confirmation rejects new execution but permits committed operation replay`() =
        runTest {
            val user = activeUser()
            val committedTarget = service.createOneTime(user.id, "Удалить после подтверждения", When(time = LocalTime.of(17, 0)))
            val untouchedTarget = service.createOneTime(user.id, "Оставить после истечения", When(time = LocalTime.of(18, 0)))
            val key = UUID.randomUUID()
            val request = args("""{"reminder_id":${committedTarget.id}}""")
            val approved = card(user.id, committedTarget.id)
            val stale = card(user.id, untouchedTarget.id)
            val first = tools.call("delete_reminder", user.id, request, key.toString(), approved)
            assertFalse(first.isError, first.json)
            clock.now = initialNow.plus(Duration.ofHours(2))

            val replay = tools.call("delete_reminder", user.id, request, key.toString(), approved)
            val rejected =
                tools.call(
                    "delete_reminder",
                    user.id,
                    args("""{"reminder_id":${untouchedTarget.id}}"""),
                    UUID.randomUUID().toString(),
                    stale,
                )

            assertFalse(replay.isError, replay.json)
            assertEquals(first.json, replay.json)
            assertEquals("confirmation_stale", rejected.code())
            assertEquals(ReminderStatus.ACTIVE, reminders.find(user.id, untouchedTarget.id)!!.status)
            assertEquals(ReminderStatus.DELETED, reminders.find(user.id, committedTarget.id)!!.status)
        }

    @Test
    fun `unknown status remains unchanged while supported reminder is delivered`() =
        runTest {
            val user = activeUser()
            val unknown = service.createOneTime(user.id, "Будущий статус", When(time = LocalTime.of(11, 0)))
            val supported = service.createOneTime(user.id, "Известный статус", When(time = LocalTime.of(11, 0)))
            db.dataSource().connection.use { connection ->
                connection.createStatement().use { it.execute("alter table reminders drop constraint reminders_status_check") }
                connection.prepareStatement("update reminders set status='FUTURE_STATE' where id=?").use {
                    it.setLong(1, unknown.id)
                    it.executeUpdate()
                }
            }
            try {
                assertEquals(
                    "unsupported_state",
                    tools.call("get_reminder", user.id, args("""{"reminder_id":${unknown.id}}"""), null).code(),
                )
                assertEquals(
                    "unsupported_state",
                    tools
                        .call(
                            "snooze_reminder",
                            user.id,
                            args("""{"reminder_id":${unknown.id},"when":{"time":"12:00"}}"""),
                            UUID.randomUUID().toString(),
                        ).code(),
                )
                var sent = 0
                val notifier =
                    object : Notifier {
                        override suspend fun send(
                            chatId: Long,
                            text: String,
                        ): NotifyResult {
                            sent++
                            return NotifyResult.Sent(777)
                        }
                    }
                clock.now = Instant.parse("2026-11-02T08:01:00Z")
                val scheduler = ReminderScheduler(reminders, users, notifier, texts, clock, Duration.ofMinutes(5))
                scheduler.tick()

                assertEquals(1, sent)
                assertEquals(ReminderStatus.DONE, reminders.find(user.id, supported.id)!!.status)
                val preserved = requireNotNull(reminders.find(user.id, unknown.id))
                assertEquals("FUTURE_STATE", preserved.rawStatus)
                assertEquals(unknown.nextFireAt, preserved.nextFireAt)
                assertNotNull(preserved.nextFireAt)
            } finally {
                db.dataSource().connection.use { connection ->
                    connection.prepareStatement("delete from reminders where id=?").use {
                        it.setLong(1, unknown.id)
                        it.executeUpdate()
                    }
                    connection.createStatement().use {
                        it.execute(
                            "alter table reminders add constraint reminders_status_check " +
                                "check (status in ('ACTIVE','PAUSED','DONE','DELETED'))",
                        )
                    }
                }
            }
        }

    @Test
    fun `unsupported recurrence rows do not starve supported reminders with batch limit one`() =
        runTest {
            val user = activeUser()
            val supported = service.createOneTime(user.id, "Обычное напоминание", When(time = LocalTime.of(11, 0)))
            db.dataSource().connection.use { connection ->
                connection
                    .prepareStatement(
                        """insert into reminders(user_id,text,timezone,start_local,recurrence_rule,status,next_fire_at,created_at,updated_at)
                        select ?,'Неподдерживаемое расписание','Europe/Moscow','2026-11-01 00:00','FREQ=HOURLY',
                        'ACTIVE',?,?,? from generate_series(1,100)""",
                    ).use {
                        it.setLong(1, user.id)
                        it.setObject(2, initialNow.minusSeconds(60).atOffset(ZoneOffset.UTC))
                        it.setObject(3, initialNow.atOffset(ZoneOffset.UTC))
                        it.setObject(4, initialNow.atOffset(ZoneOffset.UTC))
                        it.executeUpdate()
                    }
            }
            clock.now = Instant.parse("2026-11-02T08:01:00Z")

            assertEquals(listOf(supported.id), reminders.findDue(clock.instant(), 1).map { it.id })
            var sent = 0
            val notifier =
                object : Notifier {
                    override suspend fun send(
                        chatId: Long,
                        text: String,
                    ): NotifyResult {
                        sent++
                        return NotifyResult.Sent(888)
                    }
                }
            ReminderScheduler(reminders, users, notifier, texts, clock, Duration.ofMinutes(5), batchSize = 1).tick()

            assertEquals(1, sent)
            assertEquals(ReminderStatus.DONE, reminders.find(user.id, supported.id)!!.status)
            assertEquals(1, count("reminder_deliveries"))
            db.dataSource().connection.use { connection ->
                connection.createStatement().use {
                    it
                        .executeQuery(
                            """select count(*) from reminders where recurrence_rule='FREQ=HOURLY'
                            and status='ACTIVE' and last_fired_at is null""",
                        ).use { row ->
                            assertTrue(row.next())
                            assertEquals(100, row.getLong(1))
                        }
                }
            }
        }

    @Test
    fun `root deletion clears pause fields of descendant series and deletes nested transfers`() =
        runTest {
            val user = activeUser()
            val tree = descendantTree(user.id)

            val deleted =
                tools.call(
                    "delete_reminder",
                    user.id,
                    args("""{"reminder_id":${tree.root},"scope":"series"}"""),
                    UUID.randomUUID().toString(),
                    card(user.id, tree.root),
                )

            assertFalse(deleted.isError, deleted.json)
            assertDeletedDescendants(user.id, tree)
            assertEquals(ReminderStatus.DELETED, reminders.find(user.id, tree.root)!!.status)
        }

    @Test
    fun `root schedule reset removes nested active descendants but keeps completed history`() =
        runTest {
            val user = activeUser()
            val tree = descendantTree(user.id)

            val changed =
                tools.call(
                    "update_reminder",
                    user.id,
                    args("""{"reminder_id":${tree.root},"scope":"series","recurrence":{"time":"12:00"}}"""),
                    UUID.randomUUID().toString(),
                    card(user.id, tree.root),
                )

            assertFalse(changed.isError, changed.json)
            assertDeletedDescendants(user.id, tree)
            val root = requireNotNull(reminders.find(user.id, tree.root))
            assertEquals(ReminderStatus.ACTIVE, root.status)
            assertTrue(root.excludedOccurrences.isEmpty())
        }

    private suspend fun descendantTree(userId: Long): DescendantTree {
        val root = series(userId)
        val child =
            service
                .update(
                    userId,
                    root.id,
                    "occurrence",
                    LocalDateTime.parse("2026-11-03T11:00"),
                    null,
                    When(date = LocalDate.parse("2026-11-03"), time = LocalTime.of(13, 0)),
                    null,
                ).reminder
        service.update(
            userId,
            child.id,
            null,
            null,
            null,
            null,
            Recurrence("daily", time = LocalTime.of(13, 0), startDate = LocalDate.parse("2026-11-03")),
        )
        service.pause(userId, child.id, LocalDate.parse("2026-11-10"), null)
        val grandchild =
            service
                .update(
                    userId,
                    child.id,
                    "occurrence",
                    LocalDateTime.parse("2026-11-11T13:00"),
                    "Вложенный перенос",
                    When(date = LocalDate.parse("2026-11-11"), time = LocalTime.of(14, 0)),
                    null,
                ).reminder
        val completed =
            service
                .update(
                    userId,
                    root.id,
                    "occurrence",
                    LocalDateTime.parse("2026-11-04T11:00"),
                    null,
                    When(date = LocalDate.parse("2026-11-04"), time = LocalTime.of(15, 0)),
                    null,
                ).reminder
        reminders.save(completed.copy(status = ReminderStatus.DONE, nextFireAt = null), clock.instant())
        return DescendantTree(root.id, child.id, grandchild.id, completed.id)
    }

    private suspend fun assertDeletedDescendants(
        userId: Long,
        tree: DescendantTree,
    ) {
        for (id in listOf(tree.child, tree.grandchild)) {
            val deleted = requireNotNull(reminders.find(userId, id))
            assertEquals(ReminderStatus.DELETED, deleted.status)
            assertEquals(null, deleted.nextFireAt)
            assertEquals(null, deleted.pausedUntil)
        }
        assertEquals(ReminderStatus.DONE, reminders.find(userId, tree.completed)!!.status)
    }

    private data class DescendantTree(
        val root: Long,
        val child: Long,
        val grandchild: Long,
        val completed: Long,
    )

    private class TestClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }
}
