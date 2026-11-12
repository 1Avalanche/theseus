package theseus.agent.system

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import theseus.agent.core.CoreClient
import theseus.agent.core.CoreUser
import theseus.agent.pipeline.DialogReplies
import theseus.agent.texts.Texts

/** Системные события сохраняются до исполнения. Повтор использует исходный снимок и ключи операций. */
class SystemHandler(
    private val core: CoreClient,
    private val store: AgentStore,
    private val operations: OutgoingOperations,
    private val onboarding: Onboarding,
    private val admin: AdminCommands,
    private val adminChatId: Long,
    private val botUsername: String,
    private val texts: Texts,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dialogue = DialogReplies(texts)

    /** null означает обычный текст для LLM; системное событие всегда даёт ресурсный ответ. */
    suspend fun handle(input: Incoming): BotReply? {
        val user = core.findUserByChat(input.chatId)
        val previous = store.findEvent(input.id)
        if (previous != null) return resume(previous)
        val command =
            input.text
                .orEmpty()
                .trim()
                .takeWhile { !it.isWhitespace() }
                .substringBefore('@')
        val system =
            user?.isActive != true ||
                input.text == null ||
                input.callback != null ||
                command in AdminCommands.commands ||
                command in setOf("/start", "/help", "/version")
        if (!system) return null
        val event = store.beginEvent(EventSnapshot(input, user, user?.let { store.proposal(it.id) }))
        return resume(event)
    }

    suspend fun resume(event: StoredEvent): BotReply {
        val snapshot = event.snapshot
        val input = snapshot.input
        val current = core.findUserByChat(input.chatId)
        if (current?.status == "revoked") return BotReply(texts.get("access.revoked"))
        if (current != null && current.status !in setOf("active", "onboarding")) return BotReply(texts.get("access.revoked"))
        if (snapshot.user != null && current?.id != snapshot.user.id) return BotReply(texts.get("access.revoked"))
        if (event.delivered) return BotReply("")
        event.reply?.let { return it }
        val reply = process(snapshot)
        store.finishEvent(input.id, reply)
        return reply
    }

    /** До LLM сохраняется маркер начала, без самого текста: незавершённое изменение не исполняется заново. */
    suspend fun beginDialogue(input: Incoming): StoredEvent =
        store.beginEvent(
            EventSnapshot(input.copy(text = null), core.findUserByChat(input.chatId), null, dialogue = true),
        )

    private suspend fun process(snapshot: EventSnapshot): BotReply {
        val input = snapshot.input
        val user = snapshot.user
        val command =
            input.text
                .orEmpty()
                .trim()
                .takeWhile { !it.isWhitespace() }
                .substringBefore('@')
        return when {
            snapshot.dialogue -> BotReply(texts.get("reply.unconfirmed"))
            user == null -> activate(input, command)
            input.text == null && input.callback == null -> BotReply(texts.get("reply.text_only"))
            user.status == "onboarding" ->
                if (command == "/version") {
                    BotReply(requireNotNull(dialogue.commandReply("/version", user)))
                } else {
                    onboarding.handle(snapshot)
                }
            !user.isActive -> BotReply(texts.get("access.revoked"))
            command in AdminCommands.commands ->
                if (input.chatId == adminChatId) {
                    admin.handle(input, botUsername)
                } else {
                    BotReply(texts.get("admin.forbidden"))
                }
            input.callback != null -> BotReply(texts.get("onboarding.stale"))
            else -> commandReply(input, user, command)
        }
    }

    private fun commandReply(
        input: Incoming,
        user: CoreUser,
        command: String,
    ): BotReply {
        val reply = requireNotNull(dialogue.commandReply(input.text.orEmpty(), user))
        return BotReply(if (command == "/help" && input.chatId == adminChatId) reply + "\n" + texts.get("admin.help") else reply)
    }

    private suspend fun activate(
        input: Incoming,
        command: String,
    ): BotReply {
        val code =
            input.text
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.takeIf { command == "/start" && it.size == 2 }
                ?.get(1)
                ?: return BotReply(texts.get("access.invite"))
        val result =
            operations.perform(
                "${input.id}:activate",
                "POST",
                "/users/activate",
                buildJsonObject {
                    put("chat_id", input.chatId)
                    put("invite_code", code)
                },
            )
        if (!result.successful) return errorReply(result, texts)
        val activated = json.decodeFromJsonElement(CoreUser.serializer(), result.body.getValue("user"))
        val invite = result.body.getValue("invite").jsonObject
        return onboarding.prompt(activated, input.firstName).copy(
            adminNotice =
                texts.get(
                    "onboarding.activated",
                    mapOf("id" to invite.value("id"), "user_id" to activated.id.toString(), "label" to invite.value("label")),
                ),
        )
    }
}
