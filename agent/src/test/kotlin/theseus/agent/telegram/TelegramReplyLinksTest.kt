package theseus.agent.telegram

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.requests.send.SendTextMessage
import dev.inmo.tgbotapi.types.MessageId
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import theseus.agent.core.CoreUser
import theseus.agent.dialog.ReminderReplyTarget
import theseus.agent.pipeline.FakeDialogs
import theseus.agent.system.BotReply
import theseus.agent.system.EventSnapshot
import theseus.agent.system.InMemoryAgentStore
import theseus.agent.system.Incoming
import theseus.agent.system.Outgoing
import theseus.agent.system.ReminderReplyLinks
import theseus.agent.system.RestReply
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class TelegramReplyLinksTest {
    @Test fun `telegram returned message id is persisted for creation receipt`() =
        runTest {
            val now = Instant.parse("2026-11-02T07:15:00Z")
            val clock = Clock.fixed(now, ZoneOffset.UTC)
            val user = CoreUser(7, 4242, "active", "Анна", "female", "Europe/Moscow")
            val store = InMemoryAgentStore()
            val dialogs = FakeDialogs()
            store.beginEvent(EventSnapshot(Incoming("created", user.chatId), user, null, dialogue = true))
            val op = Outgoing(UUID.randomUUID(), "created:tool:0", "MCP", "create_reminder", JsonObject(emptyMap()), user.id)
            store.beginOperation(op)
            store.finishOperation(
                op.id,
                RestReply(
                    200,
                    Json
                        .parseToJsonElement(
                            """{"status":"created","reminder":{"id":17,"kind":"recurring","timezone":"Europe/Moscow"}}""",
                        ).jsonObject,
                ),
            )
            val bot = mockk<TelegramBot>()
            coEvery { bot.execute(any<SendTextMessage>()) } returns mockk { every { messageId } returns MessageId(777) }
            val runner =
                TelegramBotRunner(
                    bot,
                    mockk(),
                    mockk(),
                    store,
                    mockk(),
                    1,
                    replyLinks = ReminderReplyLinks(store, dialogs, clock),
                )
            runner.send(user.chatId, BotReply("Поставил: «Погладить кота» — ежедневно в 10:00."), "created")
            assertEquals(ReminderReplyTarget(17, "recurring", "Europe/Moscow"), dialogs.replyTarget(user.id, 777, now))
        }
}
