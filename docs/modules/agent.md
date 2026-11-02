# Модуль agent

Telegram-бот (long polling) и LLM-пайплайн через OpenRouter. Архитектура — `docs/architecture.md` и `docs/DECISIONS.md` (разделы 2, 6, 8).

**Статус:** модуль в Gradle ещё не создан. Заранее лежат только ресурсы промптов.

## Ресурсы

| Путь | Назначение |
|---|---|
| `src/main/resources/persona/` | персона Сири и карточка фактов — см. `docs/persona.md` |
| `src/main/resources/prompts/rules.md` | блок «правила» системного промпта: поведение секретаря (черновик) |

Все промпты — на русском, хранятся в ресурсах, не в коде.
