# Модуль core

Сервис данных и домена: напоминания, пользователи, доступ, учёт usage, планировщик, уведомления (`Notifier` → Telegram). Наружу — MCP (инструменты для LLM) и internal REST (системные вызовы агента, не-ИИ клиенты). Архитектура — `docs/architecture.md`, решения — `docs/DECISIONS.md` (разделы 2–5, 7).

**Статус:** каркас. Gradle-модуль `:core`, плагин `theseus.application`, точка входа `theseus.core.MainKt` — заглушка.
