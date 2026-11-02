# Модуль agent

Telegram-бот (long polling) и LLM-пайплайн через OpenRouter. Архитектура — `docs/architecture.md`, решения — `docs/DECISIONS.md` (разделы 2, 6, 8, 9).

**Статус:** каркас. Gradle-модуль `:agent`, плагин `theseus.application`, точка входа `theseus.agent.MainKt` — заглушка.

## Ресурсы

| Путь | Назначение |
|---|---|
| `src/main/resources/persona/` | персона Сири и карточка фактов — см. `docs/persona.md` |
| `src/main/resources/prompts/rules.md` | блок «правила» системного промпта: поведение секретаря (черновик) |

Все промпты — на русском, хранятся в ресурсах, не в коде.

## Данные

Схема `agent` (отдельная от `core`; в `core` агент ходит только через MCP и REST): `dialog_sessions`, `dialog_messages`, `pending_actions`, `onboarding_state` — `docs/database.md`.

## Взаимодействие с core

- Инструменты для модели — MCP, `docs/api/mcp.md`; системные вызовы — REST, `docs/api/rest.md`.
- Вызов `delete_reminder` от модели агент в core не передаёт: показывает карточку с кнопками «удалить» / «оставить», сохраняет `pending_action` и вызывает инструмент только после подтверждения. Так же с кнопками обрабатывается `possible_duplicate` («всё равно создать»). Подробности — `docs/api/mcp.md`.
- Нажатия кнопок уведомлений (`snz:…`) агент обрабатывает без LLM через REST; reply на уведомление сопоставляет с напоминанием через REST.

## Eval-набор

Каталог `agent/eval/`: `reminders.jsonl` (кейсы) и `fixtures.json` (контекст и напоминания). Формат и правила сравнения — `docs/eval.md`. В `./gradlew build` не входит.
