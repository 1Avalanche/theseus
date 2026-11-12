# Модуль core

Сервис данных и домена: напоминания, пользователи, доступ, учёт usage, планировщик, уведомления (`Notifier` → Telegram). Наружу — MCP (инструменты для LLM) и internal REST (системные вызовы агента, не-ИИ клиенты). Архитектура — `docs/architecture.md`, решения — `docs/DECISIONS.md` (разделы 2–5, 7).

**Статус:** этап 3 реализован. Gradle-модуль `:core`, плагины `theseus.application` + `theseus.serialization`, точка входа `theseus.core.MainKt`. Проверки и их границы — `docs/roadmap.md`.

## Пакеты `theseus.core`

| Пакет | Содержимое |
|---|---|
| `domain` | `reminder` (`Reminder`, `NewReminder`, `When`, `WhenResolver`), `user` (`User`, `AdminProfile`, статусы, `Gender`), `DomainException` + `ErrorCode`, `LocalFormats`. Без инфраструктуры |
| `application` | `ReminderService`, `UserService`, `AccountsService`, `Operations`, `ReminderScheduler`; порты репозиториев, `Notifier`, `HealthProbe`; время — инжектируемый `Clock` |
| `adapters.db` | Exposed + HikariCP + Flyway: `CoreDatabase`, `ExposedUserRepository`, `ExposedInviteRepository`, `ExposedUsageRepository`, `ExposedReminderRepository`, `DatabaseOperations` |
| `adapters.mcp` | `McpServerFactory`, `ReminderTools`, DTO инструментов |
| `adapters.http` | `HttpModule`: Bearer-авторизация, `/mcp`, `/health`, REST |
| `adapters.monitoring` | снимок состояния планировщика, БД, очереди доставок и ошибок Telegram |
| `adapters.telegram` | `TelegramNotifier` (реализация `Notifier`) |
| `config` | `CoreConfig` из env, валидация при старте; переменные — `docs/operations.md` |
| `texts` | загрузка `texts/core.properties` |

Старт (`Main.kt`): конфиг → миграции Flyway → админ из конфига (`ensureAdmin`) → планировщик → HTTP-сервер (Netty).

## Реализовано

- MCP: только `create_reminder`, **только разовые** (`when`); `recurrence` → `invalid_recurrence`. Проверки дублей нет, `force` принимается и игнорируется. Остальные 8 инструментов — этапы 4–5.
- REST: `GET /health`, пользователи, инвайты, usage и отключённые квоты; изменяющие запросы используют реестр `operation_results`. Системные вызовы уведомлений — этап 5.
- Планировщик: тик раз в `THESEUS_SCHEDULER_INTERVAL_SECONDS` (30 с), только разовые напоминания. Доставка «как минимум один раз»; просроченные за время простоя досылаются с пометкой об опоздании; постоянные ошибки Telegram (400/403) закрывают напоминание как `FAILED`; пользователям не в `ACTIVE` уведомления не отправляются (напоминание закрывается без доставки).
- Уведомление — текст без кнопок snooze (обработка нажатий — этап 5).
- Новый админ создаётся в `ONBOARDING`; существующий профиль сохраняется. Инвайт одноразовый, хранится хэш кода, срок задаёт конфиг. После заполнения имени и таймзоны профиль становится активным. Квоты выключены.
- Время: `WhenResolver` считает момент в таймзоне пользователя, лимит — 5 лет вперёд, точность — минута.

Планировщик отмечает успешное завершение каждого прохода, включая пустой. Снимок `/tmp/theseus-core.monitor.json` обновляется каждые 30 секунд; количество и время самого старого наступившего активного напоминания вычисляются агрегатно, без текстов и без ограничения размером пакета планировщика. Эксплуатация — [monitoring.md](../monitoring.md).

## Публичные интерфейсы (контракт целиком; реализованное — выше)

| Интерфейс | Назначение | Контракт |
|---|---|---|
| MCP (`POST /mcp`) | 9 инструментов напоминаний для LLM; пользователь — из `_meta`, не из аргументов | `docs/api/mcp.md` |
| Internal REST (`/internal/v1`, `GET /health`) | пользователи, инвайты, usage, квоты, snooze по кнопке, поиск уведомления по reply | `docs/api/rest.md` |

Оба интерфейса — тонкие адаптеры над application-сервисами; аутентификация — сервисный токен.

## Данные

Схема `core`: `users`, `invites`, `reminders`, `reminder_deliveries`, `llm_usage`, `operation_results` — `docs/database.md`. Core мигрирует только эту схему. `V1` создаёт пользователей и напоминания; `V2` — инвайты, usage и результаты операций.

## Уведомления

core сам отправляет уведомление (кнопки snooze — этап 5; пока текст без кнопок) в Telegram (`Notifier` → `TelegramNotifier`) с inline-кнопками snooze (+15 минут, +1 час, завтра). Нажатия приходят агенту; он вызывает `POST /internal/v1/deliveries/{delivery_id}/snooze`. Формат `callback_data` (`snz:<delivery_id>:<вариант>`) — `docs/api/rest.md`, раздел «Уведомления».
