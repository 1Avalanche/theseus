package theseus.agent.pipeline

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import theseus.agent.core.CoreClient
import theseus.agent.core.CoreUnavailableException
import theseus.agent.core.CoreUser
import theseus.agent.core.ToolResult
import theseus.agent.core.ToolSpec
import theseus.agent.system.AgentStore
import theseus.agent.system.BotReply
import theseus.agent.system.InMemoryAgentStore
import theseus.agent.system.Incoming
import theseus.agent.system.McpOperations
import theseus.agent.system.Outgoing
import theseus.agent.system.RestReply
import theseus.agent.system.UncertainMcpException
import theseus.agent.texts.Texts
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class ReminderActionsTest {
    @Test
    fun `delete all handles empty list and refuses unsupported records before proposing`() =
        runTest {
            core.emptyList = true
            val empty = requireNotNull(actions.execute(owner, "delete_reminder", args("""{"scope":"all"}"""), "message:empty").reply)
            assertEquals(texts.get("reminders.empty"), empty.text)
            assertTrue(empty.buttons.isEmpty())
            core.unsupportedCount = 1
            val blocked = requireNotNull(actions.execute(owner, "delete_reminder", args("""{"scope":"all"}"""), "message:unknown").reply)
            assertEquals(texts.get("reply.error.unsupported_state"), blocked.text)
            assertTrue(blocked.buttons.isEmpty())
            assertTrue(store.pendingOperations().isEmpty())
            assertTrue(core.mutations.isEmpty())
        }

    @Test
    fun `delete all previews full snapshot and confirms once using saved operation id`() =
        runTest {
            val card = requireNotNull(actions.execute(owner, "delete_reminder", args("""{"scope":"all"}"""), "message:all").reply)
            assertTrue(card.text.contains("(2)"), card.text)
            assertEquals(
                "Удалить все",
                card.buttons
                    .single()
                    .first()
                    .text,
            )
            assertTrue(core.mutations.isEmpty())
            assertEquals(listOf("list_reminders"), core.reads.map { it.second })
            val result = requireNotNull(actions.callback(callback(card, true), owner))
            assertEquals("Удалил все текущие напоминания: 2.", result.text)
            assertEquals(args("""{"scope":"all"}"""), core.mutations.single().arguments)
            assertEquals(
                "all-fingerprint",
                core.mutations
                    .single()
                    .confirmation!!
                    .getValue("fingerprint")
                    .jsonPrimitive.content,
            )
            assertEquals(result, actions.callback(callback(card, true, "callback:replay"), owner))
            assertEquals(1, core.mutations.size)
        }

    @Test
    fun `delete all cancellation never sends mutating request`() =
        runTest {
            val card = requireNotNull(actions.execute(owner, "delete_reminder", args("""{"scope":"all"}"""), "message:all").reply)
            assertEquals(texts.get("confirmation.cancelled"), actions.callback(callback(card, false), owner)!!.text)
            assertTrue(core.mutations.isEmpty())
        }

    @Test
    fun `time only series update also prepares reset card when exclusions exist`() =
        runTest {
            core.requiresSeriesReset = true
            val result =
                actions.execute(
                    owner,
                    "update_reminder",
                    args("""{"reminder_id":7,"scope":"series","when":{"time":"19:00"}}"""),
                    "message:time",
                )
            assertNotNull(result.reply)
            assertTrue(requireNotNull(result.reply).text.contains("Изменить расписание"))
            assertTrue(requireNotNull(result.reply).text.contains("19:00"))
            assertEquals(0, core.domainWrites)
        }

    private val owner = CoreUser(7, 4242, "active", "Аня", "female", "Europe/Moscow")
    private val clock = TestClock(Instant.parse("2026-11-02T07:15:00Z"))
    private val texts = Texts.load("texts/agent.properties")
    private val store = InMemoryAgentStore()
    private val core = FakeCore(owner)
    private val renderer = ReplyRenderer(texts)
    private val operations = McpOperations(store, core)
    private val actions = ReminderActions(core, store, operations, renderer, texts, clock)

    private fun args(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject

    private suspend fun deleteCard(eventKey: String = "message:4242:1:tool:0"): BotReply =
        requireNotNull(actions.execute(owner, "delete_reminder", args("""{"reminder_id":7}"""), eventKey).reply)

    private fun callback(
        card: BotReply,
        accept: Boolean,
        id: String = "callback:1",
    ): Incoming =
        Incoming(
            id,
            owner.chatId,
            callback = card.buttons.single()[if (accept) 0 else 1].data,
        )

    @Test
    fun `delete produces persistent card before any mutating mcp request`() =
        runTest {
            val execution = actions.execute(owner, "delete_reminder", args("""{"reminder_id":7}"""), "message:4242:1:tool:0")

            assertNull(execution.result)
            val card = requireNotNull(execution.reply)
            assertEquals(2, card.buttons.single().size)
            assertEquals(listOf("get_reminder"), core.reads.map { it.second })
            assertTrue(core.mutations.isEmpty())
            val stored = store.pendingOperations().single()
            assertEquals("CONFIRM", stored.method)
            assertEquals(owner.id, stored.userId)
            assertEquals(
                "fingerprint-1",
                stored.body
                    .getValue("confirmation")
                    .jsonObject
                    .getValue("fingerprint")
                    .jsonPrimitive.content,
            )
            assertEquals(
                "2026-11-02T07:15:00Z",
                stored.body
                    .getValue("confirmation")
                    .jsonObject
                    .getValue("created_at")
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `foreign user text and emoji cannot confirm another users card`() =
        runTest {
            val card = deleteCard()

            assertNull(actions.callback(Incoming("text:1", owner.chatId, text = "да"), owner))
            assertNull(actions.callback(Incoming("text:2", owner.chatId, text = "👍"), owner))
            assertEquals(
                texts.get("confirmation.stale"),
                actions.callback(callback(card, true), owner.copy(id = 99, chatId = 999))!!.text,
            )
            assertEquals(
                texts.get("confirmation.stale"),
                actions.callback(callback(card, true), owner.copy(status = "revoked"))!!.text,
            )
            assertTrue(core.mutations.isEmpty())
            assertEquals(0, core.domainWrites)
        }

    @Test
    fun `duplicate strips model force and freezes proposed time with new confirmation operation`() =
        runTest {
            core.possibleDuplicate = true
            val execution =
                actions.execute(
                    owner,
                    "create_reminder",
                    args("""{"text":"Позвонить маме","when":{"after":{"minutes":5}},"force":true}"""),
                    "message:4242:2:tool:0",
                )
            val card = requireNotNull(execution.reply)
            val initial = core.mutations.single()
            assertFalse(initial.arguments.containsKey("force"))
            assertNull(initial.confirmation)
            clock.now = clock.now.plusSeconds(120)

            val reply = actions.callback(callback(card, true), owner)

            assertNotNull(reply)
            val confirmed = core.mutations.last()
            assertFalse(initial.id == confirmed.id)
            assertEquals(args("""{"date":"2026-11-02","time":"10:20"}"""), confirmed.arguments.getValue("when"))
            assertTrue(
                confirmed.arguments
                    .getValue("force")
                    .jsonPrimitive.boolean,
            )
            assertEquals(
                args("""{"fingerprint":"duplicate-fingerprint","created_at":"2026-11-02T07:15:00Z"}"""),
                confirmed.confirmation,
            )
            val storedCard =
                store.operations.values
                    .single { it.first.method == "CONFIRM" }
                    .first
            assertEquals(
                storedCard.body
                    .getValue("operation_id")
                    .jsonPrimitive.content,
                confirmed.id.toString(),
            )
            assertEquals(1, core.domainWrites)
        }

    @Test
    fun `cancel after lost committed confirmation resolves original operation instead of promising cancellation`() =
        runTest {
            val card = deleteCard()
            core.loseNextReply = true

            assertThrows<UncertainMcpException> { actions.callback(callback(card, true), owner) }
            val committed = core.mutations.single()
            assertEquals(1, core.domainWrites)
            assertEquals(1, store.pendingOperations().count { it.method == "MCP" })

            val resolved = requireNotNull(actions.callback(callback(card, false, "callback:2"), owner))

            assertFalse(resolved.text == texts.get("confirmation.cancelled"))
            assertTrue(resolved.text.contains("Удалил"))
            assertEquals(listOf(committed.id, committed.id), core.mutations.map { it.id })
            assertEquals(1, core.domainWrites)
            assertTrue(store.pendingOperations().none { it.method == "MCP" })
        }

    @Test
    fun `dialogue recovery retains multiple cards and already successful mcp outcome`() =
        runTest {
            val event = "message:4242:3"
            val first = deleteCard("$event:tool:0")
            val second = deleteCard("$event:tool:1")
            actions.execute(
                owner,
                "create_reminder",
                args("""{"text":"Позвонить маме","when":{"after":{"minutes":5}}}"""),
                "$event:tool:2",
            )
            val restarted = ReminderActions(core, store, McpOperations(store, core), renderer, texts, clock)

            val recovered = requireNotNull(restarted.recoverDialogue(event, owner))

            assertEquals((first.buttons + second.buttons).flatten().map { it.data }, recovered.buttons.flatten().map { it.data })
            assertTrue(recovered.text.contains("Поставил"))
            assertEquals(2, recovered.text.split("Удалить").size - 1)
            assertEquals(1, core.mutations.size)
            assertEquals(1, core.domainWrites)
        }

    @Test
    fun `pending mcp recovery reuses saved uuid after lost response without llm`() =
        runTest {
            val event = "message:4242:4"
            core.loseNextReply = true

            assertThrows<UncertainMcpException> {
                actions.execute(
                    owner,
                    "create_reminder",
                    args("""{"text":"Позвонить маме","when":{"after":{"minutes":5}}}"""),
                    "$event:tool:0",
                )
            }
            val saved = store.pendingOperations().single { it.method == "MCP" }
            val restarted = ReminderActions(core, store, McpOperations(store, core), renderer, texts, clock)

            val reply = requireNotNull(restarted.recoverDialogue(event, owner))

            assertTrue(reply.text.contains("Поставил"))
            assertEquals(listOf(saved.id, saved.id), core.mutations.map { it.id })
            assertEquals(1, core.domainWrites)
            assertTrue(store.pendingOperations().none { it.method == "MCP" })
        }

    @Test
    fun `confirmed card replay after ttl returns original response without new mutation`() =
        runTest {
            val card = deleteCard()
            val first = requireNotNull(actions.callback(callback(card, true), owner))
            clock.now = clock.now.plus(Duration.ofHours(2))

            val replay = requireNotNull(actions.callback(callback(card, true, "callback:later"), owner))

            assertEquals(first.text, replay.text)
            assertEquals(1, core.mutations.size)
            assertEquals(1, core.domainWrites)
        }

    @Test
    fun `unstarted cancellation records final outcome without mutating core`() =
        runTest {
            val card = deleteCard()

            val cancelled = requireNotNull(actions.callback(callback(card, false), owner))
            val repeated = requireNotNull(actions.callback(callback(card, true, "callback:later"), owner))

            assertEquals(texts.get("confirmation.cancelled"), cancelled.text)
            assertEquals(cancelled.text, repeated.text)
            assertTrue(core.mutations.isEmpty())
        }

    @Test
    fun `recovery rebuilds reset card after persisted confirmation required and crash before propose`() =
        runTest {
            val event = "message:4242:reset"
            val request = args("""{"reminder_id":7,"scope":"series","recurrence":{"time":"12:00"}}""")
            core.requiresSeriesReset = true
            val interruptedStore =
                object : AgentStore by store {
                    override suspend fun beginOperation(operation: Outgoing): Pair<Outgoing, RestReply?> {
                        check(operation.method != "CONFIRM") { "Сбой перед сохранением карточки" }
                        return store.beginOperation(operation)
                    }
                }
            val interrupted =
                ReminderActions(
                    core,
                    interruptedStore,
                    McpOperations(interruptedStore, core),
                    renderer,
                    texts,
                    clock,
                )

            assertThrows<IllegalStateException> {
                interrupted.execute(owner, "update_reminder", request, "$event:tool:0")
            }
            val attempted = core.mutations.single()
            val persisted = requireNotNull(store.findOperation(attempted.id))
            assertEquals(400, persisted.second!!.status)
            assertTrue(store.operations.values.none { it.first.method == "CONFIRM" })
            assertEquals(0, core.domainWrites)

            val restarted = ReminderActions(core, store, McpOperations(store, core), renderer, texts, clock)
            val recovered = requireNotNull(restarted.recoverDialogue(event, owner))

            assertTrue(recovered.text.contains("Изменить расписание"), recovered.text)
            assertEquals(2, recovered.buttons.single().size)
            assertEquals(1, core.mutations.size)
            assertEquals(0, core.domainWrites)
            val restoredCard =
                store.operations.values
                    .single { it.first.method == "CONFIRM" }
                    .first
            assertEquals(request, restoredCard.body.getValue("arguments"))
            assertFalse(
                attempted.id.toString() ==
                    restoredCard.body
                        .getValue("operation_id")
                        .jsonPrimitive.content,
            )
        }

    @Test
    fun `recurring snooze response shows snoozed local moment in reminder timezone`() {
        val result =
            """
            {"status":"snoozed","reminder":{"id":7,"text":"Позвонить маме","kind":"recurring","status":"active",
            "timezone":"Europe/Moscow","schedule":{"recurrence":{"freq":"daily","interval":1,"time":"11:00"}},
            "next_at_local":"2026-11-02T11:00","snoozed_until_local":"2026-11-02T19:00"}}
            """.trimIndent()

        val reply = requireNotNull(renderer.render("snooze_reminder", result, ZoneId.of("Asia/Yekaterinburg"), clock.instant()))

        assertTrue(reply.contains("19:00"), reply)
        assertTrue(reply.contains("Europe/Moscow"), reply)
        assertFalse(reply.contains("11:00"), reply)
    }

    private data class Mutation(
        val userId: Long,
        val name: String,
        val arguments: JsonObject,
        val id: UUID,
        val confirmation: JsonObject?,
    )

    private class FakeCore(
        private val user: CoreUser,
    ) : CoreClient {
        val reads = mutableListOf<Pair<Long, String>>()
        val mutations = mutableListOf<Mutation>()
        private val committed = mutableMapOf<UUID, ToolResult>()
        var possibleDuplicate = false
        var emptyList = false
        var unsupportedCount = 0
        var requiresSeriesReset = false
        var loseNextReply = false
        var domainWrites = 0
            private set

        override suspend fun findUserByChat(chatId: Long): CoreUser? = user.takeIf { it.chatId == chatId }

        override suspend fun listTools(): List<ToolSpec> = emptyList()

        override suspend fun callTool(
            userId: Long,
            name: String,
            arguments: JsonObject,
        ): ToolResult {
            reads += userId to name
            if (name == "list_reminders") {
                val rows = if (emptyList) "" else "$reminder,$recurring"
                return ToolResult(
                    """{"reminders":[$rows],"fingerprint":"all-fingerprint","unsupported_count":$unsupportedCount}""",
                    false,
                )
            }
            check(name == "get_reminder")
            val shownReminder = if (requiresSeriesReset) recurring else reminder
            val effects = if (requiresSeriesReset) 1 else 0
            return ToolResult(
                """{"reminder":$shownReminder,"fingerprint":"fingerprint-1","excluded_count":$effects,"pending_children_count":$effects,
                    "next_occurrence_local":"2026-11-02T11:00"}""",
                false,
            )
        }

        override suspend fun callTool(
            userId: Long,
            name: String,
            arguments: JsonObject,
            operationId: UUID,
            confirmation: JsonObject?,
        ): ToolResult {
            mutations += Mutation(userId, name, arguments, operationId, confirmation)
            val result =
                committed.getOrPut(operationId) {
                    if (name == "update_reminder" && requiresSeriesReset && confirmation == null) {
                        ToolResult("""{"error":{"code":"confirmation_required","message":"Нужно подтверждение"}}""", true)
                    } else if (name == "create_reminder" && possibleDuplicate && arguments["force"] != JsonPrimitive(true)) {
                        ToolResult(
                            """{"status":"possible_duplicate","candidates":[$reminder],"proposed":$proposed,
                            "fingerprint":"duplicate-fingerprint"}""",
                            false,
                        )
                    } else {
                        domainWrites++
                        if (name == "delete_reminder" && arguments["scope"] == JsonPrimitive("all")) {
                            ToolResult("""{"status":"deleted_all","deleted_count":2}""", false)
                        } else {
                            val status = if (name == "delete_reminder") "deleted" else "created"
                            ToolResult("""{"status":"$status","reminder":$reminder,"warnings":[]}""", false)
                        }
                    }
                }
            if (loseNextReply) {
                loseNextReply = false
                throw CoreUnavailableException("Ответ потерян после коммита")
            }
            return result
        }

        companion object {
            private val recurring =
                """
                {"id":7,"text":"Позвонить маме","kind":"recurring","status":"active","timezone":"Europe/Moscow",
                "schedule":{"recurrence":{"freq":"daily","interval":1,"time":"11:00"}},"next_at_local":"2026-11-02T11:00"}
                """.trimIndent()
            private val reminder =
                """
                {"id":7,"text":"Позвонить маме","kind":"one_time","status":"active","timezone":"Europe/Moscow",
                "schedule":{"at_local":"2026-11-02T11:00"},"next_at_local":"2026-11-02T11:00"}
                """.trimIndent()
            private val proposed =
                """
                {"id":0,"text":"Позвонить маме","kind":"one_time","status":"active","timezone":"Europe/Moscow",
                "schedule":{"at_local":"2026-11-02T10:20"},"next_at_local":"2026-11-02T10:20"}
                """.trimIndent()
        }
    }

    private class TestClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now
    }
}
