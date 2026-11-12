package theseus.core.adapters.http

import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import theseus.core.domain.user.AccountException
import theseus.core.domain.user.Gender

internal fun canonical(value: JsonElement): String =
    when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${canonical(it.value)}" }
        is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
        else -> value.toString()
    }

internal fun JsonObject.string(key: String): String =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw AccountException("invalid_arguments")

internal fun JsonObject.optionalString(key: String): String? = if (key in this) string(key) else null

internal fun JsonObject.nullableString(key: String): String? = if (get(key) == null || get(key) == JsonNull) null else string(key)

internal fun JsonObject.long(key: String): Long =
    (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        ?: throw AccountException("invalid_arguments")

internal fun JsonObject.int(key: String): Int {
    val value = long(key)
    validArgument(value in 0..Int.MAX_VALUE)
    return value.toInt()
}

internal fun String.gender(): Gender =
    Gender.entries.firstOrNull { it.name.lowercase() == this } ?: throw AccountException("invalid_arguments")

internal fun ApplicationCall.id(): Long = parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw AccountException("invalid_arguments")

internal fun validArgument(valid: Boolean) {
    if (!valid) throw AccountException("invalid_arguments")
}
