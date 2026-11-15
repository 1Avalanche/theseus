package theseus.core.adapters.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import theseus.core.adapters.canonical
import theseus.core.application.AccountsService
import theseus.core.application.Confirmation
import theseus.core.application.OperationReply
import theseus.core.application.OperationRequest
import theseus.core.application.Operations
import theseus.core.application.ReminderService
import theseus.core.domain.DomainException
import theseus.core.domain.ErrorCode
import theseus.core.domain.LocalFormats
import theseus.core.domain.reminder.After
import theseus.core.domain.reminder.PartOfDay
import theseus.core.domain.reminder.Recurrence
import theseus.core.domain.reminder.Reminder
import theseus.core.domain.reminder.When
import theseus.core.domain.user.AccountException
import theseus.core.texts.Texts
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class ToolOutcome(
    val json: String,
    val isError: Boolean,
)

/** Разбор wire-контракта; доменное изменение и исходный ответ сохраняются одной транзакцией. */
@Suppress("TooManyFunctions") // Девять методов протокола и строгие преобразования их аргументов.
class ReminderTools(
    private val reminders: ReminderService,
    private val texts: Texts,
    private val operations: Operations? = null,
) {
    private val log = LoggerFactory.getLogger(ReminderTools::class.java)

    suspend fun call(
        name: String,
        userId: Long?,
        arguments: JsonObject?,
        operationId: String?,
        confirmationJson: JsonObject? = null,
    ): ToolOutcome =
        handle {
            val user = userId ?: throw DomainException(ErrorCode.USER_INACTIVE)
            val args = arguments ?: JsonObject(emptyMap())
            val confirmation =
                confirmationJson?.let {
                    checkFields(it, setOf("fingerprint", "created_at"))
                    Confirmation(it.string("fingerprint"), Instant.parse(it.string("created_at")))
                }
            if (name !in changing) return@handle dispatch(name, user, args, confirmation).toString()
            val raw = operationId ?: invalid()
            val key = UUID.fromString(raw)
            if (!key.toString().equals(raw, true)) invalid()
            val registry = operations ?: error("Реестр MCP не подключён")
            val hash =
                AccountsService.hashCode(
                    canonical(
                        buildJsonObject {
                            put("arguments", args)
                            if (confirmationJson != null) put("confirmation", confirmationJson)
                        },
                    ),
                )
            val reply =
                registry.execute(
                    OperationRequest(key, "user:$user", name, hash, user, channel = "MCP"),
                    reminders.now(),
                    { reminders.authorize(user) },
                ) {
                    OperationReply(SUCCESS, dispatch(name, user, args, confirmation).toString())
                }
            if (reply.status != SUCCESS) {
                val body = Json.parseToJsonElement(reply.body).jsonObject
                return@handle body.toString()
            }
            reply.body
        }

    /** Прямой шов адаптера для юнит-тестов; сервер использует только [call] с реестром операций. */
    internal suspend fun createReminder(
        userId: Long?,
        arguments: JsonObject?,
    ): ToolOutcome =
        handle {
            dispatch(
                "create_reminder",
                userId ?: throw DomainException(ErrorCode.USER_INACTIVE),
                arguments ?: JsonObject(emptyMap()),
                null,
            ).toString()
        }

    private suspend fun dispatch(
        name: String,
        user: Long,
        args: JsonObject,
        confirmation: Confirmation?,
    ): JsonObject =
        when (name) {
            "create_reminder" -> create(user, args, confirmation)
            "list_reminders" -> list(user, args)
            "find_reminders" -> {
                checkFields(args, setOf("query"))
                buildJsonObject { put("candidates", JsonArray(reminders.find(user, args.string("query")).map(ReminderView::of))) }
            }
            "get_reminder" -> get(user, args)
            "update_reminder" -> update(user, args, confirmation)
            "delete_reminder" -> delete(user, args, confirmation)
            "snooze_reminder" -> {
                checkFields(args, setOf("reminder_id", "when"))
                val reminder = reminders.snooze(user, args.id(), whenDomain(args.objectValue("when")))
                buildJsonObject {
                    put("status", "snoozed")
                    put("reminder", ReminderView.of(reminder))
                }
            }
            "pause_reminder" -> {
                checkFields(args, setOf("reminder_id", "resume_on", "resume_after"))
                val reminder = reminders.pause(user, args.id(), args.date("resume_on"), args.date("resume_after"))
                buildJsonObject {
                    put("status", "paused")
                    put("reminder", ReminderView.of(reminder))
                }
            }
            "resume_reminder" -> {
                checkFields(args, setOf("reminder_id"))
                val reminder = reminders.resume(user, args.id())
                buildJsonObject {
                    put("status", "resumed")
                    put("reminder", ReminderView.of(reminder))
                }
            }
            else -> invalid()
        }

    private suspend fun delete(
        user: Long,
        args: JsonObject,
        confirmation: Confirmation?,
    ): JsonObject {
        checkFields(args, setOf("reminder_id", "scope", "occurrence_local"))
        val scope = args.optionalString("scope")
        if (scope == "all") {
            if (args.keys != setOf("scope")) invalid()
            return buildJsonObject {
                put("status", "deleted_all")
                put("deleted_count", reminders.deleteAll(user, confirmation))
            }
        }
        val local = args.moment("occurrence_local")
        if (scope == "occurrence" && local == null && confirmation != null) invalid()
        val reminder = reminders.delete(user, args.id(), scope, local, confirmation)
        return buildJsonObject {
            put("status", if (scope == "occurrence") "occurrence_skipped" else "deleted")
            put("reminder", ReminderView.of(reminder))
            if (scope == "occurrence") put("occurrence_local", args.string("occurrence_local"))
        }
    }

    private suspend fun get(
        user: Long,
        args: JsonObject,
    ): JsonObject =
        reminders.atomic(user) {
            checkFields(args, setOf("reminder_id"))
            val id = args.id()
            val reminder = reminders.get(user, id)
            val effects = reminders.consequences(user, id)
            buildJsonObject {
                put("reminder", ReminderView.of(reminder))
                put("upcoming_local", JsonArray(reminders.upcoming(user, id).map { JsonPrimitive(LocalFormats.formatMoment(it)) }))
                put("fingerprint", reminders.snapshot(user, id))
                put("excluded_count", effects.first)
                put("pending_children_count", effects.second)
                put("next_occurrence_local", reminders.nextOccurrence(user, id)?.let(LocalFormats::formatMoment))
            }
        }

    private suspend fun list(
        user: Long,
        args: JsonObject,
    ): JsonObject {
        checkFields(args, setOf("from", "to", "include_paused"))
        val list = reminders.list(user, args.date("from"), args.date("to"), args.bool("include_paused", true))
        return buildJsonObject {
            put("reminders", JsonArray(list.reminders.map(ReminderView::of)))
            list.fingerprint?.let { put("fingerprint", it) }
            put("unsupported_count", list.unsupportedCount)
            list.occurrences?.let { occurrences ->
                put(
                    "occurrences",
                    JsonArray(
                        occurrences.map { (id, local) ->
                            buildJsonObject {
                                put("reminder_id", id)
                                put("at_local", LocalFormats.formatMoment(local))
                            }
                        },
                    ),
                )
                if (list.truncated) put("truncated", true)
            }
        }
    }

    private suspend fun create(
        user: Long,
        args: JsonObject,
        confirmation: Confirmation?,
    ): JsonObject {
        checkFields(args, setOf("text", "when", "recurrence", "force"))
        val created =
            reminders.create(
                user,
                args.string("text"),
                args.optionalObject("when")?.let(::whenDomain),
                args.optionalObject("recurrence")?.let(::recurrenceDomain),
                args.bool("force", false),
                confirmation,
            )
        return buildJsonObject {
            if (created.reminder != null) {
                put("status", "created")
                put("reminder", ReminderView.of(created.reminder))
                put("warnings", JsonArray(created.warnings.map(::JsonPrimitive)))
            } else {
                put("status", "possible_duplicate")
                put("candidates", JsonArray(created.candidates.map(ReminderView::of)))
                put("fingerprint", created.fingerprint)
                created.proposed?.let { put("proposed", ReminderView.of(it)) }
            }
        }
    }

    private suspend fun update(
        user: Long,
        args: JsonObject,
        confirmation: Confirmation?,
    ): JsonObject =
        reminders.atomic(user) {
            checkFields(args, setOf("reminder_id", "scope", "occurrence_local", "text", "when", "recurrence"))
            val old = reminders.get(user, args.id())
            val at = args.optionalObject("when")?.let(::whenDomain)
            val timePatch = timePatch(old, args, at)
            val patch = args.optionalObject("recurrence") ?: timePatch
            patch?.let { checkFields(it, recurrenceFields) }
            val repeated = patch?.let { mergeRecurrence(old, it) }
            val change =
                reminders.update(
                    user,
                    args.id(),
                    args.optionalString("scope"),
                    args.optionalString("occurrence_local")?.let {
                        if (it.length == DATE_LENGTH) parseDate(it).atTime(old.startLocal.toLocalTime()) else parseMoment(it)
                    },
                    args.optionalString("text"),
                    at.takeUnless { timePatch != null },
                    repeated,
                    confirmation,
                )
            buildJsonObject {
                put("status", "updated")
                put("reminder", ReminderView.of(change.reminder))
                change.series?.let { put("series", ReminderView.of(it)) }
                put("warnings", JsonArray(change.warnings.map(::JsonPrimitive)))
            }
        }

    private fun timePatch(
        old: Reminder,
        args: JsonObject,
        at: When?,
    ): JsonObject? {
        if (old.recurrenceRule == null || args.optionalString("scope") != "series" || at == null) return null
        if (at.date != null || at.after != null || "recurrence" in args) invalid()
        if (at.time == null && at.partOfDay == null) invalid()
        return args.objectValue("when")
    }

    private fun mergeRecurrence(
        old: Reminder,
        patch: JsonObject,
    ): Recurrence {
        val merged = if (old.recurrenceRule == null) mutableMapOf() else recurrenceJson(recurrenceOf(old)).toMutableMap()
        if ("part_of_day" in patch && "time" !in patch) merged.remove("time")
        patch.forEach { (key, value) ->
            if (value == JsonNull) merged.remove(key) else merged[key] = value
        }
        return recurrenceDomain(JsonObject(merged))
    }

    private fun whenDomain(value: JsonObject): When {
        val dto = json.decodeFromJsonElement<WhenDto>(value)
        return When(
            date = dto.date?.let(::parseDate),
            time = dto.time?.let { LocalFormats.parseTime(it) ?: invalid() },
            after = dto.after?.let { After(it.days, it.hours, it.minutes) },
            partOfDay = dto.partOfDay?.let(::partOfDay),
        )
    }

    private fun recurrenceDomain(value: JsonObject): Recurrence {
        val dto = json.decodeFromJsonElement<RecurrenceDto>(value)
        return Recurrence(
            dto.freq,
            dto.interval,
            dto.byWeekday,
            dto.byMonthDay,
            dto.byMonth,
            dto.time?.let { LocalFormats.parseTime(it) ?: invalid() },
            dto.startDate?.let(::parseDate),
            dto.until?.let(::parseDate),
            dto.count,
            dto.partOfDay?.let(::partOfDay),
        )
    }

    private fun partOfDay(value: String): PartOfDay = PartOfDay.entries.firstOrNull { it.name.lowercase() == value } ?: invalid()

    private suspend fun handle(action: suspend () -> String): ToolOutcome =
        try {
            val body = action()
            ToolOutcome(body, Json.parseToJsonElement(body).jsonObject.containsKey("error"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: DomainException) {
            failure(e.error, e.details)
        } catch (e: AccountException) {
            failure(if (e.code == "idempotency_conflict") ErrorCode.IDEMPOTENCY_CONFLICT else ErrorCode.INVALID_STATE, emptyMap())
        } catch (_: IllegalArgumentException) {
            failure(ErrorCode.INVALID_ARGUMENTS, emptyMap())
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.error("Ошибка инструмента: {}", e.javaClass.simpleName)
            failure(ErrorCode.INTERNAL, emptyMap())
        }

    private fun failure(
        code: ErrorCode,
        details: Map<String, String>,
    ): ToolOutcome =
        ToolOutcome(json.encodeToString(ErrorBody(ErrorPayload(code.code, texts.get("error.${code.code}", details), details))), true)

    private fun checkFields(
        args: JsonObject,
        allowed: Set<String>,
    ) {
        if ((args.keys - allowed).isNotEmpty()) invalid()
    }

    private fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid()

    private fun JsonObject.optionalString(key: String): String? = if (key in this) string(key) else null

    private fun JsonObject.objectValue(key: String): JsonObject = get(key) as? JsonObject ?: invalid()

    private fun JsonObject.optionalObject(key: String): JsonObject? = if (key in this) objectValue(key) else null

    private fun JsonObject.bool(
        key: String,
        default: Boolean,
    ): Boolean = if (key !in this) default else (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid()

    private fun JsonObject.id(): Long =
        (get("reminder_id") as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.longOrNull
            ?.takeIf { it > 0 } ?: invalid()

    private fun parseDate(value: String) = LocalFormats.parseDate(value) ?: throw DomainException(ErrorCode.INVALID_DATE)

    private fun parseMoment(value: String): LocalDateTime {
        parseDate(value.substringBefore('T'))
        return LocalFormats.parseMoment(value) ?: invalid()
    }

    private fun JsonObject.date(key: String) = optionalString(key)?.let(::parseDate)

    private fun JsonObject.moment(key: String): LocalDateTime? = optionalString(key)?.let(::parseMoment)

    private fun invalid(): Nothing = throw DomainException(ErrorCode.INVALID_ARGUMENTS)

    companion object {
        private val recurrenceFields =
            setOf(
                "freq",
                "interval",
                "by_weekday",
                "by_month_day",
                "by_month",
                "time",
                "part_of_day",
                "start_date",
                "until",
                "count",
            )
        private const val SUCCESS = 200
        private const val DATE_LENGTH = 10
        val names =
            listOf(
                "create_reminder",
                "list_reminders",
                "find_reminders",
                "get_reminder",
                "update_reminder",
                "delete_reminder",
                "snooze_reminder",
                "pause_reminder",
                "resume_reminder",
            )
        val changing =
            setOf("create_reminder", "update_reminder", "delete_reminder", "snooze_reminder", "pause_reminder", "resume_reminder")
        internal val json =
            Json {
                ignoreUnknownKeys = false
                encodeDefaults = true
            }
    }
}
