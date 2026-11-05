# Модуль agent

Telegram-бот (long polling) и LLM-пайплайн через OpenRouter. Архитектура — `docs/architecture.md`, решения — `docs/DECISIONS.md` (разделы 2, 6, 8, 9).

**Статус:** этап 2 («ходячий скелет»). Gradle-модуль `:agent`, плагины `theseus.application` + `theseus.serialization`, точка входа `theseus.agent.MainKt`. Упрощения этапа 2 и причины — `docs/DECISIONS.md`, §13.

## Пакеты `theseus.agent`

| Пакет | Содержимое |
|---|---|
| `config` | `AgentConfig` из env, валидация при старте; переменные — `docs/operations.md` |
| `core` | порт `CoreClient` (`findUserByChat`, `listTools`, `callTool`); `HttpCoreClient` — REST + MCP-клиент (Streamable HTTP), Bearer-токен, `userId` в `_meta["theseus/user_id"]` |
| `llm` | порт `LlmClient`; `OpenRouterClient` — запрос с `models` (fallback по порядку из `THESEUS_LLM_MAIN_MODELS`) и `provider.data_collection = deny` |
| `prompt` | `PromptBuilder`: системный промпт из блоков `persona/siri.md` → `persona/siri-facts.md` → `prompts/rules.md` → `prompts/context.md` (имя, род, локальное время и таймзона пользователя; стабильные блоки первыми — для prompt caching) |
| `pipeline` | `MessageHandler` (обработка сообщения), `ReplyRenderer` (ответ кодом по шаблону из результата инструмента) |
| `telegram` | `TelegramBotRunner` — long polling, личные чаты, мьютекс на chatId; `HealthHeartbeat` — пульс для healthcheck (`getMe` раз в 30 с, файл `THESEUS_HEALTH_FILE`) |
| `texts` | загрузка `texts/agent.properties` |

## Поток сообщения (этап 2)

1. `findUserByChat` в core **до** LLM; незнакомый или не `ACTIVE` chatId — без ответа и без вызова модели.
2. Модель получает системный промпт и инструменты из MCP core (список кэшируется в процессе).
3. Вызовы инструментов уходят в core с `userId` в `_meta`. Успех — ответ шаблоном (`ReplyRenderer`, дата словами в таймзоне пользователя); ошибка — результат возвращается модели, она формулирует ответ. Не больше 2 раундов инструментов на сообщение; сбой — шаблон `reply.failure`.

Чего ещё нет: БД и истории диалога, `pending_actions`, подтверждения удаления и дублей, кнопки, онбординг и инвайты, учёт `llm_usage`, шаблон ответа незнакомому chatId (этапы 3–5). Из ролей моделей настроена только `main`.

## Ресурсы

| Путь | Назначение |
|---|---|
| `src/main/resources/persona/` | персона Сири и карточка фактов — см. `docs/persona.md` |
| `src/main/resources/prompts/rules.md` | блок «правила» системного промпта: поведение секретаря (черновик) |
| `src/main/resources/prompts/context.md` | шаблон блока «контекст» (подстановки `{name}`, `{gender}`, `{now_local}`, `{weekday}`, `{timezone}`) |
| `src/main/resources/texts/agent.properties` | тексты ответов в голосе Сири |

Все промпты — на русском, хранятся в ресурсах, не в коде.

## Данные

На этапе 2 у agent нет БД. План: схема `agent` (отдельная от `core`; в `core` агент ходит только через MCP и REST): `dialog_sessions`, `dialog_messages`, `pending_actions`, `onboarding_state` — `docs/database.md`.

## Взаимодействие с core

- Инструменты для модели — MCP, `docs/api/mcp.md`; системные вызовы — REST, `docs/api/rest.md`.
- Вызов `delete_reminder` от модели агент в core не передаёт: показывает карточку с кнопками «удалить» / «оставить», сохраняет `pending_action` и вызывает инструмент только после подтверждения. Так же с кнопками обрабатывается `possible_duplicate` («всё равно создать»). Подробности — `docs/api/mcp.md`.
- Нажатия кнопок уведомлений (`snz:…`) агент обрабатывает без LLM через REST; reply на уведомление сопоставляет с напоминанием через REST.

## Eval-набор

Каталог `agent/eval/`: `reminders.jsonl` (кейсы) и `fixtures.json` (контекст и напоминания). Формат и правила сравнения — `docs/eval.md`. В `./gradlew build` не входит.
