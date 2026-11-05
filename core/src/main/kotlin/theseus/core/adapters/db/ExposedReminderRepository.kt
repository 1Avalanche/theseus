package theseus.core.adapters.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import theseus.core.application.ReminderRepository
import theseus.core.domain.reminder.DeliveryRecord
import theseus.core.domain.reminder.NewReminder
import theseus.core.domain.reminder.Reminder
import theseus.core.domain.reminder.ReminderStatus
import java.time.Instant
import java.time.ZoneId

class ExposedReminderRepository(
    private val db: CoreDatabase,
) : ReminderRepository {
    override suspend fun create(
        reminder: NewReminder,
        now: Instant,
    ): Reminder =
        db.tx {
            val id =
                RemindersTable.insert {
                    it[userId] = reminder.userId
                    it[text] = reminder.text
                    it[timezone] = reminder.timezone.id
                    it[startLocal] = reminder.startLocal
                    it[status] = ReminderStatus.ACTIVE.name
                    it[nextFireAt] = reminder.nextFireAt.toUtc()
                    it[createdAt] = now.toUtc()
                    it[updatedAt] = now.toUtc()
                }[RemindersTable.id]
            RemindersTable
                .selectAll()
                .where { RemindersTable.id eq id }
                .single()
                .toReminder()
        }

    override suspend fun findDue(
        now: Instant,
        limit: Int,
    ): List<Reminder> =
        db.tx {
            RemindersTable
                .selectAll()
                .where { (RemindersTable.status eq ReminderStatus.ACTIVE.name) and (RemindersTable.nextFireAt lessEq now.toUtc()) }
                .orderBy(RemindersTable.nextFireAt to SortOrder.ASC)
                .limit(limit)
                .map { it.toReminder() }
        }

    override suspend fun completeOneTime(
        reminder: Reminder,
        delivery: DeliveryRecord?,
        now: Instant,
    ) {
        db.tx {
            if (delivery != null) {
                DeliveriesTable.insertIgnore {
                    it[reminderId] = reminder.id
                    it[userId] = reminder.userId
                    it[occurrenceAt] = delivery.occurrenceAt.toUtc()
                    it[kind] = delivery.kind.name
                    it[status] = delivery.status.name
                    it[late] = delivery.late
                    it[sentAt] = if (delivery.telegramMessageId != null) now.toUtc() else null
                    it[telegramMessageId] = delivery.telegramMessageId
                    it[error] = delivery.error
                    it[createdAt] = now.toUtc()
                }
            }
            RemindersTable.update({
                (RemindersTable.id eq reminder.id) and (RemindersTable.status eq ReminderStatus.ACTIVE.name)
            }) {
                it[status] = ReminderStatus.DONE.name
                it[nextFireAt] = null
                it[lastFiredAt] = if (delivery != null) now.toUtc() else null
                it[updatedAt] = now.toUtc()
            }
        }
    }

    private fun ResultRow.toReminder() =
        Reminder(
            id = this[RemindersTable.id],
            userId = this[RemindersTable.userId],
            text = this[RemindersTable.text],
            timezone = ZoneId.of(this[RemindersTable.timezone]),
            startLocal = this[RemindersTable.startLocal],
            recurrenceRule = this[RemindersTable.recurrenceRule],
            status = ReminderStatus.valueOf(this[RemindersTable.status]),
            nextFireAt = this[RemindersTable.nextFireAt]?.toInstant(),
            lastFiredAt = this[RemindersTable.lastFiredAt]?.toInstant(),
            createdAt = this[RemindersTable.createdAt].toInstant(),
        )
}
