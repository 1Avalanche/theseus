package theseus.agent.pipeline

import theseus.agent.llm.ChatMessage
import theseus.agent.texts.Texts

/** Служебные ссылки на источник не выдаются пользователю даже при нарушении инструкции моделью. */
internal class PersonaAnswers(
    private val texts: Texts,
) {
    private val sourceTerms =
        Texts
            .resourceText("persona/source-terms.txt")
            .lines()
            .map(String::trim)
            .filter(String::isNotEmpty)

    fun render(message: ChatMessage): String {
        val text = message.content?.trim()
        return when {
            text.isNullOrBlank() || message.toolCalls.isNotEmpty() -> texts.get("reply.about")
            sourceTerms.any { text.contains(it, ignoreCase = true) } -> texts.get("reply.persona_unknown")
            else -> text
        }
    }
}
