package theseus.agent.texts

import java.util.Properties

/** Тексты и шаблоны из ресурсов (`.properties` в UTF-8). Подстановки — `{имя}`. */
class Texts(
    private val values: Map<String, String>,
) {
    fun get(
        key: String,
        params: Map<String, String> = emptyMap(),
    ): String {
        val template = values[key] ?: error("Нет текста с ключом '$key'")
        return params.entries.fold(template) { acc, (name, value) -> acc.replace("{$name}", value) }
    }

    companion object {
        fun load(resource: String): Texts {
            val properties = Properties()
            resourceText(resource).reader().use(properties::load)
            return Texts(properties.stringPropertyNames().associateWith(properties::getProperty))
        }

        fun resourceText(resource: String): String =
            Texts::class.java.classLoader
                .getResource(resource)
                ?.readText(Charsets.UTF_8)
                ?: error("Ресурс '$resource' не найден")
    }
}
