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

    @Test fun `natural admission and expedition phrasing are kept unchanged`() {
        for (text in listOf("Не знаю, почему меня так назвали.", "Я летал на «Тесее». Не люблю об этом говорить.")) {
            assertEquals(text, answers.render(ChatMessage(Role.ASSISTANT, text)))
        }
        val prompt = Texts.resourceText("prompts/persona-answer.md")
        assertTrue(prompt.contains("Не выдумывай, кто дал имя"))
        assertTrue(Texts.resourceText("persona/siri-facts.md").contains("Не любишь говорить"))
    }
}
