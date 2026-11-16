package theseus.agent.pipeline

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.agent.core.CoreClient
import theseus.agent.core.CoreUser
import theseus.agent.core.ToolResult
import theseus.agent.core.ToolSpec
import theseus.agent.llm.ChatMessage
import theseus.agent.llm.LlmClient
import theseus.agent.llm.LlmException
import theseus.agent.llm.LlmRequest
import theseus.agent.llm.LlmResponse
import theseus.agent.llm.Role
import theseus.agent.llm.ToolCall
import theseus.agent.prompt.PromptBuilder
import theseus.agent.texts.Texts
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MessageHandlerTest {
    @Test
    fun `ambiguous batch pause never changes either series and offers ids`() =
        runTest {
            val core = FakeCore(mapOf(owner.chatId to owner))
            core.results +=
                ToolResult(
                    """{"candidates":[{"id":17,"text":"Пить воду","next_at_local":"2026-11-03T18:45"},
                        {"id":18,"text":"Пить воду","next_at_local":"2026-11-03T18:45"}]}""",
                    false,
                )
            val llm =
                FakeLlm(
                    ChatMessage(Role.ASSISTANT, null, toolCalls = listOf(ToolCall("find", "find_reminders", """{"query":"вода"}"""))),
                    ChatMessage(
                        Role.ASSISTANT,
                        null,
                        toolCalls =
                            listOf(
                                ToolCall("pause1", "pause_reminder", """{"reminder_id":17}"""),
                                ToolCall("pause2", "pause_reminder", """{"reminder_id":18}"""),
                            ),
                    ),
                )
            val result = requireNotNull(handler(core, llm).handle(owner.chatId, "Приостанови серию про воду"))
            assertEquals(listOf("find_reminders"), core.calls.map { it.second })
            assertTrue(result.contains("ID 17"), result)
            assertTrue(result.contains("ID 18"), result)
            assertTrue(result.contains("ничего не менял"), result)
        }

    @Test
    fun `invalid date from core gets explicit reply without generic clarification`() =
        runTest {
            val core = FakeCore(mapOf(owner.chatId to owner), ToolResult("""{"error":{"code":"invalid_date","message":"ошибка"}}""", true))
            val llm =
                FakeLlm(
                    ChatMessage(
                        Role.ASSISTANT,
                        null,
                        toolCalls = listOf(ToolCall("list", "list_reminders", """{"from":"2026-11-31","to":"2026-11-31"}""")),
                    ),
                )
            assertEquals(texts.get("reply.error.invalid_date"), handler(core, llm).handle(owner.chatId, "Покажи напоминания на 31 ноября"))
            assertEquals(1, llm.requests.size)
        }

    @Test
    fun `find candidates reach model before updating selected reminder`() =
        runTest {
            val candidates = """{"candidates":[{"id":17,"text":"Позвонить маме","timezone":"Europe/Moscow"}]}"""
            val updated = """{"status":"updated","reminder":{"id":17,"text":"Позвонить маме","kind":"one_time",
            "status":"active","timezone":"Europe/Moscow","schedule":{"at_local":"2026-11-03T18:00"},
            "next_at_local":"2026-11-03T18:00"},"warnings":[]}"""
            val core = FakeCore(mapOf(owner.chatId to owner))
            core.results += ToolResult(candidates, false)
            core.results += ToolResult(updated, false)
            val llm =
                FakeLlm(
                    ChatMessage(Role.ASSISTANT, null, toolCalls = listOf(ToolCall("find", "find_reminders", """{"query":"мама"}"""))),
                    ChatMessage(
                        Role.ASSISTANT,
                        null,
                        toolCalls =
                            listOf(
                                ToolCall(
                                    "update",
                                    "update_reminder",
                                    """{"reminder_id":17,"when":{"date":"2026-11-03","time":"18:00"}}""",
                                ),
                            ),
                    ),
                )
            val reply = handler(core, llm).handle(owner.chatId, "Перенеси звонок маме на завтра в шесть вечера")
            assertEquals(listOf("find_reminders", "update_reminder"), core.calls.map { it.second })
            assertEquals(2, llm.requests.size)
            assertTrue(llm.requests[1].messages.any { it.role == Role.TOOL && it.content == candidates })
            assertTrue(requireNotNull(reply).startsWith("Изменил:"))
        }

    private val texts = Texts.load("texts/agent.properties")
    private val owner = CoreUser(id = 7, chatId = 4242, status = "active", name = "Аня", gender = "female", timezone = "Europe/Moscow")
    private val clock = Clock.fixed(Instant.parse("2026-11-02T07:15:00Z"), ZoneOffset.UTC)

    private val createdJson =
        """{"status":"created","reminder":{"id":1,"text":"Выключить духовку","kind":"one_time","status":"active",""" +
            """"schedule":{"at_local":"2026-11-02T10:20"},"next_at_local":"2026-11-02T10:20"},"warnings":[]}"""

    internal class FakeCore(
        val users: Map<Long, CoreUser>,
        var result: ToolResult = ToolResult("{}", isError = false),
    ) : CoreClient {
        val calls = mutableListOf<Triple<Long, String, JsonObject>>()
        val results = ArrayDeque<ToolResult>()
        var delayMs = 0L
        val confirmations = mutableListOf<JsonObject>()
        var specifications = listOf(ToolSpec("create_reminder", "Создать напоминание", JsonObject(emptyMap())))

        override suspend fun findUserByChat(chatId: Long) = users[chatId]

        override suspend fun listTools() = specifications

        override suspend fun callTool(
            userId: Long,
            name: String,
            arguments: JsonObject,
            operationId: java.util.UUID,
            confirmation: JsonObject?,
        ): ToolResult {
            confirmation?.let(confirmations::add)
            return callTool(userId, name, arguments)
        }

        override suspend fun callTool(
            userId: Long,
            name: String,
            arguments: JsonObject,
        ): ToolResult {
            calls += Triple(userId, name, arguments)
            kotlinx.coroutines.delay(delayMs)
            return results.removeFirstOrNull() ?: result
        }
    }

    internal class FakeLlm(
        vararg answers: ChatMessage,
    ) : LlmClient {
        private val queue = ArrayDeque(answers.toList())
        val requests = mutableListOf<LlmRequest>()
        var failure: Exception? = null

        override suspend fun complete(request: LlmRequest): LlmResponse {
            failure?.let { throw it }
            requests += request
            return LlmResponse(queue.removeFirst(), "test/model", null)
        }
    }

    private fun toolCall(arguments: String) =
        ChatMessage(Role.ASSISTANT, null, toolCalls = listOf(ToolCall("call_1", "create_reminder", arguments)))

    private fun handler(
        core: FakeCore,
        llm: FakeLlm,
        dialogs: theseus.agent.dialog.DialogStore? = null,
        systemCore: theseus.agent.system.SystemCore? = null,
    ) = MessageHandler(
        core,
        llm,
        PromptBuilder(listOf("Персона"), "Сейчас {now_local}", texts),
        ReplyRenderer(texts),
        texts,
        clock,
        dialogs = dialogs,
        systemCore = systemCore,
    )

    @Test fun `short clarification keeps original request and rendered answer in next turn`() =
        runTest {
            val dialogs = FakeDialogs()
            val core = FakeCore(mapOf(owner.chatId to owner), ToolResult(createdJson, false))
            val llm =
                FakeLlm(
                    ChatMessage(Role.ASSISTANT, null, listOf(ToolCall("ask", "assistant_reply", """{"topic":"missing_time"}"""))),
                    toolCall("""{"text":"Выключить духовку","when":{"after":{"minutes":5}}}"""),
                )
            val handler = handler(core, llm, dialogs)
            handler.handle(owner.chatId, "Напомни выключить духовку", "first")
            handler.handle(owner.chatId, "Через 5 минут", "second")
            val messages = llm.requests.last().messages
            assertTrue(messages.any { it.role == Role.USER && it.content == "Напомни выключить духовку" })
            assertTrue(messages.any { it.role == Role.ASSISTANT && it.content == texts.get("reply.missing_time") })
            assertEquals(1, core.calls.size)
        }

    @Test fun `reply metadata comes from authenticated system lookup and reaches model`() =
        runTest {
            val llm = FakeLlm(ChatMessage(Role.ASSISTANT, null, listOf(ToolCall("ask", "assistant_reply", """{"topic":"missing_time"}"""))))
            val system =
                theseus.agent.system.SystemCore { method, path, body, key ->
                    assertEquals("GET", method)
                    assertEquals("/deliveries/by-message?user_id=7&message_id=123", path)
                    assertNull(body)
                    assertNull(key)
                    theseus.agent.system.RestReply(
                        200,
                        kotlinx.serialization.json.Json
                            .parseToJsonElement(
                                """{"reminder_id":42,"occurrence_local":"2026-11-16T09:00"}""",
                            ).jsonObject,
                    )
                }
            handler(
                FakeCore(mapOf(owner.chatId to owner)),
                llm,
                systemCore = system,
            ).handleReply(owner.chatId, "Перенеси на вечер", "reply", 123)
            assertTrue(
                llm.requests
                    .single()
                    .messages
                    .any { it.content?.contains("\"reminder_id\":42") == true },
            )
        }

    @Test fun `oversized tool batch changes nothing and returns budget reply`() =
        runTest {
            val core = FakeCore(mapOf(owner.chatId to owner))
            val llm = FakeLlm(ChatMessage(Role.ASSISTANT, null, (1..7).map { ToolCall("$it", "create_reminder", "{\"text\":\"$it\"}") }))
            assertEquals(texts.get("reply.budget"), handler(core, llm).handle(owner.chatId, "Семь напоминаний"))
            assertTrue(core.calls.isEmpty())
        }

    @Test fun `slow generation ends after ninety seconds without mutation claim`() =
        runTest {
            val core = FakeCore(mapOf(owner.chatId to owner))
            val slow =
                object : LlmClient {
                    override suspend fun complete(request: LlmRequest): LlmResponse {
                        kotlinx.coroutines.delay(90_001)
                        error("Не должен завершиться")
                    }
                }
            val handler = MessageHandler(core, slow, PromptBuilder.fromResources(texts), ReplyRenderer(texts), texts, clock)
            assertEquals(texts.get("reply.budget"), handler.handle(owner.chatId, "Привет"))
            assertTrue(core.calls.isEmpty())
        }

    @Test fun `timed out mutation keeps outcome uncertain instead of declaring failure`() =
        runTest {
            val core = FakeCore(mapOf(owner.chatId to owner)).apply { delayMs = 90_001 }
            val llm = FakeLlm(toolCall("""{"text":"Вода","when":{"after":{"minutes":1}}}"""))
            org.junit.jupiter.api.assertThrows<theseus.agent.system.UncertainMcpException> {
                handler(core, llm).handle(owner.chatId, "Напомни воду")
            }
            assertEquals(1, core.calls.size)
            assertEquals(1, llm.requests.size)
        }

    @Test fun `resolved list number reaches model before target mutation`() =
        runTest {
            val dialogs = FakeDialogs().apply { session = session.copy(references = listOf(theseus.agent.dialog.ListReference(42))) }
            val core = FakeCore(mapOf(owner.chatId to owner), ToolResult(createdJson.replace("created", "updated"), false))
            val llm =
                FakeLlm(
                    ChatMessage(Role.ASSISTANT, null, listOf(ToolCall("resolve", "resolve_reference", "{\"number\":1}"))),
                    ChatMessage(
                        Role.ASSISTANT,
                        null,
                        listOf(ToolCall("update", "update_reminder", """{"reminder_id":42,"when":{"time":"18:00"}}""")),
                    ),
                )
            val response = handler(core, llm, dialogs).handle(owner.chatId, "Перенеси первый на 18:00")
            assertTrue(response!!.startsWith("Изменил:"))
            assertEquals("update_reminder", core.calls.single().second)
            assertTrue(
                llm.requests
                    .last()
                    .messages
                    .any { it.role == Role.TOOL && it.content == "{\"reminder_id\":42}" },
            )
        }

    @Test fun `new tool result evicts old history without breaking current call result pair`() =
        runTest {
            val oldText = "А".repeat(4000)
            val dialogs =
                FakeDialogs().apply {
                    session =
                        session.copy(
                            entries =
                                listOf(
                                    theseus.agent.dialog.DialogEntry(1, Role.USER, oldText),
                                    theseus.agent.dialog.DialogEntry(2, Role.ASSISTANT, "Старый ответ"),
                                ),
                        )
                }
            val candidates =
                kotlinx.serialization.json
                    .buildJsonObject {
                        put(
                            "candidates",
                            kotlinx.serialization.json.JsonArray(
                                (1..5).map { id ->
                                    kotlinx.serialization.json.buildJsonObject {
                                        put("id", kotlinx.serialization.json.JsonPrimitive(id))
                                        put("text", kotlinx.serialization.json.JsonPrimitive("Б".repeat(800) + id))
                                    }
                                },
                            ),
                        )
                    }.toString()
            val core =
                FakeCore(mapOf(owner.chatId to owner)).apply {
                    results += ToolResult(candidates, false)
                    results += ToolResult(createdJson.replace("created", "updated"), false)
                }
            val llm =
                FakeLlm(
                    ChatMessage(Role.ASSISTANT, null, listOf(ToolCall("find", "find_reminders", "{\"query\":\"вода\"}"))),
                    ChatMessage(
                        Role.ASSISTANT,
                        null,
                        listOf(ToolCall("update", "update_reminder", """{"reminder_id":1,"when":{"time":"18:00"}}""")),
                    ),
                )
            assertTrue(handler(core, llm, dialogs).handle(owner.chatId, "Перенеси воду на 18:00")!!.startsWith("Изменил:"))
            val next = llm.requests.last().messages
            assertTrue(next.none { it.content == oldText })
            assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL), next.map { it.role })
            assertEquals("find", next.last().toolCallId)
        }

    @Test fun `summary generation failure still permits main request but usage failure blocks mutation`() =
        runTest {
            for (usageFailure in listOf(false, true)) {
                val dialogs =
                    FakeDialogs().apply {
                        session =
                            session.copy(
                                entries =
                                    (1..22).map {
                                        theseus.agent.dialog.DialogEntry(
                                            it.toLong(),
                                            if (it % 2 ==
                                                1
                                            ) {
                                                Role.USER
                                            } else {
                                                Role.ASSISTANT
                                            },
                                            "Предыдущая реплика $it",
                                        )
                                    },
                            )
                    }
                val core = FakeCore(mapOf(owner.chatId to owner), ToolResult(createdJson, false))
                val requests = mutableListOf<String>()
                val llm =
                    object : LlmClient {
                        override suspend fun complete(request: LlmRequest): LlmResponse {
                            requests += request.role
                            if (request.role == "summary") {
                                if (!usageFailure) throw LlmException("Summary provider unavailable")
                                return LlmResponse(ChatMessage(Role.ASSISTANT, "Сводка"), "test/summary", null)
                            }
                            return LlmResponse(toolCall("""{"text":"Духовка","when":{"after":{"minutes":5}}}"""), "test/main", null)
                        }
                    }
                val usage =
                    object : theseus.agent.system.UsageRecorder {
                        override suspend fun before(userId: Long) = Unit

                        override suspend fun record(
                            userId: Long,
                            eventKey: String,
                            response: LlmResponse,
                        ) {
                            if (usageFailure) throw LlmException("Usage unavailable")
                        }
                    }
                val handler =
                    MessageHandler(
                        core,
                        llm,
                        PromptBuilder.fromResources(texts),
                        ReplyRenderer(texts),
                        texts,
                        clock,
                        dialogs = dialogs,
                        usage = usage,
                    )
                val result = handler.handle(owner.chatId, "Напомни через 5 минут выключить духовку")
                if (usageFailure) {
                    assertEquals(texts.get("reply.failure"), result)
                    assertTrue(core.calls.isEmpty())
                    assertEquals(listOf("summary"), requests)
                } else {
                    assertTrue(result!!.startsWith("Поставил:"))
                    assertEquals(1, core.calls.size)
                    assertEquals(listOf("summary", "main"), requests)
                }
            }
        }

    @Test
    fun `unknown chat is dropped before llm`() =
        runTest {
            val llm = FakeLlm()

            assertNull(handler(FakeCore(emptyMap()), llm).handle(1, "напомни через 5 минут"))
            assertTrue(llm.requests.isEmpty())
        }

    @Test
    fun `inactive user is dropped before llm`() =
        runTest {
            val llm = FakeLlm()
            val core = FakeCore(mapOf(4242L to owner.copy(status = "revoked")))

            assertNull(handler(core, llm).handle(4242, "привет"))
            assertTrue(llm.requests.isEmpty())
        }

    @Test
    fun `successful tool call is answered by template without second llm call`() =
        runTest {
            val core = FakeCore(mapOf(4242L to owner), ToolResult(createdJson, isError = false))
            val llm = FakeLlm(toolCall("""{"text":"Выключить духовку","when":{"after":{"minutes":5}}}"""))

            val reply = handler(core, llm).handle(4242, "напомни через 5 минут выключить духовку")

            assertEquals("Поставил: «Выключить духовку» — сегодня в 10:20.", reply)
            assertEquals(1, llm.requests.size)
            val (userId, name, args) = core.calls.single()
            assertEquals(7L, userId)
            assertEquals("create_reminder", name)
            assertEquals("Выключить духовку", args["text"]!!.jsonPrimitive.content)
        }

    @Test
    fun `model confirmation without tool call is not presented as success`() =
        runTest {
            val core = FakeCore(mapOf(4242L to owner))
            val llm = FakeLlm(ChatMessage(Role.ASSISTANT, "Напоминание на 14:15: проверить стенд"))

            val reply = handler(core, llm).handle(4242, "Напомни через 2 минуты проверить стенд")

            assertEquals(texts.get("reply.not_created"), reply)
            assertTrue(core.calls.isEmpty())
            assertEquals(1, llm.requests.size)
        }

    @Test
    fun `model confirmation after tool error is not presented as success`() =
        runTest {
            val core = FakeCore(mapOf(4242L to owner), ToolResult("{}", isError = true))
            val llm = FakeLlm(toolCall("{}"), ChatMessage(Role.ASSISTANT, "Готово, поставил напоминание."))

            assertEquals(texts.get("reply.unconfirmed"), handler(core, llm).handle(4242, "напомни"))
            assertEquals(1, core.calls.size)
        }

    @Test
    fun `start greets known user without llm or reminder creation`() =
        runTest {
            val core = FakeCore(mapOf(4242L to owner))
            val llm = FakeLlm()

            assertEquals(texts.get("reply.welcome_named", mapOf("name" to "Аня")), handler(core, llm).handle(4242, "/start"))
            assertTrue(llm.requests.isEmpty())
            assertTrue(core.calls.isEmpty())
        }

    @Test
    fun `who are you uses separate generation by persona card without core mutation`() =
        runTest {
            val core = FakeCore(mapOf(4242L to owner))
            val llm =
                FakeLlm(
                    ChatMessage(
                        Role.ASSISTANT,
                        null,
                        toolCalls = listOf(ToolCall("dialogue_1", "assistant_reply", """{"topic":"about"}""")),
                    ),
                    ChatMessage(Role.ASSISTANT, "Я синтет. Перевожу сложные системы на понятный язык."),
                )

            assertEquals("Я синтет. Перевожу сложные системы на понятный язык.", handler(core, llm).handle(4242, "кто ты"))
            assertTrue(core.calls.isEmpty())
            assertEquals(2, llm.requests.size)
            assertTrue(
                llm.requests
                    .last()
                    .tools
                    .isEmpty(),
            )
            assertTrue(
                llm.requests
                    .last()
                    .messages
                    .first()
                    .content!!
                    .contains("половину мозга"),
            )
            assertEquals(
                "кто ты",
                llm.requests
                    .last()
                    .messages
                    .last()
                    .content,
            )
        }

    @Test
    fun `start and help do not bypass user access checks`() =
        runTest {
            for (users in listOf(emptyMap(), mapOf(4242L to owner.copy(status = "revoked")))) {
                val core = FakeCore(users)
                val llm = FakeLlm()
                val handler = handler(core, llm)

                assertNull(handler.handle(4242, "/start"))
                assertNull(handler.handle(4242, "/help"))
                assertTrue(llm.requests.isEmpty())
                assertTrue(core.calls.isEmpty())
            }
        }

    @Test
    fun `invented dialogue capabilities and success claims are not displayed or forwarded to core`() =
        runTest {
            val core = FakeCore(mapOf(4242L to owner))
            val llm =
                FakeLlm(
                    ChatMessage(
                        Role.ASSISTANT,
                        null,
                        toolCalls =
                            listOf(
                                ToolCall("dialogue_1", "assistant_reply", """{"topic":"about","text":"Я создал календарь"}"""),
                            ),
                    ),
                    ChatMessage(Role.ASSISTANT, "Готово, всё создано."),
                )

            assertEquals(texts.get("reply.unconfirmed"), handler(core, llm).handle(4242, "кто ты"))
            assertTrue(core.calls.isEmpty())
        }

    @Test
    fun `system prompt and tools from core are sent to llm`() =
        runTest {
            val llm = FakeLlm(ChatMessage(Role.ASSISTANT, "Привет."))

            handler(FakeCore(mapOf(4242L to owner)), llm).handle(4242, "привет")

            val request = llm.requests.single()
            assertEquals(listOf(Role.SYSTEM, Role.USER), request.messages.map { it.role })
            assertTrue(
                request.messages
                    .first()
                    .content!!
                    .contains("Сейчас 2026-11-02 10:15"),
            )
            assertEquals(listOf("create_reminder", "assistant_reply"), request.tools.map { it.name })
        }

    @Test
    fun `tool error goes back to llm but text alone does not confirm creation`() =
        runTest {
            val error = """{"error":{"code":"time_in_past","message":"Время уже прошло"}}"""
            val core = FakeCore(mapOf(4242L to owner), ToolResult(error, isError = true))
            val llm =
                FakeLlm(toolCall("""{"text":"X","when":{"time":"09:00"}}"""), ChatMessage(Role.ASSISTANT, "Это время уже прошло. Завтра?"))

            val reply = handler(core, llm).handle(4242, "напомни в 9 про X")

            assertEquals(texts.get("reply.unconfirmed"), reply)
            val second = llm.requests[1].messages
            assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL), second.map { it.role })
            assertEquals(error, second.last().content)
            assertEquals("call_1", second.last().toolCallId)
        }

    @Test
    fun `partial batch preserves confirmed creation without asking llm to repeat it`() =
        runTest {
            val core = FakeCore(mapOf(4242L to owner))
            core.results.addAll(listOf(ToolResult(createdJson, isError = false), ToolResult("{}", isError = true)))
            val llm =
                FakeLlm(
                    ChatMessage(
                        Role.ASSISTANT,
                        null,
                        toolCalls =
                            listOf(
                                ToolCall("call_1", "create_reminder", "{}"),
                                ToolCall("call_2", "create_reminder", "{\"text\":\"Другое напоминание\"}"),
                            ),
                    ),
                )

            val reply = handler(core, llm).handle(4242, "создай два напоминания")

            assertEquals("Поставил: «Выключить духовку» — сегодня в 10:20.\n" + texts.get("reply.unconfirmed"), reply)
            assertEquals(2, core.calls.size)
            assertEquals(1, llm.requests.size)
        }

    @Test
    fun `model can correct tool arguments and success still comes from core`() =
        runTest {
            val core = FakeCore(mapOf(4242L to owner))
            core.results.addAll(listOf(ToolResult("{}", isError = true), ToolResult(createdJson, isError = false)))
            val llm = FakeLlm(toolCall("{}"), toolCall("""{"text":"Выключить духовку","when":{"after":{"minutes":5}}}"""))

            assertEquals("Поставил: «Выключить духовку» — сегодня в 10:20.", handler(core, llm).handle(4242, "напомни"))
            assertEquals(2, core.calls.size)
        }

    @Test
    fun `malformed tool arguments are not sent to core`() =
        runTest {
            val core = FakeCore(mapOf(4242L to owner))
            val llm = FakeLlm(toolCall("{не json"), ChatMessage(Role.ASSISTANT, "Повтори, пожалуйста."))

            handler(core, llm).handle(4242, "напомни")

            assertTrue(core.calls.isEmpty())
            assertTrue(
                llm.requests[1]
                    .messages
                    .last()
                    .content!!
                    .contains("invalid_arguments"),
            )
        }

    @Test
    fun `endless tool calls stop with failure reply`() =
        runTest {
            val core = FakeCore(mapOf(4242L to owner), ToolResult("""{"error":{"code":"internal"}}""", isError = true))
            val call = toolCall("""{"text":"X","when":{"time":"09:00"}}""")
            val llm = FakeLlm(call, call, call, call)

            assertEquals(texts.get("reply.budget"), handler(core, llm).handle(4242, "X"))
            assertEquals(2, llm.requests.size)
            assertEquals(1, core.calls.size)
        }

    @Test
    fun `llm failure gives failure reply`() =
        runTest {
            val llm = FakeLlm().apply { failure = LlmException("OpenRouter 502") }

            assertEquals(texts.get("reply.failure"), handler(FakeCore(mapOf(4242L to owner)), llm).handle(4242, "привет"))
        }
}
