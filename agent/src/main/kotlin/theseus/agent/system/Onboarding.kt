package theseus.agent.system

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import theseus.agent.core.CoreUser
import theseus.agent.texts.Texts
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties
import java.util.UUID

/** Имя и таймзона собираются кодом; LLM и геолокация не используются. */
class Onboarding(
    private val store: AgentStore,
    private val operations: OutgoingOperations,
    private val texts: Texts,
    private val clock: Clock,
    private val zones: List<Pair<String, String>>,
) {
    private val cities = Properties().apply { Texts.resourceText("onboarding/cities.properties").reader().use(::load) }

    suspend fun handle(event: EventSnapshot): BotReply {
        val user = requireNotNull(event.user)
        val input = event.input
        val command =
            input.text
                ?.trim()
                ?.takeWhile { !it.isWhitespace() }
                ?.substringBefore('@')
        if (command in setOf("/start", "/help")) return prompt(user, input.firstName)
        if (user.name == null) return name(event)
        val callback = input.callback
        if (callback == "ob:other") return BotReply(texts.get("onboarding.city"))
        if (callback?.startsWith("ob:yes:") == true) return confirm(event)
        val zone =
            if (callback?.startsWith("ob:zone:") == true) {
                callback.substringAfterLast(':').toIntOrNull()?.let { zones.getOrNull(it)?.second }
            } else {
                input.text?.trim()?.let(::resolve)
            }
        if (zone == null) return BotReply(texts.get(if (callback != null) "onboarding.stale" else "onboarding.unknown_zone"))
        // Идентификатор предложения зависит от события, поэтому рестарт сохраняет ту же кнопку.
        val proposal = ZoneProposal(UUID.nameUUIDFromBytes(input.id.toByteArray()).toString().take(8), zone)
        store.propose(user.id, proposal)
        val time = clock.instant().atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("HH:mm"))
        return BotReply(
            texts.get("onboarding.confirm", mapOf("timezone" to zone, "time" to time)),
            listOf(
                listOf(
                    Button(texts.get("onboarding.yes"), "ob:yes:${proposal.id}"),
                    Button(texts.get("onboarding.no"), "ob:other"),
                ),
            ),
        )
    }

    fun prompt(
        user: CoreUser,
        firstName: String?,
    ): BotReply =
        if (user.name == null) {
            BotReply(
                texts.get("onboarding.name"),
                firstName
                    ?.trim()
                    ?.takeIf(::validName)
                    ?.let {
                        listOf(listOf(Button(it, "ob:name")))
                    }.orEmpty(),
            )
        } else {
            BotReply(
                texts.get("onboarding.timezone"),
                zones.mapIndexed { index, (label, _) ->
                    listOf(Button(label, "ob:zone:$index"))
                } + listOf(listOf(Button(texts.get("onboarding.other"), "ob:other"))),
            )
        }

    private suspend fun name(event: EventSnapshot): BotReply {
        val user = requireNotNull(event.user)
        val name = if (event.input.callback == "ob:name") event.input.firstName?.trim() else event.input.text?.trim()
        if (name == null || name.startsWith('/') || !validName(name)) return BotReply(texts.get("onboarding.invalid_name"))
        val response =
            operations.perform(
                "${event.input.id}:profile",
                "PATCH",
                "/users/${user.id}",
                buildJsonObject { put("name", name) },
                user.id,
            )
        return if (response.successful) prompt(user.copy(name = name), event.input.firstName) else errorReply(response, texts)
    }

    private suspend fun confirm(event: EventSnapshot): BotReply {
        val user = requireNotNull(event.user)
        val proposal = event.proposal
        if (proposal == null || event.input.callback != "ob:yes:${proposal.id}") return BotReply(texts.get("onboarding.stale"))
        val response =
            operations.perform(
                "${event.input.id}:profile",
                "PATCH",
                "/users/${user.id}",
                buildJsonObject { put("timezone", proposal.timezone) },
                user.id,
            )
        if (!response.successful) return errorReply(response, texts)
        store.propose(user.id, null)
        return BotReply(texts.get("onboarding.done"))
    }

    internal fun resolve(text: String): String? {
        if (text in ZoneId.getAvailableZoneIds()) return text
        return cities.getProperty(text.lowercase(Locale.ROOT).replace('ё', 'е'))
    }

    private fun validName(name: String): Boolean =
        name.isNotBlank() &&
            name.codePointCount(0, name.length) in 1..MAX_NAME_LENGTH &&
            name.none { it.isISOControl() }

    companion object {
        private const val MAX_NAME_LENGTH = 64
    }
}
