package theseus.agent.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

internal fun JsonObject.rows(key: String): List<JsonElement> = (get(key) as? JsonArray).orEmpty()

internal fun objectJson(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

internal class EvalData(
    val root: Path,
) {
    val directory: Path = root.resolve("agent/eval")
    val config = objectJson(Files.readString(directory.resolve("config.json")))
    val models = config.rows("models").map { it.jsonPrimitive.content }
    val fixtures = objectJson(Files.readString(directory.resolve("fixtures.json")))
    val cases: List<JsonObject> =
        Files.list(directory).use { files ->
            files.filter { it.toString().endsWith(".jsonl") }.sorted().toList().flatMap { file ->
                Files.readAllLines(file).filter(String::isNotBlank).map(::objectJson)
            }
        }

    init {
        require(models.size == models.distinct().size && models.isNotEmpty())
        require(cases.map { it.string("id") }.distinct().size == cases.size)
        require(config.string("budget_usd").toBigDecimal() in java.math.BigDecimal.ZERO..java.math.BigDecimal("5"))
        cases.forEach { case ->
            require(("expect" in case) != ("expect_any" in case))
            require(case.string("input").isNotBlank())
            reminders(case["fixture"]?.jsonPrimitive?.content ?: "empty")
        }
    }

    fun context(case: JsonObject): JsonObject =
        JsonObject(
            fixtures.getValue("context").jsonObject + (case["context"] as? JsonObject).orEmpty(),
        )

    fun reminders(
        name: String,
        ancestors: Set<String> = emptySet(),
    ): List<JsonObject> {
        require(name !in ancestors)
        val fixture =
            fixtures
                .getValue("fixtures")
                .jsonObject
                .getValue(name)
                .jsonObject
        val parent = fixture["extends"]?.jsonPrimitive?.content
        val rows = parent?.let { reminders(it, ancestors + name) } ?: fixture.rows("reminders").map { it.jsonObject }
        return rows.map { row -> JsonObject(row + ((fixture["override"] as? JsonObject)?.get(row.string("id")) as? JsonObject).orEmpty()) }
    }
}

internal fun now(context: JsonObject): LocalDateTime = LocalDateTime.parse(context.string("now_local"))

internal fun zone(context: JsonObject): ZoneId = ZoneId.of(context.string("timezone"))
