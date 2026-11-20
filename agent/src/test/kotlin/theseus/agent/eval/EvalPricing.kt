package theseus.agent.eval

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

/** Только текст и инструменты: платные web/audio/image функции в запросе отсутствуют. */
internal fun textPrice(price: JsonObject): Pair<BigDecimal, BigDecimal> {
    val known =
        setOf(
            "prompt",
            "completion",
            "request",
            "image",
            "audio",
            "web_search",
            "internal_reasoning",
            "input_cache_read",
            "input_cache_write",
            "input_cache_write_1h",
            "input_audio_cache",
            "overrides",
        )
    require(price.keys.all { it in known }) { "Неизвестный вид тарифа" }
    require(
        price["request"]
            ?.jsonPrimitive
            ?.content
            ?.toBigDecimal()
            ?.compareTo(BigDecimal.ZERO) != 1,
    )

    fun rate(key: String): BigDecimal = price[key]?.jsonPrimitive?.content?.toBigDecimal() ?: BigDecimal.ZERO
    val prompt = rate("prompt")
    val completion = rate("completion")
    require(prompt >= BigDecimal.ZERO && completion >= BigDecimal.ZERO)
    // Резерв покрывает и потенциальную запись кеша; скидки не уменьшают оценку.
    val cache = maxOf(rate("input_cache_write"), rate("input_cache_write_1h"), rate("input_cache_read"))
    return (prompt + cache) to (completion + rate("internal_reasoning"))
}
