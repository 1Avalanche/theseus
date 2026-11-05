package theseus.core.adapters.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// Отображение таблиц схемы `core` (DDL — Flyway, `db/migration/core`). Здесь только используемые колонки.

internal object UsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramChatId = long("telegram_chat_id")
    val role = text("role")
    val status = text("status")
    val name = text("name").nullable()
    val gender = text("gender")
    val timezone = text("timezone").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val onboardedAt = timestampWithTimeZone("onboarded_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

internal object RemindersTable : Table("reminders") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val text = text("text")
    val timezone = text("timezone")
    val startLocal = datetime("start_local")
    val recurrenceRule = text("recurrence_rule").nullable()
    val status = text("status")
    val nextFireAt = timestampWithTimeZone("next_fire_at").nullable()
    val lastFiredAt = timestampWithTimeZone("last_fired_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

internal object DeliveriesTable : Table("reminder_deliveries") {
    val id = long("id").autoIncrement()
    val reminderId = long("reminder_id")
    val userId = long("user_id")
    val occurrenceAt = timestampWithTimeZone("occurrence_at")
    val kind = text("kind")
    val status = text("status")
    val late = bool("late")
    val sentAt = timestampWithTimeZone("sent_at").nullable()
    val telegramMessageId = long("telegram_message_id").nullable()
    val error = text("error").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}
