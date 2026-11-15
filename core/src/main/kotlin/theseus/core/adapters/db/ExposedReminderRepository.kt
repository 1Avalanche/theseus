// Числа JDBC — позиции параметров SQL.
@file:Suppress("MagicNumber")

package theseus.core.adapters.db

import theseus.core.application.DeliveryBacklog
import theseus.core.application.ReminderRepository
import theseus.core.domain.reminder.DeliveryRecord
import theseus.core.domain.reminder.NewReminder
import theseus.core.domain.reminder.Reminder
import theseus.core.domain.reminder.ReminderStatus
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** JDBC внутри транзакций Exposed; исключения хранятся штатным PostgreSQL timestamptz[]. */
@Suppress("TooManyFunctions") // Единый репозиторий напоминаний и журнала доставок.
class ExposedReminderRepository(
    private val db: CoreDatabase,
) : ReminderRepository {
    override suspend fun nextDeliveryId(): Long =
        db.tx {
            val jdbc = connection.connection as Connection
            jdbc.createStatement().use { statement ->
                statement.executeQuery("select nextval('reminder_deliveries_id_seq')").use { row ->
                    check(row.next())
                    row.getLong(1)
                }
            }
        }

    override suspend fun delivery(
        userId: Long,
        id: Long?,
        messageId: Long?,
    ): theseus.core.application.DeliveryReference? =
        db.tx {
            require((id == null) != (messageId == null))
            val jdbc = connection.connection as Connection
            val column = if (id != null) "id" else "telegram_message_id"
            jdbc
                .prepareStatement(
                    "select id,reminder_id,occurrence_at from reminder_deliveries where user_id=? and $column=? and status='SENT'",
                ).use {
                    it.setLong(1, userId)
                    it.setLong(2, id ?: requireNotNull(messageId))
                    it.executeQuery().use { row ->
                        if (row.next()) {
                            theseus.core.application.DeliveryReference(
                                row.getLong(1),
                                row.getLong(2),
                                row.getObject(3, OffsetDateTime::class.java).toInstant(),
                            )
                        } else {
                            null
                        }
                    }
                }
        }

    override suspend fun <T> withUserLock(
        userId: Long,
        block: suspend () -> T,
    ): T =
        db.atomic {
            val jdbc = connection.connection as Connection
            jdbc.prepareStatement("select id from users where id=? for update").use {
                it.setLong(1, userId)
                it.executeQuery().use { row -> row.next() }
            }
            block()
        }

    override suspend fun find(
        userId: Long,
        id: Long,
    ): Reminder? = query("select * from reminders where user_id=? and id=?", userId, id).singleOrNull()

    override suspend fun list(userId: Long): List<Reminder> = query("select * from reminders where user_id=? order by id", userId)

    override suspend fun findDue(
        now: Instant,
        limit: Int,
    ): List<Reminder> =
        query(
            "select * from reminders where status='ACTIVE' and next_fire_at<=? order by next_fire_at,id",
            now.toUtc(),
        ).filter { it.status == ReminderStatus.ACTIVE }.take(limit)

    override suspend fun findResumable(now: Instant): List<Reminder> =
        query("select * from reminders where status='PAUSED' and paused_until<=? order by id", now.toUtc())
            .filter { it.status == ReminderStatus.PAUSED }

    override suspend fun deliveryBacklog(now: Instant): DeliveryBacklog =
        db.tx {
            val jdbc = connection.connection as Connection
            jdbc.prepareStatement("select count(*),min(next_fire_at) from reminders where status='ACTIVE' and next_fire_at<=?").use {
                it.setObject(1, now.toUtc())
                it.executeQuery().use { row ->
                    check(row.next())
                    DeliveryBacklog(row.getLong(1), row.getObject(2, OffsetDateTime::class.java)?.toInstant())
                }
            }
        }

    override suspend fun create(
        reminder: NewReminder,
        now: Instant,
    ): Reminder =
        db.tx {
            val jdbc = connection.connection as Connection
            jdbc
                .prepareStatement(
                    """insert into reminders(user_id,text,timezone,start_local,recurrence_rule,parent_id,status,next_fire_at,created_at,updated_at)
                values(?,?,?,?,?,?,'ACTIVE',?,?,?) returning *""",
                ).use {
                    it.setLong(1, reminder.userId)
                    it.setString(2, reminder.text)
                    it.setString(3, reminder.timezone.id)
                    it.setObject(4, reminder.startLocal)
                    it.setString(5, reminder.recurrenceRule)
                    it.setObject(6, reminder.parentId)
                    it.setObject(7, reminder.nextFireAt.toUtc())
                    it.setObject(8, now.toUtc())
                    it.setObject(9, now.toUtc())
                    it.executeQuery().use { row ->
                        check(row.next())
                        row.reminder()
                    }
                }
        }

    override suspend fun save(
        reminder: Reminder,
        now: Instant,
    ): Reminder =
        db.tx {
            check(reminder.status != ReminderStatus.UNSUPPORTED)
            val jdbc = connection.connection as Connection
            val excluded = jdbc.createArrayOf("timestamptz", reminder.excludedOccurrences.map { it.toUtc() }.toTypedArray())
            try {
                jdbc
                    .prepareStatement(
                        """update reminders set text=?,start_local=?,recurrence_rule=?,status=?,next_fire_at=?,last_fired_at=?,
                    excluded_occurrences=?,paused_until=?,snoozed_until=?,updated_at=?,deleted_at=?
                    where user_id=? and id=? returning *""",
                    ).use {
                        it.setString(1, reminder.text)
                        it.setObject(2, reminder.startLocal)
                        it.setString(3, reminder.recurrenceRule)
                        it.setString(4, reminder.status.name)
                        it.setObject(5, reminder.nextFireAt?.toUtc())
                        it.setObject(6, reminder.lastFiredAt?.toUtc())
                        it.setArray(7, excluded)
                        it.setObject(8, reminder.pausedUntil?.toUtc())
                        it.setObject(9, reminder.snoozedUntil?.toUtc())
                        it.setObject(10, now.toUtc())
                        it.setObject(11, if (reminder.status == ReminderStatus.DELETED) now.toUtc() else null)
                        it.setLong(12, reminder.userId)
                        it.setLong(13, reminder.id)
                        it.executeQuery().use { row ->
                            check(row.next())
                            row.reminder()
                        }
                    }
            } finally {
                excluded.free()
            }
        }

    override suspend fun record(
        reminder: Reminder,
        delivery: DeliveryRecord?,
        now: Instant,
    ) {
        db.atomic {
            if (delivery != null) {
                val jdbc = connection.connection as Connection
                jdbc
                    .prepareStatement(
                        """insert into reminder_deliveries(reminder_id,user_id,occurrence_at,kind,status,late,sent_at,
                        telegram_message_id,error,created_at,id) overriding system value
                        values(?,?,?,?,?,?,?,?,?,?,coalesce(?,nextval('reminder_deliveries_id_seq'))) on conflict do nothing""",
                    ).use {
                        it.setLong(1, reminder.id)
                        it.setLong(2, reminder.userId)
                        it.setObject(3, delivery.occurrenceAt.toUtc())
                        it.setString(4, delivery.kind.name)
                        it.setString(5, delivery.status.name)
                        it.setBoolean(6, delivery.late)
                        it.setObject(7, if (delivery.telegramMessageId != null) now.toUtc() else null)
                        it.setObject(8, delivery.telegramMessageId)
                        it.setString(9, delivery.error)
                        it.setObject(10, now.toUtc())
                        it.setObject(11, delivery.id)
                        it.executeUpdate()
                    }
            }
            save(reminder, now)
        }
    }

    override suspend fun completeOneTime(
        reminder: Reminder,
        delivery: DeliveryRecord?,
        now: Instant,
    ) {
        record(reminder.copy(status = ReminderStatus.DONE, nextFireAt = null, lastFiredAt = delivery?.let { now }), delivery, now)
    }

    private suspend fun query(
        sql: String,
        vararg params: Any,
    ): List<Reminder> =
        db.tx {
            val jdbc = connection.connection as Connection
            jdbc.prepareStatement(sql).use {
                params.forEachIndexed { index, value -> it.setObject(index + 1, value) }
                it.executeQuery().use { row -> buildList { while (row.next()) add(row.reminder()) } }
            }
        }
}

