package theseus.agent.prompt

import theseus.agent.core.CoreUser
import theseus.agent.texts.Texts
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Системный промпт из блоков ресурсов: персона → карточка фактов → правила → контекст пользователя.
 * Стабильные блоки — первыми (prompt caching), контекст с текущим временем — последним.
 * HTML-комментарии `<!-- … -->` — для людей, в промпт не попадают.
 */
class PromptBuilder(
    stableBlocks: List<String>,
    private val contextTemplate: String,
    private val texts: Texts,
) {
    private val stablePrefix = stableBlocks.joinToString("\n\n") { stripComments(it) }

    fun system(
        user: CoreUser,
        now: Instant,
    ): String {
        val zone = ZoneId.of(requireNotNull(user.timezone))
        val local = ZonedDateTime.ofInstant(now, zone)
        val context =
            stripComments(contextTemplate)
                .replace("{name}", user.name ?: "—")
                .replace("{gender}", texts.get("gender.${user.gender}"))
                .replace("{now_local}", local.format(NOW_FORMAT))
                .replace("{weekday}", local.dayOfWeek.getDisplayName(TextStyle.FULL, RU))
                .replace("{timezone}", zone.id)
        return "$stablePrefix\n\n$context"
    }

    companion object {
        private val RU: Locale = Locale.of("ru")
        private val NOW_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

        internal fun stripComments(text: String): String = text.replace(COMMENT, "").replace(Regex("\n{3,}"), "\n\n").trim()

        fun fromResources(texts: Texts) =
            PromptBuilder(
                stableBlocks =
                    listOf("persona/siri.md", "persona/siri-facts.md", "prompts/rules.md").map(Texts::resourceText),
                contextTemplate = Texts.resourceText("prompts/context.md"),
                texts = texts,
            )
    }
}
