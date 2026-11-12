package theseus.agent.telegram

import com.sun.net.httpserver.HttpServer
import dev.inmo.tgbotapi.bot.exceptions.BotException
import dev.inmo.tgbotapi.extensions.api.bot.getMe
import dev.inmo.tgbotapi.requests.GetUpdates
import dev.inmo.tgbotapi.utils.defaultKtorEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import theseus.agent.monitoring.AgentMonitor
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramBotFactoryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `real SDK HTTP engine accepts delayed empty polling responses`() {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            // Сокращённый реальный цикл: ожидание Telegram 1 с, ответ приходит через 1,5 с.
            Thread.sleep(1_500)
            calls.incrementAndGet()
            val response = """{"ok":true,"result":[]}""".toByteArray()
            exchange.responseHeaders.add(HttpHeaders.ContentType, "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val localTelegram =
            createClientPlugin("LocalTelegram") {
                onRequest { request, _ ->
                    request.url.protocol = URLProtocol.HTTP
                    request.url.host = "127.0.0.1"
                    request.url.port = server.address.port
                }
            }
        try {
            HttpClient(defaultKtorEngine) { install(localTelegram) }.use { http ->
                val bot = createTelegramBot("123456:fake", http)
                try {
                    runBlocking {
                        repeat(2) { assertTrue(bot.execute(GetUpdates(timeout = 1)).isEmpty()) }
                    }
                    assertEquals(2, calls.get())
                } finally {
                    bot.close()
                }
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `idle polling receives empty SDK response after server wait and advances pulse`() =
        runTest {
            var requestBudget: Long? = null
            var socketBudget: Long? = null
            var wireTimeout: String? = null
            val engine =
                MockEngine.create {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        val timeout = requireNotNull(request.getCapabilityOrNull(HttpTimeoutCapability))
                        requestBudget = timeout.requestTimeoutMillis
                        socketBudget = timeout.socketTimeoutMillis
                        wireTimeout =
                            Json
                                .parseToJsonElement((request.body as TextContent).text)
                                .jsonObject["timeout"]!!
                                .jsonPrimitive.content
                        // Сетевой слой применяет реальный бюджет SDK. Сервер ждёт 30 с плюс задержка сети 1 с.
                        withTimeoutOrNull(requireNotNull(requestBudget)) {
                            delay(31_000)
                            respond(
                                """{"ok":true,"result":[]}""",
                                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        } ?: throw HttpRequestTimeoutException(request)
                    }
                }
            HttpClient(engine).use { http ->
                val raw = createTelegramBot("123456:fake", http)
                val clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC)
                val monitor = AgentMonitor(clock, directory.resolve("health"), directory.resolve("monitor.json"))
                val bot = MonitoredTelegramBot(raw, monitor)
                repeat(3) {
                    assertTrue(bot.execute(GetUpdates(timeout = 30)).isEmpty())
                    monitor.publish(true)
                }
                assertEquals("30", wireTimeout)
                assertEquals(45_000L, requestBudget)
                assertEquals(45_000L, socketBudget)
                assertEquals(clock.instant(), monitor.lastSuccessAt)
                assertTrue(Files.exists(directory.resolve("health")))
                assertEquals(
                    "0",
                    monitor.polling
                        .snapshot()["consecutive_errors"]!!
                        .jsonPrimitive.content,
                )
                raw.close()
            }
        }

    @Test
    fun `polling exceeding network budget fails without creating healthy pulse`() =
        runTest {
            val engine =
                MockEngine.create {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        val timeout = requireNotNull(request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
                        withTimeoutOrNull(timeout) {
                            delay(46_000)
                            respond("""{"ok":true,"result":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                        } ?: throw HttpRequestTimeoutException(request)
                    }
                }
            HttpClient(engine).use { http ->
                val raw = createTelegramBot("123456:fake", http)
                val clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC)
                val monitor = AgentMonitor(clock, directory.resolve("health"), directory.resolve("monitor.json"))
                assertThrows<BotException> { MonitoredTelegramBot(raw, monitor).execute(GetUpdates(timeout = 30)) }
                monitor.publish(true)
                assertNull(monitor.lastSuccessAt)
                assertFalse(Files.exists(directory.resolve("health")))
                assertEquals(
                    "1",
                    monitor.polling
                        .snapshot()["consecutive_errors"]!!
                        .jsonPrimitive.content,
                )
                raw.close()
            }
        }

    @Test
    fun `getMe keeps configured request and socket deadlines`() =
        runTest {
            val engine =
                MockEngine { request ->
                    val timeout = requireNotNull(request.getCapabilityOrNull(HttpTimeoutCapability))
                    assertEquals(7_000L, timeout.requestTimeoutMillis)
                    assertEquals(6_000L, timeout.socketTimeoutMillis)
                    assertEquals(5_000L, timeout.connectTimeoutMillis)
                    respond(
                        """
                        {"ok":true,"result":{
                          "id":1,"is_bot":true,"first_name":"test","username":"test_bot",
                          "can_join_groups":true,"can_read_all_group_messages":false,"supports_inline_queries":false
                        }}
                        """.trimIndent(),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            HttpClient(engine) {
                install(HttpTimeout) {
                    requestTimeoutMillis = 7_000
                    socketTimeoutMillis = 6_000
                    connectTimeoutMillis = 5_000
                }
            }.use { http ->
                val bot = createTelegramBot("123456:fake", http)
                assertEquals("test_bot", bot.getMe().username?.withoutAt)
                bot.close()
            }
        }
}
