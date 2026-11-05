package theseus.core.adapters.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory
import theseus.core.application.ReminderService
import theseus.core.domain.DomainException
import theseus.core.domain.ErrorCode
import theseus.core.domain.LocalFormats
import theseus.core.domain.reminder.After
import theseus.core.domain.reminder.When
import theseus.core.texts.Texts

/** Результат инструмента: JSON-текст для модели и признак ошибки (`isError`). */
data class ToolOutcome(
    val json: String,
    val isError: Boolean,
)

/**
 * Инструменты напоминаний без привязки к MCP SDK: разбор аргументов, вызов сервиса, JSON ответа.
 * Пользователь — только из `_meta` (параметр [userId]), никогда из аргументов модели.
 */
class ReminderTools(
    private val reminders: ReminderService,
    private val texts: Texts,
) {
    private val log = LoggerFactory.getLogger(ReminderTools::class.java)

    suspend fun createReminder(
        userId: Long?,
        arguments: JsonObject?,
    ): ToolOutcome =
        handle(userId) { user ->
            val args = decode<CreateReminderArgs>(arguments)
            // Ровно одно из when / recurrence (контракт); серии на этапе 2 не поддерживаются.
            if (args.recurrence != null && args.at != null) throw invalid("when, recurrence")
            if (args.recurrence != null) throw DomainException(ErrorCode.INVALID_RECURRENCE)
            val at = args.at ?: throw invalid("when")
            val reminder = reminders.createOneTime(user, args.text, at.toDomain())
            json.encodeToString(CreatedResult(status = "created", reminder = ReminderView.of(reminder)))
        }

    private suspend fun handle(
        userId: Long?,
        block: suspend (Long) -> String,
    ): ToolOutcome =
        try {
            ToolOutcome(block(userId ?: throw DomainException(ErrorCode.USER_INACTIVE)), isError = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DomainException) {
            failure(e.error, e.details)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            log.error("Ошибка инструмента", e)
            failure(ErrorCode.INTERNAL, emptyMap())
        }

    private fun failure(
        code: ErrorCode,
        details: Map<String, String>,
    ): ToolOutcome {
        val message = texts.get("error.${code.code}", details)
        return ToolOutcome(json.encodeToString(ErrorBody(ErrorPayload(code.code, message, details))), isError = true)
    }

    private inline fun <reified T> decode(arguments: JsonObject?): T =
        try {
            json.decodeFromJsonElement<T>(arguments ?: JsonObject(emptyMap()))
        } catch (e: IllegalArgumentException) {
            // SerializationException — тоже IllegalArgumentException; причина уходит модели в `field`.
            throw invalid(e.message?.substringBefore('\n')?.take(MAX_REASON_LENGTH) ?: "arguments", e)
        }

    private fun WhenDto.toDomain(): When =
        When(
            date = date?.let { LocalFormats.parseDate(it) ?: throw invalid("when.date") },
            time = time?.let { LocalFormats.parseTime(it) ?: throw invalid("when.time") },
            after = after?.let { After(it.days, it.hours, it.minutes) },
        )

    private fun invalid(
        field: String,
        cause: Throwable? = null,
    ) = DomainException(ErrorCode.INVALID_ARGUMENTS, mapOf("field" to field), cause)

    companion object {
        private const val MAX_REASON_LENGTH = 200

        internal val json =
            Json {
                ignoreUnknownKeys = false
                encodeDefaults = true
            }
    }
}
