# Модуль core

Сервис данных и домена: напоминания, пользователи, доступ, учёт usage, планировщик, уведомления (`Notifier` → Telegram). Наружу — MCP (инструменты для LLM) и internal REST (системные вызовы агента, не-ИИ клиенты). Архитектура — `docs/architecture.md`, решения — `docs/DECISIONS.md` (разделы 2–5, 7).

**Статус:** каркас. Gradle-модуль `:core`, плагин `theseus.application`, точка входа `theseus.core.MainKt` — заглушка. Ниже — контракты этапа 1 (черновики, кода пока нет).

## Публичные интерфейсы

| Интерфейс | Назначение | Контракт |
|---|---|---|
| MCP (`POST /mcp`) | 9 инструментов напоминаний для LLM; пользователь — из `_meta`, не из аргументов | `docs/api/mcp.md` |
| Internal REST (`/internal/v1`, `GET /health`) | пользователи, инвайты, usage, квоты, snooze по кнопке, поиск уведомления по reply | `docs/api/rest.md` |

Оба интерфейса — тонкие адаптеры над application-сервисами; аутентификация — сервисный токен.

## Данные

Схема `core`: `users`, `invites`, `reminders`, `reminder_deliveries`, `llm_usage` — `docs/database.md`. Core мигрирует только эту схему.

## Уведомления

core сам отправляет уведомление в Telegram (`Notifier` → `TelegramNotifier`) с inline-кнопками snooze (+15 минут, +1 час, завтра). Нажатия приходят агенту; он вызывает `POST /internal/v1/deliveries/{delivery_id}/snooze`. Формат `callback_data` (`snz:<delivery_id>:<вариант>`) — `docs/api/rest.md`, раздел «Уведомления».
