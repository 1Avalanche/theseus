# Модуль agent

Telegram-бот (long polling) и LLM-пайплайн через OpenRouter. Архитектура — `docs/architecture.md`, решения — `docs/DECISIONS.md` (разделы 2, 6, 8, 9).

**Статус:** каркас. Gradle-модуль `:agent`, плагин `theseus.application`, точка входа `theseus.agent.MainKt` — заглушка.

## Ресурсы

| Путь | Назначение |
|---|---|
| `src/main/resources/persona/` | персона Сири и карточка фактов — см. `docs/persona.md` |
| `src/main/resources/prompts/rules.md` | блок «правила» системного промпта: поведение секретаря (черновик) |

Все промпты — на русском, хранятся в ресурсах, не в коде.
