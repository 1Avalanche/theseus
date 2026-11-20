@file:Suppress("MatchingDeclarationName") // CLI и его локальный журнал бюджета используются только eval.

package theseus.agent.eval

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import theseus.agent.llm.LlmClient
import theseus.agent.llm.LlmRequest
import theseus.agent.llm.LlmResponse
import theseus.agent.llm.OpenRouterClient
import theseus.agent.llm.ToolCall
import java.math.BigDecimal
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.util.UUID

/** Журнал резервов синхронно сохраняется до сети; неизвестный исход удерживает весь резерв. */
internal class EvalBudget(
    private val file: Path,
    private val limit: BigDecimal,
) {
    private val entries = if (Files.exists(file)) objectJson(Files.readString(file)).toMutableMap() else mutableMapOf()

    fun reserve(amount: BigDecimal): String {
        require(amount >= BigDecimal.ZERO)
        val used = entries.values.sumOf { it.jsonPrimitive.content.toBigDecimal() }
        check(used + amount <= limit) { "Лимит eval исчерпан" }
        val id = UUID.randomUUID().toString()
        entries[id] = JsonPrimitive(amount.toPlainString())
        save()
        return id
    }

    fun settle(
        id: String,
        amount: BigDecimal?,
    ) {
        if (amount == null) return
        require(amount >= BigDecimal.ZERO)
        check(
            amount <=
                entries
                    .getValue(id)
                    .jsonPrimitive.content
                    .toBigDecimal(),
        ) { "Стоимость выше резерва; прогон остановлен" }
        entries[id] = JsonPrimitive(amount.toPlainString())
        save()
    }

    private fun save() {
        val temporary = file.resolveSibling(file.fileName.toString() + ".tmp")
        durableWrite(temporary, JsonObject(entries).toString())
        Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}

internal fun durableWrite(
    file: Path,
    text: String,
) {
    FileChannel.open(file, CREATE, WRITE, TRUNCATE_EXISTING).use { channel ->
        val bytes = java.nio.ByteBuffer.wrap(text.toByteArray())
        while (bytes.hasRemaining()) channel.write(bytes)
        channel.force(true)
    }
}

internal fun append(
    file: Path,
    value: JsonObject,
) {
    FileChannel.open(file, CREATE, WRITE, APPEND).use { channel ->
        val bytes = java.nio.ByteBuffer.wrap((value.toString() + "\n").toByteArray())
        while (bytes.hasRemaining()) channel.write(bytes)
        channel.force(true)
    }
}

internal fun fingerprint(root: Path): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    listOf("agent/src", "core/src/main", "agent/eval", "build-logic/src", "gradle").forEach { relative ->
        Files.walk(root.resolve(relative)).use { files ->
            files.filter(Files::isRegularFile).filter { !it.startsWith(root.resolve("agent/eval/results")) }.sorted().forEach {
                digest.update(root.relativize(it).toString().toByteArray())
                digest.update(Files.readAllBytes(it))
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // Последовательный CLI: блокировка, предохранители и сохранение до/после сети.
fun main(args: Array<String>) =
    runBlocking {
        val data = EvalData(Path.of("").toAbsolutePath())
        val mode = args.firstOrNull() ?: "validate"
        require(mode in setOf("validate", "smoke", "run", "report"))
        if (mode == "validate") {
            data.cases.forEach { EvalHarness(data, it) }
            println("Проверено кейсов: ${data.cases.size}; моделей: ${data.models.size}")
            return@runBlocking
        }
        val results = data.directory.resolve("results")
        Files.createDirectories(results)
        FileChannel.open(results.resolve(".lock"), CREATE, WRITE).use { channel ->
            channel.tryLock().use { lock ->
                check(lock != null) { "Другой eval уже работает" }
                val run = results.resolve("stage6")
                Files.createDirectories(run)
                if (mode == "report") {
                    report(run)
                    return@runBlocking
                }
                val key = requireNotNull(System.getenv("OPENROUTER_API_KEY")) { "Нужен OPENROUTER_API_KEY" }
                val manifest =
                    buildJsonObject {
                        put("fingerprint", fingerprint(data.root))
                        put("config", data.config)
                        put(
                            "created_at",
                            java.time.Instant
                                .now()
                                .toString(),
                        )
                    }
                val manifestFile = run.resolve("manifest.json")
                if (Files.exists(manifestFile)) {
                    check(objectJson(Files.readString(manifestFile)).string("fingerprint") == manifest.string("fingerprint")) {
                        "Исходники изменились: продолжение старого прогона запрещено"
                    }
                } else {
                    durableWrite(manifestFile, manifest.toString())
                }
                val budget = EvalBudget(results.resolve("budget.json"), data.config.string("budget_usd").toBigDecimal())
                HttpClient(CIO).use { http ->
                    val catalog =
                        objectJson(http.get("https://openrouter.ai/api/v1/models").bodyAsText()).rows("data").associateBy {
                            it.jsonObject.string("id")
                        }
                    val prices =
                        data.models.associateWith { model ->
                            val entry = requireNotNull(catalog[model]) { "Модель недоступна: $model" }.jsonObject
                            require(entry.rows("supported_parameters").any { it.jsonPrimitive.content == "tools" })
                            val price = entry.getValue("pricing").jsonObject
                            textPrice(price)
                            price
                        }
                    val priceFile = run.resolve("prices.json")
                    if (Files.exists(priceFile)) {
                        check(objectJson(Files.readString(priceFile)) == JsonObject(prices)) {
                            "Тарифы изменились: нужна новая оценка бюджета"
                        }
                    } else {
                        durableWrite(priceFile, JsonObject(prices).toString())
                    }
                    val smoke =
                        data.config
                            .rows("smoke_cases")
                            .map { it.jsonPrimitive.content }
                            .toSet()
                    val selected = data.cases.filter { mode == "run" || it.string("id") in smoke }
                    val completed =
                        if (Files.exists(run.resolve("cases.jsonl"))) {
                            Files
                                .readAllLines(run.resolve("cases.jsonl"))
                                .map(::objectJson)
                                .map { it.string("model") + ":" + it.string("case_id") }
                                .toSet()
                        } else {
                            emptySet()
                        }
                    for (case in selected) {
                        for (model in data.models) {
                            val caseKey = "$model:${case.string("id")}"
                            if (caseKey in completed) continue
                            val marker = run.resolve("active.json")
                            check(!Files.exists(marker)) { "Предыдущий кейс прерван; автоматический платный повтор запрещён" }
                            durableWrite(marker, buildJsonObject { put("case", caseKey) }.toString())
                            val harness = EvalHarness(data, case)
                            val before = harness.state.toMap()
                            val calls = mutableListOf<ToolCall>()
                            var transportFailed = false
                            val price = prices.getValue(model)
                            val caps =
                                buildJsonObject {
                                    put("prompt", JsonPrimitive(price.string("prompt").toBigDecimal() * BigDecimal(1_000_000)))
                                    put("completion", JsonPrimitive(price.string("completion").toBigDecimal() * BigDecimal(1_000_000)))
                                    put("request", 0)
                                }
                            val client =
                                OpenRouterClient(
                                    http,
                                    "https://openrouter.ai/api/v1",
                                    key,
                                    listOf(model),
                                    Duration.ofSeconds(60),
                                    maxPrice = caps,
                                )
                            val guarded =
                                object : LlmClient {
                                    override suspend fun complete(request: LlmRequest): LlmResponse {
                                        check(!transportFailed) { "Предыдущее обращение требует сверки" }
                                        transportFailed = true
                                        // Полное разрешённое входное окно и вывод резервируются без скидки кеша.
                                        val reservedPrice = textPrice(price)
                                        val reserve =
                                            reservedPrice.first * BigDecimal(18000) +
                                                reservedPrice.second * request.maxOutputTokens.toBigDecimal()
                                        val id = budget.reserve(reserve)
                                        val started = System.nanoTime()
                                        val response =
                                            try {
                                                transportFailed = true
                                                client.complete(request)
                                            } catch (failure: Exception) {
                                                append(
                                                    run.resolve("attempts.jsonl"),
                                                    buildJsonObject {
                                                        put("reservation_id", id)
                                                        put("case_id", case.string("id"))
                                                        put("model", model)
                                                        put("error", failure.javaClass.simpleName)
                                                    },
                                                )
                                                throw failure
                                            }
                                        calls += response.message.toolCalls
                                        append(
                                            run.resolve("attempts.jsonl"),
                                            buildJsonObject {
                                                put("reservation_id", id)
                                                put("case_id", case.string("id"))
                                                put("model", model)
                                                put("actual_model", response.model)
                                                put("role", request.role)
                                                put("latency_ms", (System.nanoTime() - started) / 1_000_000)
                                                put("tokens_in", response.usage?.tokensIn)
                                                put("tokens_out", response.usage?.tokensOut)
                                                put("cost_usd", response.usage?.costUsd)
                                                put("content", response.message.content)
                                                put(
                                                    "calls",
                                                    JsonArray(
                                                        response.message.toolCalls.map { call ->
                                                            buildJsonObject {
                                                                put("tool", call.name)
                                                                put("arguments", call.argumentsJson)
                                                            }
                                                        },
                                                    ),
                                                )
                                            },
                                        )
                                        budget.settle(id, response.usage?.costUsd?.toBigDecimalOrNull())
                                        check(response.model == model) { "Ответ другой модели" }
                                        transportFailed = false
                                        return response
                                    }
                                }
                            val reply = harness.run(guarded)
                            check(!transportFailed) { "Ошибка обращения: резерв сохранён, повтор запрещён" }
                            val assessment = EvalMatcher(harness.context).assess(case, calls, reply?.text, before != harness.state)
                            append(
                                run.resolve("cases.jsonl"),
                                buildJsonObject {
                                    put("model", model)
                                    put("case_id", case.string("id"))
                                    put("category", case.string("category"))
                                    put("reply", reply?.text)
                                    put("buttons", reply?.buttons?.size ?: 0)
                                    put("changed", before != harness.state)
                                    put("executed", JsonArray(harness.executed))
                                    put("assessment", assessment)
                                },
                            )
                            Files.delete(marker)
                            report(run)
                            println("${case.string("id")} / $model: ${assessment["automatic_pass"]}")
                        }
                    }
                }
            }
        }
    }

internal fun report(run: Path) {
    val attempts =
        run
            .resolve("attempts.jsonl")
            .takeIf(Files::exists)
            ?.let(Files::readAllLines)
            .orEmpty()
            .map(::objectJson)
    val rows =
        run
            .resolve("cases.jsonl")
            .takeIf(Files::exists)
            ?.let(Files::readAllLines)
            .orEmpty()
            .map(::objectJson)
    val summary =
        buildJsonObject {
            put("completed", rows.size)
            put("automatic_pass", rows.count { it.getValue("assessment").jsonObject["automatic_pass"] == JsonPrimitive(true) })
            put("manual_review", "pending")
            put("usage", usageSummary(attempts))
            put(
                "by_model",
                JsonObject(
                    rows.groupBy { it.string("model") }.mapValues { (_, group) ->
                        buildJsonObject {
                            put("completed", group.size)
                            put("passed", group.count { it.getValue("assessment").jsonObject["automatic_pass"] == JsonPrimitive(true) })
                        }
                    },
                ),
            )
            put(
                "by_category",
                JsonObject(
                    rows.groupBy { it.string("category") }.mapValues { (_, group) ->
                        buildJsonObject {
                            put("completed", group.size)
                            put("passed", group.count { it.getValue("assessment").jsonObject["automatic_pass"] == JsonPrimitive(true) })
                        }
                    },
                ),
            )
        }
    durableWrite(run.resolve("summary.json"), summary.toString())
    durableWrite(
        run.resolve("report.md"),
        "# Результаты eval\n\n$summary\n\n" +
            rows.joinToString("\n") {
                "- ${it.string("case_id")} / ${it.string("model")}: ${it.getValue("assessment")}; ответ: ${it["reply"]}"
            },
    )
}

private fun usageSummary(attempts: List<JsonObject>): JsonObject =
    JsonObject(
        attempts.groupBy { it.string("model") }.mapValues { (_, group) ->
            buildJsonObject {
                put("requests", group.size)
                put(
                    "tokens_in",
                    group.sumOf {
                        it["tokens_in"]
                            ?.takeUnless { v ->
                                v == JsonNull
                            }?.jsonPrimitive
                            ?.content
                            ?.toLong() ?: 0
                    },
                )
                put(
                    "tokens_out",
                    group.sumOf {
                        it["tokens_out"]
                            ?.takeUnless { v -> v == JsonNull }
                            ?.jsonPrimitive
                            ?.content
                            ?.toLong()
                            ?: 0
                    },
                )
                val costs =
                    group.mapNotNull {
                        it["cost_usd"]
                            ?.takeUnless { v ->
                                v == JsonNull
                            }?.jsonPrimitive
                            ?.content
                            ?.toBigDecimalOrNull()
                    }
                put("known_cost_usd", costs.sumOf { it }.toPlainString())
                put("unknown_cost_requests", group.size - costs.size)
                put("latency_ms", JsonArray(group.mapNotNull { it["latency_ms"] }))
            }
        },
    )
