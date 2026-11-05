package theseus.core.texts

import java.util.Properties

/**
 * Тексты из ресурсов (`.properties` в UTF-8): пользовательские сообщения в голосе Сири
 * и сообщения об ошибках для модели. Подстановки — `{имя}`.
 */
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

    fun has(key: String): Boolean = key in values

    companion object {
        fun load(resource: String): Texts {
            val stream =
                Texts::class.java.classLoader.getResourceAsStream(resource)
                    ?: error("Ресурс '$resource' не найден")
            val properties = Properties()
            stream.reader(Charsets.UTF_8).use(properties::load)
            return Texts(properties.stringPropertyNames().associateWith(properties::getProperty))
        }
    }
}
