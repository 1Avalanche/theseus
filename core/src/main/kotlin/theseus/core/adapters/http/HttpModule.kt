package theseus.core.adapters.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import theseus.core.adapters.mcp.ErrorBody
import theseus.core.adapters.mcp.ErrorPayload
import theseus.core.application.HealthProbe
import theseus.core.application.UserService
import theseus.core.domain.user.User
import java.security.MessageDigest

/** Зависимости HTTP-слоя core: MCP (`/mcp`), internal REST (`/internal/v1`), `GET /health`. */
class HttpDependencies(
    val serviceToken: String,
    val health: HealthProbe,
    val users: UserService,
    val mcpServer: Server,
    val accounts: AccountsRoutes? = null,
)

fun Application.coreModule(deps: HttpDependencies) {
    install(serviceTokenAuth(deps.serviceToken))
    // Порт core наружу не публикуется: агент ходит по имени контейнера, защита от DNS rebinding не нужна.
    mcpStatelessStreamableHttp(path = "/mcp", enableDnsRebindingProtection = false) { deps.mcpServer }
    routing {
        deps.accounts?.install(this)
        get("/health") {
            if (deps.health.isHealthy()) {
                call.respondJson(HttpStatusCode.OK, """{"status":"ok"}""")
            } else {
                call.respondJson(HttpStatusCode.ServiceUnavailable, """{"status":"unavailable"}""")
            }
        }
        get("/internal/v1/users/by-chat/{chat_id}") {
            val chatId = call.parameters["chat_id"]?.toLongOrNull()
            if (chatId == null) {
                call.respondError(HttpStatusCode.BadRequest, "invalid_arguments", "chat_id должен быть целым числом")
                return@get
            }
            val user = deps.users.findByChatId(chatId)
            if (user == null) {
                call.respondError(HttpStatusCode.NotFound, "not_found", "Пользователь не найден")
            } else {
                call.respondJson(HttpStatusCode.OK, restJson.encodeToString(UserDto.of(user)))
            }
        }
    }
}

/**
 * Bearer-токен сервиса на `/mcp` и `/internal/…`; `/health` открыт для healthcheck Docker.
 * Сравнение — за постоянное время.
 */
private fun serviceTokenAuth(token: String) =
    createApplicationPlugin("ServiceTokenAuth") {
        val expected = "Bearer $token".toByteArray()
        onCall { call ->
            val path = call.request.local.uri
            val protected = path.startsWith("/mcp") || path.startsWith("/internal/")
            val actual = call.request.headers[HttpHeaders.Authorization]?.toByteArray()
            if (protected && (actual == null || !MessageDigest.isEqual(expected, actual))) {
                call.respondError(HttpStatusCode.Unauthorized, "unauthorized", "Нет или неверный сервисный токен")
            }
        }
    }

internal val restJson = Json { encodeDefaults = true }

private suspend fun ApplicationCall.respondJson(
    status: HttpStatusCode,
    body: String,
) = respondText(body, ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
) = respondJson(status, restJson.encodeToString(ErrorBody(ErrorPayload(code, message))))

@Serializable
internal data class UserDto(
    val id: Long,
    @SerialName("chat_id") val chatId: Long,
    val role: String,
    val status: String,
    val name: String?,
    val gender: String,
    val timezone: String?,
    @SerialName("created_at") val createdAt: String,
    @SerialName("onboarded_at") val onboardedAt: String?,
) {
    companion object {
        fun of(user: User) =
            UserDto(
                id = user.id,
                chatId = user.chatId,
                role = user.role.name.lowercase(),
                status = user.status.name.lowercase(),
                name = user.name,
                gender = user.gender.name.lowercase(),
                timezone = user.timezone?.id,
                createdAt = user.createdAt.toString(),
                onboardedAt = user.onboardedAt?.toString(),
            )
    }
}
