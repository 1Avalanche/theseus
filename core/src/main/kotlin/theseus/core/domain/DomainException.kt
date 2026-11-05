package theseus.core.domain

/**
 * Коды ошибок домена. Значения [code] — часть внешних контрактов (MCP `error.code`, REST),
 * менять нельзя. Тексты сообщений рендерят адаптеры из ресурсов.
 */
enum class ErrorCode(
    val code: String,
) {
    INVALID_ARGUMENTS("invalid_arguments"),
    INVALID_WHEN("invalid_when"),
    TIME_IN_PAST("time_in_past"),
    TOO_FAR("too_far"),
    INVALID_RECURRENCE("invalid_recurrence"),
    NOT_FOUND("not_found"),
    USER_INACTIVE("user_inactive"),
    INTERNAL("internal"),
}

/**
 * Ожидаемая ошибка бизнес-логики. [details] — подстановки для текста сообщения и поле `details` ответа.
 */
class DomainException(
    val error: ErrorCode,
    val details: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(error.code, cause)
