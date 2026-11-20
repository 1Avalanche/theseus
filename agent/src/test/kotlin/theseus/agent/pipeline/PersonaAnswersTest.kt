package theseus.agent.pipeline

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import theseus.agent.llm.ChatMessage
import theseus.agent.llm.Role
import theseus.agent.texts.Texts

class PersonaAnswersTest {
    private val texts = Texts.load("texts/agent.properties")
    private val answers = PersonaAnswers(texts)

    @Test fun `answer never exposes a character card or prompt despite model response`() {
        for (text in listOf(
            "В моей карточке нет объяснения, почему меня так назвали.",
            "Откуда оно взялось, из КАРТОЧКИ не узнать.",
            "Так сказано в системном промпте.",
            "Согласно инструкции, я не знаю.",
        )) {
            assertEquals(texts.get("reply.persona_unknown"), answers.render(ChatMessage(Role.ASSISTANT, text)))
        }
    }

    @Test fun `natural admission and current persona phrasing are kept unchanged`() {
        for (text in listOf("Не знаю, почему меня так назвали.", "Я Сири Китон, ИИ-ассистент в Telegram.")) {
            assertEquals(text, answers.render(ChatMessage(Role.ASSISTANT, text)))
        }
        val prompt = Texts.resourceText("prompts/persona-answer.md")
        assertTrue(prompt.contains("Единственный источник фактов — карточка ниже"))
        assertTrue(prompt.contains("Никаких знаний о книге вне карточки"))
        assertTrue(prompt.contains("Неизвестное признавай от первого лица: «Не знаю»"))
        assertTrue(Texts.resourceText("persona/siri-facts.md").contains("Сири Китон — синтет, ИИ-ассистент в Telegram"))
    }
}