private fun ResultSet.reminder(): Reminder {
    val raw = getString("status")
    val rule = getString("recurrence_rule")
    val frequency = rule?.split(';')?.firstOrNull { it.startsWith("FREQ=") }?.substringAfter('=')
    val unsupportedFrequency = frequency != null && frequency !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")
    val excluded = getArray("excluded_occurrences")
    val instants =
        try {
            excluded.resultSet.use { values ->
                buildSet { while (values.next()) add(values.getObject(2, OffsetDateTime::class.java).toInstant()) }
            }
        } finally {
            excluded.free()
        }
    return Reminder(
        id = getLong("id"),
        userId = getLong("user_id"),
        text = getString("text"),
        timezone = ZoneId.of(getString("timezone")),
        startLocal = getObject("start_local", LocalDateTime::class.java),
        recurrenceRule = rule,
        status =
            if (unsupportedFrequency) {
                ReminderStatus.UNSUPPORTED
            } else {
                ReminderStatus.entries.firstOrNull { it.name == raw } ?: ReminderStatus.UNSUPPORTED
            },
        nextFireAt = getObject("next_fire_at", OffsetDateTime::class.java)?.toInstant(),
        lastFiredAt = getObject("last_fired_at", OffsetDateTime::class.java)?.toInstant(),
        createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
        excludedOccurrences = instants,
        parentId = getObject("parent_id")?.let { (it as Number).toLong() },
        pausedUntil = getObject("paused_until", OffsetDateTime::class.java)?.toInstant(),
        snoozedUntil = getObject("snoozed_until", OffsetDateTime::class.java)?.toInstant(),
        updatedAt = getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        rawStatus = raw.takeIf { value -> ReminderStatus.entries.none { it.name == value } },
    )
}
